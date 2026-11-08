package dev.logos.dimension.corruption;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.ServerConfig;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.runtime.ManaLifeform;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 碰到腐败的活物：每半秒一次高额魔法伤害、流失魔力（亡灵不受影响）。被腐败杀死时，
 * 在它死的地方给杀它的那个网络加电荷，多少按它的最大生命值算。也在这里每刻推动各维度的网络。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class CorruptionContact {
    /** 和原版受伤后的无敌时间对齐：每 10 刻一次。 */
    static final int PULSE = 10;
    /** 贴着方块表面也算碰到。 */
    private static final double TOUCH = .01;

    private CorruptionContact() {}

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) CorruptionNetworks.get(level).tick(level);
    }

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity living) || !(living.level() instanceof ServerLevel level)) return;
        if ((level.getGameTime() + living.getId()) % PULSE != 0 || !living.isAlive() || immune(living)) return;
        BlockPos touched = touching(level, living.getBoundingBox().inflate(TOUCH));
        if (touched == null) return;
        drainMana(living, ServerConfig.CORRUPTION_MANA_DRAIN.get());
        float damage = ServerConfig.CORRUPTION_DAMAGE.get().floatValue();
        if (damage > 0) living.hurtServer(level, source(level, touched), damage);
    }

    /** 亡灵、被腐败的生物（污秽衍生物等）、旁观者和创造模式的玩家不受影响。 */
    public static boolean immune(LivingEntity living) {
        if (living.is(EntityTypeTags.UNDEAD) || dev.logos.dimension.corruption.mob.CorruptionMobs.isCorrupted(living)) return true;
        return living instanceof Player player && (player.isSpectator() || player.isCreative());
    }

    /** 碰到的第一格腐败（脉络或污秽）。 */
    static @Nullable BlockPos touching(ServerLevel level, AABB box) {
        int x0 = Mth.floor(box.minX), y0 = Mth.floor(box.minY), z0 = Mth.floor(box.minZ);
        int x1 = Mth.floor(box.maxX), y1 = Mth.floor(box.maxY), z1 = Mth.floor(box.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pos.set(x, y, z);
                    if (level.isLoaded(pos) && Corruption.isNetworkBlock(level.getBlockState(pos))) return pos.immutable();
                }
            }
        }
        return null;
    }

    /** 伤害来源带着伤人的那一格的位置，死的时候靠它找到网络。 */
    static DamageSource source(ServerLevel level, BlockPos at) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(Corruption.DAMAGE), at.getCenter());
    }

    public static void drainMana(LivingEntity living, double amount) {
        if (amount <= 0) return;
        switch (living) {
            case Player player -> {
                PlayerMagic magic = PlayerMagic.of(player);
                magic.mana = Math.max(0, magic.mana - amount);
            }
            case ManaLifeform lifeform -> lifeform.setMana(Math.max(0, lifeform.mana() - amount));
            case ForestElf elf -> elf.spendMana((float) Math.min(elf.mana(), amount));
            case DivineFox fox -> fox.spendMana((float) Math.min(fox.mana(), amount));
            default -> {}
        }
    }

    /** 被腐败杀死：在死的那一格（和原版幽匿催发体一样往上偏半格）给网络加电荷。 */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        DamageSource source = event.getSource();
        if (!source.is(Corruption.DAMAGE) || !(event.getEntity().level() instanceof ServerLevel level)) return;
        Vec3 from = source.getSourcePosition();
        if (from == null) return;
        LivingEntity dead = event.getEntity();
        int charge = Math.round(dead.getMaxHealth() * ServerConfig.CORRUPTION_CHARGE_PER_HEALTH.get().floatValue());
        CorruptionNetworks.get(level).feed(level, BlockPos.containing(from), BlockPos.containing(dead.position().add(0, .5, 0)), charge);
    }
}
