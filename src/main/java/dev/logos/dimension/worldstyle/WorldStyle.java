package dev.logos.dimension.worldstyle;

import com.mojang.serialization.Codec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Biome;

/**
 * 区域里的世界是什么画风。新加一种时在这里加一项，再在 {@code post/world_style.fsh} 的 {@code applyStyle} 里加对应的算法。
 * 每种都有两档：透过门看到的预览，和走进去后的完全体。
 *
 * <p>画风也可以跟着群系走：把群系放进 {@link #biomes()} 这个标签，镜头在里面时整片群系都是这个画风，进出时慢慢淡入淡出。
 */
public enum WorldStyle implements StringRepresentable {
    MONOCHROME("monochrome"),
    FAIRY_TALE("fairy_tale"),
    /** 国画：宣纸、淡墨勾边、远山留白、峰间云带。云岫峰林用它。 */
    INK_WASH("ink_wash");

    public static final Codec<WorldStyle> CODEC = StringRepresentable.fromEnum(WorldStyle::values);
    private final String name;
    private final TagKey<Biome> biomes;

    WorldStyle(String name) {
        this.name = name;
        this.biomes = TagKey.create(Registries.BIOME, LogosDimension.id("world_style/" + name));
    }

    @Override public String getSerializedName() {
        return name;
    }

    /** 着色器里的编号：0 留给原画。 */
    public int shaderId() {
        return ordinal() + 1;
    }

    /** 整片群系都用这个画风的群系标签：{@code #logos_dimension:world_style/<名字>}。 */
    public TagKey<Biome> biomes() {
        return biomes;
    }
}
