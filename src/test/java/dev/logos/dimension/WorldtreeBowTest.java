package dev.logos.dimension;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.worldtree.WorldtreeAim;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 世界树之弓：锁定框的几何、伤害随锁定数增长、虚影箭的扇面、物品定义与生成的资源。 */
class WorldtreeBowTest {
    private static JsonObject json(String path) throws Exception {
        try (var in = WorldtreeBowTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void theFrameIsARectangleOnTheViewPlaneAndGrowsOpen() {
        Vec3 eye = new Vec3(0, 0, 0);
        // 朝向 0 看向 +Z：正前方在框中心，左右各 30°、上下各 18° 处正好在框边上
        var center = WorldtreeAim.local(eye, 0, 0, new Vec3(0, 0, 10)).orElseThrow();
        assertEquals(0, center.x, 1e-9);
        assertEquals(0, center.y, 1e-9);
        double side = Math.tan(Math.toRadians(WorldtreeAim.HALF_WIDTH)) * 10;
        assertEquals(1, Math.abs(WorldtreeAim.local(eye, 0, 0, new Vec3(side, 0, 10)).orElseThrow().x), 1e-6);
        double up = Math.tan(Math.toRadians(WorldtreeAim.HALF_HEIGHT)) * 10;
        assertEquals(1, WorldtreeAim.local(eye, 0, 0, new Vec3(0, up, 10)).orElseThrow().y, 1e-6);
        // 身后的不算
        assertTrue(WorldtreeAim.local(eye, 0, 0, new Vec3(0, 0, -5)).isEmpty());
        // 框从无展开到满（缓出），满了之后不再变大
        assertEquals(0, WorldtreeAim.frame(0), 1e-9);
        assertTrue(WorldtreeAim.frame(WorldtreeAim.GROW_TICKS / 2f) > .5f);
        assertEquals(1, WorldtreeAim.frame(WorldtreeAim.GROW_TICKS), 1e-6);
        assertEquals(1, WorldtreeAim.frame(100), 1e-6);
        var edge = WorldtreeAim.local(eye, 0, 0, new Vec3(side * .9, 0, 10)).orElseThrow();
        assertFalse(WorldtreeAim.inside(edge, WorldtreeAim.frame(WorldtreeAim.GROW_TICKS / 3f)));
        assertTrue(WorldtreeAim.inside(edge, 1));
    }

    @Test void theFrameTurnsWithTheView() {
        // 朝向 90 看向 -X：-X 方向的点在框中心（原版的三角函数查表，误差约 1e-4）
        var p = WorldtreeAim.local(Vec3.ZERO, 90, 0, new Vec3(-10, 0, 0)).orElseThrow();
        assertEquals(0, p.x, 1e-3);
        assertEquals(10, p.z, 1e-3);
        // 抬头 30°：斜上方 30° 的点在中心
        var q = WorldtreeAim.local(Vec3.ZERO, 0, -30, new Vec3(0, Math.sin(Math.toRadians(30)) * 10, Math.cos(Math.toRadians(30)) * 10)).orElseThrow();
        assertEquals(0, q.y, 1e-3);
    }

    @Test void moreLocksHitHarder() {
        assertEquals(3, WorldtreeAim.homingDamage(1), 1e-6);
        assertEquals(6.75, WorldtreeAim.homingDamage(WorldtreeAim.MAX_LOCKS), 1e-6);
        for (int n = 1; n < WorldtreeAim.MAX_LOCKS; n++) assertTrue(WorldtreeAim.homingDamage(n + 1) > WorldtreeAim.homingDamage(n));
    }

    @Test void phantomArrowsFanOutAlternatelyWithoutOverlapping() {
        var seen = new HashSet<Float>();
        for (int k = 0; k < WorldtreeAim.MAX_LOCKS; k++) {
            float a = dev.logos.dimension.item.WorldtreeBowItem.fan(k);
            assertTrue(seen.add(a), "第 " + k + " 支与前面的重叠");
            assertEquals(k % 2 == 0, a > 0);
            assertTrue(Math.abs(a) <= 24.001f);
        }
    }

    /** 物品定义：原版的拉弓模型切换加上画箭的模型；三段拉弓去掉了交付里那支箭、第一人称换成原版拉弓的朝向。 */
    @Test void itemDefinitionAndModels() throws Exception {
        var root = json("/assets/logos_dimension/items/worldtree_bow.json").getAsJsonObject("model");
        // 左键横着拉弓时换横过来的拉弓模型
        assertEquals("logos_dimension:worldtree_sideways", root.get("property").getAsString());
        var model = root.getAsJsonObject("on_false");
        assertEquals("minecraft:composite", model.get("type").getAsString());
        var arrows = model.getAsJsonArray("models").get(1).getAsJsonObject();
        assertEquals(arrows.get("stages"), root.getAsJsonObject("on_true").getAsJsonArray("models").get(1).getAsJsonObject().get("stages"));
        assertEquals("logos_dimension:worldtree_arrows", arrows.get("type").getAsString());
        assertEquals(3, arrows.getAsJsonArray("stages").size());
        assertEquals(2, arrows.getAsJsonArray("thresholds").size());
        float previous = Float.MAX_VALUE;
        for (var stage : arrows.getAsJsonArray("stages")) {
            var nock = stage.getAsJsonObject().getAsJsonArray("nock");
            var tip = stage.getAsJsonObject().getAsJsonArray("tip");
            // 箭朝 +X、越拉搭箭点越往后
            assertTrue(tip.get(0).getAsFloat() > nock.get(0).getAsFloat());
            assertTrue(nock.get(0).getAsFloat() < previous);
            previous = nock.get(0).getAsFloat();
        }
        var still = json("/assets/logos_dimension/models/item/worldtree_bow.json");
        int bowCubes = still.getAsJsonArray("elements").size();
        // 静止与拉弓的第一人称同一个摆法（按原版弓换算），拉弓时弓不跳
        assertEquals(still.getAsJsonObject("display").get("firstperson_righthand"),
                json("/assets/logos_dimension/models/item/worldtree_bow_pulling_0.json").getAsJsonObject("display").get("firstperson_righthand"));
        for (int i = 0; i < 3; i++) {
            var pulling = json("/assets/logos_dimension/models/item/worldtree_bow_pulling_" + i + ".json");
            assertEquals(bowCubes, pulling.getAsJsonArray("elements").size(), "拉弓模型里不该再有箭");
            assertEquals(-180, pulling.getAsJsonObject("display").getAsJsonObject("firstperson_righthand").getAsJsonArray("rotation").get(0).getAsFloat(), 1e-6);
            var side = json("/assets/logos_dimension/models/item/worldtree_bow_side_pulling_" + i + ".json");
            assertEquals(bowCubes, side.getAsJsonArray("elements").size());
            assertNotEquals(pulling.getAsJsonObject("display").get("firstperson_righthand"), side.getAsJsonObject("display").get("firstperson_righthand"));
            assertEquals(pulling.getAsJsonObject("display").get("gui"), side.getAsJsonObject("display").get("gui"));
        }
    }

    @Test void sixteenRisingLockSounds() throws Exception {
        var sounds = json("/assets/logos_dimension/sounds.json");
        for (int i = 1; i <= WorldtreeAim.MAX_LOCKS; i++) {
            String name = String.format("lock_%02d", i);
            assertTrue(sounds.has("item.worldtree_bow." + name), name);
            assertNotNull(WorldtreeBowTest.class.getResource("/assets/logos_dimension/sounds/item/worldtree_bow/" + name + ".ogg"), name);
        }
        for (String path : new String[] {"textures/entity/projectiles/worldtree_arrow.png", "textures/entity/projectiles/worldtree_arrow_phantom.png",
                "textures/gui/worldtree/frame.png", "textures/gui/worldtree/lock.png", "textures/item/worldtree_bow.png"})
            assertNotNull(WorldtreeBowTest.class.getResource("/assets/logos_dimension/" + path), path);
    }
}
