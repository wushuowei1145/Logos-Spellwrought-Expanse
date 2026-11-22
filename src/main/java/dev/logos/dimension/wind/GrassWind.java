package dev.logos.dimension.wind;

/** 草叶顶点的权重和风场编码；世界位置先在双精度下取相位，避免远坐标抖动。 */
public final class GrassWind {
    public static final double TAU = Math.PI * 2;
    private GrassWind() {}

    /** 一株双层草共用根点；腰部权重为二分之一，根部严格为零。 */
    public static int weight(double height, boolean tall) {
        return (int) Math.round(Math.clamp(height / (tall ? 2 : 1), 0, 1) * 255);
    }

    /** 两个字节分别携带方向与强度，供自有管线的 UV1 使用，不占用光照坐标。 */
    public static int wind(WindField.Wind wind, double amount) {
        int angle = (int) Math.round(wrap(wind.angle()) * 255 / TAU);
        int strength = (int) Math.round(Math.clamp(wind.strength() * amount / WindField.STORM_MAX, 0, 1) * 255);
        return angle | strength << 8;
    }

    /** 长波和细波共用整株的根坐标，左右顶点与双层接缝不会分家。 */
    public static int phase(double x, double z, WindField.Wind wind) {
        double along = x * Math.cos(wind.angle()) + z * Math.sin(wind.angle());
        double across = -x * Math.sin(wind.angle()) + z * Math.cos(wind.angle());
        int longWave = (int) Math.round(wrap(along * .34) * 255 / TAU);
        int ripple = (int) Math.round(wrap(along * 1.13 + across * .57) * 255 / TAU);
        return longWave | ripple << 8;
    }

    public static double wrap(double phase) {
        return phase - Math.floor(phase / TAU) * TAU;
    }

    /** 在换回远景静态草之前收回摆幅，避免接管范围边缘突然折回。 */
    public static double fade(double distance) {
        double t = Math.clamp((distance - 32) / 16, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }
}
