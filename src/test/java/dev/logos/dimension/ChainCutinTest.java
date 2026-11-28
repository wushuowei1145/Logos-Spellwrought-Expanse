package dev.logos.dimension;

import dev.logos.dimension.cutin.ChainCutin;
import dev.logos.dimension.cutin.ChainLayout;
import dev.logos.dimension.cutin.ChainTimeline;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChainCutinTest {
    /** 一到四位同伴：格子数是人数，玩家永远在正中间那一格。 */
    @Test void playerAlwaysStandsInTheMiddle() {
        for (int n = 1; n <= ChainCutin.MAX_MEMBERS; n++) {
            ChainLayout l = ChainLayout.of(n);
            assertEquals(n + 1, l.slots().size());
            assertEquals(l.slots().size() + 1, l.lines().size());
            assertEquals(.5f, l.slots().get(l.playerSlot()).center(), 1e-6, n + " 位");
            // 每位同伴正好一格
            List<Integer> members = l.slots().stream().map(ChainLayout.Slot::member).filter(m -> m != ChainLayout.PLAYER).sorted().toList();
            assertEquals(java.util.stream.IntStream.range(0, n).boxed().toList(), members);
        }
    }

    /** 第一位在玩家左边，第二位在右边，第三位最左，第四位最右。 */
    @Test void companionsFanOutFromTheMiddle() {
        ChainLayout l = ChainLayout.of(4);
        assertEquals(List.of(2, 0, ChainLayout.PLAYER, 1, 3), l.slots().stream().map(ChainLayout.Slot::member).toList());
        assertEquals(List.of(0, ChainLayout.PLAYER), ChainLayout.of(1).slots().stream().map(ChainLayout.Slot::member).toList());
        assertEquals(List.of(0, ChainLayout.PLAYER, 1), ChainLayout.of(2).slots().stream().map(ChainLayout.Slot::member).toList());
        assertEquals(List.of(2, 0, ChainLayout.PLAYER, 1), ChainLayout.of(3).slots().stream().map(ChainLayout.Slot::member).toList());
    }

    /** 分割线从左到右排开，角色都在自己的两道线之间；越靠外越斜，左边往左上倒、右边往右上倒，不竖直。 */
    @Test void linesAreSlantedAndOrdered() {
        for (int n = 1; n <= 4; n++) {
            ChainLayout l = ChainLayout.of(n);
            for (int i = 0; i < l.slots().size(); i++) {
                float c = l.slots().get(i).center();
                assertTrue(l.lines().get(i).x() < c && c < l.lines().get(i + 1).x(), n + " 位第 " + i + " 格");
            }
            for (ChainLayout.Line line : l.lines()) {
                float tilt = Math.abs(line.tilt());
                assertTrue(tilt >= 5 && tilt <= 12.5f, "倾角 " + line.tilt() + "（x=" + line.x() + "）");
                assertEquals(line.x() < .5f ? -1 : 1, Math.signum(line.tilt()), "左边往左上倒，右边往右上倒");
            }
        }
        assertTrue(Math.abs(ChainLayout.of(4).lines().get(1).tilt()) > Math.abs(ChainLayout.of(4).lines().get(2).tilt()), "越靠外越斜");
    }

    @Test void onlyTheDuoLeavesTheBattlefieldVisible() {
        assertFalse(ChainLayout.of(1).fullPlate());
        for (int n = 2; n <= 4; n++) assertTrue(ChainLayout.of(n).fullPlate());
        assertEquals(2, ChainLayout.of(0).slots().size(), "不在范围里按最近的算");
        assertEquals(5, ChainLayout.of(9).slots().size());
    }

    @Test void phasesRunInOrder() {
        float[] at = {ChainTimeline.PIERCE, ChainTimeline.TAKEOVER, ChainTimeline.LAND, ChainTimeline.HOLD, ChainTimeline.RISE, ChainTimeline.PEAK,
                ChainTimeline.SWAP, ChainTimeline.FADE, ChainTimeline.END};
        for (int i = 1; i < at.length; i++) assertTrue(at[i] > at[i - 1]);
        // 规格里刺穿到接管约两秒
        assertEquals(1.65, ChainTimeline.END / 20.0, .2);
        assertTrue((ChainTimeline.PEAK - ChainTimeline.LAND) / 20 >= .7, "落位爆闪和峰值闪白至少隔 0.7 秒");
    }

    /** 角色冲进来之前和甩出去之后都在画面外；落位时正好在原位；相邻两格方向相反；中途不跳。 */
    @Test void portraitsSlideInAndOutAlternately() {
        for (int slot = 0; slot < 5; slot++) {
            assertTrue(Math.abs(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot)) >= 1, "冲进来之前在画面外");
            assertTrue(Math.abs(ChainTimeline.slide(ChainTimeline.SWAP, slot)) >= 1, "甩出去之后在画面外");
            assertEquals(0, ChainTimeline.slide(ChainTimeline.LAND, slot), 1e-4);
            assertEquals(Math.signum(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot)), -Math.signum(ChainTimeline.slide(ChainTimeline.SWAP, slot)),
                    "顺着进来的方向出去");
            assertEquals(Math.signum(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot)), -Math.signum(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot + 1)),
                    "相邻两格一上一下");
            assertTrue(ChainTimeline.slide(ChainTimeline.TAKEOVER, 0) > 0, "第一格从上面下来");
            for (float a = ChainTimeline.LAND; a < ChainTimeline.PEAK; a += .05f) {
                assertEquals(ChainTimeline.slide(a, slot), ChainTimeline.slide(a + .05f, slot), .01, "停留时只是慢慢漂");
            }
        }
    }

    @Test void linesPierceFirstThenRunFullHeight() {
        assertEquals(0, ChainTimeline.grow(0, 0, .55f), 1e-6);
        assertTrue(ChainTimeline.grow(1.5f, 2, .95f) > .5f, "中间那道先刺到很高");
        assertTrue(ChainTimeline.grow(1.5f, 2, .95f) <= .95f);
        assertEquals(1, ChainTimeline.grow(ChainTimeline.TAKEOVER, 3, .3f), 1e-6, "黑底接管时全部贯穿");
    }

    /** 白光盖满的那一段两种模式都一样：卸掉分格、界面回来都发生在全白底下。 */
    @Test void swapHappensUnderFullWhite() {
        for (boolean gentle : new boolean[] {false, true}) {
            for (float a = ChainTimeline.PEAK + 1; a < ChainTimeline.FADE; a += .1f) assertEquals(1, ChainTimeline.white(a, gentle), 1e-6);
            assertEquals(0, ChainTimeline.white(ChainTimeline.END, gentle), 1e-6);
            assertEquals(0, ChainTimeline.white(ChainTimeline.LAND, gentle), 1e-6);
        }
        assertFalse(ChainTimeline.portraits(ChainTimeline.SWAP));
        assertTrue(ChainTimeline.portraits(ChainTimeline.SWAP - .01f));
        assertFalse(ChainTimeline.hidesHud(ChainTimeline.SWAP), "界面在白光底下回来");
        assertTrue(ChainTimeline.hidesHud(ChainTimeline.TAKEOVER));
        assertFalse(ChainTimeline.hidesHud(ChainTimeline.TAKEOVER - .01f), "刺穿那一拍界面还在");
    }

    /** 「隐藏闪电的闪烁」：没有落位爆闪，闪白和黑底都是慢慢盖上的（一刻之内变化不超过三成）。 */
    @Test void gentleModeHasNoSuddenFlashes() {
        for (float a = 0; a < ChainTimeline.END; a += .25f) {
            assertTrue(Math.abs(ChainTimeline.white(a + 1, true) - ChainTimeline.white(a, true)) <= .3f, "闪白在第 " + a + " 刻");
            // 黑底和白光云在幕布那一刻一下子撤掉，那时已经全白，看不见
            if (a + 1 >= ChainTimeline.SWAP) continue;
            assertTrue(Math.abs(ChainTimeline.cloud(a + 1, true) - ChainTimeline.cloud(a, true)) <= .3f, "白光云在第 " + a + " 刻");
            assertTrue(Math.abs(ChainTimeline.plate(a + 1, true) - ChainTimeline.plate(a, true)) <= .8f, "黑底在第 " + a + " 刻");
        }
        assertTrue(Math.abs(ChainTimeline.plate(ChainTimeline.TAKEOVER + .5f, false) - ChainTimeline.plate(ChainTimeline.TAKEOVER, false)) > .9f,
                "平常黑底是一下子接管的");
        assertTrue(ChainTimeline.cloud(ChainTimeline.LAND + .3f, false) > .7f, "平常落位时炸开");
        assertTrue(ChainTimeline.cloud(ChainTimeline.LAND + .3f, true) < .2f);
    }

    /** 每位同伴的动画文件里都有切入的姿势（tools/chain_cutin_poses.py 生成），循环播；表情、眼睛、嘴不在里面，交给 face_*。 */
    @Test void everyCompanionHasACutinPose() throws Exception {
        java.util.regex.Pattern face = java.util.regex.Pattern.compile("(Eye|Lid|Mouth|Iris|Pupil|Lash|Blush|Brow)");
        for (String asset : List.of("shiraume_companion", "elf_princess_companion", "kohaku_companion", "lacerta_companion")) {
            String path = "/assets/logos_dimension/geckolib/animations/entity/" + asset + ".animation.json";
            try (var in = ChainCutinTest.class.getResourceAsStream(path)) {
                assertNotNull(in, path);
                var clips = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("animations");
                assertTrue(clips.has("cutin"), asset + " 没有 cutin");
                var cutin = clips.getAsJsonObject("cutin");
                assertTrue(cutin.get("loop").getAsBoolean(), asset);
                var bones = cutin.getAsJsonObject("bones").keySet();
                assertFalse(bones.isEmpty(), asset);
                for (String bone : bones) assertFalse(face.matcher(bone).find(), asset + " 的 cutin 动了表情骨骼 " + bone);
            }
        }
    }

    @Test void codecKeepsTheParty() {
        var cutin = new ChainCutin(7, List.of(11, 12, 13, 14), 99L);
        var buf = Unpooled.buffer();
        ChainCutin.CODEC.encode(buf, cutin);
        assertEquals(cutin, ChainCutin.CODEC.decode(buf));
        assertEquals(5, cutin.size());
        assertEquals(4, new ChainCutin(1, List.of(1, 2, 3, 4, 5), 0).members().size(), "最多四位");
    }
}
