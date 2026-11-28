package dev.logos.dimension.client.ultimate;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.companion.CompanionBookClient;
import dev.logos.dimension.client.companion.CompanionStyle;
import dev.logos.dimension.client.cutin.HeroCutin;
import dev.logos.dimension.ultimate.FinisherTemplate;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimatePayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 客户端这边的奥义（docs/ULTIMATES.md「按键」「画面」）：
 * <ul>
 *   <li>C（「同伴」一栏里可以改）：短按下令（准星对着的那一位，否则按队伍顺序的下一位）；按住超过 {@link ClientConfig#ULTIMATE_HOLD}
 *       打开号令轮盘（{@link UltimateWheelScreen}），甩到谁、松开就是谁；汇流里一按下就放玩家自己的最后一击。</li>
 *   <li>「同伴 1–4 的奥义」四个热键默认不绑，按在场的队伍顺序直接点名。</li>
 *   <li>服务端发来的状态喂给侧边小队（{@link UltimateHud}）和轮盘；开演时按演的人在她的档案里找画面（{@link UltimateShow}）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class UltimateClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * 奥义键默认是 C。改绑到和别的键同一个键时，别人有自己的场合并且这时是活的（Logos 化身时的「变回」、附身时的「本能」，默认都是 V），
     * 就让给它们；Logos 骑巨像时的「切换驾驶」不分场合，骑着巨像、两个键又是同一个时也让开。
     */
    static final IKeyConflictContext COMMANDING = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            var mc = Minecraft.getInstance();
            if (mc.screen != null || mc.player == null) return false;
            for (KeyMapping other : mc.options.keyMappings) {
                if (other == KEY || !other.getKey().equals(KEY.getKey())) continue;
                IKeyConflictContext context = other.getKeyConflictContext();
                if (context != KeyConflictContext.UNIVERSAL && context != this && context.isActive()) return false;
                if (COLOSSUS_MODE.equals(other.getName()) && ridingColossus(mc.player)) return false;
            }
            return true;
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    };
    /** Logos 骑巨像时切换驾驶的那个键。 */
    private static final String COLOSSUS_MODE = "key.logos.colossus_mode";
    public static final KeyMapping KEY = new KeyMapping("key.logos_dimension.ultimate", COMMANDING, InputConstants.Type.KEYSYM, InputConstants.KEY_C,
            CompanionBookClient.CATEGORY);
    public static final List<KeyMapping> DIRECT = List.of(direct(1), direct(2), direct(3), direct(4));

    private static UltimatePayloads.Status status = UltimatePayloads.Status.EMPTY;
    /** 收到状态时的客户端时刻（侧边小队按它往前推冷却、窗口）。 */
    private static long statusAt;
    private static int held;
    /** 奥义键一共被认出按下过几次（测试用）。 */
    private static int presses;
    private static boolean wheel, finisherSent;
    /** 缺魔时她那一格闪红：同伴 ID → 还剩几刻。 */
    private static final Map<String, Integer> FLASH = new HashMap<>();
    /** 没人能放时整队抖一下：还剩几刻。 */
    private static int shake;

    private record Running(UltimatePayloads.Cast cast, UltimateShow show, int[] age) {}

    private static final Map<Integer, Running> SHOWS = new HashMap<>();

    private UltimateClient() {}

    /** 骑着 Logos 的巨像（这时它那个键是切换驾驶）。 */
    static boolean ridingColossus(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) return false;
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
        return "logos".equals(id.getNamespace()) && id.getPath().contains("colossus");
    }

    private static KeyMapping direct(int n) {
        return new KeyMapping("key.logos_dimension.ultimate_" + n, InputConstants.UNKNOWN.getValue(), CompanionBookClient.CATEGORY);
    }

    @SubscribeEvent
    static void keys(RegisterKeyMappingsEvent event) {
        event.register(KEY);
        DIRECT.forEach(event::register);
    }

    @SubscribeEvent
    static void layers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CHAT, LogosDimension.id("ultimate_party"), UltimateHud::render);
    }

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(UltimatePayloads.Status.TYPE, (payload, context) -> context.enqueueWork(() -> receive(payload)));
        event.register(UltimatePayloads.Cast.TYPE, (payload, context) -> context.enqueueWork(() -> begin(payload)));
        event.register(UltimatePayloads.End.TYPE, (payload, context) -> context.enqueueWork(() -> stop(payload.performer(), payload.interrupted())));
        event.register(UltimatePayloads.Denied.TYPE, (payload, context) -> context.enqueueWork(() -> denied(payload)));
    }

    // ---------------------------------------------------------------- 状态

    /** 服务端最近一次发来的状态。 */
    public static UltimatePayloads.Status status() {
        return status;
    }

    /** 收到状态以后过了几刻（带帧内插值）。 */
    public static float since(float partial) {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : Math.max(0, level.getGameTime() - statusAt + partial);
    }

    static void receive(UltimatePayloads.Status next) {
        status = next;
        var level = Minecraft.getInstance().level;
        statusAt = level == null ? 0 : level.getGameTime();
    }

    /** 她那一格还要闪几刻红（缺魔）。 */
    public static int flash(String companion) {
        return FLASH.getOrDefault(companion, 0);
    }

    /** 整队还要抖几刻（没人能放）。 */
    public static int shake() {
        return shake;
    }

    static void denied(UltimatePayloads.Denied d) {
        var mc = Minecraft.getInstance();
        if (d.reason() == UltimatePayloads.Denied.NO_MANA) {
            FLASH.put(d.companion(), 20);
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_DEACTIVATE, .6f, .6f));
        } else {
            shake = 8;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), .5f, .5f));
        }
    }

    // ---------------------------------------------------------------- 按键

    /** 下令（空串：由服务端挑）。号令轮盘、测试也用。 */
    public static void order(String companion) {
        ClientPacketDistributor.sendToServer(new UltimatePayloads.Order(companion));
    }

    /** 按住多少刻算长按。 */
    static int holdTicks() {
        try {
            return Math.max(3, (int) Math.round(ClientConfig.ULTIMATE_HOLD.get() * 20));
        } catch (IllegalStateException notLoaded) {
            return 4;
        }
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        input(mc);
        FLASH.replaceAll((id, t) -> t - 1);
        FLASH.values().removeIf(t -> t <= 0);
        if (shake > 0) shake--;
        for (Running r : List.copyOf(SHOWS.values())) {
            boolean alive;
            try {
                alive = r.show.tick(r.cast, r.age[0]);
            } catch (RuntimeException error) {
                LOGGER.error("奥义画面 {} 出错，收起", r.cast.key(), error);
                alive = false;
            }
            r.age[0]++;
            if (!alive || r.age[0] > r.cast.length() + 40) stop(r.cast.performer(), false);
        }
    }

    private static void input(Minecraft mc) {
        while (KEY.consumeClick()) { /* 按下由 isDown 判断，点击计数不用 */ }
        UltimateChain.Phase phase = status.chainPhase();
        boolean down = mc.screen == null && KEY.isDown();
        if (down) {
            if (++held == 1) presses++;
            if (held == 1 && phase == UltimateChain.Phase.CONVERGED) {
                // 汇流里一按下就放，不等松手
                order("");
                finisherSent = true;
            } else if (!finisherSent && !wheel && held == holdTicks() && !status.members().isEmpty()) {
                wheel = true;
                mc.setScreen(new UltimateWheelScreen());
            }
        } else {
            // 松开：没到长按、也没开轮盘、不是汇流里那一下，就是短按下令。轮盘开着时松开由轮盘自己确认
            if (held > 0 && held < holdTicks() && !wheel && !finisherSent) order("");
            if (!(mc.screen instanceof UltimateWheelScreen)) {
                held = 0;
                wheel = false;
                finisherSent = false;
            }
        }
        for (int i = 0; i < DIRECT.size(); i++) {
            while (DIRECT.get(i).consumeClick()) {
                if (mc.screen != null) continue;
                List<UltimatePayloads.Member> members = status.members();
                if (phase == UltimateChain.Phase.CONVERGED) order("");
                else if (i < members.size()) order(members.get(i).id());
            }
        }
    }

    // ---------------------------------------------------------------- 画面

    /** 这一段的画面在谁的档案里：同伴的 {@link CompanionStyle#ultimate}，玩家的 {@link HeroCutin#finisher}。 */
    static UltimateShow.Factory factory(String key) {
        if (key.startsWith("hero/")) return HeroCutin.PLAYER.finisher(FinisherTemplate.byId(key.substring("hero/".length())));
        return CompanionStyle.of(key).ultimate();
    }

    static void begin(UltimatePayloads.Cast cast) {
        stop(cast.performer(), true);
        UltimateShow show;
        try {
            show = factory(cast.key()).begin(cast);
        } catch (RuntimeException error) {
            LOGGER.error("奥义画面 {} 开不了", cast.key(), error);
            show = null;
        }
        if (show != null) SHOWS.put(cast.performer(), new Running(cast, show, new int[1]));
    }

    static void stop(int performer, boolean interrupted) {
        Running r = SHOWS.remove(performer);
        if (r == null) return;
        try {
            r.show.end(r.cast, interrupted);
        } catch (RuntimeException error) {
            LOGGER.error("奥义画面 {} 收不起来", r.cast.key(), error);
        }
    }

    /** 奥义键一共被认出按下过几次（测试用：一按下就可能放出过场、把按键清掉，看这个数比看按住了几刻稳）。 */
    public static int presses() {
        return presses;
    }

    /** 奥义键已经按住了几刻（测试用：按下去、客户端看到了再松开，就是一次短按）。 */
    public static int held() {
        return held;
    }

    /**
     * 有奥义的画面正在放（本机看得到的任何一段）：这一帧 {@code PostVfx} 收几何，画面里交上去的发光特效（魔弹、光柱、法阵）
     * 才画得出来。没在放时不开，PostVfx 不跑。
     */
    public static boolean postVfx() {
        return !SHOWS.isEmpty();
    }

    /** 正在放的画面有几段（测试用）。 */
    public static int showing() {
        return SHOWS.size();
    }

    /** {@code performer} 正在放的画面；没有是 null（测试用）。 */
    public static @Nullable UltimateShow show(int performer) {
        Running r = SHOWS.get(performer);
        return r == null ? null : r.show;
    }

    /** 正在放的每一段的全屏后期（{@link UltimateShow#screen}），由 {@link UltimateFilmRenderer} 在过场叠层之前调。 */
    static void screens(float partial) {
        for (Running r : new ArrayList<>(SHOWS.values())) {
            try {
                r.show.screen(r.cast, r.age[0] + partial);
            } catch (RuntimeException error) {
                LOGGER.error("奥义画面 {} 的后期出错", r.cast.key(), error);
            }
        }
    }

    @SubscribeEvent
    static void frame(RenderFrameEvent.Pre event) {
        if (SHOWS.isEmpty()) return;
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        for (Running r : new ArrayList<>(SHOWS.values())) r.show.frame(r.cast, r.age[0] + partial);
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (Integer performer : List.copyOf(SHOWS.keySet())) stop(performer, true);
        status = UltimatePayloads.Status.EMPTY;
        FLASH.clear();
        shake = 0;
        held = 0;
        wheel = finisherSent = false;
    }
}
