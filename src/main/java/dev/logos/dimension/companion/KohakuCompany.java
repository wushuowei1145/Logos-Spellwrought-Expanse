package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.runtime.SpellModifiers;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 琥珀相伴（docs/KOHAKU_COMPANION.md 第 9.5 节）：和她一起睡醒以后的几分钟，魔力回复 +50%、汇集快一成（同灵猫相伴，两个效果可以叠）；
 * 羁绊每段魔力回复再 +10%（到「羁绊」段 +90%）。羁绊段数记在效果等级里（0 起），所以回复的加成不是原版那样按等级成倍，而是这里自己算。
 */
public final class KohakuCompany {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, LogosDimension.ID);
    /** 魔力回复：基础、羁绊每段；汇集速度。 */
    public static final double REGEN = .5, STAGE_REGEN = .1, CHANNEL = .1;
    /** 持续多久（刻）：5 分钟。 */
    public static final int TICKS = 6000;
    /** 琥珀色。 */
    public static final int COLOR = 0xE8A043;
    private static final Identifier REGEN_ID = LogosDimension.id("kohaku_company_regen"), CHANNEL_ID = LogosDimension.id("kohaku_company_channel");

    public static final Holder<MobEffect> EFFECT = EFFECTS.register("kohaku_company", Effect::new);

    /** 羁绊在第 {@code stage} 段（效果等级）时魔力回复加几成。 */
    public static double regen(int stage) {
        return REGEN + STAGE_REGEN * Math.max(0, stage);
    }

    private static final class Effect extends MobEffect {
        Effect() {
            super(MobEffectCategory.BENEFICIAL, COLOR);
        }

        @Override
        public void createModifiers(int amplifier, BiConsumer<Holder<Attribute>, AttributeModifier> out) {
            out.accept(SpellModifiers.MANA_REGENERATION, new AttributeModifier(REGEN_ID, regen(amplifier), AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            out.accept(SpellModifiers.CHANNEL_SPEED, new AttributeModifier(CHANNEL_ID, CHANNEL, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
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

    static void register(IEventBus bus) {
        EFFECTS.register(bus);
    }

    private KohakuCompany() {}
}
