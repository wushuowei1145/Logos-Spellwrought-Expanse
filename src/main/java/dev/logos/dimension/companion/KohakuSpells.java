package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Intent;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀自带的术式（docs/KOHAKU_COMPANION.md 第 5 节）：都是载页，放在 {@code data/logos_dimension/companion/spells/kohaku/*.logos}，
 * 可以直接改。伤害由 Logos 乘她的法术强度，治疗、护盾乘她的治疗强度（等于她的法术强度）。随爪之契越解锁越多。
 *
 * @param id       文件名（也是面板上能力的 ID）
 * @param seal     第几枚爪之契解锁（0 是本来就会）
 * @param intent   意图（她按意图挑页）
 * @param cooldown 这一页自己的冷却（刻）
 * @param aim      怎么放（对着谁、落在哪）
 */
record KohakuSpells(String id, int seal, Intent intent, int cooldown, Aim aim) {
    /**
     * 怎么放（施放启式的最外层没有 {@code target}，所以页里用 {@code here}、{@code (master)} 或弹射物命中的那个）：
     * <ul>
     *   <li>{@code FOE}：对着敌人直线放（她的视线就是方向）；</li>
     *   <li>{@code ARC}：对着敌人抛（代码按抛物线算仰角）；</li>
     *   <li>{@code CLUSTER}：落在敌群中心或强敌脚下（交给页的 {@code here}）；</li>
     *   <li>{@code ALLY}：给你或最需要的那位，落在那位脚下（{@code here}）；</li>
     *   <li>{@code AT}：对着那位同伴直线放（你给的、作用「单体」的支援页，多半是打中谁给谁的弹射物）；</li>
     *   <li>{@code SELF}：只作用施法者自己（你给的、写给自己的支援页）：放在她自己身上，只在她自己需要时用。</li>
     * </ul>
     */
    enum Aim { FOE, ARC, CLUSTER, ALLY, AT, SELF }

    /** 一滴就够：本来就会的单体攻击。 */
    static final KohakuSpells ONE_DROP = new KohakuSpells("one_drop", 0, Intent.STRIKE, 24, Aim.FOE);
    static final KohakuSpells CAT_EYE = new KohakuSpells("cat_eye", 1, Intent.UTILITY, 2400, Aim.ALLY);
    static final KohakuSpells BOILING_FLASK = new KohakuSpells("boiling_flask", 2, Intent.AREA, 120, Aim.ARC);
    static final KohakuSpells NAP_WARD = new KohakuSpells("nap_ward", 3, Intent.CONTROL, 280, Aim.CLUSTER);
    static final KohakuSpells GREENHOUSE = new KohakuSpells("greenhouse", 4, Intent.HEAL, 400, Aim.ALLY);
    static final KohakuSpells QUICK_ENCHANT = new KohakuSpells("quick_enchant", 5, Intent.EMPOWER, 900, Aim.ALLY);
    static final KohakuSpells TIME_AMBER = new KohakuSpells("time_amber", 6, Intent.CONTROL, 600, Aim.FOE);
    static final KohakuSpells MISTY_UMBRELLA = new KohakuSpells("misty_umbrella", 7, Intent.WARD, 500, Aim.ALLY);
    static final KohakuSpells STARBLOOM = new KohakuSpells("starbloom", 8, Intent.AREA, 800, Aim.CLUSTER);
    /** 猫步（本来就会）、两个人的猫步（第九印）：落点交给 here，由 {@link KohakuMoves} 放（有自己的起跳、落地动作）。 */
    static final KohakuSpells CAT_STEP = new KohakuSpells("cat_step", 0, Intent.MOVE, KohakuMoves.BLINK_COOLDOWN, Aim.CLUSTER);
    static final KohakuSpells TWO_STEP = new KohakuSpells("two_step", 9, Intent.MOVE, 0, Aim.CLUSTER);
    /** 她的页，按契印排。 */
    static final List<KohakuSpells> ALL = List.of(ONE_DROP, CAT_STEP, CAT_EYE, BOILING_FLASK, NAP_WARD, GREENHOUSE, QUICK_ENCHANT, TIME_AMBER,
            MISTY_UMBRELLA, STARBLOOM, TWO_STEP);
    /** 沸腾烧瓶飞多快（页上写的 :speed）、抛物线每刻往下拉多少（Logos 的 ARC），算仰角用。 */
    static final double FLASK_SPEED = 1, ARC_GRAVITY = .05;

    /**
     * 非玩家用不了的符文（docs/KOHAKU_COMPANION.md 第 3 节）：她施放时什么都不做。你给的页里有这些，她不放那一页。
     * {@code echo} 只给玩家看，不算（放了也没关系）。
     */
    static final Set<String> PLAYER_ONLY = Set.of("summon", "equip", "possess", "incarnate", "anchor", "unanchor", "use-block");

    private static @Nullable MinecraftServer server;
    private static int generation = -1;
    private static final Map<String, Optional<Spell>> CACHE = new HashMap<>();

    /**
     * 这一页编好的术式；「攻击玩家」开着时（{@code players}）加上 {@code :players true}。读不到、编不过时为 null（记一条日志）。
     * 资源重载、Logos 的限制变了以后重新编。
     */
    @Nullable Spell spell(MinecraftServer s, boolean players) {
        synchronized (CACHE) {
            if (server != s || generation != Casting.generation()) {
                server = s;
                generation = Casting.generation();
                CACHE.clear();
            }
            return CACHE.computeIfAbsent(id + "|" + players, k -> {
                try (var reader = s.getResourceManager().getResourceOrThrow(LogosDimension.id("companion/spells/kohaku/" + id + ".logos")).openAsReader()) {
                    String source = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                    if (players) source = source.replace(":trigger cast", ":trigger cast :players true");
                    return Optional.of(Casting.compile(source));
                } catch (Exception error) {
                    com.mojang.logging.LogUtils.getLogger().warn("琥珀的术式 {} 不可用", id, error);
                    return Optional.empty();
                }
            }).orElse(null);
        }
    }

    /** 盖了 {@code seals} 枚爪之契时会不会这一页。 */
    boolean unlocked(int seals) {
        return CompanionBond.unlocked(seals, seal);
    }

    static @Nullable KohakuSpells byId(String id) {
        for (KohakuSpells s : ALL) if (s.id.equals(id)) return s;
        return null;
    }

    // ---------------------------------------------------------------- 你给的术式

    /**
     * 你给她的那根杖上挂着的一页（第 5.3 节）。{@code key} 记冷却用；冷却按注入量算（每 100 魔力 1 秒，至少 2 秒），免得一页连放。
     * 怎么放按意图：攻击、控制对着敌人，范围落在敌群中心，治疗、守护、强化给你或最需要的那位；位移、杂务她不自己放。
     */
    record Yours(String key, Spell spell, PageTier tier, Intent intent, Aim aim, int cooldown) {}

    /** 你给的杖 {@code wand} 上她能放的页；带着非玩家用不了的符文的、位移和杂务的都不算。 */
    static List<Yours> yours(ItemStack wand) {
        List<Yours> out = new ArrayList<>();
        for (int i = 0; i < PageContainer.size(wand); i++) {
            ItemStack page = PageContainer.get(wand, i);
            if (!SpellPages.isInscribed(page)) continue;
            Spell spell = SpellPages.spell(page);
            if (spell == null || !spell.trigger().equals("cast") || playerOnly(spell)) continue;
            Intent intent = spell.profile().primary();
            Aim aim = aimFor(intent, spell.profile().reach().getOrDefault(intent, Set.of(dev.logos.lang.Reach.GROUP)));
            if (aim == null) continue;
            PageTier tier = SpellPages.tier(page);
            if (tier == null) tier = PageTier.BASIC;
            double mana = Casting.injection(spell, tier, 0).mana();
            out.add(new Yours("yours:" + i + ":" + spell.name(), spell, tier, intent, aim, (int) Math.max(40, mana / 100 * 20)));
        }
        return out;
    }

    /**
     * 你给的页按意图和作用对象（Logos 推断的 {@code reach}）怎么放；她不自己放的为 null：位移、杂务，还有只作用自己的强化
     * （写给你自己的强化，放在她身上没用，她又不近战）。支援页作用得到群体的落在那位脚下，作用单体的对着那位放，只作用自己的给她自己。
     */
    static @Nullable Aim aimFor(Intent intent, Set<dev.logos.lang.Reach> reach) {
        return switch (intent) {
            case STRIKE, CONTROL -> Aim.FOE;
            case AREA -> Aim.CLUSTER;
            case HEAL, WARD, EMPOWER -> reach.contains(dev.logos.lang.Reach.GROUP) ? Aim.ALLY
                    : reach.contains(dev.logos.lang.Reach.ONE) ? Aim.AT : intent == Intent.EMPOWER ? null : Aim.SELF;
            case MOVE, UTILITY -> null;
        };
    }

    static @Nullable Aim aimFor(Intent intent) {
        return aimFor(intent, Set.of(dev.logos.lang.Reach.GROUP));
    }

    /** 这一页用了非玩家用不了的符文（{@link #PLAYER_ONLY}）。 */
    static boolean playerOnly(Spell spell) {
        for (Node n : spell.body()) if (uses(n)) return true;
        return false;
    }

    private static boolean uses(Node n) {
        if (n.kind == Node.Kind.CALL && n.rune != null && PLAYER_ONLY.contains(n.rune.name)) return true;
        for (Node a : n.args) if (uses(a)) return true;
        for (Node a : n.kw.values()) if (uses(a)) return true;
        for (Node a : n.body) if (uses(a)) return true;
        for (List<Node> e : n.events.values()) for (Node a : e) if (uses(a)) return true;
        return false;
    }
}
