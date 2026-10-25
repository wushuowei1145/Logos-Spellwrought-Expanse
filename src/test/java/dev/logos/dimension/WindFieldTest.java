package dev.logos.dimension;

import dev.logos.dimension.wind.WindField;
import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 风场是"不卡"的基础：纯函数、连续、有节律。这里把这三条钉死。
 *
 * <p>纯数学测试，不加载游戏。
 */
class WindFieldTest {
    private static final long DAY=24000L;

    @Test void staysInRange() {
        for(long t=0;t<DAY*3;t+=7){
            var w=WindField.sample(t,false,false);
            assertTrue(w.strength()>=WindField.MIN-1e-4,"强度低于下限："+w.strength());
            assertTrue(w.strength()<=WindField.STORM_MAX+1e-4,"强度超出上限："+w.strength());
            assertTrue(w.angle()>=0&&w.angle()<Math.PI*2+1e-4,"风向角度越界："+w.angle());
            assertTrue(w.speed()>0,"波速必须为正");
        }
    }

    /** 相邻两刻的强度不能跳变，否则草浪会一顿一顿的。 */
    @Test void changesSmoothly() {
        float maxStep=0;
        for(long t=0;t<DAY*2;t++){
            float a=WindField.sample(t,false,false).strength();
            float b=WindField.sample(t+1,false,false).strength();
            maxStep=Math.max(maxStep,Math.abs(a-b));
        }
        assertTrue(maxStep<0.02f,"单刻强度跳变过大："+maxStep);
    }

    /** 一段时间内必须真的"时大时小"，而不是几乎不变。 */
    @Test void actuallyVaries() {
        var samples=new ArrayList<Float>();
        for(long t=0;t<DAY;t+=20)samples.add(WindField.sample(t,false,false).strength());
        float min=Float.MAX_VALUE,max=Float.MIN_VALUE;
        for(float s:samples){min=Math.min(min,s);max=Math.max(max,s);}
        assertTrue(max-min>0.25f,"一天之内风几乎没有变化："+min+".."+max);
    }

    /** 同一输入必须同一输出——服务端和客户端各算一次要能对上。 */
    @Test void isDeterministic() {
        var random=new Random(42);
        for(int i=0;i<200;i++){
            long t=random.nextInt(24000*10);
            boolean rain=random.nextBoolean(),thunder=rain&&random.nextBoolean();
            var a=WindField.sample(t,rain,thunder);
            var b=WindField.sample(t,rain,thunder);
            assertEquals(a.strength(),b.strength(),1e-6f);
            assertEquals(a.angle(),b.angle(),1e-6f);
        }
    }

    /** 风雨天平均风力要明显更高。 */
    @Test void stormRaisesAverageWind() {
        double calm=0,storm=0;
        int n=0;
        for(long t=0;t<DAY*2;t+=40){
            calm+=WindField.sample(t,false,false).strength();
            storm+=WindField.sample(t,true,true).strength();
            n++;
        }
        assertTrue(storm/n>calm/n+0.15,"雷暴天的平均风力没有明显提高");
    }

    /** 风向在几分钟的尺度上会转，不会永远吹同一个方向。 */
    @Test void directionDrifts() {
        float first=WindField.sample(0,false,false).angle();
        boolean drifted=false;
        for(long t=20*60;t<=20*60*6;t+=20*30){
            float angle=WindField.sample(t,false,false).angle();
            if(Math.abs(angle-first)>0.2f)drifted=true;
        }
        assertTrue(drifted,"十分钟里风向没有变化");
    }

    /** 调试覆盖：设了就必须原样返回，清掉就回到自动。 */
    @Test void overrideWins() {
        try{
            WindField.setOverride(0.77f);
            assertEquals(0.77f,WindField.sample(1234,false,false).strength(),1e-6f);
        }finally{
            WindField.setOverride(null);
        }
        assertNotEquals(0.77f,WindField.sample(1234,false,false).strength(),1e-6f);
    }
}
