package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import net.minecraft.world.phys.Vec3;

/** Four actual views of adjacent limestone blocks, including the formerly recessed sides. */
@LDLRegisterClient(name="limestone_closed_faces",group="limestone_fix",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public final class LimestoneSeamView extends JourneyArtViews {
    @Override protected int scene(){return 5;}
    @Override protected String shot(){return "limestone_closed_front";}
    @Override public void define(ScenarioBuilder s){
        super.define(s);
        s.server("back face",ctx->DimensionTravel.teleport(ctx.player(),ctx.player().level(),new Vec3(0,232,-9),0,0)).ticks(20).screenshot("limestone_closed_back")
            .server("side face",ctx->DimensionTravel.teleport(ctx.player(),ctx.player().level(),new Vec3(10,232,0),90,0)).ticks(20).screenshot("limestone_closed_side")
            .server("upper joints",ctx->DimensionTravel.teleport(ctx.player(),ctx.player().level(),new Vec3(1,238,1),180,80)).ticks(20).screenshot("limestone_closed_top");
    }
}
