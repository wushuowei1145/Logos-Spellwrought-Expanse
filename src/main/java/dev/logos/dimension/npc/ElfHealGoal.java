package dev.logos.dimension.npc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 森林精灵的治疗（docs/NPC_MAGIC.md 第 5、6 节）：周围单个同伴受伤时放 Logos 示例「抚生之触」，
 * 两个及以上受伤时放「生息之环」；魔力从精灵自己的池子付。
 *
 * <p>同伴是附近的森林精灵（含自己）；精灵是某个玩家的使魔时，玩家也算同伴，治疗玩家用加 {@code :players true} 的版本。
 * 不占移动与视线（和远射同时进行），施法前直接转向目标。只在附近有玩家时判断，免得没人看的精灵白放法术。
 */
public final class ElfHealGoal extends Goal {
    public static final double RANGE = 16, PLAYER_NEARBY = 48;
    public static final int SINGLE_COST = 20, GROUP_COST = 100;
    private static final int CHECK = 20, SINGLE_COOLDOWN = 40, GROUP_COOLDOWN = 200;
    /** 受伤：少了至少 4 点生命，或者不到上限的 75%。 */
    private static final float MISSING = 4, FRACTION = .75f;

    private final ForestElf elf;
    private int nextCheck, singleCooldown, groupCooldown;

    public ElfHealGoal(ForestElf elf) {
        this.elf = elf;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    static boolean injured(LivingEntity e) {
        return e.isAlive() && (e.getMaxHealth() - e.getHealth() >= MISSING || e.getHealth() < e.getMaxHealth() * FRACTION);
    }

    @Override
    public boolean canUse() {
        if (singleCooldown > 0) singleCooldown--;
        if (groupCooldown > 0) groupCooldown--;
        if (--nextCheck > 0) return false;
        nextCheck = CHECK;
        return elf.level().getNearestPlayer(elf, PLAYER_NEARBY) != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        boolean familiar = elf.isFamiliar();
        List<LivingEntity> hurt = new ArrayList<>();
        for (ForestElf other : elf.level().getEntitiesOfClass(ForestElf.class, elf.getBoundingBox().inflate(RANGE))) {
            if (injured(other)) hurt.add(other);
        }
        if (familiar) {
            for (Player p : elf.level().getEntitiesOfClass(Player.class, elf.getBoundingBox().inflate(RANGE))) {
                if (!p.isSpectator() && injured(p)) hurt.add(p);
            }
        }
        if (hurt.isEmpty()) return;
        boolean players = hurt.stream().anyMatch(e -> e instanceof Player);
        if (hurt.size() >= 2 && groupCooldown <= 0 && elf.mana() >= GROUP_COST
                && LogosMagicBridge.available(LogosMagicBridge.NPC_GROUP_HEAL, players)) {
            if (elf.spendMana(GROUP_COST) && LogosMagicBridge.cast(elf, LogosMagicBridge.NPC_GROUP_HEAL, players, GROUP_COST, null)) {
                groupCooldown = GROUP_COOLDOWN;
                singleCooldown = SINGLE_COOLDOWN;
                elf.playSound(ElfSounds.CAST.get(), 1, elf.getVoicePitch());
            }
            return;
        }
        if (singleCooldown > 0 || elf.mana() < SINGLE_COST) return;
        // 抚生之触沿视线命中第一个生物：挑看得见的、伤得最重的一个，不能是自己
        LivingEntity target = hurt.stream().filter(e -> e != elf && elf.hasLineOfSight(e) && elf.distanceTo(e) <= 24)
                .min(Comparator.comparingDouble(e -> e.getHealth() / e.getMaxHealth())).orElse(null);
        if (target == null) return;
        boolean forPlayer = target instanceof Player;
        if (!LogosMagicBridge.available(LogosMagicBridge.NPC_HEAL, forPlayer)) return;
        face(target);
        if (elf.spendMana(SINGLE_COST) && LogosMagicBridge.cast(elf, LogosMagicBridge.NPC_HEAL, forPlayer, SINGLE_COST, target)) {
            singleCooldown = SINGLE_COOLDOWN;
            elf.playSound(ElfSounds.CAST.get(), 1, elf.getVoicePitch());
        }
    }

    /** 射线法术沿视线生效：施法前直接把身体、头和视线对准目标的胸口。 */
    private void face(LivingEntity target) {
        Vec3 d = target.position().add(0, target.getBbHeight() * .6, 0).subtract(elf.getEyePosition());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) (-(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG));
        elf.setYRot(yaw);
        elf.setYHeadRot(yaw);
        elf.setYBodyRot(yaw);
        elf.setXRot(pitch);
    }
}
