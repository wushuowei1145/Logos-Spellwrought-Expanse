package dev.logos.dimension.longsword;

/** 两把长剑共用的服务端动作与客户端取样规则，时间单位为游戏刻。 */
public enum LongswordPhase {
    CLEAR, DRAW, IDLE, ATTACK_1, ATTACK_2, ATTACK_3, CHARGING, RELEASE, CANCEL, SHEATHE;

    public boolean attack() { return this == ATTACK_1 || this == ATTACK_2 || this == ATTACK_3; }
    public double duration(float rate, float reverse) {
        if (attack()) return 12.5 / rate;
        return switch (this) {
            case DRAW, SHEATHE -> 24;
            case RELEASE -> 21;
            case CANCEL -> reverse;
            default -> Double.POSITIVE_INFINITY;
        };
    }
    public double contact(float rate) { return attack() ? (this == ATTACK_1 ? 4.8 : 5) / rate : 10.8; }
    public String clip(double age) {
        return switch (this) {
            case DRAW -> "draw";
            case ATTACK_1 -> "attack_1";
            case ATTACK_2 -> "attack_2";
            case ATTACK_3 -> "attack_3";
            case CHARGING -> age < 11 ? "charge_start" : "charge_hold";
            case RELEASE -> "charge_release";
            case CANCEL -> "charge_start";
            case SHEATHE -> "sheathe";
            default -> "idle";
        };
    }
    public float seconds(double age, float rate, float reverse) {
        age = Math.max(0, age);
        if (attack()) return (float) Math.min(.625, age * rate / 20);
        return (float) switch (this) {
            case CHARGING -> age < 11 ? age / 20 : (age - 11) / 20 % 1.6;
            case IDLE -> age / 20 % 2.4;
            case CANCEL -> Math.max(0, reverse - age) / 20;
            default -> Math.min(duration(rate, reverse), age) / 20;
        };
    }
}
