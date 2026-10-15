package dev.logos.dimension.client;

/** Continuous, clock-derived choreography. Looking away never resets the transformation. */
public final class ArcaneArrayMotion {
    public static final int CYCLE_TICKS = 720;
    public record Frame(double time, float unfold, float fracture, float opening, float glow,
                        float outerAngle, float orbitAngle, float sealAngle) {}
    public static Frame sample(double ticks) {
        double cycle = ((ticks % CYCLE_TICKS) + CYCLE_TICKS) % CYCLE_TICKS / CYCLE_TICKS;
        float unfold = (float) (.88 + .12 * Math.sin(cycle * Math.PI * 2 - Math.PI / 2));
        float fracture = (float) Math.pow(Math.max(0, Math.sin(cycle * Math.PI * 2)), 3);
        float opening = (float) (.5 + .5 * Math.sin(cycle * Math.PI * 2 + .7));
        float glow = (float) (.72 + .28 * Math.pow(.5 + .5 * Math.sin(ticks * .035), 2));
        return new Frame(ticks, unfold, fracture, opening, glow,
                (float) ((ticks % 3600) * .10), (float) (-(ticks % 2400) * .15), (float) ((ticks % 6000) * .06));
    }
    /** Both star polygons overlap briefly instead of replacing all eight connections in one frame. */
    public static float starBlend(Frame f) { return ease((f.opening()-.32f)/.36f); }
    public static float progress(double time,double period,double offset) {
        double p=time/period+offset;return (float)(p-Math.floor(p));
    }
    /** A travelling glint is invisible before its position wraps to the next lap. */
    public static float travelOpacity(float progress) {
        return ease(progress/.16f)*ease((1-progress)/.16f);
    }
    public static float waveOpacity(float progress) {
        float s=(float)Math.sin(progress*Math.PI);return s*s;
    }
    private static float ease(float x) {
        x=Math.clamp(x,0,1);return Math.clamp(x*x*x*(x*(x*6-15)+10),0,1);
    }
    private ArcaneArrayMotion() {}
}
