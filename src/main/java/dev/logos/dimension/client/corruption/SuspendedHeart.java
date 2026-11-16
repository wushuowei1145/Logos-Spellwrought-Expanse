package dev.logos.dimension.client.corruption;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.texture.OverlayTexture;
import dev.logos.dimension.elven.blight.HeartVeins;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/** 悬吊树心：收尖的心脏体素和八条分叉血管，只有一个核心渲染器，不给每段血管创建实体。 */
final class SuspendedHeart {
    private SuspendedHeart() {}
    static CoreShape body() {
        CoreShape s = new CoreShape(48, 56);
        for (int y = 1; y < 55; y++) for (int x = 3; x < 45; x++) for (int z = 5; z < 43; z++) {
            double dx = x + .5 - 24, dy = y + .5 - (dx < 0 ? 28 : 26), dz = z + .5 - 24;
            double taper = y < 25 ? .45 + .55 * y / 25 : 1;
            double d = dx * dx / (18 * 18 * taper * taper) + dy * dy / (25 * 25) + dz * dz / (16 * 16);
            double grain = CoreShape.noise(x, y, z, 6, 71);
            if (d > 1 + (grain - .5) * .16 || y > 49 && Math.abs(dx) < 2.5) continue;
            double vein = Math.abs(CoreShape.noise(x, y, z, 7, 93) - .5);
            int color = vein < .04 && d > .55 ? 7 + (vein < .018 ? 2 : 0) : 1 + (int) Math.min(4, grain * 5);
            s.set(x, y, z, color);
        }
        return s;
    }
    /** 五秒一轮，收缩短、舒张长；没有快速双跳和整颗核心的漂移。 */
    static float beat(float time) {
        double phase = Math.floorMod((long) Math.floor(time), 100) + time - Math.floor(time);
        double t = phase / 100;
        return (float) Math.exp(-Math.pow((t - .24) / .13, 2));
    }
    private record Panel(Vec3[] centers, Vec3[] offsets, Vec3 normal, int color, float phase, float startWeight, float endWeight, int vein) {}
    private static List<Panel> panels;
    private static List<Vec3> attachments;
    private static void build() {
        if (panels != null) return;
        panels = new ArrayList<>();
        attachments = new ArrayList<>();
        // 接头扎进扩大后大厅（半径 24，见 tools/blighted_world_tree_arena.py）的墙里，落在环形高台上方一人高处，站在高台上能砍到。
        // 接头位置和服务端的可砍接头共用 HeartVeins；第 i 条主血管和它的分叉都算第 i 条（砍断后一起变暗）。
        for (int i = 0; i < HeartVeins.MAIN; i++) {
            double angle = HeartVeins.angle(i);
            Vec3 start = radial(angle, 2.6, 4.2 + Math.sin(angle) * .5);
            Vec3 end = anchor(HeartVeins.joint(i));
            Vec3 bend = radial(angle + .08, 12, 7.4 + i % 2);
            tube(start, bend, end, .29, 14, i, 1, i);
            Vec3 fork = curve(start, bend, end, .62);
            tube(fork, radial(angle - .17, 18, 7.6), anchor(HeartVeins.anchor(angle - .27, HeartVeins.WALL + .2, 4.6 + i % 2 * .8)), .13, 8, i + 11, .38f, i);
        }
        panels = List.copyOf(panels);
        attachments = List.copyOf(attachments);
    }
    private static Vec3 anchor(Vec3 p) {
        attachments.add(p);
        return p;
    }
    static List<Vec3> attachments() { build(); return attachments; }
    private static Vec3 radial(double a, double radius, double y) { return new Vec3(Math.cos(a) * radius, y, Math.sin(a) * radius); }
    private static Vec3 curve(Vec3 a, Vec3 b, Vec3 c, double t) { return a.scale((1 - t) * (1 - t)).add(b.scale(2 * t * (1 - t))).add(c.scale(t * t)); }
    private static void tube(Vec3 a, Vec3 b, Vec3 c, double radius, int steps, int seed, float weight, int vein) {
        Vec3[][] centers = new Vec3[steps + 1][6], offsets = new Vec3[steps + 1][6];
        for (int j = 0; j <= steps; j++) {
            double t = j / (double) steps;
            Vec3 at = curve(a, b, c, t);
            Vec3 tangent = b.subtract(a).scale(1 - t).add(c.subtract(b).scale(t)).normalize();
            Vec3 u = tangent.cross(new Vec3(0, 1, 0)).normalize(), v = tangent.cross(u).normalize();
            double r = radius * (1 - .35 * t) * (1 + .07 * Math.sin(j * 1.7 + seed));
            for (int k = 0; k < 6; k++) {
                double angle = k * Math.PI / 3;
                centers[j][k] = at;
                offsets[j][k] = u.scale(Math.cos(angle) * r).add(v.scale(Math.sin(angle) * r));
            }
        }
        for (int j = 0; j < steps; j++) for (int k = 0; k < 6; k++) {
            int n = (k + 1) % 6;
            Vec3[] cs = {centers[j][k], centers[j][n], centers[j + 1][n], centers[j + 1][k]};
            Vec3[] os = {offsets[j][k], offsets[j][n], offsets[j + 1][n], offsets[j + 1][k]};
            Vec3 normal = os[0].add(os[1]).normalize();
            panels.add(new Panel(cs, os, normal, k == 2 ? 8 : 2 + (k + seed) % 3, j * 3, weight * (1 - j / (float) steps), weight * (1 - (j + 1) / (float) steps), vein));
        }
    }
    /** {@code severed}：砍断了的主血管（第 i 位），它们和分叉都不再搏动、不再发光。 */
    static void draw(PoseStack.Pose pose, VertexConsumer out, int light, float time, boolean defeated, double drop, float collapse, int severed) {
        build();
        for (Panel p : panels) {
            boolean dead = defeated || (severed & 1 << p.vein) != 0;
            float pulse = dead ? 0 : beat(time - p.phase);
            boolean glow = p.color >= 6 && !dead;
            int value = Math.round(255 * (dead ? .12f : .28f + .55f * pulse));
            int tint = p.color >= 6 ? 0xff000000 | value << 16 | value << 8 | value : dead ? 0xff7a6a6a : 0xffffffff;
            for (int i = 0; i < 4; i++) {
                Vec3 at = p.centers[i].add(p.offsets[i].scale((1 + .07 * pulse) * (dead && !defeated ? .7 : 1)));
                // 核心下降时管道端点仍扎在树壁；共殒时才把整张控制网络收回核心。
                at = at.add(0, drop * (i < 2 ? p.startWeight : p.endWeight), 0).lerp(new Vec3(0, 3 + drop, 0), collapse);
                out.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(tint).setUv((p.color + .5f) / 16, .5f / 16)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(glow ? LightCoordsUtil.FULL_BRIGHT : light)
                        .setNormal(pose, (float) p.normal.x, (float) p.normal.y, (float) p.normal.z);
            }
        }
    }
}
