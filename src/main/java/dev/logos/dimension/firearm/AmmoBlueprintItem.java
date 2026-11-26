package dev.logos.dimension.firearm;

import dev.logos.LogosMod;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPageItem;
import dev.logos.item.SpellPages;
import dev.logos.lang.Node;
import dev.logos.runtime.HostedSpells;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 弹药图纸：写术式弹的术式用的载页（docs/FIREARMS.md 第 9 节）。合成时选弹种（纸、金粒加一发那种弹），
 * 做出来就写好了「启式 → 这种弹」，拿到术式编纂台上像写载页一样接上命中生物、命中方块、消散三个事件；
 * 写好后在铸弹台上把它刻进普通弹，图纸不消耗。
 * <p>
 * 它就是一张 Logos 载页（拓印、符文解锁、书灵都照常），品级取最高一档只是为了编纂台不按载页承载报"超出"：
 * 每发能注入多少由铸弹台的蚀刻档位决定。挂到法杖上直接施放时，寄托形体只会让术式结束。
 */
public class AmmoBlueprintItem extends SpellPageItem {
    public static final DeferredItem<AmmoBlueprintItem> ITEM = DimensionBlocks.ITEMS.registerItem("ammo_blueprint",
            p -> new AmmoBlueprintItem(p.stacksTo(1)));

    public AmmoBlueprintItem(Properties properties) {
        super(properties, PageTier.PRINCIPLE, true);
    }

    public static void init() {}

    /** 一张写着 {@code source} 的图纸（合成配方用同样的原文，测试和创造物品栏用这个）。 */
    public static ItemStack of(String source) {
        ItemStack stack = new ItemStack(ITEM.get());
        stack.set(LogosMod.SPELL_SOURCE.get(), source);
        return stack;
    }

    /** 三种弹各一张新图纸，和合成配方做出来的一样（创造物品栏用）。 */
    public static java.util.List<ItemStack> templates() {
        return java.util.List.of(of(template(SpellRounds.BUCKSHOT, "霰弹术式")), of(template(SpellRounds.RIFLE, "步枪弹术式")),
                of(template(SpellRounds.BLANK, "空弹头术式")));
    }

    /** 新图纸上写好的那一句：「启式 → 这种弹」，三个事件还空着。 */
    public static String template(String word, String name) {
        return "(spell :name \"" + name + "\" :mana 10 (" + word + "))";
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        var spell = SpellPages.spell(stack);
        var form = spell == null ? null : HostedSpells.form(spell);
        if (form == null) {
            builder.accept(Component.translatable("tooltip.logos_dimension.ammo_blueprint.broken").withStyle(ChatFormatting.RED));
            return;
        }
        builder.accept(Component.translatable("tooltip.logos_dimension.ammo_blueprint.round", Component.translatable("logos.rune." + form.rune.name))
                .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.logos_dimension.ammo_blueprint.mana", Node.fmt(spell.mana()), Node.fmt(spell.stability()))
                .withStyle(ChatFormatting.AQUA));
        builder.accept(Component.translatable(form.events.isEmpty() ? "tooltip.logos_dimension.ammo_blueprint.empty"
                : "tooltip.logos_dimension.ammo_blueprint.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
