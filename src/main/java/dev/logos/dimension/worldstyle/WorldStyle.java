package dev.logos.dimension.worldstyle;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * 区域里的世界是什么画风。新加一种时在这里加一项，再在 {@code post/world_style.fsh} 的 {@code applyStyle} 里加对应的算法。
 * 每种都有两档：透过门看到的预览，和走进去后的完全体。
 */
public enum WorldStyle implements StringRepresentable {
    MONOCHROME("monochrome"),
    FAIRY_TALE("fairy_tale");

    public static final Codec<WorldStyle> CODEC = StringRepresentable.fromEnum(WorldStyle::values);
    private final String name;

    WorldStyle(String name) {
        this.name = name;
    }

    @Override public String getSerializedName() {
        return name;
    }

    /** 着色器里的编号：0 留给原画。 */
    public int shaderId() {
        return ordinal() + 1;
    }
}
