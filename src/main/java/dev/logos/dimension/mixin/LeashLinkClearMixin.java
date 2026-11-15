package dev.logos.dimension.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.world.entity.Leashable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端解开拴绳时发来「拴绳没人拿着」（对方实体 id 为 0，实体 id 从 1 开始），原版客户端照样
 * {@code setDelayedLeashHolderId(0)}，留下一条既没有拴的人、也没有待找的位置的拴绳记录，之后再也不清。
 * 平时没事，但这条记录存不了盘（{@code LeashData.CODEC}：「Invalid LeashData had no attachment」）：
 * 车万女仆的魂符在客户端也把女仆存一遍，拴绳牵上琴凳、工位（上座时先解开拴绳）的女仆一收就崩。
 * 这里在客户端照服务端的样子把它清成 null。只动原版，不碰女仆模组。
 */
@Mixin(ClientPacketListener.class)
public abstract class LeashLinkClearMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "handleEntityLinkPacket", at = @At("TAIL"))
    private void logos$clearEmptyLeash(ClientboundSetEntityLinkPacket packet, CallbackInfo ci) {
        if (packet.getDestId() == 0 && this.level.getEntity(packet.getSourceId()) instanceof Leashable leashable
            && leashable.getLeashData() != null && leashable.getLeashData().leashHolder == null) {
            leashable.setLeashData(null);
        }
    }
}
