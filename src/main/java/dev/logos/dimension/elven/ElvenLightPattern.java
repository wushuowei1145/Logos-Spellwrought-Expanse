package dev.logos.dimension.elven;

/** 世界坐标噪声决定光点位置；所有点共享六秒呼吸周期，没有独立随机相位。 */
public final class ElvenLightPattern {
    public static final int PERIOD=120,GRID=6;
    public static long hash(int x,int y,int z){
        long n=mix(x*0x632BE59BD9B4E019L+y*0x9E3779B97F4A7C15L);
        return mix(n+z*0x85157AF5L);
    }
    private static long mix(long n){
        n=(n^(n>>>30))*0xBF58476D1CE4E5B9L;n=(n^(n>>>27))*0x94D049BB133111EBL;
        return n^(n>>>31);
    }
    public static double pulse(double ticks){
        double wave=.5-.5*Math.cos((ticks%PERIOD)*Math.PI*2/PERIOD);
        return .16+.84*wave*wave*(3-2*wave);
    }
    public static int jitter(long hash,int shift){return (int)((hash>>>shift)&0x7fffffff)%GRID;}
    public static int capacity(int particles){return particles>=2?0:particles==1?64:160;}
    private ElvenLightPattern(){}
}
