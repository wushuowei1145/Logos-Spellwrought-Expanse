package dev.logos.dimension.mixin;

import dev.logos.dimension.item.SpearShieldGuard;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版已切回服务端线程、确认客户端加载完成后，解析反手矛盾搭配的原版戳刺包。 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SpearShieldPacketMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;getAction()Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;"), cancellable = true)
    private void guardedOffhandStab(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.STAB && SpearShieldGuard.reversed(player)) {
            SpearShieldGuard.offhandAttack(player); ci.cancel();
        }
    }
}
