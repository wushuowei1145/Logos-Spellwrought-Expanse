package dev.logos.dimension.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * 同伴对话的选项树（docs/SHIRAUME_COMPANION.md 第 8.13 节）。两边共用：客户端照着它画选项，服务端照着它认选项。
 *
 * <p>顶层是通用的（{@link #standard}）：聊天（每位同伴自己的话题）、看看包袱、作战策略、详细数据、一会再聊。
 * 选项的文字先找同伴自己的键 {@code talk.logos_dimension.<同伴>.option.<id>}，没有再用通用的 {@code talk.logos_dimension.option.<id>}，
 * 所以别的同伴只要写自己的话题，通用的那几项不用重写。
 */
public final class TalkMenu {
    /**
     * 选了以后做什么：{@code MENU} 进下一层；{@code SERVER} 交给服务端（她回话、做事）；{@code PANEL} 打开角色面板（客户端）；
     * {@code CLOSE} 道别、关掉对话；{@code BACK} 回上一层。
     */
    public enum Kind { MENU, SERVER, PANEL, CLOSE, BACK }

    /**
     * 一个选项。{@code state} 不为空时是「这一项是不是现在的样子」，格式 {@code 键=值}（{@code tactic=ranged}、{@code staying=true}、
     * {@code outfit=miko}），客户端照档案打勾。
     */
    public record Option(String id, Kind kind, List<Option> children, @Nullable String state) {
        public static Option menu(String id, Option... children) {
            return new Option(id, Kind.MENU, List.of(children), null);
        }

        public static Option server(String id) {
            return new Option(id, Kind.SERVER, List.of(), null);
        }

        public static Option server(String id, String state) {
            return new Option(id, Kind.SERVER, List.of(), state);
        }

        public static Option back() {
            return new Option("back", Kind.BACK, List.of(), null);
        }
    }

    /** 通用选项的 ID。 */
    public static final String CHAT = "chat", POUCH = "pouch", TACTIC = "tactic", PLAN = "plan", STATS = "stats", BYE = "bye",
            FOLLOW = "move.follow", STAY = "move.stay";

    private static final Map<String, List<Option>> MENUS = new ConcurrentHashMap<>();

    private TalkMenu() {}

    /**
     * 通用的顶层：聊天（{@code chat} 是这位同伴自己的话题，后面自动加「返回」）、包袱（{@code pouch} 为假时没有）、
     * 作战策略（跟上 / 留在原地 / 行动策略）、详细数据、一会再聊。
     */
    public static List<Option> standard(List<Option> chat, boolean pouch) {
        List<Option> topics = new ArrayList<>(chat);
        topics.add(Option.back());
        List<Option> tactics = new ArrayList<>();
        for (CompanionTactic t : CompanionTactic.values()) tactics.add(Option.server("tactic." + t.id, "tactic=" + t.id));
        tactics.add(Option.back());
        List<Option> root = new ArrayList<>();
        root.add(new Option(CHAT, Kind.MENU, List.copyOf(topics), null));
        if (pouch) root.add(Option.server(POUCH));
        root.add(Option.menu(TACTIC, Option.server(FOLLOW, "staying=false"), Option.server(STAY, "staying=true"),
                new Option(PLAN, Kind.MENU, List.copyOf(tactics), null), Option.back()));
        root.add(new Option(STATS, Kind.PANEL, List.of(), null));
        root.add(new Option(BYE, Kind.CLOSE, List.of(), null));
        return List.copyOf(root);
    }

    /** 登记 {@code companion} 的选项树（实体类加载时登记，两边都有）。 */
    public static void register(String companion, List<Option> root) {
        MENUS.put(companion, root);
    }

    public static List<Option> of(String companion) {
        return MENUS.getOrDefault(companion, List.of());
    }

    /** 在树里找 {@code id}（深度优先，找不到为 null）。 */
    public static @Nullable Option find(List<Option> menu, String id) {
        for (Option o : menu) {
            if (o.id().equals(id)) return o;
            Option in = find(o.children(), id);
            if (in != null) return in;
        }
        return null;
    }

    /** {@code option} 现在是不是打勾的那一项（照档案）。 */
    public static boolean current(Option option, CompanionSheet sheet) {
        String s = option.state();
        if (s == null) return false;
        int eq = s.indexOf('=');
        String key = s.substring(0, eq), value = s.substring(eq + 1);
        return switch (key) {
            case "tactic" -> sheet.tactic().equals(value);
            case "staying" -> Boolean.toString(sheet.staying()).equals(value);
            case "outfit" -> sheet.outfit().equals(value);
            default -> false;
        };
    }
}
