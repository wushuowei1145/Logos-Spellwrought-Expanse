package dev.logos.dimension.camera;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 运镜的服务端入口：方块、Boss 等想让玩家看什么，就在服务端组一个 {@link CameraShot} 调 {@link #play}。
 * 只有这个玩家的画面会动，玩家实体、判定和别的玩家都不受影响。用法和限制见 docs/CAMERA_SHOTS.md。
 */
public final class CameraShots {
    /** 镜头被方块挡住时和方块留的距离，近裁剪面才不会切进去。 */
    private static final double CLEARANCE = .35;

    private CameraShots() {}

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(CameraShotPayload.TYPE, CameraShotPayload.CODEC));
    }

    public static void play(ServerPlayer player, CameraShot shot) {
        if (player.connection.hasChannel(CameraShotPayload.TYPE)) PacketDistributor.sendToPlayer(player, new CameraShotPayload(shot));
    }

    /** 从 {@code from} 往 {@code to} 走，半路被方块挡住就停在它前面一点；不挡就是 {@code to}。 */
    public static Vec3 reach(Level level, Vec3 from, Vec3 to) {
        var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return to;
        double room = Math.max(0, hit.getLocation().distanceTo(from) - CLEARANCE);
        return from.add(to.subtract(from).normalize().scale(room));
    }

    /** {@code who} 头顶 {@code height} 格的机位，被上方的方块挡住就矮一些。 */
    public static Vec3 above(Entity who, double height) {
        Vec3 eye = who.getEyePosition();
        return reach(who.level(), eye, eye.add(0, height, 0));
    }
}
