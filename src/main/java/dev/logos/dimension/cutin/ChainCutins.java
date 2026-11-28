package dev.logos.dimension.cutin;

import dev.logos.dimension.companion.CompanionDef;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.Companions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 连携切入的服务端入口（docs/CHAIN_CUTIN.md）：{@link #play} 点名当前在玩家身边的同伴，给这个玩家放一次切入。
 * 现在只有画面，不打伤害、不挪人；怎么触发、打多少以后再定。
 */
public final class ChainCutins {
    /** 离玩家多远以内的同伴才算在场（格）。 */
    public static final double RANGE = 48;

    private ChainCutins() {}

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(ChainCutinPayload.TYPE, ChainCutinPayload.CODEC));
    }

    /** 在场的同伴：召唤在外面、活着、同一个世界、{@link #RANGE} 格以内，按同伴表的顺序。 */
    public static List<CompanionEntity> members(ServerPlayer owner) {
        List<CompanionEntity> out = new ArrayList<>();
        for (CompanionDef d : Companions.ALL) {
            CompanionEntity c = Companions.out(owner, d);
            if (c == null || !c.isAlive() || c.level() != owner.level() || c.distanceToSqr(owner) > RANGE * RANGE) continue;
            out.add(c);
            if (out.size() == ChainCutin.MAX_MEMBERS) break;
        }
        return out;
    }

    /** 给 {@code owner} 放一次切入；没有同伴在场时不放，返回 false。 */
    public static boolean play(ServerPlayer owner) {
        List<CompanionEntity> members = members(owner);
        if (members.isEmpty()) return false;
        ChainCutin cutin = new ChainCutin(owner.getId(), members.stream().map(CompanionEntity::getId).toList(), owner.getRandom().nextLong());
        if (owner.connection.hasChannel(ChainCutinPayload.TYPE)) PacketDistributor.sendToPlayer(owner, new ChainCutinPayload(cutin));
        return true;
    }
}
