package dev.logos.dimension;

import dev.logos.dimension.net.HauntPayload;


import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class UnderIslandHaunt {
    public static final TagKey<EntityType<?>> ISLAND_BOSSES = TagKey.create(Registries.ENTITY_TYPE, LogosDimension.id("island_bosses"));

    public static HauntPayload state(ServerLevel level,double x,double y,double z){
        if(!LogosDimension.isCrucible(level.dimension()))return HauntPayload.CLEAR;
        var center=NativeWorlds.center();
        double distance=Math.hypot(x-NativeWorlds.ISLAND_X,z-NativeWorlds.ISLAND_Z);
        if(distance>NativeWorlds.ISLAND_RADIUS+32||y>=NativeWorlds.ISLAND_TOP-72||IslandProgress.of(level).defeated(center))return HauntPayload.CLEAR;
        int ground=level.getMinY();
        for(int sample=NativeWorlds.ISLAND_TOP-73;sample>level.getMinY();sample--){
            var state=level.getBlockState(new net.minecraft.core.BlockPos((int)x,sample,(int)z));
            if(!state.isAir()&&state.blocksMotion()){ground=sample+1;break;}
        }
        float density=(float)(Math.clamp((NativeWorlds.ISLAND_RADIUS-distance)/18,0,1)*Math.clamp((y-ground)/6,0,1));
        return new HauntPayload(true,density,NativeWorlds.ISLAND_X,NativeWorlds.ISLAND_Z,NativeWorlds.ISLAND_RADIUS,ground+2,NativeWorlds.ISLAND_TOP-82);
    }
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        PacketDistributor.sendToPlayer(p, state(p.level(), p.getX(), p.getY(), p.getZ()));
    }
    public static void death(LivingDeathEvent event) {
        // LOGOS_DIMENSION_ISLAND_BOSS_HOOK: add the future Boss entity to this tag, no placeholder mob is registered.
        if (!(event.getEntity().level() instanceof ServerLevel level)
                || !event.getEntity().getType().builtInRegistryHolder().is(ISLAND_BOSSES)
                || !LogosDimension.isCrucible(level.dimension())) return;
        var boss = event.getEntity(); var center = NativeWorlds.center();
        if (Math.hypot(boss.getX()-NativeWorlds.ISLAND_X,boss.getZ()-NativeWorlds.ISLAND_Z)<=NativeWorlds.ISLAND_RADIUS)
            IslandProgress.of(level).setDefeated(center, true);
    }
    private UnderIslandHaunt() {}
}
