package dev.logos.dimension.music;

import org.jspecify.annotations.Nullable;

/**
 * 把电子琴（MIDI 键盘）发来的一条短消息翻译成乐器能用的事件：按下、松开、延音踏板、全部松开、换音色。
 * 处理力度为 0 的「按下」（等于松开）、通道过滤、整体移调与力度曲线。
 *
 * <p>纯 Java，不依赖 javax.sound.midi 和游戏类：客户端收到消息后调用，单元测试直接测。
 */
public final class MidiInput {
    /** 力度曲线：轻（轻轻一按也有声）、标准、重（要用力才响）。 */
    public static final int LIGHT = 0, NORMAL = 1, HEAVY = 2;

    public enum Type { NOTE_ON, NOTE_OFF, PEDAL, ALL_OFF, PROGRAM }

    /** 翻译后的事件：value 是音高（按下 / 松开）、踏板是否踩下（1/0）或音色号。 */
    public record Event(Type type, int value, int velocity) {}

    /** channel：0 表示接收所有通道，1–16 只收那一个；transpose：整体移调的半音数。 */
    public record Settings(int channel, int transpose, int curve) {
        public static final Settings DEFAULT = new Settings(0, 0, NORMAL);
    }

    private MidiInput() {}

    public static @Nullable Event translate(int status, int data1, int data2, Settings s) {
        if (status < 0x80 || status >= 0xF0) return null;
        int kind = status & 0xF0;
        int channel = (status & 0x0F) + 1;
        if (s.channel() != 0 && s.channel() != channel) return null;
        data1 &= 0x7F;
        data2 &= 0x7F;
        return switch (kind) {
            case 0x90 -> data2 == 0 ? note(Type.NOTE_OFF, data1, 0, s) : note(Type.NOTE_ON, data1, curve(data2, s.curve()), s);
            case 0x80 -> note(Type.NOTE_OFF, data1, 0, s);
            case 0xB0 -> switch (data1) {
                case 64 -> new Event(Type.PEDAL, data2 >= 64 ? 1 : 0, 0);
                case 120, 123 -> new Event(Type.ALL_OFF, 0, 0);
                default -> null;
            };
            case 0xC0 -> new Event(Type.PROGRAM, data1, 0);
            default -> null;
        };
    }

    private static @Nullable Event note(Type type, int pitch, int velocity, Settings s) {
        int p = pitch + s.transpose();
        if (p < 0 || p > 127) return null;
        return new Event(type, p, velocity);
    }

    /** 力度曲线：轻 = 开平方方向抬高，重 = 平方方向压低，都保持 1–127。 */
    public static int curve(int velocity, int curve) {
        double v = Math.max(1, Math.min(127, velocity)) / 127.0;
        double out = switch (curve) {
            case LIGHT -> Math.pow(v, 0.6);
            case HEAVY -> Math.pow(v, 1.6);
            default -> v;
        };
        return (int) Math.max(1, Math.min(127, Math.round(out * 127)));
    }
}
