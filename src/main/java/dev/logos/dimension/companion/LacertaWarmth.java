package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 绯翼余温（接入方案第七节第 1 条，对标琥珀相伴，战士向）：拉塞塔守着你睡过一整夜，醒来以后 5 分钟抗火、不怕冻（细雪冻不着），
 * 近战伤害多、挨打少一点：基础各 5%，羁绊每段再 +2.5%（到「羁绊」段 15%）。羁绊段数记在效果等级里（0 起）。可以和琥珀相伴一起有。
 */
public final class LacertaWarmth {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, LogosDimension.ID);
    /** 近战伤害、减伤：基础、羁绊每段。 */
    public static final double BASE = .05, STAGE = .025;
    /** 持续多久（刻）：5 分钟。 */
    public static final int TICKS = 6000;
    /** 珊瑚红（她的面板色）。 */
    public static final int COLOR = 0xE0566B;
    private static final Identifier ATTACK_ID = LogosDimension.id("lacerta_warmth_attack");

    public static final Holder<MobEffect> EFFECT = EFFECTS.register("lacerta_warmth", Effect::new);
    /** 测试用：给过几次。 */
    static int given;

    /** 羁绊在第 {@code stage} 段（效果等级）时伤害、减伤各几成。 */
    public static double bonus(int stage) {
        return BASE + STAGE * Math.max(0, stage);
    }

    /** 给 {@code p} 一份（羁绊第 {@code stage} 段）：绯翼余温加原版的抗火，都是 5 分钟。 */
    static void give(ServerPlayer p, int stage) {
        p.addEffect(new MobEffectInstance(EFFECT, TICKS, stage, false, true, true));
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, TICKS, 0, false, false, true));
        given++;
    }

    public static int given() {
        return given;
    }

    private static final class Effect extends MobEffect {
        Effect() {
            super(MobEffectCategory.BENEFICIAL, COLOR);
        }

        @Override
        public void createModifiers(int amplifier, BiConsumer<Holder<Attribute>, AttributeModifier> out) {
            out.accept(Attributes.ATTACK_DAMAGE, new AttributeModifier(ATTACK_ID, bonus(amplifier), AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }

        @Override
        public void addAttributeModifiers(AttributeMap map, int amplifier) {
            createModifiers(amplifier, (attribute, modifier) -> {
                var instance = map.getInstance(attribute);
                if (instance == null) return;
                instance.removeModifier(modifier.id());
                instance.addPermanentModifier(modifier);
            });
        }

        @Override
        public void removeAttributeModifiers(AttributeMap map) {
            createModifiers(0, (attribute, modifier) -> {
                var instance = map.getInstance(attribute);
                if (instance != null) instance.removeModifier(modifier.id());
            });
        }
    }

    /** 挨打少几成。 */
    private static void onHurt(LivingDamageEvent.Pre e) {
        MobEffectInstance warm = e.getEntity().getEffect(EFFECT);
        if (warm == null || e.getNewDamage() <= 0) return;
        e.setNewDamage((float) (e.getNewDamage() * (1 - bonus(warm.getAmplifier()))));
    }

    /** 不怕冻：身上的冻结一直清掉。 */
    private static void onTick(PlayerTickEvent.Post e) {
        var p = e.getEntity();
        if (!p.level().isClientSide() && p.getTicksFrozen() > 0 && p.hasEffect(EFFECT)) p.setTicksFrozen(0);
    }

    static void register(IEventBus bus) {
        EFFECTS.register(bus);
        NeoForge.EVENT_BUS.addListener(LacertaWarmth::onHurt);
        NeoForge.EVENT_BUS.addListener(LacertaWarmth::onTick);
    }

    private LacertaWarmth() {}
}
