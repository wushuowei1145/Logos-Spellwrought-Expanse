package dev.logos.dimension.gametest;

import dev.logos.dimension.instance.CauldronWorlds;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Isolated GameTest fixture, not an additional production dimension or survival entrance. */
public final class NativeTestWorlds {
    private static MinecraftServer owner;
    private static UUID fixture;
    public static ServerLevel crucible(MinecraftServer server){
        if(owner!=server){owner=server;fixture=UUID.randomUUID();}
        return CauldronWorlds.load(server,fixture);
    }
    private NativeTestWorlds(){}
}
