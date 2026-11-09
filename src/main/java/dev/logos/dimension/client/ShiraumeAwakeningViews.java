package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.shiraume.ShiraumeClient;
import dev.logos.dimension.client.shiraume.ShiraumeTitle;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.fox.FoxSanctuaryStructure;
import dev.logos.dimension.fox.SanctuaryVaultPiece;
import dev.logos.dimension.shiraume.Shiraume;
import dev.logos.dimension.shiraume.ShiraumeCue;
import dev.logos.dimension.shiraume.ShiraumeFight;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 白梅觉醒的实机场景（本地开发用，docs/SHIRAUME_BOSS.md 第 2 节）：在真实的地下封印之间里，玩家站在封印杭那一圈以内，
 * 一根根拆掉九根封印杭（每根一下色散），看红光蔓延、两次震动、杀生石炸开、玩家被挪到底座外面、白梅跪坐、起身凝刀、
 * 标题，最后开打。逐段截图并检查状态。和 {@code fox_vault_views} 一样强制每处聚落都带地下场地。
 */
@LDLRegisterClient(name = "shiraume_awakening", group = "shiraume", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ShiraumeAwakeningViews implements UIScenario {
    private static SanctuaryVaultPiece vault;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("shiraume").defaultTimeoutMs(400000).scenarioTimeoutMs(1_200_000);
    }

    private static ServerLevel level(com.lowdragmc.lowdraglib2.uitest.ServerContext ctx) {
        return ctx.server().getLevel(LogosDimension.CLOUDSEA);
    }

    private static Shiraume boss(ServerLevel level) {
        Vec3 c = Vec3.atBottomCenterOf(vault.sealStone());
        return level.getEntitiesOfClass(Shiraume.class, new AABB(c, c).inflate(40)).stream().findFirst().orElse(null);
    }

    private static ShiraumeFight.State state(ServerLevel level) {
        return ShiraumeFight.state(level, vault.sealStone().asLong());
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("difficulty normal").runCommand("weather clear")
                .step("language", ctx -> {
                    // 标题、提示用中文拍（维护者玩的是中文）
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected("zh_cn");
                    mc.options.languageCode = "zh_cn";
                    mc.reloadResourcePacks();
                })
                .ticks(100)
                .step("camera", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.options.renderDistance().set(12);
                    mc.options.fov().set(75);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                })
                .server("locate", ctx -> {
                    var level = level(ctx);
                    level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, ctx.server());
                    String forced = System.getProperty("logos.foxPlan", System.getenv("LOGOS_FOX_PLAN"));
                    FoxSanctuaryStructure.devPlan = forced == null ? -1 : Integer.parseInt(forced.trim());
                    FoxSanctuaryStructure.devVault = 1;
                    var start = FoxSanctuaryViews.locate(ctx.server());
                    if (start == null) throw new IllegalStateException("没找到神狐聚落");
                    for (var piece : start.getPieces()) if (piece instanceof SanctuaryVaultPiece v) vault = v;
                    if (vault == null) throw new IllegalStateException("聚落没有地下封印场地");
                    level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK), 6000);
                    ShiraumeFight.reset(level);
                    Vec3 c = Vec3.atBottomCenterOf(vault.sealStone().below(2));
                    ctx.player().teleportTo(level, c.x, c.y + 40, c.z, java.util.Set.of(), 0, 90, false);
                })
                .ticks(900)
                // 站在封印杭那一圈以内（半径 9），面朝杀生石
                .server("stand inside", ctx -> {
                    var level = level(ctx);
                    var player = ctx.player();
                    player.setGameMode(GameType.SURVIVAL);
                    Vec3 c = Vec3.atBottomCenterOf(vault.sealStone().below(2));
                    Vec3 at = c.add(0, 0, -9);
                    player.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), 0, 5, false);
                    player.setNoGravity(false);
                    var attribute = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
                    attribute.addOrReplacePermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(LogosDimension.id("shiraume_test"), 380,
                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
                    player.setHealth(player.getMaxHealth());
                })
                .ticks(40)
                .screenshot("awakening_before")
                // 一根根拆封印杭：每根色散一下；最后一根断开时开始觉醒
                .server("break seals", ctx -> {
                    var level = level(ctx);
                    var player = ctx.player();
                    int broken = 0;
                    for (BlockPos p : vault.seals()) {
                        if (!level.getBlockState(p).is(FoxWildsBlocks.SEAL_STAKE.get())) continue;
                        player.gameMode.destroyBlock(p.above());
                        broken++;
                    }
                    System.out.println("[shiraume] broke " + broken + " seal stakes, state " + state(level));
                    if (state(level) != ShiraumeFight.State.AWAKENING) throw new IllegalStateException("拆完封印没有开始觉醒：" + state(level));
                })
                .check("每根都有色散，红光开了", ctx -> ShiraumeClient.received(ShiraumeCue.ABERRATION) >= 9 && ShiraumeClient.received(ShiraumeCue.MOOD) > 0)
                .ticks(50)
                .screenshot("awakening_red_halos")
                .waitUntilServer("第一次震动之后", ctx -> state(level(ctx)) == ShiraumeFight.State.AWAKENING
                        && ctx.server().overworld().getGameTime() > 0 && boss(level(ctx)) == null && ShiraumeClient.received(ShiraumeCue.SHAKE) >= 1)
                .waitUntilServer("杀生石炸开，白梅跪坐", ctx -> boss(level(ctx)) != null)
                .server("relocated and cleared", ctx -> {
                    var level = level(ctx);
                    Vec3 c = Vec3.atBottomCenterOf(vault.sealStone().below(2));
                    double flat = Math.hypot(ctx.player().getX() - c.x, ctx.player().getZ() - c.z);
                    int left = 0;
                    // 悬着的狐火是看不见的光源方块，不算
                    for (var group : SanctuaryVaultPiece.Removable.values()) for (BlockPos p : vault.blocks(group))
                        if (!level.getBlockState(p).isAir() && !level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.LIGHT)) left++;
                    Shiraume b = boss(level);
                    System.out.println("[shiraume] player at r=" + flat + " left blocks=" + left + " boss move=" + b.move() + " scripted=" + b.scripted());
                    if (flat < 15) throw new IllegalStateException("太近的玩家没有挪到底座外面：" + flat);
                    if (left != 0) throw new IllegalStateException("杀生石、石台或底座没拆干净：" + left);
                    if (!b.scripted() || !b.move().equals("seiza")) throw new IllegalStateException("白梅没有在过场里跪坐：" + b.move());
                })
                .ticks(30)
                .screenshot("awakening_seiza_wide")
                .ticks(40)
                .screenshot("awakening_seiza_medium")
                .waitUntilServer("起身", ctx -> boss(level(ctx)).move().equals("rise"))
                .ticks(38)
                .screenshot("awakening_rise_manifest")
                .ticks(40)
                .screenshot("awakening_face")
                .waitUntilServer("标题", ctx -> ShiraumeClient.received(ShiraumeCue.TITLE) > 0)
                .step("hold title", ctx -> ShiraumeTitle.hold(30))
                .ticks(40)
                .screenshot("awakening_title")
                .step("release title", ctx -> ShiraumeTitle.hold(-1))
                .waitUntilServer("开打", ctx -> state(level(ctx)) == ShiraumeFight.State.PHASE_ONE)
                .check("开打时她不在过场里", ctx -> Minecraft.getInstance().level.getEntitiesOfClass(Shiraume.class,
                        new AABB(Minecraft.getInstance().player.blockPosition()).inflate(48)).stream().noneMatch(Shiraume::scriptedClient))
                .ticks(60)
                .screenshot("awakening_fight")
                .teardown("restore", ctx -> {
                    Minecraft.getInstance().getLanguageManager().setSelected("en_us");
                    Minecraft.getInstance().options.languageCode = "en_us";
                    Minecraft.getInstance().options.hideGui = false;
                    FoxSanctuaryStructure.devVault = -1;
                    ShiraumeTitle.hold(-1);
                    ShiraumeClient.clear();
                });
    }
}
