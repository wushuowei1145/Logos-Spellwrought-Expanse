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
            for (int order = 0; order < 5; order++) {
                float land = ChainTimeline.landing(order);
                assertTrue(Math.abs(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot, order)) >= 1, "冲进来之前在画面外");
                assertTrue(Math.abs(ChainTimeline.slide(ChainTimeline.SWAP, slot, order)) >= 1, "甩出去之后在画面外");
                assertEquals(0, ChainTimeline.slide(land, slot, order), 1e-4);
                assertEquals(Math.signum(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot, order)), -Math.signum(ChainTimeline.slide(ChainTimeline.SWAP, slot, order)),
                        "顺着进来的方向出去");
                assertEquals(Math.signum(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot, order)),
                        -Math.signum(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot + 1, order)), "相邻两格一上一下");
                assertEquals(-Math.signum(ChainTimeline.slide(ChainTimeline.TAKEOVER, slot, order)), ChainTimeline.direction(slot), "背景顺着她冲进来的方向走");
                for (float a = land; a < ChainTimeline.PEAK; a += .05f) {
                    assertEquals(ChainTimeline.slide(a, slot, order), ChainTimeline.slide(a + .05f, slot, order), .01, "停留时只是慢慢漂");
                }
            }
            assertTrue(ChainTimeline.slide(ChainTimeline.TAKEOVER, 0, 0) > 0, "第一格从上面下来");
        }
    }

    /** 一个接一个落位：玩家先到，同伴从中间一左一右往外，最后一位在停留之前到；还没轮到的那一格是空的。 */
    @Test void landingsFollowOneAnother() {
        ChainLayout l = ChainLayout.of(4);
        assertEquals(List.of(3, 1, 0, 2, 4), l.slots().stream().map(ChainLayout.Slot::order).toList());
        assertEquals(ChainTimeline.LAND, ChainTimeline.landing(0), 1e-6, "玩家落位就是爆闪那一下");
        assertTrue(ChainTimeline.landing(4) < ChainTimeline.HOLD, "都到了才算停留");
        for (int order = 1; order <= 4; order++) {
            float gap = ChainTimeline.landing(order) - ChainTimeline.landing(order - 1);
            assertTrue(gap >= .8f && gap <= 1.5f, "相隔 " + gap + " 刻：看得出先后，又连成一串");
        }
        assertTrue(Math.abs(ChainTimeline.slide(ChainTimeline.landing(0), 0, 4)) >= 1, "玩家落位时最外那位还没进来");
    }

    /** 镜头：冲进来时从远处跟着冲，落位时冲过头一点再回来；停留时一直在走、越走越慢但到峰值还在走；甩出时往前冲；歪的角度落位后摆正。 */
    @Test void cameraRushesInAndKeepsMoving() {
        for (int order = 0; order < 5; order++) {
            float land = ChainTimeline.landing(order);
            assertTrue(ChainTimeline.approach(ChainTimeline.TAKEOVER, order) > .5f, "冲进来之前镜头在远处");
            assertEquals(0, ChainTimeline.approach(land, order), 1e-3);
            assertTrue(ChainTimeline.approach(land + 1.5f, order) < -.04f, "落位时冲过头");
            assertEquals(0, ChainTimeline.approach(land + 4, order), 1e-6);
            assertTrue(ChainTimeline.approach(ChainTimeline.SWAP, order) < -.3f, "甩出时往前冲");
            float last = -1;
            for (float a = land; a <= ChainTimeline.PEAK; a += .5f) {
                float p = ChainTimeline.progress(a, order);
                assertTrue(p > last, "停留时一直在走");
                last = p;
            }
            assertEquals(1, ChainTimeline.progress(ChainTimeline.PEAK, order), 1e-6);
            float endSpeed = ChainTimeline.progress(ChainTimeline.PEAK, order) - ChainTimeline.progress(ChainTimeline.PEAK - 1, order);
            assertTrue(endSpeed > .3f / (ChainTimeline.PEAK - land) * .9f, "到峰值还在走");
            assertEquals(1, ChainTimeline.settle(land - 1, order), 1e-6);
            assertTrue(ChainTimeline.settle(land + 3, order) < .2f, "落位后摆正");
        }
    }

    @Test void linesPierceFirstThenRunFullHeight() {
        assertEquals(0, ChainTimeline.grow(0, 0, .55f), 1e-6);
        assertTrue(ChainTimeline.grow(1.5f, 2, .95f) > .5f, "中间那道先刺到很高");
        assertTrue(ChainTimeline.grow(1.5f, 2, .95f) <= .95f);
        assertEquals(1, ChainTimeline.grow(ChainTimeline.TAKEOVER, 3, .3f), 1e-6, "黑底接管时全部贯穿");
    }

    /** 收尾的光幕两种模式都一样：盖满的那一段很短（不晃眼），卸掉分格、界面回来都在盖满时；黑底在光幕底下化开，不是一下子切掉。 */
    @Test void swapHappensUnderTheVeil() {
        for (boolean gentle : new boolean[] {false, true}) {
            for (float a = ChainTimeline.PEAK + ChainTimeline.VEIL_RAMP; a < ChainTimeline.FADE; a += .1f) assertEquals(1, ChainTimeline.veil(a, gentle), 1e-6);
            assertTrue(ChainTimeline.veil(ChainTimeline.SWAP, gentle) == 1, "幕布在盖满时");
            assertEquals(0, ChainTimeline.veil(ChainTimeline.END, gentle), 1e-6);
            assertEquals(0, ChainTimeline.veil(ChainTimeline.LAND, gentle), 1e-6);
            assertTrue(ChainTimeline.plate(ChainTimeline.SWAP - .05f, gentle) < .02f, "幕布之前黑底已经化开了");
            for (float a = ChainTimeline.PEAK; a < ChainTimeline.END; a += .1f) {
                assertEquals(ChainTimeline.plate(a, gentle), ChainTimeline.plate(a + .1f, gentle), .1, "黑底化开，不是切掉（第 " + a + " 刻）");
                assertEquals(ChainTimeline.dim(a), ChainTimeline.dim(a + .1f), .1, "战场慢慢回来（第 " + a + " 刻）");
                assertEquals(ChainTimeline.cloud(a, gentle), ChainTimeline.cloud(a + .1f, gentle), .1, "白光云散掉（第 " + a + " 刻）");
            }
        }
        assertTrue(ChainTimeline.FADE - (ChainTimeline.PEAK + ChainTimeline.VEIL_RAMP) <= 2, "盖满不超过两刻");
        assertTrue(ChainTimeline.veil(ChainTimeline.FADE + 2, false) < .4f, "退得快：两刻后退掉大半");
        assertFalse(ChainTimeline.portraits(ChainTimeline.SWAP));
        assertTrue(ChainTimeline.portraits(ChainTimeline.SWAP - .01f));
        assertFalse(ChainTimeline.hidesHud(ChainTimeline.SWAP), "界面在光幕底下回来");
        assertTrue(ChainTimeline.hidesHud(ChainTimeline.TAKEOVER));
        assertFalse(ChainTimeline.hidesHud(ChainTimeline.TAKEOVER - .01f), "刺穿那一拍界面还在");
    }

    /** 「隐藏闪电的闪烁」：没有落位爆闪，光幕和黑底都是慢慢盖上的（一刻之内变化不超过三成）。 */
    @Test void gentleModeHasNoSuddenFlashes() {
        for (float a = 0; a < ChainTimeline.END; a += .25f) {
            assertTrue(Math.abs(ChainTimeline.veil(a + 1, true) - ChainTimeline.veil(a, true)) <= .3f, "光幕在第 " + a + " 刻");
            assertTrue(Math.abs(ChainTimeline.cloud(a + 1, true) - ChainTimeline.cloud(a, true)) <= .3f, "白光云在第 " + a + " 刻");
            assertTrue(Math.abs(ChainTimeline.plate(a + 1, true) - ChainTimeline.plate(a, true)) <= .8f, "黑底在第 " + a + " 刻");
            assertTrue(Math.abs(ChainTimeline.exposure(a + 1, true) - ChainTimeline.exposure(a, true)) <= .2f, "曝光在第 " + a + " 刻");
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
