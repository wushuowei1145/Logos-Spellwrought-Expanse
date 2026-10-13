package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.*;
import dev.logos.dimension.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Short, real-client terrain inspections, including a sky pass usable with shader packs. */
public abstract class AlchemicalViews implements UIScenario {
    protected volatile BlockPos viewedGround;
    protected abstract String shot();
    protected AlchemicalLayout.Region region() { return AlchemicalLayout.Region.AETHER; }
    protected Vec3 camera(AlchemicalChunkGenerator gen, net.minecraft.server.level.ServerLevel level) {
        var ground = DimensionTravel.findLanding(level, gen.terrain().layout().center(0, 0), region());
        if (ground == null) throw new IllegalStateException("No safe viewpoint in " + region());
        viewedGround = BlockPos.containing(ground).below();
        return ground.add(0, 10, 0);
    }
    protected float yaw() { return 55; }
    protected float pitch() { return 18; }
    protected int settleTicks() { return 150; }
    @Override public void configure(ScenarioOptions o) {
        o.tags("logos_dimension", "terrain").guiScale(2).defaultTimeoutMs(45_000).scenarioTimeoutMs(90_000);
    }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
                .step("terrain camera settings", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.hideGui = true;
                    mc.options.renderDistance().set(16);
                })
                .server("enter real generated dimension", ctx -> {
                    var p = ctx.player();
                    if (!p.level().dimension().equals(LogosDimension.CRUCIBLE) && !DimensionTravel.enter(p))
                        throw new IllegalStateException("Dimension entry failed");
                    var gen = (AlchemicalChunkGenerator) p.level().getChunkSource().getGenerator();
                    var at = camera(gen, p.level());
                    p.getAbilities().flying = true; p.onUpdateAbilities();
                    if (!DimensionTravel.teleport(p, p.level(), at, yaw(), pitch()))
                        throw new IllegalStateException("Viewpoint teleport failed");
                })
                .checkServer("viewpoint's terrain is generated", ctx -> !ctx.player().level().getBlockState(viewedGround).isAir())
                .waitUntil("client received the Crucible", ctx -> {
                    var level = Minecraft.getInstance().level;
                    return level != null && level.dimension().equals(LogosDimension.CRUCIBLE) && !level.getBlockState(viewedGround).isAir();
                })
                .runCommand("alchemy phase albedo").ticks(settleTicks())
                .check("actual terrain has arrived", ctx -> !Minecraft.getInstance().level.getBlockState(viewedGround).isAir())
                .screenshot(shot());
        extra(s);
        s.teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
    protected void extra(ScenarioBuilder s) {}

    @LDLRegisterClient(name="crucible_aether", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Aether extends AlchemicalViews { protected String shot() { return "aether_valley"; } }
    @LDLRegisterClient(name="crucible_sulfur", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Sulfur extends AlchemicalViews {
        protected String shot() { return "sulfur_fissures"; }
        protected AlchemicalLayout.Region region() { return AlchemicalLayout.Region.SULFUR; }
    }
    @LDLRegisterClient(name="crucible_mercury", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Mercury extends AlchemicalViews {
        protected String shot() { return "mercury_basin"; }
        protected AlchemicalLayout.Region region() { return AlchemicalLayout.Region.MERCURY; }
    }
    @LDLRegisterClient(name="crucible_salt", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Salt extends AlchemicalViews {
        protected String shot() { return "salt_crystals"; }
        protected AlchemicalLayout.Region region() { return AlchemicalLayout.Region.SALT; }
    }
    @LDLRegisterClient(name="crucible_island", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Island extends AlchemicalViews {
        protected String shot() { return "island_reserved_plateau"; }
        protected Vec3 camera(AlchemicalChunkGenerator gen, net.minecraft.server.level.ServerLevel level) {
            var center = gen.terrain().layout().center(0, 0);
            int top = gen.terrain().column(center.x(), center.z()).islandTop();
            viewedGround = new BlockPos(center.x(), top, center.z());
            return new Vec3(center.x(), top + 14, center.z());
        }
    }
    @LDLRegisterClient(name="crucible_retort", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Retort extends AlchemicalViews {
        protected String shot() { return "inverted_glass_retort"; }
        protected float yaw() { return 90; }
        protected float pitch() { return 10; }
        protected Vec3 camera(AlchemicalChunkGenerator gen, net.minecraft.server.level.ServerLevel level) {
            var center = gen.terrain().layout().center(0, 0);
            viewedGround = new BlockPos(center.x() + 6040, 280, center.z() + 1200);
            level.getChunk(viewedGround);
            return new Vec3(center.x() + 6110, 310, center.z() + 1200);
        }
    }
    @LDLRegisterClient(name="crucible_sky", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Sky extends AlchemicalViews {
        protected String shot() { return "sky_albedo"; }
        protected float pitch() { return -70; }
        protected void extra(ScenarioBuilder s) {
            s.runCommand("alchemy phase citrinitas").ticks(25).screenshot("sky_citrinitas")
                    .runCommand("alchemy phase rubedo").ticks(25).screenshot("sky_rubedo")
                    .runCommand("alchemy phase nigredo").ticks(25).screenshot("sky_nigredo");
        }
    }
    @LDLRegisterClient(name="crucible_haunt", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Haunt extends AlchemicalViews {
        protected String shot() { return "under_island_shadow"; }
        protected int settleTicks() { return 70; }
        protected float pitch() { return 0; }
        protected Vec3 camera(AlchemicalChunkGenerator gen, net.minecraft.server.level.ServerLevel level) {
            var center = gen.terrain().layout().center(0,0);
            IslandProgress.of(level).setDefeated(center, false);
            var col = gen.terrain().column(center.x() + 24, center.z() + 24);
            viewedGround = new BlockPos(center.x() + 24, col.ground(), center.z() + 24);
            return viewedGround.above().getBottomCenter();
        }
        protected void extra(ScenarioBuilder s) {
            s.check("haunted fog active", ctx -> IslandHauntClient.density() > .7)
                    .check("apparition appeared", ctx -> IslandHauntClient.shades() > 0)
                    .server("execute Boss completion command as the actual player", ctx -> {
                        try {
                            int result = ctx.server().getCommands().getDispatcher().execute("alchemy boss defeated",
                                    ctx.server().createCommandSourceStack().withEntity(ctx.player()));
                            if (result != 1) throw new IllegalStateException("Boss command failed");
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) { throw new IllegalStateException(error); }
                    }).ticks(60)
                    .check("Boss victory clears fog and shadows", ctx -> IslandHauntClient.density() < .05 && IslandHauntClient.shades() == 0)
                    .screenshot("under_island_after_boss");
            s.teardownServer("reset test island progression", ctx -> {
                var p = ctx.player(); var gen = (AlchemicalChunkGenerator) p.level().getChunkSource().getGenerator();
                IslandProgress.of(p.level()).setDefeated(gen.terrain().layout().center(0,0), false);
            });
        }
    }
}
