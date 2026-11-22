package dev.logos.dimension.client.jiansui;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.camera.CameraJitter;
import dev.logos.dimension.client.screenfx.ScreenCoverRenderer;
import dev.logos.dimension.jiansui.FallStage;
import dev.logos.dimension.jiansui.JianSuiEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import org.jspecify.annotations.Nullable;

/**
 * 第三阶段「坠渊」本机这边的气氛（docs/JIAN_SUI_BOSS.md 第 10.2 节）：镜头在场景的空洞里时，
 * 雾只按这边的设置来（渊雾的规则关掉）、一直响着很大的风声、压掉洞穴的环境音、镜头轻轻地上下颤动。
 * 场景的画面本身见 {@link FallStageRenderer}。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class FallStageClient {
    /** 空洞的范围（相对场景实体）：半径、上下沿。 */
    private static final double RADIUS = 37, BELOW = 21, ABOVE = 19.5;
    /** 雾：远处的壳要看得见在往上滚，所以雾不浓；颜色是中间那段的灰蓝。 */
    private static final float FOG_NEAR = 12, FOG_FAR = 150, RED = .42f, GREEN = .46f, BLUE = .50f;
    private static float amount;
    private static @Nullable Wind wind;
    /** 上一刻的世界：换了一个（换维度、换世界）时风声重来。 */
    private static @Nullable ClientLevel shown;

    private FallStageClient() {}

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(JianSuiEntities.FALL_STAGE.get(), FallStageRenderer::new);
        event.registerEntityRenderer(JianSuiEntities.JIAN_SUI.get(), JianSuiRenderer::new);
    }

    /** 镜头所在的那个场景；不在任何场景里时返回 null。 */
    public static @Nullable FallStage stage() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Vec3 eye = mc.gameRenderer.getMainCamera().position();
        for (FallStage stage : mc.level.getEntitiesOfClass(FallStage.class, new AABB(eye, eye).inflate(RADIUS + 4))) {
            double dx = eye.x - stage.getX(), dz = eye.z - stage.getZ(), dy = eye.y - stage.getY();
            if (dx * dx + dz * dz <= RADIUS * RADIUS && dy > -BELOW && dy < ABOVE) return stage;
        }
        return null;
    }

    /** 镜头在场景里（渊雾的规则据此让开）。 */
    public static boolean inStage() {
        return amount > 0;
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level != shown) {
            shown = mc.level;
            stopWind();
        }
        if (mc.isPaused()) return;
        FallStage stage = stage();
        float speed = stage == null ? 0 : stage.speedAt(mc.level.getGameTime()) / FallStage.FALL_SPEED;
        float target = stage == null ? 0 : 1;
        amount += (target - amount) * .2f;
        if (amount < .003f) amount = 0;
        if (stage != null && speed > .05f) CameraJitter.want(speed);
        // 声音引擎会自己把它丢掉（重连、重生、换维度时全停，实例却不算停了），所以还要问它是不是还在放
        if (stage != null && speed > .05f && (wind == null || wind.isStopped() || !mc.getSoundManager().isActive(wind))) {
            wind = new Wind();
            mc.getSoundManager().play(wind);
        }
        if (wind != null) {
            wind.target = stage == null ? 0 : Mth.clamp(speed, 0, 1);
            // 掉进虚空的黑屏（第 10.4 节）：盖上时风声跟着压低，退去时回来
            wind.hush = 1 - ScreenCoverRenderer.alpha(0);
        }
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stopWind();
    }

    /** 风声停掉、忘掉（还在场景里时下一刻重新起一个）。 */
    private static void stopWind() {
        if (wind != null) Minecraft.getInstance().getSoundManager().stop(wind);
        wind = null;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void fogColor(ViewportEvent.ComputeFogColor event) {
        if (amount <= 0) return;
        event.setRed(Mth.lerp(amount, event.getRed(), RED));
        event.setGreen(Mth.lerp(amount, event.getGreen(), GREEN));
        event.setBlue(Mth.lerp(amount, event.getBlue(), BLUE));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void fogDistance(ViewportEvent.RenderFog event) {
        if (amount <= 0) return;
        event.setNearPlaneDistance(Mth.lerp(amount, event.getNearPlaneDistance(), FOG_NEAR));
        event.setFarPlaneDistance(Mth.lerp(amount, event.getFarPlaneDistance(), FOG_FAR));
    }

    /** 外面那一层：在 Logos 光影叠加层的 begin 之后交（见 {@link FallStageRenderer#submitShell}）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void shell(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = event.getLevelRenderState().cameraRenderState;
        Vec3 cam = camera.pos;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var out = dev.logos.dimension.client.LogosSkyOverlay.collector(event.getSubmitNodeCollector());
        for (FallStage stage : mc.level.getEntitiesOfClass(FallStage.class, new AABB(cam, cam).inflate(96))) {
            var state = new FallStageRenderer.State();
            FallStageRenderer.fill(stage, state, partial);
            var pose = event.getPoseStack();
            pose.pushPose();
            pose.translate(state.entity.x - cam.x, state.entity.y - cam.y, state.entity.z - cam.z);
            FallStageRenderer.submitShell(state, pose, out, camera);
            pose.popPose();
        }
    }

    /** 场景里压掉洞穴的环境音（「洞穴里的怪声」在一直往下掉的时候不对劲）。 */
    @SubscribeEvent
    static void ambience(PlaySoundEvent event) {
        if (amount <= 0 || event.getSound() == null) return;
        if (event.getSound().getSource() == SoundSource.AMBIENT && event.getSound() != wind
                && event.getSound().getIdentifier().getPath().startsWith("ambient.cave")) event.setSound(null);
    }

    /** 一直响着的风声：原版鞘翅的风，压低音调、贴着耳朵（不随位置衰减），进出场景时慢慢起落；全屏黑、白盖着时压低。 */
    private static final class Wind extends AbstractTickableSoundInstance {
        float target = 1;
        /** 全屏盖着时音量乘多少（1 不压）：只压音量，不因此停掉。 */
        float hush = 1;

        Wind() {
            super(SoundEvents.ELYTRA_FLYING, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = .01f;
            pitch = .72f;
            relative = true;
            attenuation = Attenuation.NONE;
        }

        @Override
        public void tick() {
            volume += (target * hush * .62f - volume) * .08f;
            pitch = .62f + .12f * target;
            if (target <= 0 && volume < .01f) stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
