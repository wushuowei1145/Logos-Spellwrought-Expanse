package dev.logos.dimension.wind;

import net.minecraft.world.level.Level;

/**
 * 云海的风场：随游戏时间连续变化的阵风，分"慢阵风"与"快抖动"两层。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>纯函数</b>：只依赖游戏时间与天气，服务端和客户端各算一次就能得到同一个值，
 *       不需要每帧同步数据包——这是"不卡"的第一条。</li>
 *   <li><b>连续</b>：所有噪声都用 smoothstep 插值，强度与风向都不会跳变，
 *       草浪才不会一顿一顿的。</li>
 *   <li><b>有节律</b>：慢层周期约 150 秒、快层约 11 秒，叠加出"时大时小"的阵风感；
 *       下雨/雷暴时整体增强。</li>
 * </ul>
 *
 * <p>数值范围：{@code strength} 0–1.2（1 以上是风雨），{@code angle} 弧度，{@code speed} 波速系数。
 */
public final class WindField {
    /** 完全无风时也留一点微动，免得草地像贴纸。 */
    public static final float MIN=0.05f;
    /** 常态风的上限。 */
    public static final float MAX=1.0f;
    /** 风雨时的上限。 */
    public static final float STORM_MAX=1.2f;

    private static final float SLOW_SECONDS=150f;
    private static final float FAST_SECONDS=11f;
    private static final float ANGLE_SECONDS=420f;
    private static final long SEED=0x5EED_1F00_D0CL;

    /** 当前风：强度、方向（弧度）、波速系数。 */
    public record Wind(float strength,float angle,float speed){}

    private static volatile Float override=null;

    private WindField(){}

    /** 采样某一刻的风。纯函数：同样的入参永远同样的结果。 */
    public static Wind sample(long gameTime,boolean raining,boolean thundering){
        float seconds=gameTime/20f;
        // 慢层：3 个八度的值噪声，像远处推过来的阵风。
        float slow=0.5f+0.5f*fbm(seconds/SLOW_SECONDS,SEED,3);
        // 快层：草叶自身的抖动。
        float fast=0.5f+0.5f*smoothNoise(seconds/FAST_SECONDS,SEED+1);
        float base=MIN+(MAX-MIN)*(0.74f*slow+0.26f*fast);
        // 天气加成：下雨 +0.3，雷暴再加 +0.3，封顶 STORM_MAX。
        float weather=1f+(raining?0.3f:0f)+(thundering?0.3f:0f);
        float strength=Math.min(STORM_MAX,base*weather);
        Float forced=override;
        if(forced!=null)strength=forced;
        // 风向缓慢转动（约 7 分钟一圈），并跟随强度稍微加快。
        float angle=(float)(Math.PI*2*(0.5+0.5*smoothNoise(seconds/ANGLE_SECONDS,SEED+2)));
        float speed=0.6f+0.9f*strength;
        return new Wind(strength,angle,speed);
    }

    /** 从世界里采样（客户端与服务端都能用）。 */
    public static Wind of(Level level){
        return sample(level.getGameTime(),level.isRaining(),level.isThundering());
    }

    /** 调试命令用：强制指定强度；传 null 恢复自动。 */
    public static void setOverride(Float value){override=value;}

    public static Float override(){return override;}

    // ---- 噪声：整数格点随机值 + smoothstep 插值 ----

    private static float lattice(long seed,int index){
        long n=index*0x9E3779B97F4A7C15L+seed;
        n^=n>>>30;n*=0xBF58476D1CE4E5B9L;
        n^=n>>>27;n*=0x94D049BB133111EBL;
        n^=n>>>31;
        return (n&0xFFFF)/32767.5f-1f;
    }

    private static float smoothNoise(float x,long seed){
        int i=(int)Math.floor(x);
        float f=x-i;
        float t=f*f*(3f-2f*f);
        float a=lattice(seed,i),b=lattice(seed,i+1);
        return a+(b-a)*t;
    }

    private static float fbm(float x,long seed,int octaves){
        float sum=0f,amp=1f,total=0f;
        for(int i=0;i<octaves;i++){
            sum+=amp*smoothNoise(x,seed+i*7919L);
            total+=amp;
            amp*=0.5f;
            x*=2f;
        }
        return sum/Math.max(total,1e-4f);
    }
}
