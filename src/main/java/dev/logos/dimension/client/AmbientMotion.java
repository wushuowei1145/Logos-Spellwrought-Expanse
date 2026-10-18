package dev.logos.dimension.client;

/** Small continuous paths and fades, sampled by the native particle engine. No game state. */
public final class AmbientMotion {
    public enum Kind { FIREFLY, MOTE, GLIMMER }
    public record Frame(double x,double y,double z,float alpha){}
    private static double smooth(double t){t=Math.clamp(t,0,1);return t*t*(3-2*t);}
    public static double night(long ticks){
        long day=Math.floorMod(ticks,24000);
        return smooth((day-11800)/1800.0)*(1-smooth((day-21800)/1800.0));
    }
    public static boolean fireflies(int season,long ticks,boolean warmHabitat,boolean raining){
        return season!=3&&warmHabitat&&!raining&&night(ticks)>.05;
    }
    public static int capacity(int particleSetting){return particleSetting>=2?0:particleSetting==1?28:80;}
    public static int interval(int particleSetting){return particleSetting==1?8:3;}
    public static Frame sample(Kind kind,double age,int lifetime,double phase){
        double t=Math.clamp(age,0,Math.max(1,lifetime)),p=t/Math.max(1,lifetime);
        double fade=smooth(p/.12)*smooth((1-p)/.2);
        return switch(kind){
            case FIREFLY->new Frame(.72*Math.sin(t*.043+phase)+.13*Math.sin(t*.17+phase),.22*Math.sin(t*.065+phase),.65*Math.cos(t*.037+phase),
                    (float)(fade*(.18+.82*Math.pow(.5+.5*Math.sin(t*.115+phase),1.8))));
            case MOTE->new Frame(.32*Math.sin(t*.028+phase),t*.0032+.09*Math.sin(t*.055+phase),.30*Math.cos(t*.025+phase),
                    (float)(fade*(.36+.12*Math.sin(t*.07+phase))));
            case GLIMMER->new Frame(.10*Math.sin(t*.11+phase),t*.009,.10*Math.cos(t*.09+phase),(float)(fade*.80));
        };
    }
    private AmbientMotion(){}
}
