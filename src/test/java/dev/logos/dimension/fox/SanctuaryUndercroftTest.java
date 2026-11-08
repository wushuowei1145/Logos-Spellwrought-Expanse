package dev.logos.dimension.fox;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * 地块变体与地下封印场地的数据：居酒屋每处至多三家；每套方案都备有几条走得通的地下路线，换掉的都是住宅；
 * 岩洞埋得够深，不会挖穿地面；封印杭九根，都在封印之间里。实际观感看 fox_vault_views 截图。
 */
class SanctuaryUndercroftTest {
    private static boolean exists(String template) {
        return SanctuaryUndercroftTest.class.getResource("/data/logos_dimension/structure/fox_sanctuary/" + template + ".nbt") != null;
    }

    @Test
    void izakayaAreCappedAtThree() {
        for (int plan = 0; plan < SanctuaryLayout.plans(); plan++) {
            SanctuaryLayout layout = SanctuaryLayout.get(plan);
            assertFalse(layout.izakaya.isEmpty(), "方案 " + plan + " 没有居酒屋名单");
            int most = 0;
            for (long seed = 0; seed < 300; seed++) {
                String[] chosen = FoxSanctuaryStructure.pickVariants(layout, RandomSource.create(seed), null, null);
                int n = 0;
                for (String t : chosen) if (t != null && layout.izakaya.contains(t)) n++;
                assertTrue(n <= 3, "方案 " + plan + " 种子 " + seed + " 有 " + n + " 家居酒屋");
                most = Math.max(most, n);
                for (int i = 0; i < chosen.length; i++) {
                    if (chosen[i] != null) assertTrue(layout.variants.get(layout.lots.get(i).kind()).contains(chosen[i]), chosen[i]);
                }
            }
            assertTrue(most >= 2, "方案 " + plan + " 居酒屋一家都少见了");
        }
    }

    @Test
    void everyPlanHasRoutesThatReplaceHouses() {
        SanctuaryUndercroft under = SanctuaryUndercroft.get();
        assertTrue(exists(under.gateHouse), "缺少封じの堂模板");
        for (var tile : under.tiles) assertTrue(exists(tile.template()), "缺少岩洞模板 " + tile.template());
        for (int plan = 0; plan < SanctuaryLayout.plans(); plan++) {
            SanctuaryLayout layout = SanctuaryLayout.get(plan);
            assertTrue(layout.routes.size() >= 2, "方案 " + plan + " 地下路线太少：" + layout.routes.size());
            for (var route : layout.routes) {
                assertEquals("house", layout.lots.get(route.lot()).kind(), "方案 " + plan + " 路线换掉的不是住宅");
                assertTrue(exists(route.template()), "缺少路线模板 " + route.template());
                assertTrue(route.path().length >= 6, "路线太短");
                String[] chosen = FoxSanctuaryStructure.pickVariants(layout, RandomSource.create(7), route, under);
                assertEquals(under.gateHouse, chosen[route.lot()]);
            }
        }
    }

    @Test
    void vaultStaysWellBelowTheSurface() {
        SanctuaryUndercroft under = SanctuaryUndercroft.get();
        for (int plan = 0; plan < SanctuaryLayout.plans(); plan++) {
            SanctuaryLayout layout = SanctuaryLayout.get(plan);
            for (int i = 0; i + 5 < under.boxes.length; i += 6) {
                int top = under.boxes[i + 4] + under.shell;
                for (int u = under.boxes[i] - under.shell; u <= under.boxes[i + 3] + under.shell; u++) {
                    for (int v = under.boxes[i + 2] - under.shell; v <= under.boxes[i + 5] + under.shell; v++) {
                        assertTrue(layout.inside(u, v), "岩洞伸出了核心区：" + u + "," + v);
                        assertTrue(layout.height(u, v) - top >= 12, "方案 " + plan + " 岩洞离地面太近：" + u + "," + v);
                    }
                }
            }
        }
    }

    @Test
    void nineSealsStandInsideTheArena() {
        SanctuaryUndercroft under = SanctuaryUndercroft.get();
        int[] a = under.arena;
        assertEquals(27, under.seals.length, "封印杭应当九根");
        for (int i = 0; i < under.seals.length; i += 3) {
            int u = under.seals[i], y = under.seals[i + 1], v = under.seals[i + 2];
            assertTrue(u >= a[0] && u <= a[2] && v >= a[1] && v <= a[3] && y > a[4] && y + 1 <= a[5], "封印杭不在封印之间：" + u + "," + y + "," + v);
            double r = Math.hypot(u - under.center[0], v - under.center[2]);
            assertTrue(r > 8 && r < 18, "封印杭离杀生石的距离不对：" + r);
        }
        // 首领战时可拆的三组：杀生石从石台顶面起，石台在它下面两层，底座盖住整根封印杭；都在封印之间里、离地面以上
        int[] r = under.removable;
        assertEquals(9, r.length);
        assertEquals(under.center[1], r[1], "杀生石的可拆范围应当从石台顶面起");
        assertEquals(r[1] - 1, r[5], "石台应当紧接在杀生石下面");
        assertEquals(a[4] + 1, r[4], "石台从地面上一层起");
        assertTrue(r[8] >= under.seals[1] + 1, "底座的可拆范围要盖住两格高的封印杭");
        assertTrue(r[2] < a[5] && r[0] < 13, "杀生石的范围碰到岩顶或封印杭了");
        // 世界坐标换算可逆：任意镜像与旋转下，封印杭落回同一个设计格
        SanctuaryFrame frame = new SanctuaryFrame(2, 4000, -700, Mirror.FRONT_BACK, Rotation.COUNTERCLOCKWISE_90, 150, 100, 99);
        for (int i = 0; i < under.seals.length; i += 3) {
            BlockPos w = frame.world(under.seals[i], 150 + under.seals[i + 1], under.seals[i + 2]);
            assertArrayEquals(new int[]{under.seals[i], under.seals[i + 2]}, frame.local(w.getX(), w.getZ()));
        }
    }
}
