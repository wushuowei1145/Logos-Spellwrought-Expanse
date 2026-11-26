package dev.logos.dimension.firearm;

import dev.logos.graph.RuneNodes;
import dev.logos.lang.Rune;
import dev.logos.lang.Runes;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.HostedSpells;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 术式弹（docs/FIREARMS.md 第 9 节）：三种弹各是 Logos 的一个寄托形体（{@code Runes.hosted}），术式写在弹药图纸上，
 * 「启式 → 这种弹 → 命中生物 / 命中方块 / 消散」，在铸弹台上刻进普通弹（{@link EtchedSpell}）。开枪时弹药先照常结算物理伤害，
 * 再带着刻进去的术式（施术者是开枪的人，魔力是刻的时候注入的）按命中情况触发事件：
 * <ul>
 *   <li>{@code on-hit}：每个被打中的生物一次（霰弹几颗打中同一个只算一次，步枪弹穿透的每个都算）；here 是命中点，target 是它。</li>
 *   <li>{@code on-impact}：一个都没打中、弹道落在方块上时；here 是那个方块（霰弹看正中那条弹道）。</li>
 *   <li>{@code on-expire}：一个都没打中、飞到射程尽头时；here 是尽头那一点。</li>
 * </ul>
 */
public final class SpellRounds {
    private SpellRounds() {}

    public static final String BUCKSHOT = "buckshot-round", RIFLE = "rifle-round", BLANK = "blank-round";

    /** 在 Logos 里登记三种弹，编辑器里各有一个节点。模组构造时调用，在任何术式编译之前。 */
    public static void register() {
        if (Runes.get(BUCKSHOT) != null) return;
        hosted(BUCKSHOT, "霰弹：铳打出的一发霰弹，八颗弹丸锥形散开。物理伤害由弹头金属决定，术式另算，只写在弹药图纸上、刻进霰弹里才会生效。",
                "每个被弹丸打中的生物一次（几颗打中同一个只算一次；here 是命中点）");
        hosted(RIFLE, "步枪弹：铳打出的一发步枪弹，沿视线直射。物理伤害由弹头金属决定，术式另算，只写在弹药图纸上、刻进步枪弹里才会生效。",
                "每个被打中的生物一次（下界合金弹头穿透的也算；here 是命中点）");
        hosted(BLANK, "空弹头：铳打出的一发空弹头，没有弹丸，只有一道沿视线直射的术式。不带物理伤害，能注入的魔力是同档的两倍，只写在弹药图纸上、刻进空弹头里才会生效。",
                "打中的第一个生物（here 是命中点）");
        RuneNodes.BY_RUNE.put(BUCKSHOT, RoundNodes.Buckshot::new);
        RuneNodes.BY_RUNE.put(RIFLE, RoundNodes.Rifle::new);
        RuneNodes.BY_RUNE.put(BLANK, RoundNodes.Blank::new);
    }

    private static void hosted(String word, String doc, String hit) {
        Runes.hosted(word, doc,
                new Rune.EventSpec("on-hit", true, hit),
                new Rune.EventSpec("on-impact", false, "一个都没打中、弹道落在方块上时（here 是那个方块）"),
                new Rune.EventSpec("on-expire", false, "一个都没打中、飞到射程尽头时（here 是尽头那一点）"));
    }

    /** 这种弹在术式里是哪个词：霰弹、步枪弹、空弹头；不是能刻术式的弹就是 null。 */
    public static @Nullable String word(Item item) {
        if (item instanceof BuckshotItem) return BUCKSHOT;
        if (item instanceof RifleRoundItem) return RIFLE;
        if (item instanceof BlankRoundItem) return BLANK;
        return null;
    }

    /** 能注入多少：空弹头没有弹丸，同一档能注入的魔力加倍。 */
    public static int capacity(String word, int tierCapacity) {
        return word.equals(BLANK) ? tierCapacity * 2 : tierCapacity;
    }

    /** {@code source} 写的是不是 {@code word} 这种弹的术式（「启式 → 这种弹」），是就返回编译好的术式。 */
    public static @Nullable Spell spellFor(@Nullable String source, String word) {
        Spell spell = source == null ? null : Casting.compileOrNull(source);
        var form = spell == null ? null : HostedSpells.form(spell);
        return form != null && form.rune.name.equals(word) ? spell : null;
    }

    /** 这一发刻着的术式，带上它出发；没刻、或者原文编译不过了（Logos 改过符文）就是 null，只剩物理伤害。 */
    public static HostedSpells.@Nullable Carried carry(Ammo.Shot shot, String word) {
        var etched = shot.round().get(Firearms.ETCHED.get());
        Spell spell = etched == null ? null : spellFor(etched.source(), word);
        if (spell == null) return null;
        return HostedSpells.carry(spell, shot.shooter(), etched.mana(), etched.pageTier().recovery());
    }

    /** 一发打中的一个生物和打中的位置。 */
    public record Struck(LivingEntity entity, Vec3 at) {}

    /**
     * 按这一发的结果触发事件然后收尾：打中的生物各一次 {@code on-hit}；一个都没打中时，弹道落在方块上是 {@code on-impact}，
     * 飞到尽头 {@code end} 是 {@code on-expire}。{@code carried} 为空（没刻术式）时什么都不做。
     */
    public static void strike(HostedSpells.@Nullable Carried carried, ServerLevel level, Vec3 dir, List<Struck> struck,
                              @Nullable BlockHitResult block, Vec3 end) {
        if (carried == null) return;
        for (var s : struck) carried.fire("on-hit", level, s.at(), dir, s.entity());
        if (struck.isEmpty()) {
            if (block != null) carried.fire("on-impact", level, Vec3.atCenterOf(block.getBlockPos()), dir, null);
            else carried.fire("on-expire", level, end, dir, null);
        }
        carried.end();
    }

    // ---------------------------------------------------------------- 物品上的样子

    /** 刻了术式的弹：名字后面带上术式名，像写好的载页那样。 */
    public static Component name(ItemStack stack, Component plain) {
        var etched = stack.get(Firearms.ETCHED.get());
        Spell spell = etched == null ? null : Casting.compileOrNull(etched.source());
        return spell == null ? plain : plain.copy().append("《").append(spell.name()).append("》");
    }

    /** 刻了术式的弹的说明：第几档、每发注入多少。 */
    public static void tooltip(ItemStack stack, Consumer<Component> builder) {
        var etched = stack.get(Firearms.ETCHED.get());
        if (etched == null) return;
        builder.accept(Component.translatable("tooltip.logos_dimension.etched_round", etched.tier(), (int) Math.round(etched.mana()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    public static boolean etched(ItemStack stack) {
        return stack.has(Firearms.ETCHED.get());
    }
}
