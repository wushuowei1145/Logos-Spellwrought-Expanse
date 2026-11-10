package dev.logos.dimension.client.trail;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.lang.Element;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 刀光（docs/WEAPON_TRAILS.md）：样式与设置文件都读得通、引用的东西都在，扫过的面按段记点、帧间补点贴着圆弧。 */
class WeaponTrailsTest {
    /** 测试类路径上的资源目录（构建好的 resources），工作目录不一定是工程根目录。 */
    private static final Path ASSETS = assets();

    private static Path assets() {
        try {
            return Path.of(WeaponTrailsTest.class.getResource("/assets/logos_dimension/combat/trail_styles/physical.json").toURI()).getParent().getParent().getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject json(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static List<Path> files(String dir) throws IOException {
        try (Stream<Path> s = Files.list(ASSETS.resolve(dir))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /** 本附属的贴图要真的在；Logos 的贴图在它的 jar 里，这里不查。 */
    private static void assertTexture(Identifier id, String where) {
        if (!id.getNamespace().equals("logos_dimension")) return;
        assertTrue(Files.isRegularFile(ASSETS.resolve(id.getPath())), where + " 引用的贴图不存在：" + id);
    }

    @Test void everyStyleParsesAndItsTexturesExist() throws IOException {
        List<Path> styles = files("combat/trail_styles");
        assertFalse(styles.isEmpty());
        for (Path p : styles) {
            String name = p.getFileName().toString().replace(".json", "");
            TrailStyle style = TrailStyle.parse(Identifier.fromNamespaceAndPath("logos_dimension", name), json(p));
            assertFalse(style.layers().isEmpty(), name);
            assertTrue(style.life() > 0 && style.life() < 2, name + " 的 life 不合理");
            for (TrailStyle.Layer l : style.layers()) {
                assertTexture(l.mask(), name);
                assertTexture(l.erosionNoise(), name);
                assertTexture(l.detailNoise(), name);
                assertTexture(l.distortNoise(), name);
                assertTrue(l.inner() < l.outer(), name + " 的 span 要从内到外");
                float occlude = l.define("OCCLUDE");
                assertTrue(occlude >= 0 && occlude <= 1, name + " 的 occlude 要在 0–1");
                assertTrue(l.narrow() >= 0 && l.narrow() < 1, name + " 的 narrow 要在 0–1");
                // 着色器用到的每个 #define 都要有值，少一个就编译不过
                for (String define : SHADER_DEFINES) assertTrue(l.defines().containsKey(define), name + " 缺少 " + define);
            }
            if (style.sparks() != null) assertTexture(style.sparks().texture(), name);
        }
    }

    /** weapon_trail.fsh 里用到的 #define（颜色按分量）。 */
    private static final List<String> SHADER_DEFINES = List.of("C1_R", "C1_G", "C1_B", "C2_R", "C2_G", "C2_B", "C3_R", "C3_G", "C3_B",
            "COLOR_STRENGTH", "COLOR_CONTRAST", "COLOR_TILE_U", "COLOR_TILE_V", "COLOR_PAN", "HOT_FROM", "HOT_GAIN", "EMISSIVE_MASK",
            "MAIN_CONTRAST", "MAIN_STRENGTH", "OPACITY", "OCCLUDE", "DISTORT", "DIST_TILE_U", "DIST_TILE_V", "DIST_PAN",
            "OM_STRENGTH", "OM_CONTRAST", "OM_TILE_U", "OM_TILE_V", "OM_PAN", "EROSION", "ER_START", "ER_POW", "ER_FEATHER",
            "ER_CONTRAST", "ER_STRENGTH", "ER_TILE_U", "ER_TILE_V", "ER_PAN", "NARROW", "NARROW_POW", "NARROW_SOFT",
            "FADE_IN", "FADE_OUT", "FADE_TAIL", "RIM", "RIM_WIDTH");

    @Test void shaderUsesExactlyTheDefinesStylesGive() throws IOException {
        // 清单里的宏着色器都要用到（清单与 TrailStyle 给出的一致），免得改了一边忘了另一边
        String fsh = Files.readString(ASSETS.resolve("shaders/core/weapon_trail.fsh"));
        for (String define : SHADER_DEFINES) assertTrue(fsh.contains(define), "着色器没有用到 " + define);
    }

    @Test void everyDamageTypeHasAStyle() {
        for (Element e : Element.values()) {
            Path p = ASSETS.resolve("combat/trail_styles/" + e.name().toLowerCase(Locale.ROOT) + ".json");
            assertTrue(Files.isRegularFile(p), e + " 没有刀光样式");
        }
        assertTrue(Files.isRegularFile(ASSETS.resolve("combat/trail_styles/foxfire.json")), "狐火没有刀光样式");
    }

    @Test void everyTrailSettingMatchesItsAnimations() throws IOException {
        for (Path p : files("combat/trails")) {
            String set = p.getFileName().toString().replace(".json", "");
            TrailConfigs.Set config = TrailConfigs.parse(json(p));
            assertNotNull(config.blade() != null ? config.blade() : config.meshes().values().stream().findFirst().orElse(null),
                    set + " 没有写刀身范围（blade 或 meshes）");
            for (var blade : config.meshes().values()) assertTrue(blade.from() < blade.to(), set);
            if (config.blade() != null) assertTrue(config.blade().from() < config.blade().to(), set);
            assertTrue(Files.isRegularFile(ASSETS.resolve("combat/trail_styles/" + config.style().getPath() + ".json")), set + " 的默认样式不存在");
            // 和 LongswordRig 一样：动作组的动作，再叠上各网格自己的动作文件（拔剑、收剑、施法姿势等）
            JsonObject clips = json(ASSETS.resolve("combat/animations/" + set + "_tp.animation.json")).getAsJsonObject("animations").deepCopy();
            for (String mesh : config.meshes().keySet()) {
                Path own = ASSETS.resolve("combat/animations/" + mesh + "_tp.animation.json");
                if (Files.isRegularFile(own)) for (var e : json(own).getAsJsonObject("animations").entrySet()) clips.add(e.getKey(), e.getValue());
            }
            for (var entry : config.clips().entrySet()) {
                String clip = entry.getKey();
                assertTrue(clips.has(clip), set + " 里没有动作 " + clip);
                double length = clips.getAsJsonObject(clip).get("animation_length").getAsDouble();
                for (float[] w : entry.getValue().windows())
                    assertTrue(w[0] >= 0 && w[0] < w[1] && w[1] <= length + 1e-6, set + "/" + clip + " 的出光时段超出动作");
                if (entry.getValue().style() != null)
                    assertTrue(Files.isRegularFile(ASSETS.resolve("combat/trail_styles/" + entry.getValue().style().getPath() + ".json")),
                            set + "/" + clip + " 的样式不存在");
            }
        }
    }

    @Test void ribbonKeepsSwingsApartAndDropsRepeatedFrames() {
        TrailRibbon r = new TrailRibbon();
        r.add(0, Vec3.ZERO, new Vec3(0, 1, 0), true);
        r.add(.01, Vec3.ZERO, new Vec3(.1, 1, 0), true);
        r.add(.01, Vec3.ZERO, new Vec3(.5, 1, 0), true);   // 同一帧画了两次
        assertEquals(2, r.points().size());
        r.add(.02, Vec3.ZERO, new Vec3(.2, 1, 0), false);  // 停
        r.add(.5, Vec3.ZERO, new Vec3(0, 0, 1), true);     // 下一刀
        assertEquals(3, r.points().size());
        assertNotEquals(r.points().get(1).segment(), r.points().get(2).segment(), "两刀之间不能连起来");
        assertEquals(0, r.points().get(2).arc(), 1e-6, "新的一刀从头算路程");
        r.prune(.6, .2f);
        assertEquals(1, r.points().size(), "第一刀已散尽");
    }

    @Test void resumingAfterTheTrailFadedStartsANewSwing() {
        // 送点的一方停了比 life 还久（被剔除），点全散尽后又接着送：另起一段，不能空指针
        TrailRibbon r = new TrailRibbon();
        r.add(0, Vec3.ZERO, new Vec3(0, 1, 0), true);
        r.add(.02, Vec3.ZERO, new Vec3(.2, 1, 0), true);
        r.prune(1, .2f);
        assertTrue(r.points().isEmpty());
        assertFalse(r.emitting());
        r.add(1.01, Vec3.ZERO, new Vec3(0, 0, 1), true);
        assertEquals(1, r.points().size());
        assertEquals(0, r.points().get(0).arc(), 1e-6);
    }

    @Test void smoothingFollowsTheArc() {
        // 刀尖绕原点转、半径 1，每帧转 40°（低帧率的快速挥砍）：补出来的点应贴着圆，而不是落在弦上
        TrailRibbon r = new TrailRibbon();
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(40 * i);
            r.addSmoothed(i * .02, Vec3.ZERO, new Vec3(Math.cos(a), Math.sin(a), 0), true, .05f);
        }
        assertTrue(r.points().size() > 5, "帧间要补点");
        for (TrailRibbon.Point p : r.points()) {
            double radius = p.tip().length();
            // 最新一段还是直的（下一帧才补），弦的中点离圆心 cos 20° ≈ 0.94
            assertTrue(radius > .93 && radius < 1.03, "刀尖离开了圆弧：" + radius);
        }
        // 补过的段：中间点离圆心比弦中点更接近 1
        TrailRibbon.Point mid = r.points().get(r.points().size() / 3);
        assertTrue(mid.tip().length() > .97, "补点没有贴着圆弧：" + mid.tip().length());
    }
}
