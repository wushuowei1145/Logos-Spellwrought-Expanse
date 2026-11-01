package dev.logos.dimension.client.longsword;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 直接取样审定的双手骨骼，剑与手臂共用矩阵；展示人偶的头、躯干和腿不参与渲染。 */
public final class LongswordRig {
    private static final Map<String, LongswordRig> MODELS = new HashMap<>();
    private static final Map<String, JsonObject> MOTIONS = new HashMap<>();
    private record Bone(String name, String parent, Vector3f pivot) {}
    private record Quad(String bone, boolean skin, Vector3f[] points, Vector3f normal, float[] uv) {}
    public record Sample(Map<String, Matrix4f> bones, Vector3f bodyRotation) {}
    private final List<Bone> bones = new ArrayList<>();
    private final List<Quad> quads = new ArrayList<>();
    private final JsonObject clips;
    private final Identifier texture;

    public static void clear() { MODELS.clear(); MOTIONS.clear(); }
    public static LongswordRig get(int variant, boolean firstPerson) {
        String name = variant == 1 ? "blacksteel_longsword" : "dawnvow", view = firstPerson ? "fp" : "tp";
        return MODELS.computeIfAbsent(name + "_" + view, key -> new LongswordRig(name, view));
    }
    private static JsonObject read(String path) {
        try (var reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(LogosDimension.id("longsword/" + path + ".json")).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) { throw new IllegalStateException("无法读取长剑动作素材 " + path, e); }
    }
    private LongswordRig(String name, String view) {
        var model = read(name + "_" + view);
        clips = MOTIONS.computeIfAbsent(view, key -> read("motion_" + view));
        texture = LogosDimension.id("textures/item/" + name + "_rig.png");
        for (var value : model.getAsJsonArray("bones")) {
            var b = value.getAsJsonObject();
            bones.add(new Bone(b.get("name").getAsString(), b.get("parent").isJsonNull() ? null : b.get("parent").getAsString(), vector(b.getAsJsonArray("pivot"), 0)));
        }
        for (var value : model.getAsJsonArray("cubes")) cube(value.getAsJsonObject());
    }
    private static Vector3f vector(JsonArray a, int offset) { return new Vector3f(a.get(offset).getAsFloat(), a.get(offset + 1).getAsFloat(), a.get(offset + 2).getAsFloat()); }
    private static Matrix4f local(Vector3f pivot, Vector3f position, Vector3f rotation) {
        float r = (float) Math.PI / 180;
        return new Matrix4f().translation(new Vector3f(pivot).add(position))
                .rotateZ(rotation.z * r).rotateY(rotation.y * r).rotateX(rotation.x * r).translate(-pivot.x, -pivot.y, -pivot.z);
    }
    private static Vector3f channel(JsonObject track, String name, float time) {
        if (track == null || !track.has(name)) return new Vector3f();
        var keys = track.getAsJsonArray(name);
        int lo = 0, hi = keys.size() - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (keys.get(mid).getAsJsonArray().get(0).getAsFloat() <= time) lo = mid; else hi = mid; }
        var a = keys.get(lo).getAsJsonArray(); var b = keys.get(hi).getAsJsonArray();
        float t0 = a.get(0).getAsFloat(), t1 = b.get(0).getAsFloat();
        return vector(a, 1).lerp(vector(b, 1), Math.clamp((time - t0) / Math.max(.000001f, t1 - t0), 0, 1));
    }
    public Sample sample(String clip, float seconds) {
        var tracks = clips.getAsJsonObject(clip).getAsJsonObject("tracks");
        Map<String, Matrix4f> matrices = new HashMap<>();
        Vector3f body = new Vector3f();
        for (var bone : bones) {
            var track = tracks.getAsJsonObject(bone.name);
            var rotation = channel(track, "rotation", seconds);
            var matrix = local(bone.pivot, channel(track, "position", seconds), rotation);
            if (bone.parent != null) matrix = new Matrix4f(matrices.get(bone.parent)).mul(matrix);
            matrices.put(bone.name, matrix);
            if (bone.name.equals("body")) body = rotation;
        }
        return new Sample(matrices, body);
    }
    private void cube(JsonObject cube) {
        var from = vector(cube.getAsJsonArray("start"), 0); var to = vector(cube.getAsJsonArray("end"), 0);
        var transform = local(vector(cube.getAsJsonArray("pivot"), 0), new Vector3f(), vector(cube.getAsJsonArray("rotation"), 0));
        String bone = cube.get("bone").getAsString(); boolean skin = cube.get("skin").getAsBoolean();
        float x = from.x, y = from.y, z = from.z, X = to.x, Y = to.y, Z = to.z;
        float[][][] faces = {
            {{X,y,z},{x,y,z},{x,Y,z},{X,Y,z}}, {{x,y,Z},{X,y,Z},{X,Y,Z},{x,Y,Z}},
            {{x,y,z},{x,y,Z},{x,Y,Z},{x,Y,z}}, {{X,y,Z},{X,y,z},{X,Y,z},{X,Y,Z}},
            {{x,Y,Z},{X,Y,Z},{X,Y,z},{x,Y,z}}, {{x,y,z},{X,y,z},{X,y,Z},{x,y,Z}}
        };
        String[] names = {"north", "south", "west", "east", "up", "down"};
        for (int face = 0; face < 6; face++) {
            var points = new Vector3f[4];
            for (int i = 0; i < 4; i++) points[i] = transform.transformPosition(new Vector3f(faces[face][i]));
            var normal = new Vector3f(points[1]).sub(points[0]).cross(new Vector3f(points[2]).sub(points[0])).normalize();
            var uv = cube.getAsJsonObject("uv").getAsJsonArray(names[face]);
            float[] textureUv = {uv.get(0).getAsFloat()/256, uv.get(1).getAsFloat()/512, uv.get(2).getAsFloat()/256, uv.get(3).getAsFloat()/512};
            if (skin) textureUv = skinUv(bone, face);
            quads.add(new Quad(bone, skin, points, normal, textureUv));
        }
    }
    private static float[] skinUv(String bone, int face) {
        boolean left = bone.startsWith("left");
        float u = left ? 32 : 40, v = left ? 48 : 16;
        float top = bone.endsWith("upper_arm") ? 4 : bone.endsWith("forearm") ? 10 : 14;
        float bottom = bone.endsWith("upper_arm") ? 10 : bone.endsWith("forearm") ? 14 : 16;
        float[] offset = switch (face) {
            case 0 -> new float[]{4,top,8,bottom};
            case 1 -> new float[]{12,top,16,bottom};
            case 2 -> new float[]{0,top,4,bottom};
            case 3 -> new float[]{8,top,12,bottom};
            case 4 -> new float[]{4,0,8,4};
            default -> new float[]{8,0,12,4};
        };
        return new float[]{(u+offset[0])/64,(v+offset[1])/64,(u+offset[2])/64,(v+offset[3])/64};
    }
    public void submit(Sample sample, PoseStack pose, SubmitNodeCollector collector, int light, Identifier skin, boolean arms) {
        submitMaterial(sample, pose, collector, light, texture, false);
        if (arms) submitMaterial(sample, pose, collector, light, skin, true);
    }
    private void submitMaterial(Sample sample, PoseStack pose, SubmitNodeCollector collector, int light, Identifier texture, boolean skin) {
        // 提交前冻结顶点，延迟渲染回调不读取玩家或可变骨骼状态。
        List<Quad> transformed = new ArrayList<>();
        for (var quad : quads) if (quad.skin == skin) {
            var matrix = sample.bones.get(quad.bone);
            var points = new Vector3f[4];
            for (int i = 0; i < 4; i++) points[i] = matrix.transformPosition(new Vector3f(quad.points[i]));
            transformed.add(new Quad(quad.bone, skin, points, matrix.transformDirection(new Vector3f(quad.normal)).normalize(), quad.uv));
        }
        collector.order(-1).submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (p, buffer) -> {
            for (var quad : transformed) for (int i = 0; i < 4; i++) {
                var point = quad.points[i]; var uv = quad.uv; var n = quad.normal;
                float u = i == 0 || i == 3 ? uv[2] : uv[0], v = i < 2 ? uv[3] : uv[1];
                buffer.addVertex(p, point.x, point.y, point.z).setColor(255,255,255,255).setUv(u,v)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p,n.x,n.y,n.z);
            }
        });
    }
}
