package dev.logos.dimension.ultimate;

import net.minecraft.world.entity.LivingEntity;

/**
 * 奥义打中的一下（{@link UltimateCast#hit}）：伤害已经乘过连携倍率和馈赠的威力，接力馈赠还可以再改。
 *
 * @see RelayGift#hit
 */
public final class UltimateHit {
    private final LivingEntity target;
    private float amount;
    private double pierce;

    UltimateHit(LivingEntity target, float amount) {
        this.target = target;
        this.amount = amount;
    }

    public LivingEntity target() {
        return target;
    }

    public float amount() {
        return amount;
    }

    public void amount(float amount) {
        this.amount = Math.max(0, amount);
    }

    /** 这一下无视目标几成护甲和韧性（0–1）；几份馈赠都给时取最大的。 */
    public double pierce() {
        return pierce;
    }

    public void pierce(double fraction) {
        pierce = Math.max(pierce, Math.clamp(fraction, 0, 1));
    }
}
