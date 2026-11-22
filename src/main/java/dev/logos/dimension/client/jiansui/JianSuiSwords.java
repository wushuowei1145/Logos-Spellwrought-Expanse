package dev.logos.dimension.client.jiansui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatPose;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 剑祟的七把剑（手中古剑、贯穿古剑、五柄背剑）的网格（{@code assets/logos_dimension/jian_sui/swords/*.json}，
 * 由仓库外的 v6 工程导出：模型单位、握点在原点、剑尖朝 +Y，贴图就是剑祟自己那张）。
 * 手中古剑不并进模型，画在空的 {@code Weapon} 骨骼上；掷出去、飞起来的剑由飞剑实体画同一份网格。
 *
 * <p>网格是 Blockbench 空间；GeckoLib 的骨骼坐标 X 是反的，所以画在骨骼上时先沿 X 镜像（{@link #submitOnBone}）。
 */
public final class JianSuiSwords {
    public static final Identifier TEXTURE = LogosDimension.id("textures/entity/jian_sui.png");
    public static final String HELD = "jian_sui_sword", AXIAL = "jian_sui_axial_sword";
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** 一个面：四个点（模型单位）、贴图 [u0, v0, u1, v1]（0–1）、法线。 */
    record Face(Vector3f[] points, float[] uv, Vector3f normal) {}

    private static final Map<String, List<Face>> MESHES = new HashMap<>();

    private JianSuiSwords() {}

    public static String back(int index) {
        return "jian_sui_back_sword_" + index;
    }

    static synchronized @Nullable List<Face> mesh(String name) {
        return MESHES.computeIfAbsent(name, JianSuiSwords::load);
    }

    private static @Nullable List<Face> load(String name) {
        var id = LogosDimension.id("jian_sui/swords/" + name + ".json");
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(id);
            if (resource.isEmpty()) return null;
            try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray size = json.getAsJsonArray("texture_size");
                float w = size.get(0).getAsFloat(), h = size.get(1).getAsFloat();
                List<Face> faces = new ArrayList<>();
                for (var c : json.getAsJsonArray("cubes")) cube(c.getAsJsonObject(), w, h, faces);
                return faces;
            }
        } catch (Exception error) {
            LOG.warn("Jian Sui sword mesh {} could not be read: {}", name, error.toString());
            return null;
        }
    }

    /** 同 {@code LongswordRig} 的方块 → 六个面（点的顺序、贴图的对应都一样）。 */
    private static void cube(JsonObject cube, float w, float h, List<Face> out) {
        Vector3f from = vector(cube.getAsJsonArray("start")), to = vector(cube.getAsJsonArray("end"));
        Matrix4f transform = CombatPose.local(vector(cube.getAsJsonArray("pivot")),
                new CombatPose.Joint(new Vector3f(), vector(cube.getAsJsonArray("rotation")), new Vector3f(1)));
        float x = from.x, y = from.y, z = from.z, X = to.x, Y = to.y, Z = to.z;
        float[][][] faces = {
            {{X, y, z}, {x, y, z}, {x, Y, z}, {X, Y, z}}, {{x, y, Z}, {X, y, Z}, {X, Y, Z}, {x, Y, Z}},
            {{x, y, z}, {x, y, Z}, {x, Y, Z}, {x, Y, z}}, {{X, y, Z}, {X, y, z}, {X, Y, z}, {X, Y, Z}},
            {{x, Y, Z}, {X, Y, Z}, {X, Y, z}, {x, Y, z}}, {{x, y, z}, {X, y, z}, {X, y, Z}, {x, y, Z}}
        };
        String[] names = {"north", "south", "west", "east", "up", "down"};
        JsonObject uvs = cube.getAsJsonObject("uv");
        for (int f = 0; f < 6; f++) {
            if (!uvs.has(names[f])) continue;
            Vector3f[] points = new Vector3f[4];
            for (int i = 0; i < 4; i++) points[i] = transform.transformPosition(new Vector3f(faces[f][i]));
            Vector3f normal = new Vector3f(points[1]).sub(points[0]).cross(new Vector3f(points[2]).sub(points[0])).normalize();
            JsonArray uv = uvs.getAsJsonArray(names[f]);
            out.add(new Face(points, new float[] {uv.get(0).getAsFloat() / w, uv.get(1).getAsFloat() / h,
                    uv.get(2).getAsFloat() / w, uv.get(3).getAsFloat() / h}, normal));
        }
    }

    private static Vector3f vector(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    /**
     * 在 GeckoLib 骨骼回调里画一把剑：姿势以「模型像素 / 16」为单位、原点在骨骼枢轴上；网格先沿 X 镜像（Blockbench → GeckoLib）。
     * {@code scale} 是额外的放大（巨剑四倍）。
     */
    public static void submitOnBone(PoseStack pose, SubmitNodeCollector out, int light, String name, float scale, int color) {
        pose.pushPose();
        pose.scale(-scale / 16f, scale / 16f, scale / 16f);
        submit(pose, out, light, name, color);
        pose.popPose();
    }

    /** 按当前姿势（网格坐标即姿势坐标）画一把剑。 */
    public static void submit(PoseStack pose, SubmitNodeCollector out, int light, String name, int color) {
        List<Face> faces = mesh(name);
        if (faces == null || faces.isEmpty()) return;
        out.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, v) -> emit(p, v, faces, light, color));
    }

    /** 发光、半透明地画一把剑（巨剑、刚插下的遗剑那点光）：贴图照旧，全亮，颜色的 alpha 管透明。 */
    public static void submitGlow(PoseStack pose, SubmitNodeCollector out, String name, int color) {
        List<Face> faces = mesh(name);
        if (faces == null || faces.isEmpty() || (color >>> 24) < 2) return;
        out.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(TEXTURE), (p, v) -> emit(p, v, faces, net.minecraft.util.LightCoordsUtil.FULL_BRIGHT, color));
    }

    static void emit(PoseStack.Pose p, VertexConsumer v, List<Face> faces, int light, int color) {
        for (Face face : faces) {
            float[] uv = face.uv;
            for (int i = 0; i < 4; i++) {
                Vector3f q = face.points[i];
                float u = i == 0 || i == 3 ? uv[2] : uv[0], vv = i < 2 ? uv[3] : uv[1];
                v.addVertex(p, q.x, q.y, q.z).setColor(color).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light).setNormal(p, face.normal.x, face.normal.y, face.normal.z);
            }
        }
    }
}
