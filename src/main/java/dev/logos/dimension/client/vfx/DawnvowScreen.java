package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.dawnvow.DawnvowBurst;
import dev.logos.dimension.dawnvow.SwordWave;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 曦誓的全屏后期，一个通道，排在爆裂魔法的通道之后、{@link PostVfx} 收到的特效之前（{@code ScreenEffectMixin}）：
 * <ul>
 *   <li><b>大型蓄力</b>：蓄力的人和身边一圈被照亮，稍远的地方暗下去、褪色，天空也暗；四周升起的光点、身上的光是在调暗之后
 *       由 {@link PostVfx} 画的（{@link PostLayer}），所以格外亮。巨型剑气冲出去时，剑气头照亮它经过的地面。</li>
 *   <li><b>剑气到头，光柱冲起之前</b>（{@link DawnvowBurst} 的时间轴）：地上一团光鼓成穹顶，四周几乎全黑；黑色的尖刺炸满画面，
 *       外围反而是亮的，亮光向内收紧；然后是几何黑白闪（反色、白底黑星、黑底白星，24fps 一拍，每拍至少显示一帧）；
 *       穹顶猛地涨大扑满画面，画面整个白掉，光柱冲起后白光退去、周围慢慢亮回来。</li>
 * </ul>
 * 位置由半透明之前的深度还原（与 {@link PostVfx} 共用那一份），只读最终画面，和 Sodium、光影包走同一条路。
 * <p>
 * 调暗只调世界本身：世界画到几个阶段时各留一份画面（{@link #capture}），比较前后就知道哪些像素是之后才加上去的光，
 * 那些地方保留原来的颜色，不跟着暗——半透明特效那一遍（Logos 物品上的火焰和它散出的火苗、光柱、剑身金光）和第一人称的手那一遍
 * （手上那把剑的火焰）。只认「变亮了、没有哪个通道变暗」的变化：玩家皮肤也是半透明画的，但那是把颜色换掉，照常调暗。
 * 原版、Sodium、Iris 关光影时留三份：实心的世界画完、半透明特效画完、天气画完（手之前）；水、粒子、云和天气照常调暗。
 * 开着光影包时 Logos 等光影包合成完画面再画自己的特效（{@code ShaderPackOverlay}），就在那之前留一份。
 * 都留不到时退回整幅画面一起调暗。
 * <p>
 * 没有大型蓄力和巨型剑气时一个通道都不跑。原版选项「隐藏闪电的闪烁」打开时不放黑白闪，黑色爆发和白光减半。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class DawnvowScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/dawnvow"))
            .withVertexShader(LogosDimension.id("post/dawnvow"))
            .withFragmentShader(LogosDimension.id("post/dawnvow"))
            .withSampler("InSampler")
            .withSampler("DepthSampler")
            .withSampler("HandDepthSampler")
            .withSampler("NoiseSampler")
            .withSampler("OpaqueSampler")
            .withSampler("TransSampler")
            .withSampler("WeatherSampler")
            .withUniform("Dawnvow", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putMat4f()
            .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();
    /** 大型蓄力时周围最暗到哪；剑气到头后再暗一些。 */
    private static final float CHARGE_DARK = .85f, BURST_DARK = .95f;
    /** 多远以内的人看得到这些后期：蓄力的人身边，和剑气的落点。 */
    private static final float NEAR = 48, FAR = 120, BURST_NEAR = 64, BURST_FAR = 160;

    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    /** 分层留下的画面：实心的世界、半透明特效画完、天气画完；开着光影包时第一份是合成完、Logos 特效之前的画面。 */
    private static final RenderTarget[] LAYERS = new RenderTarget[3];
    private static final String[] LAYER_NAMES = {"opaque", "translucent", "weather"};
    /**
     * 半透明特效画完时的深度：玩家的皮肤是半透明画的，半透明之前的深度里没有玩家，蓄力的人会被当成背后远处的东西调暗；
     * 发光的特效不写深度，不在里面。原版的画法里用它还原位置。
     */
    private static @Nullable RenderTarget depthLayer;
    private static boolean depthCaptured;
    /** 这一帧留到了哪几份（按位）；开着光影包时留到的是合成完的那一份。 */
    private static int captured;
    private static boolean overlay;
    /** {@code () -> boolean}：开没开光影包（Iris 的公开接口，没装 Iris 时为 null）。 */
    private static final @Nullable MethodHandle SHADER_PACK = irisShaderPack();
    private static long passes;
    /** 黑白闪现在放到第几拍：一帧最多往前走一拍，帧率再低每一拍也都能被看到。 */
    private static int shownBeat = -1, beatsShown;
    private static @Nullable ExcaliburWave beatWave;
    /** 上一帧的黑白闪和白光：界面据此在画面整个换掉时不画。 */
    private static boolean replaced;

    private DawnvowScreen() {}

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    /** 世界要开始画了（由 Mixin 调用）：这一帧分层留的画面从这里重新算。 */
    public static void beginFrame() {
        captured = 0;
        overlay = false;
        depthCaptured = false;
    }

    @SubscribeEvent
    static void afterOpaque(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        capture(0);
    }

    @SubscribeEvent
    static void afterTranslucentFeatures(RenderLevelStageEvent.AfterTranslucentFeatures event) {
        capture(1);
        // 深度开着光影包时也留：Iris 画世界时用的也是这一份深度
        if (!active()) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (depthLayer == null || depthLayer.useStencil != main.useStencil) {
            if (depthLayer != null) depthLayer.destroyBuffers();
            depthLayer = new TextureTarget("logos_dimension dawnvow depth", main.width, main.height, true, main.useStencil);
        } else if (depthLayer.width != main.width || depthLayer.height != main.height) {
            depthLayer.resize(main.width, main.height);
        }
        depthLayer.copyDepthFrom(main);
        depthCaptured = true;
    }

    @SubscribeEvent
    static void afterWeather(RenderLevelStageEvent.AfterWeather event) {
        capture(2);
    }

    /** 开着光影包时：光影包合成完画面、Logos 还没把特效画上去的那一刻（由 Mixin 调用）。 */
    public static void beforeOverlay() {
        if (!active()) return;
        copyMain(0);
        overlay = true;
    }

    /** 原版的画法：世界画到第 {@code i} 个阶段时留一份。开着光影包时这时的主画面还不是光影包的结果，不留。 */
    private static void capture(int i) {
        if (!active() || shaderPack()) return;
        copyMain(i);
        captured |= 1 << i;
    }

    private static void copyMain(int i) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        RenderTarget layer = LAYERS[i];
        if (layer == null) {
            layer = LAYERS[i] = new TextureTarget("logos_dimension dawnvow " + LAYER_NAMES[i], main.width, main.height, false);
        } else if (layer.width != main.width || layer.height != main.height) {
            layer.resize(main.width, main.height);
        }
        RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(main.getColorTexture(), layer.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
    }

    /** 这一帧用哪种分层：0 不分层（整幅画面一起调暗），1 原版三份，2 光影包合成完的那一份。 */
    private static int layers(RenderTarget main) {
        if (overlay) return fits(0, main) ? 2 : 0;
        return captured == 7 && fits(0, main) && fits(1, main) && fits(2, main) ? 1 : 0;
    }

    private static boolean fits(int i, RenderTarget main) {
        return LAYERS[i] != null && LAYERS[i].width == main.width && LAYERS[i].height == main.height;
    }

    private static boolean shaderPack() {
        if (SHADER_PACK == null) return false;
        try {
            return (boolean) SHADER_PACK.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }

    private static @Nullable MethodHandle irisShaderPack() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", true, DawnvowScreen.class.getClassLoader());
            var lookup = MethodHandles.publicLookup();
            Object instance = lookup.findStatic(api, "getInstance", MethodType.methodType(api)).invoke();
            return lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(instance);
        } catch (Throwable absent) {
            return null;
        }
    }

    /** 这一帧要不要曦誓的后期：有人在大型蓄力或有巨型剑气。为 true 时 {@link PostVfx} 会留深度，曦誓的特效交给它画。 */
    public static boolean active() {
        return DawnvowClient.busy();
    }

    /** 通道一共跑了多少次（客户端场景检查用）。 */
    public static long passes() {
        return passes;
    }

    /** 最近一次画的时候用的是哪种分层（客户端场景检查用）：0 不分层，1 原版三份，2 光影包合成完的那一份。 */
    public static int lastLayers() {
        return lastLayers;
    }

    private static int lastLayers;

    /** 最近一道剑气的黑白闪实际放了几拍（客户端场景检查用：每一拍都要被看到）。 */
    public static int beatsShown() {
        return beatsShown;
    }

    /** 这一帧交给着色器的东西。 */
    static final class Look {
        @Nullable Vec3 spotA, spotB, dome;
        float radiusA, radiusB, dark, lift, ground, light;
        boolean column;
        float a, b, white, halo, alpha;
        float front, black, rim, expand, flash;
        DawnvowBurst.@Nullable Beat beat;
        int seed;

        boolean empty() {
            return dark <= 0 && alpha <= 0 && black <= 0 && flash <= 0 && beat == null;
        }
    }

    /** 由 Mixin 在世界画完之后调用。 */
    public static void draw(Matrix4f viewProjection) {
        Minecraft mc = Minecraft.getInstance();
        replaced = false;
        if (mc.level == null || !active()) {
            shownBeat = -1;
            beatWave = null;
            return;
        }
        RenderTarget depth = PostVfx.depth();
        if (depth == null) return;
        float pt = DawnvowClient.partial();
        double now = DawnvowClient.now(pt);
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        Look look = look(mc, eye, now, pt, true);
        if (look.empty()) return;
        replaced = look.beat != null || look.flash > .6f;
        pass(mc, camera, viewProjection, depth, look, now);
    }

    /** 画面整个换掉的那几拍（黑白闪、白光）不画界面。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void hud(RenderGuiEvent.Pre event) {
        if (replaced) event.setCanceled(true);
    }

    /** 这一帧的样子；{@code step} 时黑白闪往前走一拍（每帧只在真正画的时候走）。 */
    static Look look(Minecraft mc, Vec3 eye, double now, float pt, boolean step) {
        Look look = new Look();
        boolean calm = mc.options.hideLightningFlash().get();
        // ---- 大型蓄力：离镜头最近的那位蓄力的人身边亮、周围暗 ----
        DawnvowAura nearest = null;
        Vec3 chest = null;
        double best = Double.MAX_VALUE;
        for (DawnvowAura aura : DawnvowClient.auraList()) {
            Entity body = mc.level.getEntity(aura.entity);
            if (body == null) continue;
            Vec3 at = body.getPosition(pt).add(0, body.getBbHeight() * .55, 0);
            double d = eye.distanceTo(at);
            if (d < best) {
                best = d;
                nearest = aura;
                chest = at;
            }
        }
        if (nearest != null) {
            float k = nearest.spotlight(now);
            float near = 1 - VfxParts.smooth(NEAR, FAR, (float) best);
            look.dark = CHARGE_DARK * k * near;
            look.lift = .32f * k * near;
            look.spotA = chest;
            look.radiusA = nearest.spotRadius(now);
        }
        // ---- 巨型剑气：离镜头最近的那一道 ----
        ExcaliburWave wave = null;
        best = Double.MAX_VALUE;
        for (ExcaliburWave w : DawnvowClient.waveList()) {
            double d = eye.distanceTo(w.end());
            if (d < best) {
                best = d;
                wave = w;
            }
        }
        if (wave == null) {
            if (step) {
                shownBeat = -1;
                beatWave = null;
            }
            return look;
        }
        Vec3 end = wave.end();
        float near = 1 - VfxParts.smooth(BURST_NEAR, BURST_FAR, (float) best);
        float age = (float) (now - wave.arrive());
        if (age < 0) {
            // 剑气还在走：周围保持蓄力时的暗，剑气头照亮它经过的地面
            look.dark = Math.max(look.dark, CHARGE_DARK * near);
            look.lift = Math.max(look.lift, .25f * near);
            look.spotB = wave.headAt(now).add(0, 1, 0);
            look.radiusB = 9;
            stepBeat(wave, -1, step);
            return look;
        }
        int pillar = SwordWave.PILLAR_DELAY;
        float dark = Mth.lerp(VfxParts.smooth(0, DawnvowBurst.BLACK, age), CHARGE_DARK, BURST_DARK);
        // 光柱冲起后很快亮回来：光柱的火焰是半透明的，背后是远处的天空，暗着的话火焰也会跟着发灰
        look.dark = Math.max(look.dark, dark * (1 - VfxParts.smooth(pillar, pillar + ExcaliburWave.AFTER, age)) * near);
        // 地上的一团光：扁扁的一片，鼓成半球；最后猛地涨大，扑过镜头
        float appear = VfxParts.smooth(0, 2, age), swell = VfxParts.smooth(1, DawnvowBurst.BLACK, age);
        float a = 2.5f + 3.5f * swell, b = a * (.14f + .82f * swell);
        float pulse = 1 + .04f * Mth.sin(age * 2.2f) * VfxParts.smooth(DawnvowBurst.BLACK - 4, DawnvowBurst.BLACK, age);
        a *= pulse;
        b *= pulse;
        // 照亮地面、光晕的范围按涨大之前的穹顶算：扑过镜头时周围仍是黑的，人是穹顶前的剪影
        look.light = a * Math.max(appear, .05f);
        float expand = VfxParts.smooth(DawnvowBurst.EXPAND, DawnvowBurst.WHITE, age);
        if (expand > 0) {
            float reach = Math.max(a, (float) eye.distanceTo(end) + 10);
            float e = expand * expand;
            a = Mth.lerp(e, a, reach);
            b = Mth.lerp(e, b, a);
        }
        look.dome = end;
        look.a = a * Math.max(appear, .05f);
        look.b = Math.max(.3f, b * appear);
        look.white = .6f + .4f * swell;
        look.halo = (.55f + .45f * swell) * appear * (1 - expand) * (1 - expand);
        look.alpha = appear * (1 - VfxParts.smooth(pillar - .5f, pillar + 1, age));
        look.ground = .9f * appear * (1 - expand) * (1 - VfxParts.smooth(pillar, pillar + 6, age)) * near;
        look.expand = expand;
        boolean pillared = age >= pillar;
        look.spotB = end;
        look.column = pillared;
        // 光柱冲起后整根光柱和它甩开的火焰都在亮区里（只看水平距离）
        look.radiusB = pillared ? 28 : 4 + 2 * look.light;
        // 黑色爆发：先炸满画面，再被外围的亮光向内压回穹顶边上
        if (age >= DawnvowBurst.BLACK - .5f && age < DawnvowBurst.EXPAND) {
            float domeR = screenRadius(mc, eye, end, Math.max(look.a, look.b));
            float burst = VfxParts.smooth(DawnvowBurst.BLACK, DawnvowBurst.BLACK + 1.5f, age);
            float in = VfxParts.smooth(DawnvowBurst.BLACK + 1, DawnvowBurst.CONTRAST, age);
            float rest = domeR * 1.6f + .05f;
            look.front = Mth.lerp(in, Mth.lerp(burst, domeR * 1.1f, 2.6f), rest);
            look.black = VfxParts.smooth(DawnvowBurst.BLACK - .5f, DawnvowBurst.BLACK + .3f, age)
                    * (1 - VfxParts.smooth(DawnvowBurst.EXPAND - .6f, DawnvowBurst.EXPAND, age)) * near * (calm ? .5f : 1);
            look.rim = 1;
        }
        // 几何黑白闪：离得太远、或者玩家关掉了闪烁时不放
        int due = age >= DawnvowBurst.CONTRAST ? (int) Math.floor((age - DawnvowBurst.CONTRAST) / DawnvowBurst.BEAT) : -1;
        int shown = calm || near < .5f ? -1 : stepBeat(wave, due, step);
        if (shown >= 0 && shown < DawnvowBurst.BEATS.length) {
            look.beat = DawnvowBurst.BEATS[shown];
            look.seed = wave.seed() + shown * 17;
        }
        // 极亮：画面整个白掉，光柱冲起后很快退去，露出光柱
        float gone = VfxParts.smooth(pillar, pillar + 6, age);
        look.flash = VfxParts.smooth(DawnvowBurst.WHITE - 1, pillar - 1, age) * (1 - gone) * (1 - gone) * near * (calm ? .5f : 1);
        return look;
    }

    /** 黑白闪往前走：一帧最多一拍；停住时钟截图时直接用该放的那一拍。返回这一帧放第几拍。 */
    private static int stepBeat(ExcaliburWave wave, int due, boolean step) {
        if (DawnvowClient.pinned()) return due;
        if (!step) return shownBeat;
        if (wave != beatWave) {
            beatWave = wave;
            shownBeat = -1;
            beatsShown = 0;
        }
        if (due < 0) shownBeat = -1;
        else if (due > shownBeat && ++shownBeat < DawnvowBurst.BEATS.length) beatsShown++;
        return shownBeat;
    }

    /** 穹顶在画面上看上去多大（半径，按画面高度）。 */
    private static float screenRadius(Minecraft mc, Vec3 eye, Vec3 center, float radius) {
        double d = Math.max(1, eye.distanceTo(center));
        double half = Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
        return (float) Mth.clamp(radius / (2 * d * half), .02, 1.2);
    }

    private static void pass(Minecraft mc, Camera camera, Matrix4f viewProjection, RenderTarget depth, Look look, double now) {
        Vec3 eye = camera.position();
        RenderTarget main = mc.getMainRenderTarget();
        boolean zeroToOne = RenderSystem.getDevice().isZZeroToOne();
        // 穹顶在画面上的位置；在镜头身后时退回画面中央
        float sx = .5f, sy = .5f, facing = .3f;
        Vec3 dome = look.dome == null ? eye.add(0, -1000, 0) : look.dome;
        if (look.dome != null) {
            Vec3 mid = dome.add(0, Math.min(look.b, look.light) * .5, 0);
            var f = camera.forwardVector();
            Vec3 to = mid.subtract(eye);
            double dot = to.lengthSqr() < 1e-6 ? 1 : new Vec3(f.x(), f.y(), f.z()).dot(to.normalize());
            if (dot > 0) {
                Vec3 ndc = mc.gameRenderer.projectPointToScreen(mid);
                sx = (float) (ndc.x * .5 + .5);
                sy = (float) (ndc.y * .5 + .5);
                facing = (float) Mth.clamp((dot + .3) / .9, .3, 1);
            }
        }
        Vec3 a = look.spotA == null ? Vec3.ZERO : look.spotA.subtract(eye);
        Vec3 b = look.spotB == null ? Vec3.ZERO : look.spotB.subtract(eye);
        Vec3 c = dome.subtract(eye);
        float beat = look.beat == null ? 0 : switch (look.beat) {
            case INVERT -> 1;
            case STARS_ON_WHITE -> 2;
            case STARS_ON_BLACK -> 3;
        };
        RenderTarget target = target(main);
        int layers = layers(main);
        lastLayers = layers;
        // 用半透明特效画完时的深度（有玩家的身体），留不到时用 PostVfx 留的半透明之前的深度
        if (depthCaptured && depthLayer != null && depthLayer.width == main.width && depthLayer.height == main.height) depth = depthLayer;
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension dawnvow", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        float seconds = (float) (now % 72000 / 20);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(new Matrix4f(viewProjection).invert())
                    .putVec4((float) a.x, (float) a.y, (float) a.z, look.spotA == null ? 0 : look.radiusA)
                    .putVec4((float) b.x, (float) b.y, (float) b.z, look.spotB == null ? 0 : look.radiusB)
                    .putVec4(look.dark, look.lift, look.ground, look.column ? 1 : 0)
                    .putVec4((float) c.x, (float) c.y, (float) c.z, look.dome == null ? 0 : look.a)
                    .putVec4(look.b, look.white, look.halo * facing, look.alpha)
                    .putVec4(look.front, look.black, look.rim, look.expand)
                    .putVec4(beat, look.seed, look.flash, 1)
                    .putVec4(sx, sy, zeroToOne ? 1 : 0, facing)
                    .putVec4((float) main.width / main.height, look.light, seconds, main.height)
                    .putVec4(layers, 0, 0, 0);
        }
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var noise = mc.getTextureManager().getTexture(PostVfx.logos("ward/noise"));
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension dawnvow", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("Dawnvow", uniforms.currentBuffer());
            pass.bindTexture("InSampler", target.getColorTextureView(), linear);
            pass.bindTexture("DepthSampler", depth.getDepthTextureView(), nearest);
            pass.bindTexture("HandDepthSampler", main.getDepthTextureView(), nearest);
            pass.bindTexture("NoiseSampler", noise.getTextureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
            // 没留到的那几份拿副本顶上，着色器按 Layers 不去读它们
            for (int i = 0; i < 3; i++) {
                RenderTarget layer = layers == 1 || layers == 2 && i == 0 ? LAYERS[i] : target;
                pass.bindTexture(new String[] {"OpaqueSampler", "TransSampler", "WeatherSampler"}[i], layer.getColorTextureView(), nearest);
            }
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    private static RenderTarget target(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget("logos_dimension dawnvow", main.width, main.height, false);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }
}
