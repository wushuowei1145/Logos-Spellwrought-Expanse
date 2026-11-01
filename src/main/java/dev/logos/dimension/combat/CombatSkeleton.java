package dev.logos.dimension.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import org.joml.Vector3f;

/**
 * 一副战斗骨架：骨骼名、父级与枢轴，坐标为 Blockbench 空间（Y 向上，正面 -Z，角色右侧 +X，像素单位）。
 * 骨骼按父级在前排列，求世界矩阵时顺序遍历即可。
 */
public final class CombatSkeleton {
    public record Bone(String name, String parent, Vector3f pivot) {}
    private final List<Bone> bones;
    private final Map<String, Bone> byName = new HashMap<>();

    public CombatSkeleton(List<Bone> bones) {
        List<Bone> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        Deque<Bone> pending = new ArrayDeque<>(bones);
        // 父级不存在的骨骼当作根骨骼，不因第三方模型缺骨而失败。
        Set<String> names = new HashSet<>();
        for (var bone : bones) names.add(bone.name);
        int stalled = 0;
        while (!pending.isEmpty() && stalled <= pending.size()) {
            var bone = pending.poll();
            if (bone.parent == null || !names.contains(bone.parent) || placed.contains(bone.parent)) {
                ordered.add(bone.parent != null && !names.contains(bone.parent) ? new Bone(bone.name, null, bone.pivot) : bone);
                placed.add(bone.name); stalled = 0;
            } else { pending.add(bone); stalled++; }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("战斗骨架存在循环父级：" + pending.peek().name);
        this.bones = List.copyOf(ordered);
        for (var bone : this.bones) byName.put(bone.name, bone);
    }

    /** 读取 {@code {"bones":[{"name","parent","pivot":[x,y,z]}]}}，pivot 为 Blockbench 空间。 */
    public static CombatSkeleton fromJson(JsonObject json) {
        List<Bone> bones = new ArrayList<>();
        for (var value : json.getAsJsonArray("bones")) {
            var b = value.getAsJsonObject();
            var parent = b.get("parent");
            bones.add(new Bone(b.get("name").getAsString(), parent == null || parent.isJsonNull() ? null : parent.getAsString(), vector(b.getAsJsonArray("pivot"))));
        }
        return new CombatSkeleton(bones);
    }
    static Vector3f vector(JsonArray a) { return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()); }

    public List<Bone> bones() { return bones; }
    public Bone bone(String name) { return byName.get(name); }
    public boolean has(String name) { return byName.containsKey(name); }
    /** 缺少的标准必需骨骼；为空才算符合 docs/COMBAT_SKELETON.md。 */
    public List<String> missingRequired() {
        return CombatBones.REQUIRED.stream().filter(name -> !byName.containsKey(name)).toList();
    }
}
