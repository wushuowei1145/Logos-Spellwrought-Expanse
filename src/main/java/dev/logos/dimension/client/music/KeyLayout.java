package dev.logos.dimension.client.music;

import dev.logos.dimension.ClientConfig;
import java.util.HashMap;
import java.util.Map;

/**
 * 演奏界面的键位。
 * <ul>
 *   <li>半音阶（默认，钢琴和竖琴都能用）：两排「钢琴式」布局，下排 Z–M 及其间的 S D G H J 是低八度，
 *       上排 Q–P 及其间的数字是高八度，共 29 个键；可以在客户端配置 instrumentKeys 里改。</li>
 *   <li>全音阶（竖琴）：三排字母键全部是白键，一次覆盖四个多八度，适合刮奏；数字 1–7 像竖琴的踏板一样
 *       给 C–B 各自升半音。</li>
 * </ul>
 * GLFW 的键码对字母、数字和这些标点就是它们的 ASCII 码。
 */
final class KeyLayout {
    static final String DEFAULT = "ZSXDCVGBHNJMQ2W3ER5T6Y7UI9O0P";
    /** 全音阶模式的三排白键，从低到高。 */
    static final String DIATONIC = "ZXCVBNM,./" + "ASDFGHJKL;'" + "QWERTYUIOP";
    private static final int[] WHITE = {0, 2, 4, 5, 7, 9, 11};
    private static final String ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789,./;'[]-=";

    private KeyLayout() {}

    /** 配置里的半音阶键位；不合法时退回默认。 */
    static String chromatic() {
        String s = ClientConfig.INSTRUMENT_KEYS.get().toUpperCase();
        if (s.isEmpty() || s.length() > 40) return DEFAULT;
        Map<Character, Boolean> seen = new HashMap<>();
        for (char c : s.toCharArray()) {
            if (ALLOWED.indexOf(c) < 0 || seen.put(c, true) != null) return DEFAULT;
        }
        return s;
    }

    static int keyCode(char c) {
        return Character.toUpperCase(c);
    }

    /** 半音阶：键码 → 相对起点的半音数，没有映射时 -1。 */
    static int chromaticOffset(String layout, int key) {
        for (int i = 0; i < layout.length(); i++) if (keyCode(layout.charAt(i)) == key) return i;
        return -1;
    }

    /** 全音阶：键码 → 第几个白键，没有映射时 -1。 */
    static int whiteIndex(int key) {
        for (int i = 0; i < DIATONIC.length(); i++) if (keyCode(DIATONIC.charAt(i)) == key) return i;
        return -1;
    }

    /** 第 index 个白键（从 base 的 C 起）的音高，按踏板升半音。 */
    static int whitePitch(int base, int index, boolean[] sharp) {
        int degree = index % 7;
        return base + 12 * (index / 7) + WHITE[degree] + (sharp[degree] ? 1 : 0);
    }

    /** 数字 1–7 → 踏板（C–B），没有映射时 -1。 */
    static int pedalDegree(int key) {
        return key >= '1' && key <= '7' ? key - '1' : -1;
    }

    static boolean isBlack(int pitch) {
        int n = Math.floorMod(pitch, 12);
        return n == 1 || n == 3 || n == 6 || n == 8 || n == 10;
    }

    static String noteName(int pitch) {
        String[] names = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
        return names[Math.floorMod(pitch, 12)] + (Math.floorDiv(pitch, 12) - 1);
    }

    static String label(char c) {
        return String.valueOf(c);
    }
}
