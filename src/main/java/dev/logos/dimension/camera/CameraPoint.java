package dev.logos.dimension.camera;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 运镜里的一个点，既能作机位也能作视线目标：世界里的固定点，或者跟着实体走的点（实体脚底加偏移）。
 * 方块用固定点，Boss 用跟实体的点。
 */
public record CameraPoint(int entity, Vec3 offset) {
    private static final int NO_ENTITY = -1;
    public static final StreamCodec<ByteBuf, CameraPoint> CODEC = StreamCodec.of((b, p) -> {
        b.writeInt(p.entity);
        b.writeDouble(p.offset.x); b.writeDouble(p.offset.y); b.writeDouble(p.offset.z);
    }, b -> new CameraPoint(b.readInt(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble())));

    public static CameraPoint world(Vec3 at) {
        return new CameraPoint(NO_ENTITY, at);
    }

    /** 跟着 {@code target} 走：它的脚底加上 {@code offset}。 */
    public static CameraPoint on(Entity target, Vec3 offset) {
        return new CameraPoint(target.getId(), offset);
    }

    /** 跟着 {@code target} 的眼睛。 */
    public static CameraPoint eyes(Entity target) {
        return on(target, new Vec3(0, target.getEyeHeight(), 0));
    }

    /** 这一帧的世界坐标；跟着的实体不在本地世界里时（走远了、死了）返回 {@code fallback}。 */
    public @Nullable Vec3 resolve(Level level, float partialTick, @Nullable Vec3 fallback) {
        if (entity == NO_ENTITY) return offset;
        Entity target = level.getEntity(entity);
        return target == null ? fallback : target.getPosition(partialTick).add(offset);
    }
}
