package dev.logos.dimension.client.shiraume;

import dev.logos.client.VfxParts;
import dev.logos.dimension.client.screenfx.GlowBloom;
import net.minecraft.world.phys.Vec3;

/**
 * 交给附属泛光（{@link GlowBloom}）的软边形状，坐标是世界坐标（交出去时换成相对镜头）。
 * 泛光的小图只按顶点颜色插值，四角同色的方块模糊后仍是方的亮斑，所以这里都是中间亮、边缘透明的形状
 * （同超电磁炮的做法，见 {@code RailgunDraw.Glow}）：光团是十二片扇形，带子是中线亮、两边透明的两片。
 */
public final class SoftGlow {
    private float[] data = new float[16 * 64];
    private int size;
    private Vec3 cam = Vec3.ZERO, right = new Vec3(1, 0, 0), up = new Vec3(0, 1, 0);

    public SoftGlow begin(VfxParts.View view) {
        cam = view.cam();
        right = view.right();
        up = view.up();
        size = 0;
        return this;
    }

    private void vertex(Vec3 p, float colour) {
        if (size + 4 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
        data[size++] = (float) (p.x - cam.x);
        data[size++] = (float) (p.y - cam.y);
        data[size++] = (float) (p.z - cam.z);
        data[size++] = colour;
    }

    /** 朝向镜头的一团光：中心最亮，到边缘透明。 */
    public void disc(Vec3 at, float radius, int argb) {
        if (radius <= 0 || argb >>> 24 == 0) return;
        float c = Float.intBitsToFloat(argb), edge = Float.intBitsToFloat(argb & 0xFFFFFF);
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            vertex(at, c);
            vertex(at.add(right.scale(Math.cos(a0) * radius)).add(up.scale(Math.sin(a0) * radius)), edge);
            vertex(at.add(right.scale(Math.cos(a1) * radius)).add(up.scale(Math.sin(a1) * radius)), edge);
            vertex(at, c);
        }
    }

    /** 星芒：一团光加上横竖两道细长的光（双眼红光）。 */
    public void star(Vec3 at, float radius, float length, int argb) {
        disc(at, radius, argb);
        band(at.add(right.scale(-length)), at.add(right.scale(length)), radius * .22f, argb);
        band(at.add(up.scale(-length * .55)), at.add(up.scale(length * .55)), radius * .18f, argb);
    }

    /** 朝向镜头的一条带子，半宽 {@code width}：中线最亮，往两边透明。 */
    public void band(Vec3 a, Vec3 b, float width, int argb) {
        Vec3 axis = b.subtract(a);
        if (axis.lengthSqr() < 1e-8 || argb >>> 24 == 0) return;
        Vec3 toCam = cam.subtract(a.add(b).scale(.5));
        Vec3 side = axis.cross(toCam);
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1e-8) side = new Vec3(1, 0, 0);
        side = side.normalize().scale(width);
        float c = Float.intBitsToFloat(argb), edge = Float.intBitsToFloat(argb & 0xFFFFFF);
        for (int s = -1; s <= 1; s += 2) {
            vertex(a, c);
            vertex(a.add(side.scale(s)), edge);
            vertex(b.add(side.scale(s)), edge);
            vertex(b, c);
        }
    }

    /** 垂直于 {@code n} 的一圈。 */
    public void ring(Vec3 c, Vec3 n, float radius, float width, int segments, int argb) {
        Vec3 axis = n.normalize();
        Vec3 rr = Math.abs(axis.y) > .9 ? new Vec3(1, 0, 0) : axis.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 uu = axis.cross(rr).normalize();
        Vec3 prev = null;
        for (int i = 0; i <= segments; i++) {
            double a = i * Math.PI * 2 / segments;
            Vec3 p = c.add(rr.scale(Math.cos(a) * radius)).add(uu.scale(Math.sin(a) * radius));
            if (prev != null) band(prev, p, width, argb);
            prev = p;
        }
    }

    /** 交出去；光影包的阴影通道里不要调（那时的姿势是太阳的）。 */
    public void flush() {
        if (size > 0) GlowBloom.quads(data, size, false);
        size = 0;
    }
}
