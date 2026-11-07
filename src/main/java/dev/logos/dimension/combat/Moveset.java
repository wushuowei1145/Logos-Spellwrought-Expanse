package dev.logos.dimension.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * 一套招式：招式（状态）与转移规则，读自数据包 {@code data/<命名空间>/combat/movesets/<名>.json}，格式见 docs/COMBAT_SKELETON.md。
 *
 * <p>时间都用动作时间（秒）：带 {@code attack_speed_base} 的招式按攻速缩放播放速度，其余按原速。
 * 招式只决定出招、窗口与接触时刻，伤害与命中由使用它的武器或生物结算。
 */
public record Moveset(String start, String sheathe, double comboMemory, Map<String, Move> moves) {
    /** 特殊去向：结束整套招式，交回原版。 */
    public static final String CLEAR = "$clear";
    /** 保留的招式名：玩家被弹反时强制进入（见 {@link Parry}）。招式文件里没有这个招式的武器不会被弹反僵直。 */
    public static final String PARRIED = "parried";
    /** 保留的招式名：NPC 被弹反时强制进入，比玩家的僵直长；没有时退回 {@link #PARRIED}（见 {@link Parry#staggerNpc}）。 */
    public static final String PARRIED_NPC = "parried_npc";
    /** {@code BLOCK}：原版格挡成功挡下一次攻击（服务端给出）。 */
    public enum Input { ATTACK, USE_PRESS, USE_RELEASE, BLOCK }
    public enum Playback { ONCE, LOOP, REVERSE }

    /**
     * 一条转移。{@code from}–{@code until} 是本招式内接受输入的动作时间窗口（含端点）；
     * {@code queue} 为真时输入先记下（输入缓冲），到 {@code at}（取消点，动作时间）那一刻再切换，
     * {@code at} 为 NaN 表示等本招式播完；{@code combo} 为真时，若上一段连招仍在记忆时间内，改去它的 {@code combo_next}。
     */
    public record Transition(Input input, String to, double from, double until, boolean queue, double at, boolean combo) {
        public boolean atEnd() { return Double.isNaN(at); }
    }

    /**
     * 一个招式。{@code length} 为动作时长（秒）；{@code REVERSE} 招式没有固定时长，
     * 从进入时上一招式已播放的位置倒放回 0。{@code contacts} 为各次接触时刻（升序，多数招式只有一次，连刺一段有好几次），
     * {@code next} 为播完后的去向，{@code blendIn} 为进入时从上一姿势过渡的时长（秒，只影响客户端画面）。
     * <p>结算参数由使用招式的武器解释：{@code damage} 乘在这一下的伤害上；{@code cone} 大于 0 时代替武器的命中锥形张角；
     * {@code knockback} 为假时这一下不击退；{@code fullStrength} 为真时每一下都按蓄满的攻击结算（连刺自己就是节奏，
     * 不吃原版攻击冷却）；{@code effect} 是武器自己的附加效果名（长枪的挑飞、连刺、下砸），没有为 null。
     */
    public record Move(String name, String clip, double length, Playback playback, double speedBase, List<Double> contacts,
                       boolean resetCombo, String comboNext, String next, double blendIn, List<Transition> on,
                       double damage, double cone, boolean knockback, boolean fullStrength, String effect) {
        public Move(String name, String clip, double length, Playback playback, double speedBase, double contact,
                    boolean resetCombo, String comboNext, String next, double blendIn, List<Transition> on) {
            this(name, clip, length, playback, speedBase, contact >= 0 ? List.of(contact) : List.of(), resetCombo, comboNext,
                    next, blendIn, on, 1, 0, true, false, null);
        }
        public boolean scalesWithAttackSpeed() { return speedBase > 0; }
        public boolean hasContact() { return !contacts.isEmpty(); }
        /** 第一次接触时刻；没有接触为 -1。 */
        public double contact() { return contacts.isEmpty() ? -1 : contacts.getFirst(); }
        /** 最后一次接触时刻；没有接触为 -1。 */
        public double lastContact() { return contacts.isEmpty() ? -1 : contacts.getLast(); }
        public boolean accepts(Input input) { return on.stream().anyMatch(t -> t.input == input); }
        /** 招式持续的游戏刻；循环招式为无穷大。 */
        public double durationTicks(double rate, double offset) {
            return switch (playback) {
                case LOOP -> Double.POSITIVE_INFINITY;
                case ONCE -> length * 20 / rate;
                case REVERSE -> offset * 20 / rate;
            };
        }
        /** 进入 age 刻后的动作取样时间（秒）。 */
        public double seconds(double age, double rate, double offset) { return playbackSeconds(playback, length, age, rate, offset); }
    }

    /** 客户端与服务端共用的取样时间换算；length 为动作时长，offset 为倒放起点。 */
    public static double playbackSeconds(Playback playback, double length, double age, double rate, double offset) {
        double t = Math.max(0, age) / 20 * rate;
        return switch (playback) {
            case ONCE -> Math.min(length, t);
            case LOOP -> length > 0 ? t % length : 0;
            case REVERSE -> Math.max(0, offset - t);
        };
    }

    public Move move(String name) { return moves.get(name); }

    /** 读取并校验；格式错误抛出带原因的 IllegalArgumentException。 */
    public static Moveset parse(JsonObject json) {
        Map<String, Move> moves = new LinkedHashMap<>();
        var movesJson = json.getAsJsonObject("moves");
        if (movesJson == null) throw new IllegalArgumentException("缺少 moves");
        for (var entry : movesJson.entrySet()) {
            var name = entry.getKey(); var m = entry.getValue().getAsJsonObject();
            var playback = Playback.valueOf(string(m, "playback", "once").toUpperCase(Locale.ROOT));
            double length = m.has("length") ? m.get("length").getAsDouble() : 0;
            if (playback != Playback.REVERSE && length <= 0) throw new IllegalArgumentException(name + " 缺少正的 length");
            List<Transition> on = new ArrayList<>();
            if (m.has("on")) for (JsonElement value : m.getAsJsonArray("on")) {
                var t = value.getAsJsonObject();
                double from = 0, until = Double.POSITIVE_INFINITY;
                if (t.has("window")) { var w = t.getAsJsonArray("window"); from = w.get(0).getAsDouble(); until = w.size() > 1 ? w.get(1).getAsDouble() : until; }
                on.add(new Transition(Input.valueOf(t.get("input").getAsString().toUpperCase(Locale.ROOT)), t.get("to").getAsString(),
                        from, until, t.has("queue") && t.get("queue").getAsBoolean(), t.has("at") ? t.get("at").getAsDouble() : Double.NaN,
                        t.has("combo") && t.get("combo").getAsBoolean()));
            }
            List<Double> contacts = new ArrayList<>();
            if (m.has("contact")) {
                var c = m.get("contact");
                if (c.isJsonArray()) for (var v : c.getAsJsonArray()) contacts.add(v.getAsDouble());
                else contacts.add(c.getAsDouble());
            }
            moves.put(name, new Move(name, string(m, "clip", name), length, playback,
                    m.has("attack_speed_base") ? m.get("attack_speed_base").getAsDouble() : 0, List.copyOf(contacts),
                    m.has("reset_combo") && m.get("reset_combo").getAsBoolean(), string(m, "combo_next", null), string(m, "next", null),
                    m.has("blend_in") ? m.get("blend_in").getAsDouble() : 0, List.copyOf(on),
                    m.has("damage") ? m.get("damage").getAsDouble() : 1, m.has("cone") ? m.get("cone").getAsDouble() : 0,
                    !m.has("knockback") || m.get("knockback").getAsBoolean(), m.has("full_strength") && m.get("full_strength").getAsBoolean(),
                    string(m, "effect", null)));
        }
        var set = new Moveset(json.get("start").getAsString(), string(json, "sheathe", null),
                json.has("combo_memory") ? json.get("combo_memory").getAsDouble() : 0, Map.copyOf(moves));
        set.validate();
        return set;
    }
    private static String string(JsonObject o, String key, String fallback) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback; }

    private void validate() {
        target("start", start, false);
        if (sheathe != null) target("sheathe", sheathe, false);
        for (var move : moves.values()) {
            if (move.next != null) target(move.name + ".next", move.next, true);
            else if (move.playback != Playback.LOOP) throw new IllegalArgumentException(move.name + " 不循环，必须写 next");
            if (move.comboNext != null) target(move.name + ".combo_next", move.comboNext, false);
            if (move.speedBase < 0) throw new IllegalArgumentException(move.name + " 的 attack_speed_base 不能为负");
            if (move.blendIn < 0) throw new IllegalArgumentException(move.name + " 的 blend_in 不能为负");
            for (int i = 0; i < move.contacts.size(); i++) {
                double c = move.contacts.get(i);
                if (c < 0 || i > 0 && c <= move.contacts.get(i - 1))
                    throw new IllegalArgumentException(move.name + " 的 contact 要非负且严格递增");
                if (move.playback != Playback.REVERSE && c > move.length) throw new IllegalArgumentException(move.name + " 的 contact 超过动作时长");
            }
            if (move.damage < 0) throw new IllegalArgumentException(move.name + " 的 damage 不能为负");
            if (move.cone < 0 || move.cone > 180) throw new IllegalArgumentException(move.name + " 的 cone 要在 [0, 180] 内");
            for (var t : move.on) {
                target(move.name + " 的转移", t.to, true);
                if (!t.atEnd() && !t.queue) throw new IllegalArgumentException(move.name + " 的转移写了 at 却没有 queue");
                // 取消点早于接触时刻会让缓冲的下一招吞掉这一下命中（多次接触时看最后一次）。
                if (!t.atEnd() && move.hasContact() && t.at < move.lastContact())
                    throw new IllegalArgumentException(move.name + " 的取消点 " + t.at + " 早于接触时刻 " + move.lastContact());
                if (!t.atEnd() && move.playback != Playback.REVERSE && t.at > move.length)
                    throw new IllegalArgumentException(move.name + " 的取消点超过动作时长");
            }
        }
    }
    private void target(String where, String name, boolean clearAllowed) {
        if (!moves.containsKey(name) && !(clearAllowed && CLEAR.equals(name)))
            throw new IllegalArgumentException(where + " 指向不存在的招式 " + name);
    }
}
