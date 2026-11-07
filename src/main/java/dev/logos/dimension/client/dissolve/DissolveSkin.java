package dev.logos.dimension.client.dissolve;

import com.mojang.blaze3d.platform.NativeImage;
import dev.logos.dimension.mixin.RenderSetupAccess;
import dev.logos.dimension.mixin.RenderTypeAccess;
import dev.logos.dimension.mixin.TextureBindingAccess;
import java.io.InputStream;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * 实体贴图里哪些像素真的存在，从资源里读一次。外层（僵尸的外套、帽子）是一整个盒子靠贴图挖空的，挖空的地方不能发光也不能冒火星。
 * 知道尺寸也才能沿着贴图像素切面。不是普通资源的贴图（皮肤、图集）读不到，就当没有。
 */
final class DissolveSkin {
    private static final Map<Identifier, @Nullable DissolveSkin> SKINS = new HashMap<>();

    final int width, height;
    private final BitSet solid;

    private DissolveSkin(int width, int height, BitSet solid) {
        this.width = width;
        this.height = height;
        this.solid = solid;
    }

    boolean solid(float u, float v) {
        return solid.get(Mth.clamp((int) (v * height), 0, height - 1) * width + Mth.clamp((int) (u * width), 0, width - 1));
    }

    static void clear() { SKINS.clear(); }

    static @Nullable DissolveSkin of(RenderType type) {
        var textures = ((RenderSetupAccess) (Object) ((RenderTypeAccess) type).logos_dimension$state()).logos_dimension$textures();
        Object binding = textures.get("Sampler0");
        if (binding == null) return null;
        Identifier id = ((TextureBindingAccess) binding).logos_dimension$location();
        if (SKINS.containsKey(id)) return SKINS.get(id);
        DissolveSkin skin = read(id);
        SKINS.put(id, skin);
        return skin;
    }

    private static @Nullable DissolveSkin read(Identifier id) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int w = image.getWidth(), h = image.getHeight();
            BitSet solid = new BitSet(w * h);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if ((image.getPixel(x, y) >>> 24) >= 26) solid.set(y * w + x);
            }
            return new DissolveSkin(w, h, solid);
        } catch (Exception e) {
            return null;
        }
    }
}
