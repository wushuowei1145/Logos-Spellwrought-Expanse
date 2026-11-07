package dev.logos.dimension.worldstyle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 区域的一扇门：世界里的一个矩形。门外的人透过它看到区域的画风，门里的人透过它看到原来的世界。
 * 窗户也是一扇门。用 {@link #opening} 按方块定义，不用自己算向量。
 *
 * @param right 中心到右边缘的向量
 * @param up 中心到上边缘的向量
 */
public record StyleDoor(Vec3 center, Vec3 right, Vec3 up) {
    public static final Codec<StyleDoor> CODEC = RecordCodecBuilder.create(i -> i.group(
            Vec3.CODEC.fieldOf("center").forGetter(StyleDoor::center),
            Vec3.CODEC.fieldOf("right").forGetter(StyleDoor::right),
            Vec3.CODEC.fieldOf("up").forGetter(StyleDoor::up)
    ).apply(i, StyleDoor::new));

    /** 墙上的门洞：{@code a} 到 {@code b} 这些方块是空的，{@code outward} 是朝区域外的方向，门就贴在门洞朝外的那一面。 */
    public static StyleDoor opening(BlockPos a, BlockPos b, Direction outward) {
        AABB box = AABB.encapsulatingFullBlocks(a, b);
        Vec3 middle = box.getCenter();
        Vec3 half = new Vec3(box.getXsize() / 2, box.getYsize() / 2, box.getZsize() / 2);
        double depth = outward.getAxis().choose(half.x, half.y, half.z);
        Vec3 center = middle.add(outward.getStepX() * depth, outward.getStepY() * depth, outward.getStepZ() * depth);
        return switch (outward.getAxis()) {
            case X -> new StyleDoor(center, new Vec3(0, 0, half.z), new Vec3(0, half.y, 0));
            case Y -> new StyleDoor(center, new Vec3(half.x, 0, 0), new Vec3(0, 0, half.z));
            case Z -> new StyleDoor(center, new Vec3(half.x, 0, 0), new Vec3(0, half.y, 0));
        };
    }

    /** 从 {@code from} 走到 {@code to} 的这一段有没有穿过门。 */
    public boolean crossedBy(Vec3 from, Vec3 to) {
        Vec3 normal = right.cross(up);
        Vec3 path = to.subtract(from);
        double along = path.dot(normal);
        if (Math.abs(along) < 1e-9) return false;
        double t = center.subtract(from).dot(normal) / along;
        if (t < 0 || t > 1) return false;
        Vec3 hit = from.add(path.scale(t)).subtract(center);
        return Math.abs(hit.dot(right) / right.lengthSqr()) <= 1 && Math.abs(hit.dot(up) / up.lengthSqr()) <= 1;
    }

    AABB box() {
        Vec3 corner = right.add(up);
        Vec3 other = right.subtract(up);
        return new AABB(center.add(corner), center.subtract(corner)).minmax(new AABB(center.add(other), center.subtract(other)));
    }
}
