package dev.logos.dimension.client.worldtree;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.worldtree.Worldtree;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterItemModelsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** 世界树之弓的客户端注册：箭的渲染、弓上的箭（物品模型）、锁定界面；记下这一帧的镜头，界面用它把锁定的生物投到屏幕上。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class WorldtreeClient {
    /** 这一帧镜头的位置、视图旋转与投影。 */
    public record Camera(Vec3 pos, Matrix4fc view, Matrix4fc projection) {}

    private static volatile Camera camera;
    /** 正在用左键横着拉弓：原版只看右键是否按着来决定松弓，这时改看左键（{@code WorldtreeKeybindsMixin}）。 */
    private static boolean sideways;

    private WorldtreeClient() {}

    static Camera camera() {
        return camera;
    }

    public static boolean sideways() {
        return sideways;
    }

    /** 手里是世界树之弓时，左键不攻击、不挖方块，改为开始横着拉弓（服务端也开始拉，本地先开始，免得下一刻就被松掉）。 */
    @SubscribeEvent static void attack(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (!event.isAttack() || mc.player == null || mc.screen != null || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND
                || !(mc.player.getMainHandItem().getItem() instanceof dev.logos.dimension.item.WorldtreeBowItem)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (mc.player.isUsingItem()) return;
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new Worldtree.Sideways());
        mc.player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        sideways = true;
    }

    @SubscribeEvent static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (sideways && (player == null || !player.isUsingItem())) sideways = false;
    }

    @SubscribeEvent static void conditions(net.neoforged.neoforge.client.event.RegisterConditionalItemModelPropertyEvent event) {
        event.register(SidewaysProperty.ID, SidewaysProperty.CODEC);
    }

    @SubscribeEvent static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Worldtree.ARROW.get(), WorldtreeArrowRenderer::new);
    }

    @SubscribeEvent static void itemModels(RegisterItemModelsEvent event) {
        event.register(WorldtreeArrowsModel.TYPE, WorldtreeArrowsModel.Unbaked.CODEC);
    }

    @SubscribeEvent static void layers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CROSSHAIR, WorldtreeHud.ID, WorldtreeHud::render);
    }

    @SubscribeEvent static void afterLevel(RenderLevelStageEvent.AfterLevel event) {
        var c = event.getLevelRenderState().cameraRenderState;
        if (c == null || c.pos == null || c.projectionMatrix == null || c.viewRotationMatrix == null) return;
        camera = new Camera(c.pos, new Matrix4f(c.viewRotationMatrix), new Matrix4f(c.projectionMatrix));
    }
}
