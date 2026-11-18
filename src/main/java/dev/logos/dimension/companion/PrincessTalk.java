package dev.logos.dimension.companion;

import dev.logos.dimension.companion.CompanionTalk.Line;
import dev.logos.dimension.companion.CompanionTalk.Reply;
import dev.logos.dimension.companion.TalkMenu.Option;
import dev.logos.dimension.worldtree.WorldtreeAim;
import dev.logos.dimension.worldtree.WorldtreeArrow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/**
 * 精灵公主的对话和档案（docs/ELF_PRINCESS_COMPANION.md 第 8 节）：她的话题、她怎么回、角色面板上她的数值、能力和往事。
 * 台词都在语言文件 {@code line.logos_dimension.elf_princess.talk.*}；她一直用「你」，说话软，常常说着说着跑题，说到森林就认真起来。
 */
final class PrincessTalk {
    static final String ID = ElfPrincessCompanion.ID;
    private static final String LINE = "line.logos_dimension.elf_princess.talk.";
    /** 契印一共九枚（叶之契：袋子里的小世界树每多一枚长一片叶子）。 */
    static final int MAX_SEALS = ElfPrincessCompanion.MAX_SEALS;
    /** 往事：第 0 段本来就记得，之后每盖一枚契印她多讲一段，一共十段；每段几句。角色面板的「往事」一页可以重读。 */
    static final int[] MEMORY_LINES = {2, 2, 2, 2, 2, 2, 2, 2, 2, 3};
    /** 她只有一套衣服（森林礼装）。 */
    static final String OUTFIT = "dress";

    /** 聊天里她自己的话题；其余（包袱、作战策略、详细数据、一会再聊）是通用的。 */
    static final List<Option> MENU = TalkMenu.standard(List.of(
            Option.server("chat.bag"), Option.server("chat.food"), Option.server("chat.forest"), Option.server("chat.mission"),
            Option.server("chat.past"), Option.server("chat.leaves"), Option.server("chat.us"), Option.server("chat.rest")), true);

    private PrincessTalk() {}

    /** 两边都要登记选项树（客户端画、服务端认），模组加载时调用。 */
    static void init() {
        TalkMenu.register(ID, MENU);
    }

    static Line line(String id, Object... args) {
        return Line.of(LINE + id, args);
    }

    private static String stage(ElfPrincessCompanion c, ServerPlayer p) {
        return CompanionBond.Stage.of(c.bond(p).affection()).name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- 档案

    static CompanionSheet sheet(ElfPrincessCompanion c, ServerPlayer p) {
        var bond = c.bond(p);
        int seals = Math.min(bond.seals(), MAX_SEALS);
        // 面板开着时每秒刷新：先按主人现在的装备算一遍，穿脱装备马上看得到
        CompanionStats.Parts parts = CompanionStats.apply(c, p, bond.seals());
        CompanionStats.Part power = c.powerParts();
        float sp = c.spellPower();
        List<CompanionSheet.Stat> stats = List.of(
                stat("health", c.getMaxHealth(), parts.health(), c.getHealth()),
                stat("spell_power", sp, power, -1),
                stat("armor", (float) c.getAttributeValue(Attributes.ARMOR), parts.armor(), -1),
                stat("toughness", (float) c.getAttributeValue(Attributes.ARMOR_TOUGHNESS), parts.toughness(), -1));
        long day = p.level().getOverworldClockTime() / 24000;
        PrincessMagic m = c.magic;
        boolean chance = m.unlocked(PrincessMagic.CHANCE_SEAL);
        String chanceToday = !chance ? "#gui.logos_dimension.companion.locked"
                : bond.guardDay() == day ? "#gui.logos_dimension.companion.used_today" : "#gui.logos_dimension.companion.ready";
        List<CompanionSheet.Skill> skills = new ArrayList<>(List.of(
                new CompanionSheet.Skill("bow", CompanionSheet.NORMAL, 0, true, List.of(num(WorldtreeAim.homingDamage(1) * sp),
                        num(WorldtreeArrow.STRAIGHT_DAMAGE * sp), num(PrincessBow.FULL / 20f))),
                new CompanionSheet.Skill("fan", CompanionSheet.NORMAL, 0, true, List.of(num(PrincessBow.FULL / PrincessBow.LOCK_EVERY),
                        num(WorldtreeAim.homingDamage(PrincessBow.FULL / PrincessBow.LOCK_EVERY) * sp))),
                new CompanionSheet.Skill("mend", CompanionSheet.SPECIAL, 0, true, List.of(num(PrincessMagic.MEND_HEAL * sp),
                        num(PrincessMagic.MEND_SPLASH), num(PrincessMagic.MEND_COOLDOWN / 20f))),
                new CompanionSheet.Skill("evergreen", CompanionSheet.PASSIVE, 0, true, List.of(num(PrincessMagic.AURA_RANGE),
                        num(PrincessMagic.AURA_EVERY / 20f), num(PrincessMagic.AURA_HEAL * sp))),
                new CompanionSheet.Skill("bless", CompanionSheet.SPECIAL, PrincessMagic.BLESS_SEAL, m.unlocked(PrincessMagic.BLESS_SEAL),
                        List.of(num(PrincessMagic.BLESS_DAMAGE * sp), num(PrincessMagic.BLESS_SECONDS), num(PrincessMagic.BLESS_COOLDOWN / 20f))),
                new CompanionSheet.Skill("shield", CompanionSheet.SPECIAL, PrincessMagic.SHIELD_SEAL, m.unlocked(PrincessMagic.SHIELD_SEAL),
                        List.of(num(PrincessMagic.SHIELD * sp), num(PrincessMagic.SHIELD_COOLDOWN / 20f))),
                new CompanionSheet.Skill("thorns", CompanionSheet.SPECIAL, PrincessMagic.SNARE_SEAL, m.unlocked(PrincessMagic.SNARE_SEAL),
                        List.of(num(5 * sp), num(PrincessMagic.SNARE_COOLDOWN / 20f))),
                new CompanionSheet.Skill("chance", CompanionSheet.SPECIAL, PrincessMagic.CHANCE_SEAL, chance,
                        List.of(num(PrincessMagic.CHANCE_HEAL * 100), chanceToday)),
                new CompanionSheet.Skill("withdraw", CompanionSheet.PASSIVE, 0, true, List.of(num(ElfPrincessCompanion.REST_TICKS / 1200f))),
                new CompanionSheet.Skill("lifeforce", CompanionSheet.PASSIVE, 0, true, List.of(num(sp)))));
        // 第四印以后的本事还没定，先写「？？？」
        if (seals >= PrincessMagic.CHANCE_SEAL && seals < MAX_SEALS) {
            skills.add(new CompanionSheet.Skill("sealed", CompanionSheet.SPECIAL, seals + 1, false, List.of(String.valueOf(seals + 1))));
        }
        CompanionBond.Stage st = CompanionBond.Stage.of(bond.affection());
        CompanionBond.Stage[] all = CompanionBond.Stage.values();
        float progress = st.ordinal() + 1 >= all.length ? 1 : (bond.affection() - st.from) / (all[st.ordinal() + 1].from - st.from);
        return new CompanionSheet(c.getId(), ID, OUTFIT, stats, List.copyOf(skills), seals, MAX_SEALS, st.name(), progress,
                bond.satiety(), CompanionBond.SATIETY_MAX, c.staying(), bond.tactic(), bond.pvp(), memories(seals), MEMORY_LINES.length,
                (float) ElfPrincessCompanion.GROWTH.ratio(bond.seals()));
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

    /** 刚开口：你受伤了先担心你；她饿了会不好意思地说；晚上说晚上的话；平时按羁绊说（每段两种说法）。 */
    static List<Line> greeting(ElfPrincessCompanion c, ServerPlayer p) {
        long now = c.level().getGameTime();
        var bond = c.bond(p);
        if (p.getHealth() < p.getMaxHealth() * .4f) {
            c.mood.talk("face_worried", now, 80);
            return List.of(line("greet_hurt"));
        }
        if (bond.satiety() < CompanionBond.HUNGRY) {
            c.mood.act("act_sheepish");
            return List.of(line("greet_hungry"));
        }
        c.mood.act("act_head_tilt");
        if (c.level().isDarkOutside() && c.getRandom().nextFloat() < .4f) return List.of(line("greet_night"));
        return List.of(line("greet_" + stage(c, p) + "_" + (1 + c.getRandom().nextInt(2))));
    }

    static Reply pick(ElfPrincessCompanion c, ServerPlayer p, String option) {
        long now = c.level().getGameTime();
        var bond = c.bond(p);
        switch (option) {
            case "chat.bag" -> {
                return bag(c, p, now);
            }
            case "chat.food" -> {
                c.mood.talk("face_happy", now, 100);
                List<Line> out = new ArrayList<>(List.of(line("food_1"), line("food_2")));
                if (bond.satiety() < CompanionBond.HUNGRY) out.add(line("food_hungry"));
                return Reply.say(out);
            }
            case "chat.forest" -> {
                c.mood.talk("face_worried", now, 140);
                return Reply.say(line("forest_1"), line("forest_2"), line("forest_3"));
            }
            case "chat.mission" -> {
                c.mood.talk("face_determined", now, 140);
                return Reply.say(line("mission_1"), line("mission_2"), line("mission_3"));
            }
            case "chat.past" -> {
                int n = memories(bond.seals()) - 1;
                c.mood.talk(n == 0 || n == MEMORY_LINES.length - 1 ? "face_content" : "face_sad", now, 160);
                List<Line> out = new ArrayList<>();
                for (int i = 1; i <= MEMORY_LINES[n]; i++) out.add(line("past_" + n + "_" + i));
                if (n < MEMORY_LINES.length - 1) out.add(line("past_more"));
                return Reply.say(out);
            }
            case "chat.leaves" -> {
                int seals = Math.min(bond.seals(), MAX_SEALS);
                c.mood.talk("face_content", now, 100);
                if (seals <= 0) return Reply.say(line("leaves_none_1"), line("leaves_none_2"));
                if (seals >= MAX_SEALS) return Reply.say(line("leaves_full"));
                return Reply.say(line("leaves_1"), line("leaves_2", seals, MAX_SEALS - seals));
            }
            case "chat.us" -> {
                String st = stage(c, p);
                c.mood.talk(switch (st) {
                    case "stranger" -> "face_daze";
                    case "familiar" -> "face_content";
                    case "close" -> "face_happy";
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
                c.mood.talk("face_happy", now, 60);
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
                c.mood.talk(on ? "face_determined" : "face_content", now, 60);
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
                case ADAPTIVE -> c.mood.act("act_resolve");
                case PASSIVE -> c.mood.talk("face_worried", now, 60);
                case MELEE -> c.mood.talk("face_surprised", now, 60);
                default -> c.mood.talk("face_content", now, 60);
            }
            return Reply.say(line("tactic_" + t.id));
        }
        return Reply.NONE;
    }

    /** 我弄丢了袋装世界树：还在身上就笑你（轻轻地）；不然要一株树苗（创造模式不用），给你再做一个写着名字的。 */
    private static Reply bag(ElfPrincessCompanion c, ServerPlayer p, long now) {
        if (BaggedWorldTreeItem.carries(p)) {
            c.mood.talk("face_happy", now, 80);
            return Reply.say(line("bag_have"));
        }
        boolean free = p.getAbilities().instabuild;
        int sapling = -1;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && sapling < 0; i++) if (inv.getItem(i).is(ItemTags.SAPLINGS)) sapling = i;
        if (!free && sapling < 0) {
            c.mood.talk("face_content", now, 80);
            return Reply.say(line("bag_sapling"));
        }
        if (!free) inv.removeItem(sapling, 1);
        ItemStack bag = BaggedWorldTreeItem.bound(p);
        if (!inv.add(bag)) p.drop(bag, false);
        c.mood.act("act_head_tilt");
        return Reply.say(Line.of("line.logos_dimension.elf_princess.remake"), line("bag_given"));
    }

    static void ended(ElfPrincessCompanion c, ServerPlayer p, boolean bye) {
        if (!bye) return;
        CompanionLines.say(c, p, "talk.bye", 50);
        c.mood.act("act_wave");
    }

    static void interrupted(ElfPrincessCompanion c, ServerPlayer p) {
        CompanionLines.say(c, p, "talk.danger", 50);
    }

    static Line back(ElfPrincessCompanion c, ServerPlayer p) {
        c.mood.talk("face_content", c.level().getGameTime(), 60);
        return line("pouch_back");
    }
}
