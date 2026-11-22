package dev.logos.dimension.companion;

import dev.logos.dimension.companion.CompanionTalk.Line;
import dev.logos.dimension.companion.CompanionTalk.Reply;
import dev.logos.dimension.companion.TalkMenu.Option;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

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
            Option.server("chat.coin"), Option.server("chat.food"), Option.server("chat.alchemy"), Option.server("chat.lazy"),
            Option.server("chat.city"), Option.server("chat.money"), Option.server("chat.enforcers"), Option.server("chat.past"),
            Option.server("chat.seals"), Option.server("chat.us"), Option.server("chat.rest")), true);

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
