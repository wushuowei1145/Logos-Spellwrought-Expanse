package dev.logos.dimension.companion;

import dev.logos.dimension.companion.CompanionTalk.Line;
import dev.logos.dimension.companion.CompanionTalk.Reply;
import dev.logos.dimension.companion.TalkMenu.Option;
import dev.logos.dimension.LogosDimension;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.runtime.SpellModifiers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀的对话和档案（docs/KOHAKU_COMPANION.md 第 13 节）：她的话题、她怎么回、角色面板上她的数值、能力和往事。
 * 台词都在语言文件 {@code line.logos_dimension.kohaku.talk.*}；她一直用「你」，句子短，常常「嗯……」「麻烦」开头，
 * 说到炼金和术式就停不下来。往事写成她的炼金笔记：头几段是她一个人的事，后来慢慢有了你。
 */
final class KohakuTalk {
    static final String ID = KohakuCompanion.ID;
    private static final String LINE = "line.logos_dimension.kohaku.talk.";
    static final int MAX_SEALS = KohakuCompanion.MAX_SEALS;
    /** 往事：第 0 段本来就记得，之后每盖一枚爪之契她多讲一段，一共十段；每段几句。角色面板的「往事」一页可以重读。 */
    static final int[] MEMORY_LINES = {3, 2, 2, 2, 2, 2, 2, 2, 3, 3};
    /** 现在穿的是开衫制服（别的几套装束以后做）。 */
    static final String OUTFIT = "cardigan";

    /** 聊天里她自己的话题；其余（包袱、作战策略、详细数据、一会再聊）是通用的。 */
    static final List<Option> MENU = TalkMenu.standard(List.of(
            Option.server("chat.coin"), Option.server("chat.food"), Option.server("chat.alchemy"), Option.server("chat.critique"),
            Option.server("chat.lazy"), Option.server("chat.city"), Option.server("chat.money"), Option.server("chat.allowance"),
            Option.server("chat.enforcers"), Option.server("chat.past"), Option.server("chat.seals"), Option.server("chat.us"),
            Option.server("chat.rest")), true);
    /** 帮你看载页：一天第一次加多少羁绊。注入的魔力比一次花的多出这么多倍、又多出这么多点，算浪费。 */
    static final float CRITIQUE_BOND = 2;
    static final double WASTE_RATIO = 3, WASTE_MIN = 40;
    /** 零花钱：一先令（12 便士）；只有金镑时收一枚、找你 19 先令。 */
    static final int ALLOWANCE_PENNIES = 12, SOVEREIGN_CHANGE = 19;
    /** 测试用：帮你看过几次载页、收过几次零花钱；最近一次点评说的是哪一种。 */
    static int critiques, allowances;
    static String lastVerdict = "";

    private KohakuTalk() {}

    /** 两边都要登记选项树（客户端画、服务端认），模组加载时调用。 */
    static void init() {
        TalkMenu.register(ID, MENU);
    }

    static Line line(String id, Object... args) {
        return Line.of(LINE + id, args);
    }

    private static String stage(KohakuCompanion c, ServerPlayer p) {
        return CompanionBond.Stage.of(c.bond(p).affection()).name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- 档案

    static CompanionSheet sheet(KohakuCompanion c, ServerPlayer p) {
        var bond = c.bond(p);
        int seals = Math.min(bond.seals(), MAX_SEALS);
        // 面板开着时每秒刷新：先按主人现在的装备算一遍
        CompanionStats.Parts parts = CompanionStats.apply(c, p, bond.seals());
        CompanionStats.Part power = c.powerParts(), mana = c.manaParts();
        float sp = c.spellPower();
        List<CompanionSheet.Stat> stats = List.of(
                stat("health", c.getMaxHealth(), parts.health(), c.getHealth()),
                stat("spell_power", sp, power, -1),
                new CompanionSheet.Stat("mana", (float) c.maxMana(), (float) mana.own(), (float) mana.bond(), (float) c.mana()),
                stat("armor", (float) c.getAttributeValue(Attributes.ARMOR), parts.armor(), -1));
        ItemStack staff = c.staff.held();
        List<CompanionSheet.Skill> skills = new ArrayList<>(List.of(
                new CompanionSheet.Skill("one_drop", CompanionSheet.NORMAL, 0, true, List.of(num(7 * sp), num(KohakuSpells.ONE_DROP.cooldown() / 20f))),
                new CompanionSheet.Skill("ward", CompanionSheet.SPECIAL, 0, true, List.of(num(KohakuMagic.wardMultiplier(seals)),
                        num(c.magic.wardAmount()), num(KohakuMagic.WARD_COST * 100), num(KohakuMagic.WARD_REFORM / 20f))),
                new CompanionSheet.Skill("conjure", CompanionSheet.SPECIAL, 0, true, List.of(num(KohakuMoves.SWEEP_DAMAGE * sp
                        * (c.staff.blade() ? 1 + KohakuMoves.BLADE_BONUS : 1)), num(KohakuMoves.SWEEP_RADIUS), num(KohakuMoves.SWEEP_COOLDOWN / 20f))),
                new CompanionSheet.Skill("cat_step", CompanionSheet.SPECIAL, 0, true, List.of(num(KohakuMoves.BLINK_MIN), num(KohakuMoves.BLINK_MAX),
                        num(KohakuMoves.BLINK_COOLDOWN / 20f))),
                page(KohakuSpells.CAT_EYE, seals, "120", "16"),
                page(KohakuSpells.BOILING_FLASK, seals, num(5 * sp), "3", "3"),
                page(KohakuSpells.NAP_WARD, seals, "4", "6"),
                page(KohakuSpells.GREENHOUSE, seals, num(1.5 * sp), "8"),
                page(KohakuSpells.QUICK_ENCHANT, seals, num(2 * sp), "30"),
                page(KohakuSpells.TIME_AMBER, seals, "3"),
                page(KohakuSpells.MISTY_UMBRELLA, seals, num(6 * sp), "6"),
                page(KohakuSpells.STARBLOOM, seals, num(12 * sp), "5"),
                new CompanionSheet.Skill("two_step", CompanionSheet.SPECIAL, KohakuSpells.TWO_STEP.seal(), KohakuSpells.TWO_STEP.unlocked(seals),
                        List.of(num(KohakuMind.CARRY_BELOW * 100))),
                new CompanionSheet.Skill("your_pages", CompanionSheet.PASSIVE, 0, true, List.of(String.valueOf(c.staff.yoursPages().size()))),
                new CompanionSheet.Skill("staff", CompanionSheet.PASSIVE, 0, true, List.of("#" + staff.getItem().getDescriptionId(),
                        num(c.staff.capacity()), c.staff.yours() ? "#talk.logos_dimension.kohaku.skill.staff.yours" : "#talk.logos_dimension.kohaku.skill.staff.own")),
                new CompanionSheet.Skill("mana", CompanionSheet.PASSIVE, 0, true, List.of(num(c.maxMana()), num(c.regenPerSecond()))),
                new CompanionSheet.Skill("company", CompanionSheet.PASSIVE, 0, true, List.of(num(KohakuCompany.TICKS / 1200f),
                        num(KohakuCompany.regen(CompanionBond.Stage.of(bond.affection()).ordinal()) * 100), num(KohakuCompany.CHANNEL * 100))),
                new CompanionSheet.Skill("genius", CompanionSheet.PASSIVE, 0, true, List.of(num(sp))),
                new CompanionSheet.Skill("withdraw", CompanionSheet.PASSIVE, 0, true, List.of(num(KohakuCompanion.REST_TICKS / 1200f)))));
        CompanionBond.Stage st = CompanionBond.Stage.of(bond.affection());
        CompanionBond.Stage[] all = CompanionBond.Stage.values();
        float progress = st.ordinal() + 1 >= all.length ? 1 : (bond.affection() - st.from) / (all[st.ordinal() + 1].from - st.from);
        return new CompanionSheet(c.getId(), ID, OUTFIT, stats, List.copyOf(skills), seals, MAX_SEALS, st.name(), progress,
                bond.satiety(), CompanionBond.SATIETY_MAX, c.staying(), bond.tactic(), bond.pvp(), memories(seals), MEMORY_LINES.length,
                (float) KohakuCompanion.GROWTH.ratio(bond.seals()));
    }

    /** 她自己的一页：第几枚解锁、会了没有；说明里的数（最后一个总是冷却秒数）。 */
    private static CompanionSheet.Skill page(KohakuSpells s, int seals, String... args) {
        List<String> a = new ArrayList<>(List.of(args));
        a.add(num(s.cooldown() / 20f));
        return new CompanionSheet.Skill(s.id(), CompanionSheet.SPECIAL, s.seal(), s.unlocked(seals), a);
    }

    private static CompanionSheet.Stat stat(String id, float value, CompanionStats.Part part, float current) {
        return new CompanionSheet.Stat(id, value, (float) part.own(), (float) part.bond(), current);
    }

    static int memories(int seals) {
        return Math.min(Math.max(0, seals), MEMORY_LINES.length - 1) + 1;
    }

    /** 数字一位小数，整数不带小数点。 */
    static String num(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    // ---------------------------------------------------------------- 对话

    /** 刚开口：你受伤了先数落你（其实是担心）；她饿了就直说；晚上说晚上的话；平时按羁绊说（每段两种说法）。 */
    static List<Line> greeting(KohakuCompanion c, ServerPlayer p) {
        long now = c.level().getGameTime();
        var bond = c.bond(p);
        if (p.getHealth() < p.getMaxHealth() * .4f) {
            c.mood.talk("face_sad", now, 80);
            return List.of(line("greet_hurt"));
        }
        if (bond.satiety() < CompanionBond.HUNGRY) {
            c.mood.act("act_sigh");
            return List.of(line("greet_hungry"));
        }
        if (c.level().isDarkOutside() && c.getRandom().nextFloat() < .4f) {
            c.mood.talk("face_curious", now, 80);
            return List.of(line("greet_night"));
        }
        c.mood.talk("face_content", now, 80);
        return List.of(line("greet_" + stage(c, p) + "_" + (1 + c.getRandom().nextInt(2))));
    }

    static Reply pick(KohakuCompanion c, ServerPlayer p, String option) {
        long now = c.level().getGameTime();
        var bond = c.bond(p);
        switch (option) {
            case "chat.coin" -> {
                return coin(c, p, now);
            }
            case "chat.food" -> {
                c.mood.talk("face_happy", now, 100);
                List<Line> out = new ArrayList<>(List.of(line("food_1"), line("food_2")));
                if (bond.satiety() < CompanionBond.HUNGRY) out.add(line("food_hungry"));
                return Reply.say(out);
            }
            case "chat.alchemy" -> {
                c.mood.act("act_formula");
                c.mood.talk("face_focus", now, 160);
                return Reply.say(line("alchemy_1"), line("alchemy_2"), line("alchemy_3"));
            }
            case "chat.lazy" -> {
                c.mood.talk("face_bored", now, 120);
                return Reply.say(line("lazy_1"), line("lazy_2"));
            }
            case "chat.city" -> {
                c.mood.talk("face_content", now, 160);
                return Reply.say(line("city_1"), line("city_2"), line("city_3"));
            }
            case "chat.critique" -> {
                return critique(c, p, now);
            }
            case "chat.allowance" -> {
                return allowance(c, p, now);
            }
            case "chat.money" -> {
                c.mood.act("act_coin_flip");
                c.mood.talk("face_smug", now, 120);
                return Reply.say(line("money_1"), line("money_2"));
            }
            case "chat.enforcers" -> {
                c.mood.talk("face_smug", now, 120);
                return Reply.say(line("enforcers_1"), line("enforcers_2"));
            }
            case "chat.past" -> {
                int n = memories(bond.seals()) - 1;
                c.mood.talk(n == 0 ? "face_smug" : "face_content", now, 160);
                List<Line> out = new ArrayList<>();
                for (int i = 1; i <= MEMORY_LINES[n]; i++) out.add(line("past_" + n + "_" + i));
                if (n < MEMORY_LINES.length - 1) out.add(line("past_more"));
                return Reply.say(out);
            }
            case "chat.seals" -> {
                int seals = Math.min(bond.seals(), MAX_SEALS);
                c.mood.talk("face_smug", now, 100);
                if (seals <= 0) return Reply.say(line("seals_none_1"), line("seals_none_2"));
                if (seals >= MAX_SEALS) return Reply.say(line("seals_full"));
                return Reply.say(line("seals_1"), line("seals_2", seals, MAX_SEALS - seals));
            }
            case "chat.us" -> {
                String st = stage(c, p);
                c.mood.talk(switch (st) {
                    case "stranger" -> "face_lazy";
                    case "familiar" -> "face_content";
                    case "close" -> "face_smug";
                    case "trusted" -> "face_content";
                    default -> "face_flustered";
                }, now, 100);
                return Reply.say(line("us_" + st));
            }
            case "chat.rest" -> {
                CompanionLines.say(c, p, "talk.rest", 50);
                Companions.dismiss(p, c.def());
                return Reply.closing();
            }
            case TalkMenu.POUCH -> {
                CompanionPouch.open(p, c);
                return Reply.away();
            }
            case TalkMenu.FOLLOW -> {
                c.setStaying(false);
                c.mood.talk("face_lazy", now, 60);
                return Reply.say(line("follow"));
            }
            case TalkMenu.STAY -> {
                c.setStaying(true);
                c.mood.talk("face_content", now, 60);
                return Reply.say(line("stay"));
            }
            case TalkMenu.PVP -> {
                boolean on = !bond.pvp();
                c.setBond(p, bond.withPvp(on));
                c.mood.talk(on ? "face_focus" : "face_lazy", now, 60);
                if (on && !p.canHarmPlayer(p)) return Reply.say(line("pvp_on"), line("pvp_off_server"));
                return Reply.say(line(on ? "pvp_on" : "pvp_off"));
            }
            default -> {
            }
        }
        if (option.startsWith("tactic.")) {
            CompanionTactic t = CompanionTactic.byId(option.substring("tactic.".length()));
            c.setBond(p, bond.withTactic(t));
            switch (t) {
                case ADAPTIVE -> c.mood.talk("face_lazy", now, 60);
                case PASSIVE -> c.mood.talk("face_happy", now, 60);
                case MELEE -> c.mood.talk("face_bored", now, 60);
                default -> c.mood.talk("face_smug", now, 60);
            }
            return Reply.say(line("tactic_" + t.id));
        }
        return Reply.NONE;
    }

    /** 我弄丢了爪印金镑：还在身上就笑你；不然要一枚真的灵猫金镑（创造模式不用），给你再压一枚。 */
    private static Reply coin(KohakuCompanion c, ServerPlayer p, long now) {
        if (PawSovereignItem.carries(p)) {
            c.mood.talk("face_smug", now, 80);
            return Reply.say(line("coin_have"));
        }
        boolean free = p.getAbilities().instabuild;
        int slot = -1;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && slot < 0; i++) if (inv.getItem(i).is(KohakuCompanion.catSovereign())) slot = i;
        if (!free && slot < 0) {
            c.mood.talk("face_bored", now, 80);
            return Reply.say(line("coin_need"));
        }
        if (!free) inv.removeItem(slot, 1);
        ItemStack coin = PawSovereignItem.bound(p);
        if (!inv.add(coin)) p.drop(coin, false);
        c.mood.act("act_coin_flip");
        return Reply.say(Line.of("line.logos_dimension.kohaku.remake"), line("coin_given"));
    }

    // ---------------------------------------------------------------- 帮我看看这张（第 10 节第 6 条）

    /** 你手里（主手，不是就副手）的载页，她看一眼，一句话点评。一天第一次加一点羁绊。只说话，不改你的页。 */
    private static Reply critique(KohakuCompanion c, ServerPlayer p, long now) {
        ItemStack page = SpellPages.isPage(p.getMainHandItem()) ? p.getMainHandItem() : p.getOffhandItem();
        String v = verdict(page, p.getAttributeValue(SpellModifiers.MAX_MANA));
        lastVerdict = v;
        critiques++;
        if (!v.equals("none")) {
            c.mood.act("act_formula");
            long day = c.level().getOverworldClockTime() / 24000;
            KohakuDays d = KohakuLife.days(p);
            if (d.critiqueDay() != day) {
                p.setData(Companions.KOHAKU_DAYS, d.withCritique(day));
                var b = c.bond(p);
                c.setBond(p, b.withAffection(b.affection() + CRITIQUE_BOND));
            }
        }
        c.mood.talk(switch (v) {
            case "good", "good_strike" -> "face_smug";
            case "none", "blank" -> "face_bored";
            default -> "face_focus";
        }, now, 120);
        return Reply.say(line("critique_" + v));
    }

    /**
     * 点评哪一种：没拿载页、空白的、编不过、纸撑不住（稳定度超过这一档纸的容量）、你自己的魔力放一次都不够、注入的魔力不够它花、
     * 有一段永远走不到（条件写死了）、注入的魔力大半用不上、递归、都还行（打人的另说一句）。
     */
    static String verdict(@Nullable ItemStack page, double ownerMaxMana) {
        if (page == null || !SpellPages.isPage(page)) return "none";
        if (SpellPages.isBlank(page) || !SpellPages.isInscribed(page)) return "blank";
        Spell s = SpellPages.spell(page);
        if (s == null) return "broken";
        PageTier tier = SpellPages.tier(page);
        return verdict(s, tier == null ? Integer.MAX_VALUE : tier.capacity(), ownerMaxMana);
    }

    /** 编得过的那一页怎么评：{@code capacity} 是这一档纸撑得住的稳定度，{@code ownerMaxMana} 是你的魔力上限。 */
    static String verdict(Spell s, int capacity, double ownerMaxMana) {
        if (s.stability() > capacity) return "unstable";
        if (s.mana() > ownerMaxMana) return "costly";
        double use = s.profile().estimate();
        if (use > s.mana() + 1e-6) return "short";
        if (dead(s.body())) return "dead";
        if (s.mana() > use * WASTE_RATIO && s.mana() - use > WASTE_MIN) return "waste";
        if (s.notes().stream().anyMatch(n -> n.contains("recurse"))) return "recursive";
        return switch (s.profile().primary()) {
            case STRIKE, AREA -> "good_strike";
            default -> "good";
        };
    }

    /** 有一段永远走不到：{@code if} 的条件是写死的（一个字面量），总有一边不会走。 */
    static boolean dead(List<Node> body) {
        for (Node n : body) {
            if (n == null || n.kind != Node.Kind.CALL) continue;
            if ("if".equals(n.rune.name) && n.arg(0) != null && n.arg(0).kind == Node.Kind.LIT) return true;
            if (dead(n.args) || dead(n.body)) return true;
            for (List<Node> e : n.events.values()) if (dead(e)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 给你零花钱（第 10 节第 7 条）

    /**
     * 给她零花钱：一天一次，收你一先令（没有先令就收 12 便士；只有金镑就收一枚、找你 19 先令；创造模式不用给）。她弹一下金镑收下，
     * 第二天早上的礼物里稀有的那一档概率翻倍（第五步做醒来的礼物）。
     */
    private static Reply allowance(KohakuCompanion c, ServerPlayer p, long now) {
        long day = c.level().getOverworldClockTime() / 24000;
        KohakuDays d = KohakuLife.days(p);
        if (d.allowanceDay() == day) {
            c.mood.talk("face_smug", now, 80);
            return Reply.say(line("allowance_today"));
        }
        boolean change = false;
        if (!p.getAbilities().instabuild) {
            Inventory inv = p.getInventory();
            if (!take(inv, coin("cat_shilling"), 1) && !take(inv, coin("cat_penny"), ALLOWANCE_PENNIES)) {
                if (!take(inv, coin("cat_sovereign"), 1)) {
                    c.mood.talk("face_bored", now, 80);
                    return Reply.say(line("allowance_none"));
                }
                ItemStack back = new ItemStack(coin("cat_shilling"), SOVEREIGN_CHANGE);
                if (!inv.add(back)) p.drop(back, false);
                change = true;
            }
        }
        p.setData(Companions.KOHAKU_DAYS, d.withAllowance(day));
        allowances++;
        c.mood.act("act_coin_flip");
        ((KohakuMood) c.mood).smug(now);
        return Reply.say(line(change ? "allowance_change" : "allowance_given"));
    }

    private static Item coin(String id) {
        return BuiltInRegistries.ITEM.getValue(LogosDimension.id(id));
    }

    /** 背包里有 {@code n} 个 {@code item} 就拿走、返回真；不够就一个都不拿。 */
    private static boolean take(Inventory inv, Item item, int n) {
        int have = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) have += inv.getItem(i).getCount();
        if (have < n) return false;
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item)) continue;
            int k = Math.min(n, s.getCount());
            s.shrink(k);
            n -= k;
        }
        return true;
    }

    static void ended(KohakuCompanion c, ServerPlayer p, boolean bye) {
        if (!bye) return;
        CompanionLines.say(c, p, "talk.bye", 50);
        c.mood.act("act_wave");
    }

    static void interrupted(KohakuCompanion c, ServerPlayer p) {
        CompanionLines.say(c, p, "talk.danger", 50);
    }

    static Line back(KohakuCompanion c, ServerPlayer p) {
        c.mood.talk("face_content", c.level().getGameTime(), 60);
        return line("pouch_back");
    }
}
