package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.CompanionTalk.Line;
import dev.logos.dimension.companion.CompanionTalk.Reply;
import dev.logos.dimension.companion.TalkMenu.Option;
import dev.logos.dimension.item.CrimsonFlameLongswordItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 拉塞塔的对话和档案（docs/LACERTA_COMPANION.md 第 11、12 节）：她的话题、她怎么回、角色面板上的数值、能力和往事《绯鳞纪事》。
 * 台词都在语言文件 {@code line.logos_dimension.lacerta.talk.*}；说话干脆，前期常把「哼」「本龙」「那当然」挂在嘴边，一说到吃的和被夸就破功。
 * 对你的称呼随羁绊变（第 11.1 节）：生疏「人类」→ 熟悉「喂 / 契约者」→ 亲近「你 / 我的主君」→ 信赖「我的主君 / 笨蛋」→ 羁绊「我的宝藏」，
 * 写在每一段的台词里。
 */
final class LacertaTalk {
    static final String ID = LacertaCompanion.ID;
    private static final String LINE = "line.logos_dimension.lacerta.talk.";
    static final int MAX_SEALS = LacertaCompanion.MAX_SEALS;
    /** 她只有一套：绯鳞骑士装。 */
    static final String OUTFIT = "knight";
    /** 往事《绯鳞纪事》：第 0 段立契就记得，之后每盖一枚鳞之契多讲一段，一共十段，每段三句。 */
    static final int[] MEMORY_LINES = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3};
    /** 夸她、让她看武器：一天第一次加多少羁绊。 */
    static final float PRAISE_BOND = 2, CRITIQUE_BOND = 2;

    /** 聊天里她自己的话题（第 11.3 节）；其余（包袱、作战策略、详细数据、一会再聊）是通用的。 */
    static final List<Option> MENU = TalkMenu.standard(List.of(
            Option.server("chat.scale"), Option.server("chat.food"), Option.server("chat.slept"), Option.server("chat.pauldron"),
            Option.server("chat.knight"), Option.server("chat.cold"), Option.server("chat.airships"), Option.server("chat.praise"),
            Option.server("chat.weapon"), Option.server("chat.past"), Option.server("chat.seals"), Option.server("chat.us"),
            Option.server("chat.rest")), true);
    /** 测试用：让她看过几次武器、最近一次说的是哪一种；夸过她几次。 */
    static int critiques, praises;
    static String lastVerdict = "";

    private LacertaTalk() {}

    static void init() {
        TalkMenu.register(ID, MENU);
    }

    static Line line(String id, Object... args) {
        return Line.of(LINE + id, args);
    }

    private static String stage(LacertaCompanion c, ServerPlayer p) {
        return CompanionBond.Stage.of(c.bond(p).affection()).name().toLowerCase(Locale.ROOT);
    }

    static LacertaDays days(ServerPlayer p) {
        return p.getData(Companions.LACERTA_DAYS);
    }

    private static long day(LacertaCompanion c) {
        return c.level().getOverworldClockTime() / 24000;
    }

    // ---------------------------------------------------------------- 档案

    static CompanionSheet sheet(LacertaCompanion c, ServerPlayer p) {
        var bond = c.bond(p);
        int seals = bond.seals();
        CompanionStats.apply(c, p, seals);
        CompanionStats.Parts parts = c.parts();
        CompanionStats.Part atk = parts.attack();
        List<CompanionSheet.Stat> stats = List.of(
                stat("health", c.getMaxHealth(), parts.health(), c.getHealth()),
                new CompanionSheet.Stat("attack", (float) (atk.total() + LacertaBlade.SWORD), (float) (atk.own() + LacertaBlade.SWORD), (float) atk.bond(), -1),
                stat("armor", (float) parts.armor().total(), parts.armor(), -1),
                stat("toughness", (float) parts.toughness().total(), parts.toughness(), -1));
        float hit = c.attack() + LacertaBlade.SWORD;
        List<CompanionSheet.Skill> skills = new ArrayList<>(List.of(
                new CompanionSheet.Skill("blade", CompanionSheet.NORMAL, 0, true, List.of(num(hit), num(hit * 1.45))),
                new CompanionSheet.Skill("thrust", CompanionSheet.SPECIAL, 0, true, List.of(num(hit * 1.25), cd("crimson_thrust"))),
                new CompanionSheet.Skill("vortex", CompanionSheet.SPECIAL, 0, true, List.of(num(hit * .85), cd("vortex_rush"))),
                new CompanionSheet.Skill("sweep", CompanionSheet.SPECIAL, 0, true, List.of(num(hit * 1.35), "10", "11", cd("flame_sweep"))),
                new CompanionSheet.Skill("skyfall", CompanionSheet.SPECIAL, 0, true, List.of(num(hit * 1.55), "12", "3.8", cd("skyfall_judgement"))),
                new CompanionSheet.Skill("parry", CompanionSheet.SPECIAL, 0, true, List.of(num(LacertaBlade.BLOCK_MELEE * 100), num(hit * 1.5))),
                new CompanionSheet.Skill("taunt", CompanionSheet.SPECIAL, 0, true, List.of(num(LacertaMind.TAUNT_RADIUS), num(LacertaMind.TAUNT_HOLD / 20f),
                        num(LacertaMind.TAUNT_GAP / 20f), num(LacertaMind.TAUNT_HEALTH * 100))),
                new CompanionSheet.Skill("flame", CompanionSheet.SPECIAL, 0, true, List.of(num(10 * c.spellPower()), num(LacertaCompanion.FLAME_COST),
                        num(LacertaCompanion.FLAME_COOLDOWN / 20f))),
                new CompanionSheet.Skill("scale", CompanionSheet.PASSIVE, 0, true, List.of(num(LacertaStats.KNOCKBACK * 100))),
                new CompanionSheet.Skill("glide", CompanionSheet.PASSIVE, 0, true, List.of(num(LacertaCompanion.GLIDE_AFTER))),
                new CompanionSheet.Skill("flight", CompanionSheet.PASSIVE, 0, true, List.of(num(LacertaFlight.PURSUE_RANGE))),
                new CompanionSheet.Skill("catch", CompanionSheet.PASSIVE, 0, true, List.of(num(LacertaFlight.REDUCE_HAND * 100),
                        num(LacertaFlight.REDUCE_EMBRACE * 100))),
                new CompanionSheet.Skill("tank", CompanionSheet.PASSIVE, 0, true, List.of(num(LacertaStats.tankRatio(seals) * 100),
                        num(LacertaStats.overflow(parts.armor().total(), parts.toughness().total()) * 100))),
                new CompanionSheet.Skill("mana", CompanionSheet.PASSIVE, 0, true, List.of(num(c.maxMana()), num(c.regenPerSecond()), num(c.spellPower()))),
                new CompanionSheet.Skill("vigil", CompanionSheet.PASSIVE, 0, true, List.of("#talk.logos_dimension.lacerta.skill.vigil."
                        + LacertaRest.Tier.of(CompanionBond.Stage.of(bond.affection())).name().toLowerCase(Locale.ROOT))),
                new CompanionSheet.Skill("withdraw", CompanionSheet.PASSIVE, 0, true, List.of(num(LacertaCompanion.REST_TICKS / 1200f)))));
        CompanionBond.Stage st = CompanionBond.Stage.of(bond.affection());
        CompanionBond.Stage[] all = CompanionBond.Stage.values();
        float progress = st.ordinal() + 1 >= all.length ? 1 : (bond.affection() - st.from) / (all[st.ordinal() + 1].from - st.from);
        return new CompanionSheet(c.getId(), ID, OUTFIT, stats, List.copyOf(skills), Math.min(seals, MAX_SEALS), MAX_SEALS, st.name(), progress,
                bond.satiety(), CompanionBond.SATIETY_MAX, c.staying(), bond.tactic(), bond.pvp(), memories(seals), MEMORY_LINES.length,
                (float) LacertaStats.tankRatio(seals));
    }

    private static String cd(String move) {
        return num(LacertaMind.COOLDOWN.getOrDefault(move, 0) / 20f);
    }

    private static CompanionSheet.Stat stat(String id, float value, CompanionStats.Part part, float current) {
        return new CompanionSheet.Stat(id, value, (float) part.own(), (float) part.bond(), current);
    }

    static int memories(int seals) {
        return Math.min(Math.max(0, seals), MEMORY_LINES.length - 1) + 1;
    }

    static String num(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    // ---------------------------------------------------------------- 对话

    /** 刚开口：你受伤了先板着脸数落（其实是担心）；她饿了就直说；平时按羁绊说（每段两种说法）。 */
    static List<Line> greeting(LacertaCompanion c, ServerPlayer p) {
        long now = c.level().getGameTime();
        var bond = c.bond(p);
        if (p.getHealth() < p.getMaxHealth() * .4f) {
            c.mood.talk("face_sad", now, 80);
            return List.of(line("greet_hurt"));
        }
        if (bond.satiety() < CompanionBond.HUNGRY) {
            c.mood.talk("face_pout", now, 80);
            return List.of(line("greet_hungry"));
        }
        String st = stage(c, p);
        c.mood.talk(st.equals("stranger") || st.equals("familiar") ? "face_aloof" : "face_sweet", now, 80);
        return List.of(line("greet_" + st + "_" + (1 + c.getRandom().nextInt(2))));
    }

    static Reply pick(LacertaCompanion c, ServerPlayer p, String option) {
        long now = c.level().getGameTime();
        var bond = c.bond(p);
        switch (option) {
            case "chat.scale" -> {
                return scale(c, p, now);
            }
            case "chat.food" -> {
                c.mood.talk("face_foodie", now, 120);
                return Reply.say(line("food_1"), line("food_2"), line("food_3"));
            }
            case "chat.slept" -> {
                c.mood.talk("face_poise", now, 120);
                return Reply.say(line("slept_1"), line("slept_2"));
            }
            case "chat.pauldron" -> {
                c.mood.talk("face_poise", now, 160);
                return Reply.say(line("pauldron_1"), line("pauldron_2"), line("pauldron_3"));
            }
            case "chat.knight" -> {
                c.mood.talk("face_flustered", now, 140);
                return Reply.say(line("knight_" + stageGroup(c, p) + "_1"), line("knight_" + stageGroup(c, p) + "_2"));
            }
            case "chat.cold" -> {
                c.mood.talk("face_pout", now, 120);
                return Reply.say(line("cold_1"), line("cold_2"));
            }
            case "chat.airships" -> {
                c.mood.talk("face_angry", now, 100);
                return Reply.say(line("airships_1"), line("airships_2"));
            }
            case "chat.praise" -> {
                return praise(c, p, now);
            }
            case "chat.weapon" -> {
                return critique(c, p, now);
            }
            case "chat.past" -> {
                int n = memories(bond.seals()) - 1;
                c.mood.talk(n < 3 ? "face_poise" : "face_sweet", now, 160);
                List<Line> out = new ArrayList<>();
                for (int i = 1; i <= MEMORY_LINES[n]; i++) out.add(line("past_" + n + "_" + i));
                if (n < MEMORY_LINES.length - 1) out.add(line("past_more"));
                return Reply.say(out);
            }
            case "chat.seals" -> {
                int seals = bond.seals();
                c.mood.talk("face_smug", now, 100);
                if (seals <= 0) return Reply.say(line("seals_none_1"), line("seals_none_2"));
                if (seals >= MAX_SEALS) return Reply.say(line("seals_full", seals));
                return Reply.say(line("seals_1"), line("seals_2", seals));
            }
            case "chat.us" -> {
                String st = stage(c, p);
                c.mood.talk(switch (st) {
                    case "stranger" -> "face_aloof";
                    case "familiar" -> "face_poise";
                    case "close" -> "face_smug";
                    case "trusted" -> "face_flustered";
                    default -> "face_sweet";
                }, now, 120);
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
                c.mood.talk("face_poise", now, 60);
                return Reply.say(line("follow"));
            }
            case TalkMenu.STAY -> {
                c.setStaying(true);
                c.mood.talk("face_aloof", now, 60);
                return Reply.say(line("stay"));
            }
            case TalkMenu.PVP -> {
                boolean on = !bond.pvp();
                c.setBond(p, bond.withPvp(on));
                c.mood.talk(on ? "face_battle" : "face_poise", now, 60);
                if (on && !p.canHarmPlayer(p)) return Reply.say(line("pvp_on"), line("pvp_off_server"));
                return Reply.say(line(on ? "pvp_on" : "pvp_off"));
            }
            default -> {
            }
        }
        if (option.startsWith("tactic.")) {
            CompanionTactic t = CompanionTactic.byId(option.substring("tactic.".length()));
            c.setBond(p, bond.withTactic(t));
            // 领命：利落的骑士礼，放下拳后尾尖才忍不住晃一下
            c.mood.act("act_salute");
            return Reply.say(line("tactic_" + t.id));
        }
        return Reply.NONE;
    }

    /** 熟悉以前、亲近以后两种说法（为什么当我的骑士）。 */
    private static String stageGroup(LacertaCompanion c, ServerPlayer p) {
        return CompanionBond.Stage.of(c.bond(p).affection()).ordinal() >= CompanionBond.Stage.CLOSE.ordinal() ? "close" : "early";
    }

    /** 我弄丢了你的鳞：还在身上就笑你；不然鼓着嘴又从心口拔下一片塞给你（创造模式也一样，她不收钱）。 */
    private static Reply scale(LacertaCompanion c, ServerPlayer p, long now) {
        if (DragonScaleItem.carries(p)) {
            c.mood.talk("face_smug", now, 80);
            return Reply.say(line("scale_have"));
        }
        ItemStack scale = DragonScaleItem.bound(p);
        if (!p.getInventory().add(scale)) p.drop(scale, false);
        c.mood.pouted(now);
        return Reply.say(Line.of("line.logos_dimension.lacerta.remake"), line("scale_given"));
    }

    /** 夸夸她：一天第一次加羁绊；端不住（被夸）。 */
    private static Reply praise(LacertaCompanion c, ServerPlayer p, long now) {
        long day = day(c);
        LacertaDays d = days(p);
        if (d.praiseDay() != day) {
            p.setData(Companions.LACERTA_DAYS, d.withPraise(day));
            var b = c.bond(p);
            c.setBond(p, b.withAffection(b.affection() + PRAISE_BOND));
        }
        praises++;
        c.mood.act("act_praised");
        return Reply.say(line("praise_" + stageGroup(c, p) + "_1"), line("praise_" + stageGroup(c, p) + "_2"));
    }

    // ---------------------------------------------------------------- 帮我看看这把武器（接入方案第七节第 6 条）

    /** 你主手（不是就副手）拿着的东西，她看一眼，古龙骑士的一句点评。一天第一次加一点羁绊（拿食物、空手糊弄她的不算）。 */
    private static Reply critique(LacertaCompanion c, ServerPlayer p, long now) {
        ItemStack held = p.getMainHandItem().isEmpty() ? p.getOffhandItem() : p.getMainHandItem();
        String v = verdict(held);
        lastVerdict = v;
        critiques++;
        if (!v.equals("none") && !v.equals("joke")) {
            long day = day(c);
            LacertaDays d = days(p);
            if (d.critiqueDay() != day) {
                p.setData(Companions.LACERTA_DAYS, d.withCritique(day));
                var b = c.bond(p);
                c.setBond(p, b.withAffection(b.affection() + CRITIQUE_BOND));
            }
        }
        c.mood.talk(switch (v) {
            case "crimson" -> "face_smug";
            case "rival" -> "face_pout";
            case "enchanted" -> "face_foodie";
            case "broken", "joke", "none" -> "face_pout";
            case "ranged" -> "face_curious";
            default -> "face_poise";
        }, now, 120);
        float attack = (float) attackOf(held);
        return Reply.say(line("weapon_" + v, num(attack)));
    }

    /**
     * 怎么评：空手 {@code none}；炽焰长剑 {@code crimson}（得意你有眼光）；别的同伴的武器（流火、凝辉……）{@code rival}（有点吃醋）；
     * 耐久快没了 {@code broken}；附魔三条以上的近战 {@code enchanted}（两眼放光）；弓弩 {@code ranged}；别的近战 {@code melee}；
     * 木棍、吃的、不是武器的 {@code joke}。
     */
    static String verdict(ItemStack s) {
        if (s.isEmpty()) return "none";
        if (s.is(CrimsonFlameLongswordItem.ITEM.get())) return "crimson";
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        if (id.equals(LogosDimension.id("ryuka").toString()) || id.equals(LogosDimension.id("vesperglass_greatsword").toString())) return "rival";
        boolean melee = s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(Items.TRIDENT) || s.is(Items.MACE) || attackOf(s) > 1;
        boolean ranged = s.is(Items.BOW) || s.is(Items.CROSSBOW);
        if (!melee && !ranged) return "joke";
        if (s.isDamageableItem() && s.getDamageValue() > s.getMaxDamage() * .75) return "broken";
        if (ranged) return "ranged";
        if (s.getEnchantments().size() >= 3) return "enchanted";
        return "melee";
    }

    /** 这件东西拿在主手时加多少攻击（原版修饰的加法部分）。 */
    static double attackOf(ItemStack s) {
        double[] sum = {0};
        s.forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.is(Attributes.ATTACK_DAMAGE) && mod.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                sum[0] += mod.amount();
            }
        });
        return sum[0] + (sum[0] > 0 ? 1 : 0);
    }

    static void ended(LacertaCompanion c, ServerPlayer p, boolean bye) {
        if (!bye) return;
        CompanionLines.say(c, p, "talk.bye", 50);
        c.mood.act("act_wave");
    }
}
