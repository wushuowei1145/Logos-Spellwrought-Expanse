package dev.logos.dimension.client.worldtree;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.WorldtreeBowItem;
import dev.logos.dimension.worldtree.Worldtree;
import dev.logos.dimension.worldtree.WorldtreeAim;
import dev.logos.dimension.worldtree.WorldtreeDraw;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * 拉世界树之弓时的界面：一圈低透明度的藤蔓框（大小随拉弓展开，与服务端判定锁定的框相同），
 * 被锁定的生物身上一个藤圈标记（锁了几次就绕圈多几片小叶），锁定时藤圈从大缩到位。不写任何文字。
 */
public final class WorldtreeHud {
    public static final Identifier ID = LogosDimension.id("worldtree_aim");
    private static final Identifier FRAME = LogosDimension.id("textures/gui/worldtree/frame.png");
    private static final Identifier LOCK = LogosDimension.id("textures/gui/worldtree/lock.png");
    private static final RenderPipeline PIPELINE = RenderPipelines.GUI_TEXTURED;
    /** 框的透明度（约四成）与标记的透明度。 */
    private static final int FRAME_ALPHA = 105, MARK_ALPHA = 190;
    /** 框的一个角在界面上的大小（界面像素）。 */
    private static final int CORNER = 24, EDGE = 6, MARK = 22;
    /** 客户端测试用：最近一帧画了几个标记、框的四边（界面像素）。 */
    static int marksDrawn, framesDrawn;
    static int[] lastFrame = new int[4];

    private WorldtreeHud() {}

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        marksDrawn = 0;
        if (player == null || mc.level == null || mc.options.hideGui || !player.isUsingItem() || !(player.getUseItem().getItem() instanceof WorldtreeBowItem)) return;
        WorldtreeDraw draw = player.getUseItem().get(Worldtree.DRAW.get());
        // 左键横着拉弓：没有框、不锁定
        if (WorldtreeClient.sideways() || draw != null && draw.sideways()) return;
        var camera = WorldtreeClient.camera();
        if (camera == null) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float ticks = player.getTicksUsingItem() + partial;
        float now = mc.level.getGameTime() + partial;
        int w = g.guiWidth(), h = g.guiHeight();
        float scale = WorldtreeAim.frame(ticks);
        if (scale > .02f) {
            // 切平面上的半宽、半高换到屏幕：ndc = P00 · tan(半角)
            float hx = camera.projection().m00() * (float) Math.tan(Math.toRadians(WorldtreeAim.HALF_WIDTH)) * scale;
            float hy = camera.projection().m11() * (float) Math.tan(Math.toRadians(WorldtreeAim.HALF_HEIGHT)) * scale;
            int x0 = Math.round(w / 2f * (1 - hx)), x1 = Math.round(w / 2f * (1 + hx));
            int y0 = Math.round(h / 2f * (1 - hy)), y1 = Math.round(h / 2f * (1 + hy));
            frame(g, x0, y0, x1, y1, Math.round(FRAME_ALPHA * Math.min(1, scale * 1.5f)));
            lastFrame = new int[] {x0, y0, x1, y1};
            framesDrawn++;
        }
        if (draw == null) return;
        Map<Integer, int[]> marks = new LinkedHashMap<>();
        for (var lock : draw.locks()) {
            int[] m = marks.computeIfAbsent(lock.entity(), id -> new int[] {0, (int) lock.time()});
            m[0]++;
        }
        for (var e : marks.entrySet()) {
            Entity target = mc.level.getEntity(e.getKey());
            if (target == null || !target.isAlive()) continue;
            Vec3 at = target.getPosition(partial).add(0, target.getBbHeight() / 2, 0);
            float[] screen = project(camera, at, w, h);
            if (screen == null) continue;
            float pop = Mth.clamp((now - e.getValue()[1]) / 4f, 0, 1);
            mark(g, screen[0], screen[1], e.getValue()[0], pop);
            marksDrawn++;
        }
    }

    /** 世界里的点 → 界面坐标；在镜头后面时为 null。 */
    static float[] project(WorldtreeClient.Camera camera, Vec3 at, int w, int h) {
        Vector4f p = new Vector4f((float) (at.x - camera.pos().x), (float) (at.y - camera.pos().y), (float) (at.z - camera.pos().z), 1);
        new Matrix4f(camera.projection()).mul(camera.view()).transform(p);
        if (p.w <= .05f) return null;
        return new float[] {(p.x / p.w + 1) / 2 * w, (1 - p.y / p.w) / 2 * h};
    }

    private static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int alpha) {
        int color = alpha << 24 | 0xFFFFFF;
        int c = Math.min(CORNER, Math.min(x1 - x0, y1 - y0) / 2);
        int across = x1 - x0 - 2 * c, down = y1 - y0 - 2 * c;
        // 同一个角、同一条边按 90° 转到四处（镜像会翻转面片朝向，界面会把它剔掉）。角的图案沿对角线对称，转过去和镜像一样。
        turned(g, x0, y0, 0, () -> {
            g.blit(PIPELINE, FRAME, 0, 0, 0, 0, c, c, 32, 32, 64, 64, color);
            if (across > 0) g.blit(PIPELINE, FRAME, c, 0, 32, 0, across, EDGE, 32, 8, 64, 64, color);
        });
        turned(g, x1, y0, 90, () -> {
            g.blit(PIPELINE, FRAME, 0, 0, 0, 0, c, c, 32, 32, 64, 64, color);
            if (down > 0) g.blit(PIPELINE, FRAME, c, 0, 32, 0, down, EDGE, 32, 8, 64, 64, color);
        });
        turned(g, x1, y1, 180, () -> {
            g.blit(PIPELINE, FRAME, 0, 0, 0, 0, c, c, 32, 32, 64, 64, color);
            if (across > 0) g.blit(PIPELINE, FRAME, c, 0, 32, 0, across, EDGE, 32, 8, 64, 64, color);
        });
        turned(g, x0, y1, 270, () -> {
            g.blit(PIPELINE, FRAME, 0, 0, 0, 0, c, c, 32, 32, 64, 64, color);
            if (down > 0) g.blit(PIPELINE, FRAME, c, 0, 32, 0, down, EDGE, 32, 8, 64, 64, color);
        });
    }

    /** 以 (x, y) 为原点顺时针转 degrees 再画：角的左上对着框的这个角，横边沿框边顺时针延伸。 */
    private static void turned(GuiGraphicsExtractor g, float x, float y, float degrees, Runnable draw) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().rotate((float) Math.toRadians(degrees));
        draw.run();
        g.pose().popMatrix();
    }

    /** 藤圈：锁定时从 1.6 倍缩到位；锁了几次就在圈外多几片小叶。 */
    private static void mark(GuiGraphicsExtractor g, float x, float y, int count, float pop) {
        float size = MARK * (1 + .6f * (1 - pop) * (1 - pop));
        int alpha = Math.round(MARK_ALPHA * Math.min(1, pop * 2));
        int color = alpha << 24 | 0xFFFFFF;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size / 24f, size / 24f);
        g.blit(PIPELINE, LOCK, -12, -12, 0, 0, 24, 24, 24, 24, 32, 32, color);
        for (int i = 1; i < count; i++) {
            g.pose().pushMatrix();
            g.pose().rotate((float) Math.toRadians(-60 + 37 * i));
            g.blit(PIPELINE, LOCK, 9, -4, 24, 0, 8, 8, 8, 8, 32, 32, color);
            g.pose().popMatrix();
        }
        g.pose().popMatrix();
    }
}
