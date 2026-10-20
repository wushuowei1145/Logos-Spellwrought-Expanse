package dev.logos.dimension.client;

/** Small continuous paths and fades, sampled by the native particle engine. No game state. */
public final class AmbientMotion {
    public enum Kind { FIREFLY, MOTE, GLIMMER, SPORE, SIGIL, RIPPLE, WISP }
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
    public static int kindCapacity(Kind kind,int setting){
        if(setting>=2)return 0;
        return setting==1?switch(kind){case FIREFLY->10;case MOTE->8;case GLIMMER->3;case SPORE->4;case SIGIL,RIPPLE,WISP->1;}
                :switch(kind){case FIREFLY->28;case MOTE->20;case GLIMMER->8;case SPORE->12;case SIGIL,RIPPLE->4;case WISP->2;};
    }
    public static float size(Kind kind,double age,int lifetime){
        double t=Math.clamp(age/Math.max(1,lifetime),0,1);
        return switch(kind){case RIPPLE->(float)(.25+.95*smooth(t));case SIGIL->(float)(.72+.28*smooth(t/.25));case WISP->(float)(1+.12*Math.sin(age*.09));default->1;};
    }
    public static Frame sample(Kind kind,double age,int lifetime,double phase){
        double t=Math.clamp(age,0,Math.max(1,lifetime)),p=t/Math.max(1,lifetime);
        double fade=smooth(p/.12)*smooth((1-p)/.2);
        return switch(kind){
            case FIREFLY->new Frame(.72*Math.sin(t*.043+phase)+.13*Math.sin(t*.17+phase),.22*Math.sin(t*.065+phase),.65*Math.cos(t*.037+phase),
                    (float)(fade*(.18+.82*Math.pow(.5+.5*Math.sin(t*.115+phase),1.8))));
            case MOTE->new Frame(.32*Math.sin(t*.028+phase),t*.0032+.09*Math.sin(t*.055+phase),.30*Math.cos(t*.025+phase),
                    (float)(fade*(.36+.12*Math.sin(t*.07+phase))));
            case GLIMMER->new Frame(.10*Math.sin(t*.11+phase),t*.009,.10*Math.cos(t*.09+phase),(float)(fade*.80));
            case SPORE->new Frame(.25*Math.sin(t*.035+phase),t*.0055+.06*Math.sin(t*.04+phase),.25*Math.cos(t*.03+phase),(float)(fade*(.35+.15*Math.sin(t*.08+phase))));
            case SIGIL->new Frame(0,0,0,(float)(fade*.42));
            case RIPPLE->new Frame(0,0,0,(float)(fade*(1-p)*.55));
            case WISP->new Frame(.62*Math.sin(t*.035+phase),.15+.20*Math.sin(t*.045+phase),.62*Math.cos(t*.029+phase),(float)(fade*(.55+.2*Math.sin(t*.08+phase))));
        };
    }
    private AmbientMotion(){}
}
