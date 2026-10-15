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
    private ArcaneArrayMotion() {}
}
