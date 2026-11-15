package dev.logos.dimension.client.corruption;

import com.mojang.blaze3d.vertex.*;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.elven.blight.QueenTether;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

/** 暴露的根结是有体积的裂瓣血管，复用核心调色板和合面网格。 */
public final class QueenTetherRenderer extends EntityRenderer<QueenTether, QueenTetherRenderer.State> {
    private static final RenderType TYPE = RenderTypes.entityCutout(LogosDimension.id("textures/entity/queen_heart_palette.png"));
    private static CoreMesh root;
    public static class State extends EntityRenderState { boolean core; float time; Vec3 endpoint; }
    public QueenTetherRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius = .6f; }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(QueenTether root, State state, float partial) {
        super.extractRenderState(root, state, partial); state.core = root.slot() == 3; state.time = root.tickCount + partial;
        var queen = root.queen(); state.endpoint = queen == null ? null : queen.corePosition(partial).add(0, 3.2, 0).subtract(root.getPosition(partial));
    }
    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.core) return;
        if (state.endpoint != null) collector.submitCustomGeometry(pose, TYPE, (p, out) -> vessel(p, out, new Vec3(0, .8, 0), state.endpoint, .11, state.time, state.lightCoords));
        if (root == null) {
            CoreShape s = new CoreShape(20, 25);
            for (int y = 0; y < 25; y++) for (int x = 0; x < 20; x++) for (int z = 0; z < 20; z++) {
                double dx = x - 9.5, dz = z - 9.5, r = Math.hypot(dx, dz), a = Math.atan2(dz, dx);
                double width = 3 + 5 * Math.pow(Math.sin(a * 3 + y * .1), 2);
                if (y < 5 && r < 9 || y >= 5 && r < width * (1 - (y - 5) / 24.0) && (r > 2 || y < 15))
                    s.set(x, y, z, r < 3 ? 9 : (x + z + y) % 6 == 0 ? 7 : 2 + y % 3);
            }
            root = CoreMesh.of(s, 1);
        }
        pose.pushPose(); pose.scale(1/16f, 1/16f, 1/16f); pose.translate(-10, 0, -10);
        collector.submitCustomGeometry(pose, TYPE, (p, out) -> root.draw(p, out, state.lightCoords, .65f + .2f * (float) Math.sin(state.time * .08)));
        pose.popPose();
    }
    /** 六面管道，位置、UV、颜色、光照、法线和覆盖层一次写全。 */
    static void vessel(PoseStack.Pose pose, VertexConsumer out, Vec3 from, Vec3 to, double radius, float time, int light) {
        Vec3 bend = from.lerp(to, .5).add(0, -1.2 + .12 * Math.sin(time * .05), 0);
        for (int j = 0; j < 12; j++) {
            double t0 = j / 12.0, t1 = (j + 1) / 12.0;
            Vec3 a = curve(from, bend, to, t0), b = curve(from, bend, to, t1);
            Vec3 tangent = b.subtract(a).normalize(), u = tangent.cross(new Vec3(0, 1, .01)).normalize(), v = tangent.cross(u).normalize();
            for (int k = 0; k < 6; k++) {
                double angle = k * Math.PI / 3, next = (k + 1) * Math.PI / 3;
                Vec3 n = u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle))), m = u.scale(Math.cos(next)).add(v.scale(Math.sin(next)));
                Vec3[] points = {a.add(n.scale(radius)), a.add(m.scale(radius)), b.add(m.scale(radius)), b.add(n.scale(radius))};
                int color = k == 2 ? 8 : 2 + k % 3;
                for (Vec3 point : points) out.addVertex(pose, (float) point.x, (float) point.y, (float) point.z).setColor(-1).setUv((color + .5f) / 16, .5f / 16)
                        .setLight(k == 2 ? net.minecraft.util.LightCoordsUtil.FULL_BRIGHT : light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
            }
        }
    }
    private static Vec3 curve(Vec3 a, Vec3 b, Vec3 c, double t) { return a.scale((1-t)*(1-t)).add(b.scale(2*t*(1-t))).add(c.scale(t*t)); }
}
