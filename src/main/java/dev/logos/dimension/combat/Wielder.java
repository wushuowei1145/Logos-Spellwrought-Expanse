package dev.logos.dimension.combat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 拿着战斗武器的那一个（docs/WEAPON_AI.md 第 2.1 节）：武器的钩子（{@link CombatWeapon.Holder}）只问它，不问是不是玩家。
 * 玩家用 {@link #of(ServerPlayer)}；同伴自己实现（{@link NpcWeapon} 里跑招式）。
 */
public interface Wielder {
    LivingEntity entity();

    /** 是玩家时就是他，同伴为 null。只对玩家有意义的钩子开头 {@code if (w.player() == null) return 默认值;}。 */
    default @Nullable ServerPlayer player() {
        return entity() instanceof ServerPlayer p ? p : null;
    }

    /** 手里的武器。 */
    ItemStack weapon();

    /** 朝哪打（单位向量）：玩家是视线，同伴看着要打的那个，没有就朝前。 */
    Vec3 aim();

    /** 对着谁：同伴是驾驶员挑的目标；玩家为 null（准星射线自己找）。 */
    default @Nullable LivingEntity aimed() {
        return null;
    }

    /** 够多远（格）：玩家是原版攻击距离，同伴是角色自己的。 */
    double reach();

    /** 一下打多少（不含招式倍数）：玩家是原版攻击力 × 攻击蓄力，同伴是她的攻击 +（1 + 武器的攻击力修饰）。 */
    double attackDamage();

    /** 攻速（带 {@code attack_speed_base} 的招式按它缩放播放速度）。 */
    double attackSpeed();

    /** 身法：{@code sneak}、{@code sprint}。玩家看按键，同伴看驾驶员这一下想走的那一支。 */
    boolean intends(String flag);

    /** 这一下打不打得：玩家不打自己、同乘的、自己的宠物；同伴只打她当作敌人的。 */
    boolean mayHit(Entity e);

    /** 每一下都带火（拉塞塔逆鳞时）。 */
    default boolean ignites() {
        return false;
    }

    /** 在空中（坠星之类的 {@code airborne} 前提）：不在地上、不在水里、没扒着梯子。 */
    default boolean airborne() {
        LivingEntity e = entity();
        return !e.onGround() && !e.isInWater() && !e.onClimbable();
    }

    /** 水平的朝向：{@link #aim()} 压平，竖直时退回身子的朝向。 */
    default Vec3 flatAim() {
        Vec3 a = aim();
        Vec3 flat = new Vec3(a.x, 0, a.z);
        return flat.lengthSqr() < 1e-6 ? Vec3.directionFromRotation(0, entity().getYRot()).normalize() : flat.normalize();
    }

    static Wielder of(ServerPlayer player) {
        return new PlayerWielder(player);
    }

    /** 玩家：视线、原版攻击距离、原版攻击力 × 攻击蓄力、按键。 */
    record PlayerWielder(ServerPlayer player) implements Wielder {
        @Override public LivingEntity entity() { return player; }
        @Override public ItemStack weapon() { return player.getMainHandItem(); }
        @Override public Vec3 aim() { return player.getLookAngle(); }
        @Override public double reach() { return player.entityInteractionRange(); }
        @Override public double attackDamage() {
            return player.getAttributeValue(Attributes.ATTACK_DAMAGE) * player.getAttackStrengthScale(0.5f);
        }
        @Override public double attackSpeed() { return player.getAttributeValue(Attributes.ATTACK_SPEED); }
        @Override public boolean intends(String flag) {
            return switch (flag) {
                case "sneak" -> player.isShiftKeyDown();
                case "sprint" -> player.isSprinting();
                default -> false;
            };
        }
        @Override public boolean mayHit(Entity e) {
            if (e == player || !e.isAlive() || e.isSpectator() || e.isPassengerOfSameVehicle(player)) return false;
            return !(e instanceof TamableAnimal pet && pet.isOwnedBy(player));
        }
    }
}
