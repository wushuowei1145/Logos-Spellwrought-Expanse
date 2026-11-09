package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 神狐打刀的刀光（docs/WEAPON_TRAILS.md）的实际试玩：一只站着不动的神狐对着隐身的靶子拔刀、连砍，
 * 从侧面、斜前方、背后三个角度截连招里每一刀的刀光，检查连砍时有刀光、拔刀、持刀待机、收刀时没有；
 * 缠上狐火后换成幽蓝的狐火刀光，缠上别的元素时换成那种元素的，白梅的淡粉狐火换成淡粉的。
 */
@LDLRegisterClient(name = "weapon_trails_fox", group = "weapon_trails_fox", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class FoxTrailView extends FoxCombatViews {
    /** 靶子放在刀够不着的地方：只为让神狐进入战斗、拔刀，砍不中就没有命中特效挡住刀光。 */
    private static final double FOX_Z = .5, TARGET_Z = 6.5;
    private static volatile boolean attacking;
    private static DivineFox serverFox;
    private static LivingEntity target;
    /** 出刀时、不该出光时看到的最多刀光条数（每帧量）。 */
    private static int attackPeak, quietPeak;
    private static volatile int sampling;
    private static boolean listening;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("weapon_trails").defaultTimeoutMs(60_000).scenarioTimeoutMs(300_000);
    }

    /** 每帧量刀光条数：{@code sampling} 为 1 记到出刀，为 2 记到不该出光。 */
    private static synchronized void listen() {
        if (listening) return;
        listening = true;
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> {
            int n = WeaponTrails.ribbons();
            if (sampling == 1) attackPeak = Math.max(attackPeak, n);
            else if (sampling == 2) quietPeak = Math.max(quietPeak, n);
        });
    }

    private static DivineFox clientFox() {
        var list = foxes();
        return list.isEmpty() ? null : list.getFirst();
    }

    private static boolean playing(String animation) {
        DivineFox fox = clientFox();
        return fox != null && playing(fox, animation);
    }

    /** 这只神狐的刀光现在的样式名（路径部分）；没有时为空。 */
    private static String style() {
        DivineFox fox = clientFox();
        var s = fox == null ? null : WeaponTrails.styleOf(FoxBladeTrail.key(fox.getId()));
        return s == null ? "" : s.id().getPath();
    }

    /** 服务端每刻：出刀期间一直按攻击（连段由招式状态机排队），神狐与靶子都不许走开。 */
    private static void drive(ServerLevel level) {
        if (serverFox == null || !serverFox.isAlive()) return;
        if (attacking) serverFox.katanaInput(Moveset.Input.ATTACK);
        serverFox.setYRot(0);
        serverFox.setYHeadRot(0);
        serverFox.setYBodyRot(0);
        if (target != null && target.isAlive()) {
            target.setPos(.5, FLOOR + 1, TARGET_Z);
            target.setDeltaMovement(Vec3.ZERO);
            target.setHealth(target.getMaxHealth());
        }
    }

    /** 这只神狐此刻在出光的时段里（真正的一刀）。 */
    private static boolean emitting() {
        DivineFox fox = clientFox();
        return fox != null && FoxBladeTrail.emitting(fox.getId());
    }

    /** 从一个角度看一轮连招，每一刀开始出光后一刻截一张（等待与截图另有各约 60 毫秒的稳定时间，截到时这一刀刚扫过、刀光还新）。 */
    private static ScenarioBuilder combo(ScenarioBuilder s, String angle, Vec3 eye, Vec3 look, int slashes) {
        s.server(angle + "镜头", ctx -> camera(ctx.player(), eye, look)).ticks(6)
                .step("开始记录", ctx -> sampling = 1)
                .server("出刀", ctx -> attacking = true);
        for (int i = 1; i <= slashes; i++) {
            String clip = "katana_slash_" + i;
            s.waitUntil("第" + i + "刀出光", ctx -> playing(clip) && emitting())
                    .ticks(1)
                    .screenshot(angle + "_slash_" + i);
        }
        return s.server("停手", ctx -> attacking = false)
                .step("停止记录", ctx -> sampling = 0)
                .waitUntil("回到持刀待机", ctx -> playing("katana_idle")).ticks(16);
    }

    @Override
    public void define(ScenarioBuilder s) {
        Vec3 side = new Vec3(3.0, FLOOR + 1.9, 1.0), sideLook = new Vec3(.5, FLOOR + 1.3, .8);
        Vec3 front = new Vec3(2.0, FLOOR + 1.8, 2.9), frontLook = new Vec3(.4, FLOOR + 1.25, .7);
        Vec3 back = new Vec3(-1.0, FLOOR + 2.8, -1.6), backLook = new Vec3(.6, FLOOR + 1.1, 1.2);
        begin(s).step("视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.fov().set(55);
                    var w = mc.getWindow();
                    w.setWindowed(1280, 720);
                    w.setWidth(1280);
                    w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720);
                    mc.gameRenderer.resize(1280, 720);
                    mc.resizeGui();
                    listen();
                    attackPeak = quietPeak = 0;
                    sampling = 0;
                })
                .server("草地、神狐与隐身的靶子", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    arena(level);
                    for (int x = -14; x <= 15; x++)
                        for (int z = -14; z <= 15; z++) level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), Blocks.GRASS_BLOCK.defaultBlockState());
                    attacking = false;
                    serverFox = fox(level, .5, FOX_Z, "outfit_miko", false);
                    serverFox.setKatana(0);
                    serverFox.giveBow(ItemStack.EMPTY);
                    var husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    husk.snapTo(.5, FLOOR + 1, TARGET_Z, 180, 0);
                    // 不能设成无敌：无敌的生物不算敌人，神狐的目标会被清掉、不拔刀；改为每刻回满血
                    husk.setNoAi(true);
                    husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                    husk.setHealth(1000);
                    husk.setSilent(true);
                    husk.setPersistenceRequired();
                    husk.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 600, 0, false, false));
                    level.addFreshEntity(husk);
                    target = husk;
                    watch(FoxTrailView::drive);
                    camera(ctx.player(), side, sideLook);
                })
                .waitUntil("神狐同步", ctx -> clientFox() != null).ticks(20)
                .step("记录不该出光的时候", ctx -> sampling = 2)
                .server("发现目标、拔刀", ctx -> {
                    serverFox.setTarget(target);
                    serverFox.drawKatana();
                })
                .waitUntil("拔刀", ctx -> playing("katana_draw"))
                .waitUntil("持刀待机", ctx -> playing("katana_idle")).ticks(20)
                .step("停止记录", ctx -> sampling = 0)
                .check("拔刀与持刀待机不出刀光", ctx -> quietPeak == 0 && WeaponTrails.ribbons() == 0)
                .screenshot("idle");
        combo(s, "side", side, sideLook, 4)
                .check("连砍时出刀光", ctx -> attackPeak > 0)
                .check("神狐的刀光是物理的", ctx -> "physical".equals(style()))
                .check("回到持刀待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0);
        combo(s, "front", front, frontLook, 4)
                .check("回到持刀待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0);
        combo(s, "back", back, backLook, 3);
        // 狐火缠绕：幽蓝的狐火刀光（不按奥秘换成紫色）
        s.server("缠上狐火", ctx -> {
                    if (!LogosMagicBridge.cast(serverFox, LogosMagicBridge.FOX_FIRE_WRAP, false, 100, target))
                        throw new IllegalStateException("狐火缠绕施放失败");
                })
                .waitUntil("客户端看得见狐火缠绕", ctx -> clientFox() != null && FoxfireWraps.flame(clientFox()) == FoxfireFlame.KITSUNE)
                .ticks(10);
        combo(s, "foxfire_front", front, frontLook, 4)
                .check("狐火缠绕换成幽蓝的狐火刀光", ctx -> "foxfire_kitsune".equals(style()));
        combo(s, "foxfire_side", side, sideLook, 2);
        // 别的元素的缠绕：按元素换（物理不固定）
        s.server("换成寒霜缠绕", ctx -> ManaWrap.wrap(serverFox, Element.FROST, 4, 600)).ticks(10);
        combo(s, "frost_front", front, frontLook, 2)
                .check("寒霜缠绕换成寒霜刀光", ctx -> "frost".equals(style()));
        // 白梅给的淡粉狐火
        s.server("换成白梅的淡粉狐火", ctx -> FoxfireWraps.wrap(serverFox, FoxfireFlame.SHIRAUME, 3, 600))
                .waitUntil("客户端看得见淡粉狐火", ctx -> clientFox() != null && FoxfireWraps.flame(clientFox()) == FoxfireFlame.SHIRAUME)
                .ticks(10);
        combo(s, "shiraume_front", front, frontLook, 2)
                .check("淡粉狐火换成淡粉的狐火刀光", ctx -> "foxfire".equals(style()));
        // 战斗结束：收刀不出光
        s.server("解除缠绕、战斗结束", ctx -> {
                    ManaWrap.wrap(serverFox, Element.PHYSICAL, 0, 0);
                    serverFox.setTarget(null);
                })
                .step("记录不该出光的时候", ctx -> {
                    quietPeak = 0;
                    sampling = 2;
                })
                .waitUntil("警戒结束、收刀", ctx -> playing("katana_sheathe"))
                .waitUntil("收刀播完", ctx -> clientFox() != null && clientFox().npcCombatMove() == null)
                .step("停止记录", ctx -> sampling = 0)
                .check("收刀不出刀光", ctx -> quietPeak == 0 && WeaponTrails.ribbons() == 0)
                .teardown("恢复", ctx -> {
                    attacking = false;
                    sampling = 0;
                    watch(null);
                    serverFox = null;
                    target = null;
                    Minecraft.getInstance().options.hideGui = false;
                });
    }
}
