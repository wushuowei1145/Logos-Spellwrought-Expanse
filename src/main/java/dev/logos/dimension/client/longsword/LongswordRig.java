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
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
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
    /** 网格分层：剑与剑鞘、宽臂皮肤、细臂皮肤，渲染时只提交一种手臂。 */
    private enum Layer { WEAPON, WIDE, SLIM }
    /**
     * material 是武器层的贴图序号：0 为武器网格自己的贴图，之后依次是各附件（和弓的箭）的贴图。
     * fades 是武器网格里标了 {@code fade} 的方块（流火的刀身）：魔力耗尽时只有它们散去。
     */
    private record Quad(String bone, Layer layer, int material, Vector3f[] points, Vector3f normal, float[] uv, boolean fades) {
        Quad(String bone, Layer layer, int material, Vector3f[] points, Vector3f normal, float[] uv) { this(bone, layer, material, points, normal, uv, false); }
    }
    /**
     * weapon 为剑网格原点的矩阵（剑骨骼 × 枢轴 × 视角缩放），魔力缠绕表面按它定位。
     * turn 是第三人称整个人的转身角（度，Blockbench 绕 +Y，即动作里 Root 的 Y 旋转）：骨架里已去掉这一转，
     * 由客户端把原版身体整个转过去（头仍看准星）；第一人称为 0（转身留在骨架里）。
     * rightLeg、leftLeg 是双腿的欧拉角（度），原版腿只取张开的角度。
     */
    public record Sample(Map<String, Matrix4f> bones, Vector3f bodyRotation, Matrix4f weapon, float turn,
                         Vector3f rightLeg, Vector3f leftLeg) {}
    private final CombatSkeleton skeleton;
    private final Map<String, CombatClip> clips;
    private final List<Quad> quads = new ArrayList<>();
    private final Identifier texture;
    /** 武器层的各贴图：武器网格自己的，接着是各附件的。 */
    private final List<Identifier> materials = new ArrayList<>();
    private final boolean firstPerson;
    /** 剑身发光层的贴图（武器网格的 {@code glow}，只有发光的像素不透明）；没有时为 null。 */
    private final Identifier glow;
    private final Vector3f weaponPivot;
    private final float weaponScale;
    /** 武器网格的不透明度（{@code alpha}，流火的晶刃略透明）；1 时按不透明裁切画。 */
    private final float alpha;
    private final int textureWidth, textureHeight;
    /** 凝聚与消散切好的片（只切武器网格自己的方块），第一次用到时切。 */
    private dev.logos.dimension.client.dissolve.WeaponDissolve.Cells cells;
    /** 拉弓按视线俯仰时，上半身最多后仰或前倾的角度（度），其余由双臂转。 */
    private static final float MAX_LEAN = 25;
    private static final java.util.Set<String> BELOW_WAIST = java.util.Set.of(CombatBones.ROOT, CombatBones.ALL_BODY, CombatBones.DOWN_BODY,
            CombatBones.RIGHT_LEG, CombatBones.RIGHT_LOWER_LEG, CombatBones.RIGHT_FOOT, CombatBones.LEFT_LEG, CombatBones.LEFT_LOWER_LEG, CombatBones.LEFT_FOOT);

    public static void clear() { MODELS.clear(); CLIPS.clear(); }
    /** 取不到资源时为 null。 */
    public static LongswordRig get(int weapon, boolean firstPerson) {
        var kind = CombatWeapon.byId(weapon);
        if (kind == null) return null;
        String name = kind.mesh(), view = firstPerson ? "fp" : "tp";
        return MODELS.computeIfAbsent(name + "_" + view, key -> {
            try { return Optional.of(new LongswordRig(name, kind.animations(), view, kind.sheathed())); }
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
    private LongswordRig(String name, String animations, String view, boolean sheathed) {
        firstPerson = view.equals("fp");
        var skeletonJson = read("combat/skeletons/player_" + view + ".json");
        var weapon = read("combat/weapons/" + name + ".json");
        // 骨架给出视角的武器缩放，武器网格可再乘一个自己的 scale（打刀比长剑长）。
        weaponScale = skeletonJson.get("weapon_scale").getAsFloat() * (weapon.has("scale") ? weapon.get("scale").getAsFloat() : 1)
                * (firstPerson && weapon.has("first_person_scale") ? weapon.get("first_person_scale").getAsFloat() : 1);
        var base = CombatSkeleton.fromJson(skeletonJson);
        if (!base.missingRequired().isEmpty()) throw new IllegalStateException("玩家骨架缺少 " + base.missingRequired());
        weaponPivot = base.bone(CombatBones.WEAPON).pivot();
        // 武器自己的骨骼（和弓的分节弓身、弦、箭）挂在 Weapon 下：枢轴是武器网格坐标，按武器缩放放进骨架。
        Set<String> own = new HashSet<>();
        if (weapon.has("bones")) {
            List<CombatSkeleton.Bone> bones = new ArrayList<>(base.bones());
            for (var value : weapon.getAsJsonArray("bones")) {
                var b = value.getAsJsonObject();
                var pivot = vector(b.getAsJsonArray("pivot")).mul(weaponScale).add(weaponPivot);
                bones.add(new CombatSkeleton.Bone(b.get("name").getAsString(), b.get("parent").getAsString(), pivot));
                own.add(b.get("name").getAsString());
            }
            skeleton = new CombatSkeleton(bones);
        } else skeleton = base;
        var shared = clips(animations, view);
        // 武器自己的动作（曦誓的拔剑、收剑）叠在共用动作上，同名的以武器自己的为准。
        if (!name.equals(animations) && Minecraft.getInstance().getResourceManager()
                .getResource(LogosDimension.id("combat/animations/" + name + "_" + view + ".animation.json")).isPresent()) {
            var merged = new HashMap<>(shared);
            merged.putAll(clips(name, view));
            shared = Map.copyOf(merged);
        }
        clips = shared;
        for (var value : skeletonJson.getAsJsonArray("skin")) {
            cube(value.getAsJsonObject(), Layer.WIDE, new Matrix4f());
            cube(value.getAsJsonObject(), Layer.SLIM, new Matrix4f());
        }
        texture = Identifier.parse(weapon.get("texture").getAsString());
        materials.add(texture);
        glow = weapon.has("glow") ? Identifier.parse(weapon.get("glow").getAsString()) : null;
        alpha = weapon.has("alpha") ? weapon.get("alpha").getAsFloat() : 1;
        textureWidth = weapon.getAsJsonArray("texture_size").get(0).getAsInt();
        textureHeight = weapon.getAsJsonArray("texture_size").get(1).getAsInt();
        for (var value : weapon.getAsJsonArray("cubes")) {
            var cube = value.getAsJsonObject();
            boolean sheath = cube.get("part").getAsString().equals("sheath");
            // 第一人称只画剑，剑鞘只在第三人称挂在腰侧；不挂剑鞘的武器（曦誓）都不画。
            if (sheath && (view.equals("fp") || !sheathed)) continue;
            var bone = skeleton.bone(sheath ? CombatBones.SHEATH : CombatBones.WEAPON);
            // 网格都以 Weapon（或 Sheath）枢轴为原点；方块写了武器自己的骨骼时跟着那根骨骼动。
            String owner = cube.has("bone") && own.contains(cube.get("bone").getAsString()) ? cube.get("bone").getAsString() : bone.name();
            var origin = new Matrix4f().translation(bone.pivot()).scale(weaponScale);
            cube.addProperty("bone", owner);
            cube(cube, Layer.WEAPON, 0, origin);
        }
        normalize(0, weapon.getAsJsonArray("texture_size"));
        // 附件：另一份网格（自己的贴图）挂在武器的某根骨骼上，origin 是它的原点在武器网格里的位置。
        if (weapon.has("attachments")) for (var value : weapon.getAsJsonArray("attachments")) {
            var attachment = value.getAsJsonObject();
            var mesh = read("combat/weapons/" + attachment.get("mesh").getAsString() + ".json");
            String bone = attachment.get("bone").getAsString();
            if (!skeleton.has(bone)) throw new IllegalStateException("附件挂的骨骼不存在：" + bone);
            int material = materials.size();
            materials.add(Identifier.parse(mesh.get("texture").getAsString()));
            var origin = new Matrix4f().translation(weaponPivot).scale(weaponScale)
                    .translate(attachment.has("origin") ? vector(attachment.getAsJsonArray("origin")) : new Vector3f());
            for (var c : mesh.getAsJsonArray("cubes")) {
                var cube = c.getAsJsonObject().deepCopy();
                cube.addProperty("bone", bone);
                cube(cube, Layer.WEAPON, material, origin);
            }
            normalize(material, mesh.getAsJsonArray("texture_size"));
        }
    }
    /** 把某一贴图的方块 UV 从像素换成 0–1。 */
    private void normalize(int material, JsonArray size) {
        float width = size.get(0).getAsFloat(), height = size.get(1).getAsFloat();
        for (var quad : quads) if (quad.layer == Layer.WEAPON && quad.material == material) {
            var uv = quad.uv;
            uv[0] /= width; uv[1] /= height; uv[2] /= width; uv[3] /= height;
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
    /** 一段动作里某个标记的各次时刻（动作时间，升序）；没有这段动作或标记时为空数组。 */
    public float[] markers(String clip, String name) {
        var animation = clips.get(clip);
        return animation == null ? new float[0] : animation.markers(name);
    }
    /** 取样一帧姿势；动作文件里没有这段动作时为 null。 */
    public CombatPose pose(String clip, float seconds) {
        var animation = clips.get(clip);
        return animation == null ? null : CombatPose.sample(animation, seconds);
    }
    /** 把姿势套到玩家参考骨架上。第三人称时动作里 Root 的转身从骨架里拿掉，交给客户端转原版身体（见 {@link Sample#turn}）。 */
    public Sample sample(CombatPose pose) {
        float turn = 0;
        if (!firstPerson && pose.joint(CombatBones.ROOT) != null) {
            turn = pose.rotation(CombatBones.ROOT).y;
            pose = pose.without(CombatBones.ROOT);
        }
        var bones = pose.matrices(skeleton);
        var weapon = new Matrix4f(bones.get(CombatBones.WEAPON)).translate(weaponPivot).scale(weaponScale);
        return new Sample(bones, pose.rotation(CombatBones.UP_BODY), weapon, turn,
                pose.rotation(CombatBones.RIGHT_LEG), pose.rotation(CombatBones.LEFT_LEG));
    }
    /**
     * 右臂（连同剑）绕右肩按 {@code degrees} 抬起或压下，转轴是肩部骨骼 {@code Arm} 的 X 轴：第三人称施法时剑尖跟着视线俯仰。
     * 正值把手臂往上抬。
     */
    public Sample aim(Sample sample, float degrees) {
        if (degrees == 0) return sample;
        var arm = sample.bones.get(CombatBones.ARM);
        var pivot = arm.transformPosition(new Vector3f(skeleton.bone(CombatBones.RIGHT_ARM).pivot()));
        var axis = arm.transformDirection(new Vector3f(1, 0, 0)).normalize();
        var turn = new Matrix4f().translation(pivot).rotate((float) Math.toRadians(degrees), axis).translate(-pivot.x, -pivot.y, -pivot.z);
        var bones = new HashMap<>(sample.bones);
        for (var bone : List.of(CombatBones.RIGHT_ARM, CombatBones.RIGHT_FORE_ARM, CombatBones.RIGHT_HAND, CombatBones.WEAPON))
            bones.put(bone, new Matrix4f(turn).mul(bones.get(bone)));
        return new Sample(Map.copyOf(bones), sample.bodyRotation, new Matrix4f(turn).mul(sample.weapon), sample.turn,
                sample.rightLeg, sample.leftLeg);
    }

    /**
     * 侧身拉弓时按视线俯仰（度，向上为正）：上半身绕腰后仰一部分（原版身体跟着仰，见 {@link Sample#bodyRotation}），
     * 双臂连同弓再绕两肩中点转其余的，整个“T”字一起动，箭始终顺着视线，右手也不离开弦。靶在骨架的 -X 一侧。
     */
    public Sample aimDraw(Sample sample, float degrees) {
        if (degrees == 0) return sample;
        float lean = Math.clamp(degrees * .5f, -MAX_LEAN, MAX_LEAN), rest = degrees - lean, radians = (float) Math.PI / 180;
        var waist = skeleton.bone(CombatBones.UP_BODY).pivot();
        var shoulders = skeleton.bone(CombatBones.ARM).pivot();
        // 绕 +Z 转正角时 -X 一侧往下，所以取反。
        var turn = new Matrix4f().translation(waist).rotateZ(-lean * radians).translate(-waist.x, -waist.y, -waist.z)
                .translate(shoulders).rotateZ(-rest * radians).translate(-shoulders.x, -shoulders.y, -shoulders.z);
        var bones = new HashMap<>(sample.bones);
        for (var entry : sample.bones.entrySet())
            if (!BELOW_WAIST.contains(entry.getKey())) bones.put(entry.getKey(), new Matrix4f(turn).mul(entry.getValue()));
        return new Sample(Map.copyOf(bones), new Vector3f(sample.bodyRotation).add(0, 0, -lean), new Matrix4f(turn).mul(sample.weapon), sample.turn,
                sample.rightLeg, sample.leftLeg);
    }

    /** 武器网格坐标（像素，原点在握柄）里的一点在这一帧骨架空间的位置。 */
    public static Vector3f onWeapon(Sample sample, Vector3f point) {
        return sample.weapon.transformPosition(new Vector3f(point));
    }

    /** 武器有没有发光层。 */
    public boolean glows() { return glow != null; }

    /**
     * 剑身发光：剑的面再画一遍发光层贴图，叠加混合、不受光照，经 Logos 的光影兼容通道提交。
     * {@code strength} 0–1，{@code rgb} 为发光颜色。
     */
    public void submitGlow(Sample sample, PoseStack pose, SubmitNodeCollector collector, boolean firstPerson, float strength, int rgb) {
        if (glow == null || strength <= 0.003f) return;
        List<Vector3f[]> faces = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        for (var quad : quads) if (quad.layer == Layer.WEAPON && quad.bone.equals(CombatBones.WEAPON)) {
            var matrix = sample.bones.get(quad.bone);
            var points = new Vector3f[4];
            for (int i = 0; i < 4; i++) points[i] = matrix.transformPosition(new Vector3f(quad.points[i]));
            faces.add(points);
            uvs.add(quad.uv);
        }
        int color = dev.logos.client.VfxParts.argb(Math.min(1, strength), rgb);
        dev.logos.client.ShaderPackOverlay.item(collector, firstPerson).submitCustomGeometry(pose, dev.logos.client.VfxParts.add(glow), (p, buffer) -> {
            for (int f = 0; f < faces.size(); f++) for (int i = 0; i < 4; i++) {
                var point = faces.get(f)[i]; var uv = uvs.get(f);
                float u = i == 0 || i == 3 ? uv[2] : uv[0], v = i < 2 ? uv[3] : uv[1];
                dev.logos.client.VfxParts.vertex(p, buffer, new net.minecraft.world.phys.Vec3(point.x, point.y, point.z), u, v, color);
            }
        });
    }

    private void cube(JsonObject cube, Layer layer, Matrix4f origin) { cube(cube, layer, 0, origin); }
    private void cube(JsonObject cube, Layer layer, int material, Matrix4f origin) {
        var from = vector(cube.getAsJsonArray("start")); var to = vector(cube.getAsJsonArray("end"));
        if (layer == Layer.SLIM) {
            // 细臂皮肤手臂宽 3 像素：按中心收窄到 3/4，刀柄仍穿过手心。
            float center = (from.x + to.x) / 2, half = (to.x - from.x) * 3 / 8;
            from.x = center - half; to.x = center + half;
        }
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
            if (layer != Layer.WEAPON) textureUv = skinUv(bone, face, layer == Layer.SLIM);
            else {
                var uv = cube.getAsJsonObject("uv").getAsJsonArray(names[face]);
                textureUv = new float[]{uv.get(0).getAsFloat(), uv.get(1).getAsFloat(), uv.get(2).getAsFloat(), uv.get(3).getAsFloat()};
            }
            quads.add(new Quad(bone, layer, material, points, normal, textureUv, cube.has("fade") && cube.get("fade").getAsBoolean()));
        }
    }
    /**
     * 原版 64×64 皮肤的手臂区域按上臂 6、前臂 4、手 2 像素分段。
     * 细臂皮肤正背面只有 3 像素宽，其余列是空白；原版加载皮肤时把手臂区域设为不透明，空白会变成黑色，所以必须按细臂排布取。
     */
    private static float[] skinUv(String bone, int face, boolean slim) {
        boolean left = bone.startsWith("Left");
        float u = left ? 32 : 40, v = left ? 48 : 16;
        String part = bone.substring(left ? 4 : 5);
        float top = part.equals("Arm") ? 4 : part.equals("ForeArm") ? 10 : 14;
        float bottom = part.equals("Arm") ? 10 : part.equals("ForeArm") ? 14 : 16;
        float w = slim ? 3 : 4;
        float[] offset = switch (face) {
            case 0 -> new float[]{4,top,4+w,bottom};
            case 1 -> new float[]{8+w,top,8+2*w,bottom};
            case 2 -> new float[]{0,top,4,bottom};
            case 3 -> new float[]{4+w,top,8+w,bottom};
            case 4 -> new float[]{4,0,4+w,4};
            default -> new float[]{4+w,0,4+2*w,4};
        };
        return new float[]{(u+offset[0])/64,(v+offset[1])/64,(u+offset[2])/64,(v+offset[3])/64};
    }
    public void submit(Sample sample, PoseStack pose, SubmitNodeCollector collector, int light, PlayerSkin skin, boolean arms) {
        submit(sample, pose, collector, light, skin, arms, true);
    }
    /** {@code weapon} 为 false 时只画手臂（曦誓拔剑凝成之前、收剑散去之后）。 */
    public void submit(Sample sample, PoseStack pose, SubmitNodeCollector collector, int light, PlayerSkin skin, boolean arms, boolean weapon) {
        submit(sample, pose, collector, light, skin, arms, weapon, false);
    }
    /** {@code hideFading} 为真时武器网格里标了 {@code fade} 的方块不画（流火只剩刀柄）。 */
    public void submit(Sample sample, PoseStack pose, SubmitNodeCollector collector, int light, PlayerSkin skin, boolean arms, boolean weapon, boolean hideFading) {
        if (weapon) for (int m = 0; m < materials.size(); m++) submitMaterial(sample, pose, collector, light, materials.get(m), Layer.WEAPON, m, hideFading);
        if (arms) submitMaterial(sample, pose, collector, light, skin.body().texturePath(),
                skin.model() == PlayerModelType.SLIM ? Layer.SLIM : Layer.WIDE, 0, false);
    }

    /** 武器网格自己的贴图该用的画法：不透明时裁切，略透明（{@code alpha} 小于 1）时半透明。 */
    private net.minecraft.client.renderer.rendertype.RenderType weaponType(Identifier texture, int material) {
        return material == 0 && alpha < 1 ? RenderTypes.entityTranslucent(texture) : RenderTypes.entityCutout(texture);
    }

    /**
     * 武器网格凝聚或消散到 {@code progress}（0–1）时的样子，代替 {@link #submit} 里的武器层（手臂另画）。
     * {@code appear} 为真时从柄往尖长出；{@code bladeOnly} 时只有标了 {@code fade} 的方块参与，其余照常画。
     * 边缘按 {@code color} 发光，粒子按颜色是否带 {@code Dissolving.PETALS} 放火星或樱花瓣；{@code key} 区分放粒子的人和视角。
     */
    public void submitDissolving(Sample sample, PoseStack pose, SubmitNodeCollector collector, int light, float progress, boolean appear,
                                 boolean bladeOnly, int color, Object key) {
        if (cells == null) {
            List<Vector3f[]> faces = new ArrayList<>();
            List<float[]> uvs = new ArrayList<>();
            List<Boolean> fading = new ArrayList<>();
            for (var quad : quads) if (quad.layer == Layer.WEAPON && quad.material == 0 && quad.bone.equals(CombatBones.WEAPON)) {
                faces.add(quad.points); uvs.add(quad.uv); fading.add(quad.fades);
            }
            cells = dev.logos.dimension.client.dissolve.WeaponDissolve.cut(faces, uvs, fading, textureWidth, textureHeight, new Vector3f(0, 1, 0));
        }
        dev.logos.dimension.client.dissolve.WeaponDissolve.submit(key, cells, sample.bones.get(CombatBones.WEAPON), pose, collector,
                weaponType(texture, 0), light, Math.round(alpha * 255), progress, appear, bladeOnly, color, firstPerson);
    }
    private void submitMaterial(Sample sample, PoseStack pose, SubmitNodeCollector collector, int light, Identifier texture, Layer layer, int material, boolean hideFading) {
        // 提交前冻结顶点，延迟渲染回调不读取玩家或可变骨骼状态。
        List<Quad> transformed = new ArrayList<>();
        for (var quad : quads) if (quad.layer == layer && quad.material == material && !(hideFading && quad.fades)) {
            var matrix = sample.bones.get(quad.bone);
            // 缩成 0 的骨骼（还没搭上或已射出的箭、第一人称藏起的右臂）不画。
            if (Math.abs(matrix.determinant3x3()) < 1e-9f) continue;
            var points = new Vector3f[4];
            for (int i = 0; i < 4; i++) points[i] = matrix.transformPosition(new Vector3f(quad.points[i]));
            transformed.add(new Quad(quad.bone, layer, material, points, matrix.transformDirection(new Vector3f(quad.normal)).normalize(), quad.uv, quad.fades));
        }
        if (transformed.isEmpty()) return;
        var type = layer == Layer.WEAPON ? weaponType(texture, material) : RenderTypes.entityCutout(texture);
        int a = layer == Layer.WEAPON && material == 0 ? Math.round(alpha * 255) : 255;
        collector.order(-1).submitCustomGeometry(pose, type, (p, buffer) -> {
            for (var quad : transformed) for (int i = 0; i < 4; i++) {
                var point = quad.points[i]; var uv = quad.uv; var n = quad.normal;
                float u = i == 0 || i == 3 ? uv[2] : uv[0], v = i < 2 ? uv[3] : uv[1];
                buffer.addVertex(p, point.x, point.y, point.z).setColor(255,255,255,a).setUv(u,v)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p,n.x,n.y,n.z);
            }
        });
    }
}
