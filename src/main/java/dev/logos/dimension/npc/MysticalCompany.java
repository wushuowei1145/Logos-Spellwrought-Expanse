package dev.logos.dimension.npc;

import dev.logos.dimension.LogosDimension;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 灵猫相伴：亲近的灵猫在身边时，她的魔力场域让法师更容易感知、控制魔力（设定：灵猫能极大提升魔法师的魔力亲和度）。
 * 法力回复 +50%、蓄力快一成，用的是 Logos 的 {@code mana_regeneration} 与 {@code channel_speed} 属性。
 * 由 {@link MysticalCat} 每两秒续一次，离开她身边十秒左右消失。
 */
public final class MysticalCompany {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, LogosDimension.ID);
    public static final double REGEN = .5, CHANNEL = .1;
    /** 青色，同灵猫的菱形印记。 */
    public static final int COLOR = 0x5FDCDD;

    public static final Holder<MobEffect> EFFECT = EFFECTS.register("mystical_company", () -> new Effect()
            .addAttributeModifier(SpellModifiers.MANA_REGENERATION, LogosDimension.id("mystical_company_regen"), REGEN,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
            .addAttributeModifier(SpellModifiers.CHANNEL_SPEED, LogosDimension.id("mystical_company_channel"), CHANNEL,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE));

    private static final class Effect extends MobEffect {
        Effect() {
            super(MobEffectCategory.BENEFICIAL, COLOR);
        }
    }

    public static void register(IEventBus bus) {
        EFFECTS.register(bus);
    }

    private MysticalCompany() {}
}
