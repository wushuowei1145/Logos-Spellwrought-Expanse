package dev.logos.dimension.elven.blight;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.*;
import java.util.*;
import net.minecraft.server.level.*;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 五秒入场：服务端定战斗时长、发镜头和前景提示，并只保护正在观看的玩家。 */
@EventBusSubscriber(modid=LogosDimension.ID)
public final class QueenEntrances {
    public static final int TICKS=100;
    private record Viewing(ServerLevel level, UUID queen, long until) {}
    private static final Map<UUID,Viewing> VIEWERS=new HashMap<>();
    private QueenEntrances(){}
    public static void register(IEventBus bus){
        bus.addListener((RegisterPayloadHandlersEvent e)->e.registrar("1").playToClient(QueenEntrancePayload.TYPE,QueenEntrancePayload.CODEC));
    }
    public static void play(CorruptedElfQueen queen, net.minecraft.core.BlockPos heart){
        if(!(queen.level() instanceof ServerLevel level))return;
        Vec3 front=queen.getLookAngle().multiply(1,0,1).normalize();
        Vec3 from=CameraShots.reach(level,queen.position().add(0,2,0),queen.position().add(front.scale(10)).add(front.cross(new Vec3(0,1,0)).scale(5)).add(0,5,0));
        var shot=CameraShot.of(CameraPoint.world(from),CameraPoint.on(queen,new Vec3(0,5.5,0))).timing(18,62,20).letterbox();
        for(ServerPlayer p:level.players()){
            if(!p.isAlive()||p.isSpectator()||p.distanceToSqr(queen)>24*24||!queen.hasLineOfSight(p))continue;
            // 没安装同版通道的连接不锁镜头，也不留下观看保护。
            if(!p.connection.hasChannel(QueenEntrancePayload.TYPE))continue;
            VIEWERS.put(p.getUUID(),new Viewing(level,queen.getUUID(),level.getGameTime()+TICKS));
            CameraShots.play(p,shot);
            PacketDistributor.sendToPlayer(p,new QueenEntrancePayload(queen.getId(),heart,front));
        }
    }
    public static boolean protectedNow(ServerPlayer p){
        Viewing v=VIEWERS.get(p.getUUID());
        if(v==null)return false;
        var boss=v.level.getEntity(v.queen);
        return p.isAlive()&&p.level()==v.level&&v.level.getGameTime()<v.until&&boss!=null&&boss.isAlive();
    }
    @SubscribeEvent static void protect(LivingIncomingDamageEvent e){
        if(e.getEntity() instanceof ServerPlayer p&&protectedNow(p)&&!e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY))e.setCanceled(true);
    }
    @SubscribeEvent static void tick(LevelTickEvent.Post e){
        if(!(e.getLevel() instanceof ServerLevel level))return;
        VIEWERS.entrySet().removeIf(entry->{
            var v=entry.getValue();
            if(v.level!=level)return false;
            var p=level.getServer().getPlayerList().getPlayer(entry.getKey());
            return p==null||!protectedNow(p);
        });
    }
}
