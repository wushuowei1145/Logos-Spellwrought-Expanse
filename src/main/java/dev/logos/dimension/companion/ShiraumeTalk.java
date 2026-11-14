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
import net.minecraft.world.item.Items;

/**
 * 白梅的对话和档案（docs/SHIRAUME_COMPANION.md 第 8.13 节）：她的话题、她怎么回、角色面板上她的数值、能力和往事。
 * 台词都在语言文件 {@code line.logos_dimension.shiraume.talk.*}；她说话的口气随羁绊变：一开始用「您」、拘谨，熟了以后随意、爱捉弄人。
 */
final class ShiraumeTalk {
    static final String ID = "shiraume";
    private static final String LINE = "line.logos_dimension.shiraume.talk.";
    /** 契印一共九枚（九尾）。 */
    static final int MAX_SEALS = 9;
    /**
     * 往事：第 0 段本来就记得，之后每盖一枚契印想起一段（第 5 节：每找回一份力量，同时找回一段记忆），一共十段；每段几句。
     * 角色面板的「往事」一页可以重读。
     */
    static final int[] MEMORY_LINES = {2, 2, 2, 2, 2, 2, 2, 2, 2, 3};

    /** 聊天里她自己的话题；其余（包袱、作战策略、详细数据、一会再聊）是通用的。 */
    static final List<Option> MENU = TalkMenu.standard(List.of(
            Option.server("chat.katashiro"), Option.server("chat.food"), Option.server("chat.past"), Option.server("chat.tails"),
            Option.server("chat.us"),
            Option.menu("chat.outfit", Option.server("outfit." + CompanionOutfit.MIKO.id, "outfit=" + CompanionOutfit.MIKO.id),
                    Option.server("outfit." + CompanionOutfit.UMEAKARI.id, "outfit=" + CompanionOutfit.UMEAKARI.id), Option.back()),
            Option.server("chat.rest")), true);

    private ShiraumeTalk() {}

    /** 两边都要登记选项树（客户端画、服务端认），模组加载时调用。 */
    static void init() {
        TalkMenu.register(ID, MENU);
    }

    static Line line(String id, Object... args) {
        return Line.of(LINE + id, args);
    }

    private static String stage(ServerPlayer p) {
        return CompanionBond.Stage.of(p.getData(Companions.BOND).affection()).name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- 档案

    static CompanionSheet sheet(ShiraumeCompanion c, ServerPlayer p) {
        var bond = p.getData(Companions.BOND);
        int seals = Math.min(bond.seals(), MAX_SEALS);
        float fox = c.foxfireDamage();
        List<CompanionSheet.Stat> stats = List.of(
                new CompanionSheet.Stat("health", c.getMaxHealth(), (float) CompanionStats.BASE_HEALTH, c.getHealth()),
                new CompanionSheet.Stat("attack", fox, (float) CompanionStats.BASE_FOXFIRE, -1),
                new CompanionSheet.Stat("armor", (float) c.getAttributeValue(Attributes.ARMOR), (float) CompanionStats.BASE_ARMOR, -1),
                new CompanionSheet.Stat("toughness", (float) c.getAttributeValue(Attributes.ARMOR_TOUGHNESS), 0, -1));
        long day = p.level().getOverworldClockTime() / 24000;
        boolean guard = CompanionBond.unlocked(seals, CompanionBond.GUARD_SEAL);
        String guardToday = !guard ? "#gui.logos_dimension.companion.locked"
                : bond.guardDay() == day ? "#gui.logos_dimension.companion.used_today" : "#gui.logos_dimension.companion.ready";
        List<CompanionSheet.Skill> skills = new ArrayList<>(List.of(
                new CompanionSheet.Skill("foxfire", CompanionSheet.NORMAL, 0, true, List.of(num(fox))),
                new CompanionSheet.Skill("ryuka", CompanionSheet.NORMAL, 0, true, List.of(num(fox * CompanionBlade.BLADE))),
                new CompanionSheet.Skill("execute", CompanionSheet.SPECIAL, 0, true, List.of()),
                new CompanionSheet.Skill("kindle", CompanionSheet.SPECIAL, 0, true, List.of(num(fox * ShiraumeCompanion.KINDLE_SHARE),
                        num(ShiraumeCompanion.KINDLE_SECONDS), num(ShiraumeCompanion.KINDLE_COOLDOWN / 20f))),
                new CompanionSheet.Skill("katashiro", CompanionSheet.SPECIAL, CompanionBond.GUARD_SEAL, guard, List.of(guardToday)),
                new CompanionSheet.Skill("lantern", CompanionSheet.PASSIVE, 0, true, List.of()),
                new CompanionSheet.Skill("intercept", CompanionSheet.PASSIVE, 0, true, List.of()),
                new CompanionSheet.Skill("parry", CompanionSheet.PASSIVE, 0, true, List.of(num(CompanionBlade.BLOCK_MELEE * 100),
                        num(CompanionBlade.BLOCK_PROJECTILE * 100)))));
        // 下一枚契印要找回的力量：还没定，先写「？？？」
        if (seals >= CompanionBond.GUARD_SEAL && seals < MAX_SEALS) {
            skills.add(new CompanionSheet.Skill("sealed", CompanionSheet.SPECIAL, seals + 1, false, List.of(String.valueOf(seals + 1))));
        }
        CompanionBond.Stage st = CompanionBond.Stage.of(bond.affection());
        CompanionBond.Stage[] all = CompanionBond.Stage.values();
        float progress = st.ordinal() + 1 >= all.length ? 1
                : (bond.affection() - st.from) / (all[st.ordinal() + 1].from - st.from);
        return new CompanionSheet(c.getId(), ID, c.outfit().id, stats, List.copyOf(skills), seals, MAX_SEALS, st.name(), progress,
                bond.satiety(), CompanionBond.SATIETY_MAX, c.staying(), bond.tactic(), memories(seals), MEMORY_LINES.length,
                (float) CompanionStats.ratio(bond.seals()));
    }

    /** 盖了 {@code seals} 枚契印时想起了几段往事。 */
    static int memories(int seals) {
        return Math.min(Math.max(0, seals), MEMORY_LINES.length - 1) + 1;
    }

    /** 数字一位小数，整数不带小数点。 */
    static String num(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    // ---------------------------------------------------------------- 对话

    /** 刚开口：看你的样子、她饿不饿、几点了，再按羁绊说话（每段两种说法）。 */
    static List<Line> greeting(ShiraumeCompanion c, ServerPlayer p) {
        long now = c.level().getGameTime();
        var bond = p.getData(Companions.BOND);
        if (p.getHealth() < p.getMaxHealth() * .4f) {
            c.mood.talk("face_sad", now, 80);
            return List.of(line("greet_hurt"));
        }
        c.mood.act("act_head_tilt");
        if (bond.satiety() < CompanionBond.HUNGRY) {
            c.mood.talk("face_pout", now, 80);
            return List.of(line("greet_hungry"));
        }
        if (c.level().isDarkOutside() && c.getRandom().nextFloat() < .4f) return List.of(line("greet_night"));
        return List.of(line("greet_" + stage(p) + "_" + (1 + c.getRandom().nextInt(2))));
    }

    /** 选了 {@code option}：她回话、做事。 */
    static Reply pick(ShiraumeCompanion c, ServerPlayer p, String option) {
        long now = c.level().getGameTime();
        var bond = p.getData(Companions.BOND);
        switch (option) {
            case "chat.katashiro" -> {
                return katashiro(c, p, now);
            }
            case "chat.food" -> {
                c.mood.talk("face_happy", now, 100);
                List<Line> out = new ArrayList<>(List.of(line("food_1"), line("food_2")));
                if (bond.satiety() < CompanionBond.HUNGRY) out.add(line("food_hungry"));
                return Reply.say(out);
            }
            case "chat.past" -> {
                int n = memories(bond.seals()) - 1;
                c.mood.talk(n == 0 || n == MEMORY_LINES.length - 1 ? "face_content" : "face_sad", now, 160);
                List<Line> out = new ArrayList<>();
                for (int i = 1; i <= MEMORY_LINES[n]; i++) out.add(line("past_" + n + "_" + i));
                if (n < MEMORY_LINES.length - 1) out.add(line("past_more"));
                return Reply.say(out);
            }
            case "chat.tails" -> {
                int seals = Math.min(bond.seals(), MAX_SEALS);
                c.mood.talk("face_kitsune", now, 100);
                if (seals <= 0) return Reply.say(line("tails_none_1"), line("tails_none_2"));
                if (seals >= MAX_SEALS) return Reply.say(line("tails_full"));
                return Reply.say(line("tails_1"), line("tails_2", seals, MAX_SEALS - seals));
            }
            case "chat.us" -> {
                String st = stage(p);
                c.mood.talk(switch (st) {
                    case "stranger" -> "face_sad";
                    case "familiar" -> "face_pout";
                    case "close" -> "face_teasing";
                    case "trusted" -> "face_content";
                    default -> "face_shy";
                }, now, 100);
                return Reply.say(line("us_" + st));
            }
            case "chat.rest" -> {
                CompanionLines.say(p, "talk.rest", 50);
                Companions.dismiss(p);
                return Reply.closing();
            }
            case TalkMenu.POUCH -> {
                CompanionPouch.open(p);
                return Reply.away();
            }
            case TalkMenu.FOLLOW -> {
                c.setStaying(false);
                c.mood.talk("face_happy", now, 60);
                return Reply.say(line("follow"));
            }
            case TalkMenu.STAY -> {
                c.setStaying(true);
                c.mood.talk("face_pout", now, 60);
                return Reply.say(line("stay"));
            }
            default -> {
            }
        }
        if (option.startsWith("tactic.")) {
            CompanionTactic t = CompanionTactic.byId(option.substring("tactic.".length()));
            p.setData(Companions.BOND, bond.withTactic(t));
            switch (t) {
                case ADAPTIVE -> c.mood.act("act_proud");
                case PASSIVE -> c.mood.talk("face_pout", now, 60);
                case MELEE -> c.mood.talk("face_teasing", now, 60);
                default -> c.mood.talk("face_content", now, 60);
            }
            return Reply.say(line("tactic_" + t.id));
        }
        if (option.startsWith("outfit.")) {
            CompanionOutfit o = CompanionOutfit.byId(option.substring("outfit.".length()));
            c.chooseOutfit(o, now);
            if (o == CompanionOutfit.UMEAKARI) c.mood.act("act_proud");
            else c.mood.talk("face_content", now, 60);
            return Reply.say(line("outfit_" + o.id));
        }
        return Reply.NONE;
    }

    /** 我弄丢了一开始的形代：还在身上就笑你；不然要一张纸（创造模式不用），给你再折一张写着名字的。 */
    private static Reply katashiro(ShiraumeCompanion c, ServerPlayer p, long now) {
        if (KatashiroItem.carries(p)) {
            c.mood.talk("face_teasing", now, 80);
            return Reply.say(line("katashiro_have"));
        }
        boolean free = p.getAbilities().instabuild;
        int paper = -1;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && paper < 0; i++) if (inv.getItem(i).is(Items.PAPER)) paper = i;
        if (!free && paper < 0) {
            c.mood.talk("face_content", now, 80);
            return Reply.say(line("katashiro_paper"));
        }
        if (!free) inv.removeItem(paper, 1);
        ItemStack k = KatashiroItem.bound(p);
        if (!inv.add(k)) p.drop(k, false);
        c.mood.act("act_head_tilt");
        return Reply.say(Line.of("line.logos_dimension.shiraume.refold"), line("katashiro_given"));
    }

    static void ended(ShiraumeCompanion c, ServerPlayer p, boolean bye) {
        if (!bye) return;
        CompanionLines.say(p, "talk.bye", 50);
        c.mood.act("act_wave");
    }

    static void interrupted(ShiraumeCompanion c, ServerPlayer p) {
        CompanionLines.say(p, "talk.danger", 50);
    }

    static Line back(ShiraumeCompanion c, ServerPlayer p) {
        c.mood.talk("face_teasing", c.level().getGameTime(), 60);
        return line("pouch_back");
    }
}
