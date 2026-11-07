package dev.logos.dimension.worldstyle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 一块画风不同的区域。镜头在 {@code bounds} 里就算走进去了，所以不用给每个玩家存状态，传送、死亡、重新登录都不会错。
 * 从外面只有透过 {@code doors} 才看得到里面的画风，所以区域要有墙围着；没有门的那几面从外面看就是普通的墙。
 */
public record StyleRealm(AABB bounds, WorldStyle style, List<StyleDoor> doors) {
    public static final Codec<StyleRealm> CODEC = RecordCodecBuilder.create(i -> i.group(
            Vec3.CODEC.fieldOf("min").forGetter(r -> r.bounds.getMinPosition()),
            Vec3.CODEC.fieldOf("max").forGetter(r -> r.bounds.getMaxPosition()),
            WorldStyle.CODEC.fieldOf("style").forGetter(StyleRealm::style),
            StyleDoor.CODEC.listOf().fieldOf("doors").forGetter(StyleRealm::doors)
    ).apply(i, (min, max, style, doors) -> new StyleRealm(new AABB(min, max), style, doors)));

    /** 范围自动扩到包住所有的门：镜头穿过门的那一刻必须正好算进入，否则门里的画风会闪一下。 */
    public StyleRealm {
        doors = List.copyOf(doors);
        for (StyleDoor door : doors) bounds = bounds.minmax(door.box());
    }

    /** 从方块 {@code a} 到 {@code b} 的区域，墙算在里面。 */
    public static StyleRealm of(BlockPos a, BlockPos b, WorldStyle style, StyleDoor... doors) {
        return new StyleRealm(AABB.encapsulatingFullBlocks(a, b), style, List.of(doors));
    }

    public boolean contains(Vec3 point) {
        return bounds.contains(point);
    }
}
