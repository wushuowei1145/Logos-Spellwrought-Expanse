package dev.logos.dimension.client.dissolve;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 把物品或方块模型切成小方片，透明的贴图像素丢掉。消散时只把还留着的格子交给原版画，所以留下的部分还是物品或方块本身：
 * 染色、附魔光效、光影包的光照都在，也不需要自己的着色器。
 */
final class DissolveMesh {
    final BakedQuad[] quads;
    /** 每一格的中心（xyz）和法线（xyz），都在模型空间。 */
    final float[] cells;
    /** 每一格的噪波，在这个模型内部摊匀到 0 到 1。 */
    final float[] noise;

    private DissolveMesh(BakedQuad[] quads, float[] cells) {
        this.quads = quads;
        this.cells = cells;
        float[] raw = new float[quads.length];
        for (int i = 0; i < raw.length; i++) raw[i] = DissolveNoise.at(cells[i * 6], cells[i * 6 + 1], cells[i * 6 + 2]) + i * 1e-6f;
        noise = DissolveNoise.ranks(raw);
    }

    /** 切多细。整块方块分别是 6144、1536、384 格。 */
    enum Detail {
        /** 每个贴图像素四格：单个东西和不多的方块用。 */
        FINE(.5f),
        /** 每个贴图像素一格。 */
        TEXEL(1),
        /** 每四个贴图像素一格，同时消散很多方块时用。 */
        COARSE(2);

        final float texels;

        Detail(float texels) { this.texels = texels; }
    }

    private static final Map<BakedQuad, DissolveMesh[]> CACHE = new IdentityHashMap<>();

    /** 细格子很占内存，只在有东西消散时留着。 */
    static void clear() { CACHE.clear(); }

    /** {@code quads} 按这个精度切好的格子。 */
    static @Nullable DissolveMesh of(List<BakedQuad> quads, Detail detail) {
        if (quads.isEmpty()) return null;
        DissolveMesh[] meshes = CACHE.computeIfAbsent(quads.getFirst(), k -> new DissolveMesh[Detail.values().length]);
        int slot = detail.ordinal();
        if (meshes[slot] == null) meshes[slot] = build(quads, detail.texels);
        return meshes[slot];
    }

    private static DissolveMesh build(List<BakedQuad> quads, float step) {
        List<BakedQuad> cut = new ArrayList<>();
        List<float[]> cells = new ArrayList<>();
        Vector3f p0 = new Vector3f(), e1 = new Vector3f(), e2 = new Vector3f(), n = new Vector3f();
        for (BakedQuad q : quads) {
            p0.set(q.position0());
            new Vector3f(q.position1()).sub(p0, e1);
            new Vector3f(q.position3()).sub(p0, e2);
            e1.cross(e2, n);
            if (n.lengthSquared() < 1e-16f) continue;
            n.normalize();
            var dir = q.direction().getUnitVec3f();
            if (n.dot(dir) < 0) n.negate();
            TextureAtlasSprite sprite = q.materialInfo().sprite();
            SpriteContents contents = sprite.contents();
            float u0 = UVPair.unpackU(q.packedUV0()), v0 = UVPair.unpackV(q.packedUV0());
            float du1 = UVPair.unpackU(q.packedUV1()) - u0, dv1 = UVPair.unpackV(q.packedUV1()) - v0;
            float du3 = UVPair.unpackU(q.packedUV3()) - u0, dv3 = UVPair.unpackV(q.packedUV3()) - v0;
            float perU = contents.width() / (sprite.getU1() - sprite.getU0()), perV = contents.height() / (sprite.getV1() - sprite.getV0());
            int ns = Mth.clamp(Math.round(Math.max(Math.abs(du1) * perU, Math.abs(dv1) * perV) / step), 1, 64);
            int nt = Mth.clamp(Math.round(Math.max(Math.abs(du3) * perU, Math.abs(dv3) * perV) / step), 1, 64);
            for (int i = 0; i < ns; i++) for (int j = 0; j < nt; j++) {
                float s0 = i / (float) ns, s1 = (i + 1f) / ns, t0 = j / (float) nt, t1 = (j + 1f) / nt;
                float sc = (s0 + s1) / 2, tc = (t0 + t1) / 2;
                int tx = Mth.clamp((int) Math.floor((u0 + sc * du1 + tc * du3 - sprite.getU0()) * perU), 0, contents.width() - 1);
                int ty = Mth.clamp((int) Math.floor((v0 + sc * dv1 + tc * dv3 - sprite.getV0()) * perV), 0, contents.height() - 1);
                if (contents.isTransparent(0, tx, ty)) continue;
                cut.add(new BakedQuad(point(p0, e1, e2, s0, t0), point(p0, e1, e2, s1, t0), point(p0, e1, e2, s1, t1), point(p0, e1, e2, s0, t1),
                        uv(u0, v0, du1, dv1, du3, dv3, s0, t0), uv(u0, v0, du1, dv1, du3, dv3, s1, t0),
                        uv(u0, v0, du1, dv1, du3, dv3, s1, t1), uv(u0, v0, du1, dv1, du3, dv3, s0, t1),
                        q.direction(), q.materialInfo(), q.bakedNormals(), q.bakedColors()));
                Vector3f c = point(p0, e1, e2, sc, tc);
                cells.add(new float[] {c.x, c.y, c.z, n.x, n.y, n.z});
            }
        }
        float[] flat = new float[cells.size() * 6];
        for (int i = 0; i < cells.size(); i++) System.arraycopy(cells.get(i), 0, flat, i * 6, 6);
        return new DissolveMesh(cut.toArray(BakedQuad[]::new), flat);
    }

    private static Vector3f point(Vector3f p0, Vector3f e1, Vector3f e2, float s, float t) {
        return new Vector3f(p0).fma(s, e1).fma(t, e2);
    }

    private static long uv(float u0, float v0, float du1, float dv1, float du3, float dv3, float s, float t) {
        return UVPair.pack(u0 + s * du1 + t * du3, v0 + s * dv1 + t * dv3);
    }
}
