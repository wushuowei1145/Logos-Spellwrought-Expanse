package dev.logos.dimension.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.NatureAttachments;
import dev.logos.dimension.item.YumiItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.SpectralArrowRenderer;
import net.minecraft.client.renderer.entity.TippableArrowRenderer;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 神狐和弓的客户端：拉弓时像原版弓一样拉近视野；和弓射出的箭画成和弓的矢（combat/weapons/yumi_arrow.json，
 * 与搭在弓上的是同一份网格），代替原版的十字箭模型。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class YumiClient {
    private static final ContextKey<Boolean> YUMI_ARROW = new ContextKey<>(LogosDimension.id("yumi_arrow"));
    /** 飞行中的矢长（格）；原版箭约 0.9 格。 */
    private static final float FLIGHT_LENGTH = 1.15f;
    /** 箭头在实体位置前方多少格（原版箭约 0.23 格）。 */
    private static final float TIP_AHEAD = 0.3f;
    private record Face(Vector3f[] points, Vector3f normal, float[] uv) {}
    private static List<Face> arrow;
    private static Identifier arrowTexture;
    private static float arrowLength;

    @SubscribeEvent static void fov(ComputeFovModifierEvent event) {
        var player = event.getPlayer();
        if (!player.isUsingItem() || !(player.getUseItem().getItem() instanceof YumiItem)) return;
        // 原版弓按拉弓的时长把视野拉近至多 15%；和弓从开始引分け起算。
        float power = YumiItem.power(player.getTicksUsingItem());
        event.setNewFovModifier(event.getNewFovModifier() * (1 - power * power * 0.15f));
    }

    @SubscribeEvent static void reload(AddClientReloadListenersEvent event) {
        event.addListener(LogosDimension.id("yumi_arrow"),
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> arrow = null);
    }

    @SubscribeEvent static void states(RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier(TippableArrowRenderer.class,
                (entity, state) -> state.setRenderData(YUMI_ARROW, entity.getData(NatureAttachments.YUMI_ARROW)));
        event.registerEntityModifier(SpectralArrowRenderer.class,
                (entity, state) -> state.setRenderData(YUMI_ARROW, entity.getData(NatureAttachments.YUMI_ARROW)));
    }

    /** 和弓的箭画成矢并返回 true（调用方不再画原版箭）；不是和弓的箭或网格读不到时返回 false。 */
    public static boolean submitArrow(ArrowRenderState state, PoseStack pose, SubmitNodeCollector collector) {
        if (!Boolean.TRUE.equals(state.getRenderData(YUMI_ARROW)) || !load()) return false;
        float scale = FLIGHT_LENGTH / arrowLength;
        pose.pushPose();
        // 与原版箭相同的朝向：+X 指向飞行方向，插在地上时按 shake 抖动。
        pose.mulPose(Axis.YP.rotationDegrees(state.yRot - 90));
        pose.mulPose(Axis.ZP.rotationDegrees(state.xRot));
        if (state.shake > 0) pose.mulPose(Axis.ZP.rotationDegrees(-Mth.sin(state.shake * 3) * state.shake));
        // 矢的网格箭头朝 -Z、原点在箭筈：转到 +X，再往后挪，让箭头在实体前方 TIP_AHEAD 格。
        pose.mulPose(Axis.YP.rotationDegrees(-90));
        pose.scale(scale, scale, scale);
        pose.translate(0, 0, arrowLength - TIP_AHEAD / scale);
        int light = state.lightCoords;
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(arrowTexture), (p, buffer) -> {
            for (var face : arrow) for (int i = 0; i < 4; i++) {
                var point = face.points[i]; var uv = face.uv; var n = face.normal;
                float u = i == 0 || i == 3 ? uv[2] : uv[0], v = i < 2 ? uv[3] : uv[1];
                buffer.addVertex(p, point.x, point.y, point.z).setColor(255, 255, 255, 255).setUv(u, v)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, n.x, n.y, n.z);
            }
        });
        pose.popPose();
        return true;
    }

    private static boolean load() {
        if (arrow != null) return !arrow.isEmpty();
        arrow = new ArrayList<>();
        try (var reader = Minecraft.getInstance().getResourceManager()
                .getResourceOrThrow(LogosDimension.id("combat/weapons/yumi_arrow.json")).openAsReader()) {
            JsonObject mesh = JsonParser.parseReader(reader).getAsJsonObject();
            arrowTexture = Identifier.parse(mesh.get("texture").getAsString());
            arrowLength = mesh.get("length").getAsFloat();
            var size = mesh.getAsJsonArray("texture_size");
            float w = size.get(0).getAsFloat(), h = size.get(1).getAsFloat();
            for (var value : mesh.getAsJsonArray("cubes")) cube(value.getAsJsonObject(), w, h);
        } catch (IOException | RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().error("和弓的箭网格读取失败，改画原版箭", e);
            arrow = List.of();
        }
        return !arrow.isEmpty();
    }

    /** 与 LongswordRig 的方块相同的角点与 UV 排布。 */
    private static void cube(JsonObject cube, float w, float h) {
        var from = vector(cube.getAsJsonArray("start")); var to = vector(cube.getAsJsonArray("end"));
        var pivot = vector(cube.getAsJsonArray("pivot")); var rotation = vector(cube.getAsJsonArray("rotation"));
        float r = (float) Math.PI / 180;
        var transform = new Matrix4f().translation(pivot).rotateZ(rotation.z * r).rotateY(rotation.y * r).rotateX(rotation.x * r)
                .translate(-pivot.x, -pivot.y, -pivot.z);
        float x = from.x, y = from.y, z = from.z, X = to.x, Y = to.y, Z = to.z;
        float[][][] faces = {
            {{X,y,z},{x,y,z},{x,Y,z},{X,Y,z}}, {{x,y,Z},{X,y,Z},{X,Y,Z},{x,Y,Z}},
            {{x,y,z},{x,y,Z},{x,Y,Z},{x,Y,z}}, {{X,y,Z},{X,y,z},{X,Y,z},{X,Y,Z}},
            {{x,Y,Z},{X,Y,Z},{X,Y,z},{x,Y,z}}, {{x,y,z},{X,y,z},{X,y,Z},{x,y,Z}}
        };
        String[] names = {"north", "south", "west", "east", "up", "down"};
        for (int face = 0; face < 6; face++) {
            var points = new Vector3f[4];
            for (int i = 0; i < 4; i++) points[i] = transform.transformPosition(new Vector3f(faces[face][i]));
            var normal = new Vector3f(points[1]).sub(points[0]).cross(new Vector3f(points[2]).sub(points[0])).normalize();
            var uv = cube.getAsJsonObject("uv").getAsJsonArray(names[face]);
            arrow.add(new Face(points, normal, new float[]{uv.get(0).getAsFloat() / w, uv.get(1).getAsFloat() / h,
                    uv.get(2).getAsFloat() / w, uv.get(3).getAsFloat() / h}));
        }
    }
    private static Vector3f vector(com.google.gson.JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
    private YumiClient() {}
}
