package dev.logos.dimension.client.longsword;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

/**
 * 按战斗骨架（docs/COMBAT_SKELETON.md）取样玩家参考骨架，剑、剑鞘与分段手臂共用同一组骨骼矩阵。
 * 骨架、武器或动作资源缺失或损坏时返回空，调用方退回原版渲染，不让资源包问题导致崩溃。
 */
public final class LongswordRig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Optional<LongswordRig>> MODELS = new HashMap<>();
    private static final Map<String, Map<String, CombatClip>> CLIPS = new HashMap<>();
    private record Quad(String bone, boolean skin, Vector3f[] points, Vector3f normal, float[] uv) {}
    /** weapon 为剑网格原点的矩阵（剑骨骼 × 枢轴 × 视角缩放），魔力缠绕表面按它定位。 */
    public record Sample(Map<String, Matrix4f> bones, Vector3f bodyRotation, Matrix4f weapon) {}
    private final CombatSkeleton skeleton;
    private final Map<String, CombatClip> clips;
    private final List<Quad> quads = new ArrayList<>();
    private final Identifier texture;
    private final Vector3f weaponPivot;
    private final float weaponScale;

    public static void clear() { MODELS.clear(); CLIPS.clear(); }
    /** 取不到资源时为 null。 */
    public static LongswordRig get(int weapon, boolean firstPerson) {
        var kind = CombatWeapon.byId(weapon);
        if (kind == null) return null;
        String name = kind.mesh(), view = firstPerson ? "fp" : "tp";
        return MODELS.computeIfAbsent(name + "_" + view, key -> {
            try { return Optional.of(new LongswordRig(name, kind.animations(), view)); }
            catch (RuntimeException e) { LOGGER.error("长剑骨架 {} 加载失败，改用原版持物渲染", key, e); return Optional.empty(); }
        }).orElse(null);
    }
    private static JsonObject read(String path) {
        try (var reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(LogosDimension.id(path)).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) { throw new IllegalStateException("无法读取战斗素材 " + path, e); }
    }
    private static Map<String, CombatClip> clips(String animations, String view) {
        return CLIPS.computeIfAbsent(animations + "_" + view, key -> {
            List<String> warnings = new ArrayList<>();
            var clips = CombatClip.parseFile(read("combat/animations/" + key + ".animation.json"), warnings);
            warnings.forEach(w -> LOGGER.warn("战斗动作 {}：{}", key, w));
            return clips;
        });
    }
    private LongswordRig(String name, String animations, String view) {
        var skeletonJson = read("combat/skeletons/player_" + view + ".json");
        skeleton = CombatSkeleton.fromJson(skeletonJson);
        if (!skeleton.missingRequired().isEmpty()) throw new IllegalStateException("玩家骨架缺少 " + skeleton.missingRequired());
        clips = clips(animations, view);
        weaponPivot = skeleton.bone(CombatBones.WEAPON).pivot();
        var weapon = read("combat/weapons/" + name + ".json");
        // 骨架给出视角的武器缩放，武器网格可再乘一个自己的 scale（打刀比长剑长）。
        weaponScale = skeletonJson.get("weapon_scale").getAsFloat() * (weapon.has("scale") ? weapon.get("scale").getAsFloat() : 1);
        for (var value : skeletonJson.getAsJsonArray("skin")) cube(value.getAsJsonObject(), true, new Matrix4f());
        texture = Identifier.parse(weapon.get("texture").getAsString());
        var size = weapon.getAsJsonArray("texture_size");
        float width = size.get(0).getAsFloat(), height = size.get(1).getAsFloat();
        for (var value : weapon.getAsJsonArray("cubes")) {
            var cube = value.getAsJsonObject();
            boolean sheath = cube.get("part").getAsString().equals("sheath");
            // 第一人称只画剑，剑鞘只在第三人称挂在腰侧。
            if (sheath && view.equals("fp")) continue;
            var bone = skeleton.bone(sheath ? CombatBones.SHEATH : CombatBones.WEAPON);
            var origin = new Matrix4f().translation(bone.pivot()).scale(weaponScale);
            cube.addProperty("bone", bone.name());
            cube(cube, false, origin);
            for (int i = quads.size() - 6; i < quads.size(); i++) {
                var uv = quads.get(i).uv;
                uv[0] /= width; uv[1] /= height; uv[2] /= width; uv[3] /= height;
            }
        }
    }
    private static Vector3f vector(JsonArray a) { return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()); }
    private static Matrix4f local(Vector3f pivot, Vector3f rotation) {
        return CombatPose.local(pivot, new CombatPose.Joint(new Vector3f(), rotation, new Vector3f(1)));
    }
    /** 动作时长（秒）；动作文件里没有这段动作时为 -1。 */
    public double length(String clip) {
        var animation = clips.get(clip);
        return animation == null ? -1 : animation.length();
    }
    /** 取样一帧姿势；动作文件里没有这段动作时为 null。 */
    public CombatPose pose(String clip, float seconds) {
        var animation = clips.get(clip);
        return animation == null ? null : CombatPose.sample(animation, seconds);
    }
    /** 把姿势套到玩家参考骨架上。 */
    public Sample sample(CombatPose pose) {
        var bones = pose.matrices(skeleton);
        var weapon = new Matrix4f(bones.get(CombatBones.WEAPON)).translate(weaponPivot).scale(weaponScale);
        return new Sample(bones, pose.rotation(CombatBones.UP_BODY), weapon);
    }
    private void cube(JsonObject cube, boolean skin, Matrix4f origin) {
        var from = vector(cube.getAsJsonArray("start")); var to = vector(cube.getAsJsonArray("end"));
        var transform = new Matrix4f(origin).mul(local(vector(cube.getAsJsonArray("pivot")), vector(cube.getAsJsonArray("rotation"))));
        String bone = cube.get("bone").getAsString();
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
            float[] textureUv;
            if (skin) textureUv = skinUv(bone, face);
            else {
                var uv = cube.getAsJsonObject("uv").getAsJsonArray(names[face]);
                textureUv = new float[]{uv.get(0).getAsFloat(), uv.get(1).getAsFloat(), uv.get(2).getAsFloat(), uv.get(3).getAsFloat()};
            }
            quads.add(new Quad(bone, skin, points, normal, textureUv));
        }
    }
    /** 原版 64×64 皮肤的手臂区域按上臂 6、前臂 4、手 2 像素分段。 */
    private static float[] skinUv(String bone, int face) {
        boolean left = bone.startsWith("Left");
        float u = left ? 32 : 40, v = left ? 48 : 16;
        String part = bone.substring(left ? 4 : 5);
        float top = part.equals("Arm") ? 4 : part.equals("ForeArm") ? 10 : 14;
        float bottom = part.equals("Arm") ? 10 : part.equals("ForeArm") ? 14 : 16;
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
