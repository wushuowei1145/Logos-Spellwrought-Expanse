package dev.logos.dimension.dissolve;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * 消散吃掉目标的顺序：谁先没、谁后没。
 * <ul>
 * <li>{@link Kind#NOISE}：到处开洞，虚幻消散材质的做法。
 * <li>{@link Kind#SPHERE}：从目标范围内的一点（每个轴 0 到 1）球形外扩。
 * <li>{@link Kind#POINT}：同样外扩，但那一点在世界里，比如命中的地方。
 * <li>{@link Kind#SWEEP}：一道波前朝某个方向扫过目标。
 * </ul>
 * {@code inward} 把顺序倒过来（球形向内收）；{@code rough} 是波前被噪波打乱的程度，0 是整齐的线。
 */
public record DissolveShape(Kind kind, float x, float y, float z, boolean inward, float rough) {
    public enum Kind { NOISE, SPHERE, POINT, SWEEP }

    private static final float ROUGH = .3f;

    public static final StreamCodec<ByteBuf, DissolveShape> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, s -> s.kind().ordinal(),
            ByteBufCodecs.FLOAT, DissolveShape::x, ByteBufCodecs.FLOAT, DissolveShape::y, ByteBufCodecs.FLOAT, DissolveShape::z,
            ByteBufCodecs.BOOL, DissolveShape::inward, ByteBufCodecs.FLOAT, DissolveShape::rough,
            (kind, x, y, z, inward, rough) -> new DissolveShape(Kind.values()[kind], x, y, z, inward, rough));

    public static DissolveShape noise() { return new DissolveShape(Kind.NOISE, 0, 0, 0, false, 1); }

    public static DissolveShape sphere(float x, float y, float z) { return new DissolveShape(Kind.SPHERE, x, y, z, false, ROUGH); }

    public static DissolveShape point(double x, double y, double z) { return new DissolveShape(Kind.POINT, (float) x, (float) y, (float) z, false, ROUGH); }

    public static DissolveShape sweep(float dx, float dy, float dz) { return new DissolveShape(Kind.SWEEP, dx, dy, dz, false, ROUGH); }

    public DissolveShape reversed() { return new DissolveShape(kind, x, y, z, !inward, rough); }

    public DissolveShape rough(float rough) { return new DissolveShape(kind, x, y, z, inward, rough); }

    /** {@code /alchemy dissolve} 接受的名字。 */
    public static final String[] NAMES = {"noise", "sphere", "implode", "feet", "down", "up", "north", "south", "east", "west", "tip", "hilt"};

    public static @Nullable DissolveShape parse(String name) {
        return switch (name) {
            case "noise" -> noise();
            case "sphere" -> sphere(.5f, .5f, .5f);
            case "implode" -> sphere(.5f, .5f, .5f).reversed();
            case "feet" -> sphere(.5f, 0, .5f);
            case "down" -> sweep(0, -1, 0);
            case "up" -> sweep(0, 1, 0);
            case "north" -> sweep(0, 0, -1);
            case "south" -> sweep(0, 0, 1);
            case "east" -> sweep(1, 0, 0);
            case "west" -> sweep(-1, 0, 0);
            // 物品模型空间里尖朝 (1,1,0)：tip 朝尖走，hilt 从尖往回走
            case "tip" -> sweep(1, 1, 0);
            case "hilt" -> sweep(-1, -1, 0);
            default -> null;
        };
    }
}
