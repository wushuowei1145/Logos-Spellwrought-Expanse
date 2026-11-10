package dev.logos.dimension.client.execution;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.execution.ExecutionTimeline.Slash;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * 旁观可交互处决的人在世界里看到的刀光：目标在半空，每砍一刀，身上划过一道弯弯的光，几刻就散。
 * 处决的人和被处决的玩家看的是特写里的刀（{@link ExecutionRenderer}），不画这个。颜色是 {@code ExecutionShot.tint}。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ExecutionSlashes {
    /** 底下一层正常混合的刀光身子（白天的天空上光是加亮会淡得看不见），上面一层加亮的光。 */
    private static final RenderType BODY = VfxParts.blend(LogosDimension.id("textures/vfx/execution_slash.png"));
    private static final RenderType GLOW = VfxParts.add(LogosDimension.id("textures/vfx/execution_slash.png"));
    /** 一道刀光从出现到散掉几刻；刀头在前 {@link #SWEEP} 刻里划过去。 */
    static final float LIFE = 6, SWEEP = 2;
    /** 一道刀光分几段画。 */
    private static final int SEGMENTS = 10;

    /** 画过几道刀光（客户端场景检查用）。 */
    private static int drawn;

    private ExecutionSlashes() {}

    public static int drawn() {
        return drawn;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<ExecutionClient.Run> runs = ExecutionClient.watched();
        if (runs.isEmpty()) return;
        float partial = ExecutionClient.partialTick();
        var camera = event.getLevelRenderState().cameraRenderState;
        VfxParts.View view = VfxParts.View.of(camera);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (ExecutionClient.Run run : runs) {
            Entity entity = run.level.getEntity(run.target);
            if (entity == null) continue;
            float age = run.age(partial);
            var timeline = run.timeline;
            ExecutionClient.Body body = run.body(age);
            Vec3 feet = entity.getPosition(partial);
            Vec3 center = feet.add(body.lift().x, body.lift().y + body.pivot(), body.lift().z);
            float radius = Math.max(.7f, run.height * .7f);
            int tint = timeline.shot().tint();
            // 身子比光深一些：在亮处靠它看得见
            int deep = VfxParts.lerpColor(tint, 0x9A2A62, .45f);
            var slashes = timeline.slashes();
            for (int i = 0; i < slashes.size(); i++) {
                float t = age - slashes.get(i).start();
                if (t < 0 || t > LIFE) continue;
                int index = i;
                drawn++;
                out.submitCustomGeometry(pose, BODY, (p, buf) -> arc(p, buf, view, center, radius, slashes.get(index), timeline.shot().seed() + index, t, deep, 1.4f, .85f));
                out.submitCustomGeometry(pose, GLOW, (p, buf) -> arc(p, buf, view, center, radius, slashes.get(index), timeline.shot().seed() + index, t, tint, .55f, 1));
            }
            // 重击：一道又粗又长、从上往下的
            float hit = age - timeline.finisher().start();
            if (hit >= 0 && hit <= LIFE * 1.5f) {
                float t = hit / 1.5f;
                out.submitCustomGeometry(pose, BODY, (p, buf) -> arc(p, buf, view, center, radius * 1.4f, timeline.finisher(), timeline.shot().seed() - 1, t, deep, 1.4f, .85f));
                out.submitCustomGeometry(pose, GLOW, (p, buf) -> arc(p, buf, view, center, radius * 1.4f, timeline.finisher(), timeline.shot().seed() - 1, t, tint, .55f, 1));
            }
        }
        pose.popPose();
    }

    /**
     * 一道刀光：在一个随机斜着的平面上，绕身体中心弯过去的一段弧（对着镜头的带子），刀头先划过去，刀尾跟着收，越来越淡。
     * 平面和起止角度由种子定，这一刀的粗细跟着 {@link Slash#width}。
     */
    private static void arc(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 center, float radius, Slash slash, long seed, float t, int tint,
                            float widthK, float alphaK) {
        Random random = new Random(seed * 0x5DEECE66DL + 11);
        Vec3 normal = new Vec3(random.nextDouble() - .5, (random.nextDouble() - .5) * .8, random.nextDouble() - .5).normalize();
        Vec3 u = normal.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1e-4) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = normal.cross(u).normalize();
        double start = random.nextDouble() * Math.TAU, span = Math.toRadians(110 + random.nextDouble() * 60);
        // 刀光不是正好绕着中心：往一边偏一点，像是砍过身体
        Vec3 c = center.add(u.scale(slash.offset() * radius * .4));
        float head = Math.min(1, t / SWEEP), tail = Math.max(0, (t - SWEEP * .6f) / (LIFE - SWEEP * .6f));
        float fade = 1 - tail;
        if (head <= tail) return;
        float width = .5f * slash.width() * radius * widthK;
        Vec3 prev = null;
        for (int k = 0; k <= SEGMENTS; k++) {
            float s = tail + (head - tail) * k / SEGMENTS;
            double a = start + span * s;
            Vec3 at = c.add(u.scale(Math.cos(a) * radius)).add(v.scale(Math.sin(a) * radius));
            if (prev != null) {
                // 刀头那端最粗最亮，往刀尾变细变淡
                float w = (float) k / SEGMENTS;
                VfxParts.streak(pose, buf, view, prev, at, width * (.35f + .65f * w), .45f, 0, .55f, 1,
                        VfxParts.argb(alphaK * fade * (.25f + .75f * w), tint));
            }
            prev = at;
        }
    }
}
