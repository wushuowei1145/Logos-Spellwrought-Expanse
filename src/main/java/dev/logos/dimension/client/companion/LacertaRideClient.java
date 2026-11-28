package dev.logos.dimension.client.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.LacertaCompanion;
import dev.logos.dimension.companion.LacertaRideCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * 骑在拉塞塔（巨龙）背上时的按键（docs/LACERTA_COMPANION.md 第 19 节）：左键不攻击、不挖，改挥龙爪；按住右键喷龙炎，松开收。
 * 走、飞由原版的骑乘输入给（W/A/S/D、空格、疾跑），见 {@code LacertaRide}。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class LacertaRideClient {
    /** 右键按着、龙炎在喷。 */
    private static boolean breathing;
    /** 测试用：换下来的键盘输入（强行按键时换成 {@link Forced}，结束时换回去）。 */
    private static @Nullable ClientInput keyboard;

    private LacertaRideClient() {}

    /** 你骑着的、你在驾驭的巨龙（不是返回 null）。 */
    static @Nullable LacertaCompanion riding() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof LacertaCompanion l) || !l.isDragonForm()
                || l.getControllingPassenger() != mc.player) return null;
        return l;
    }

    @SubscribeEvent
    static void keys(InputEvent.InteractionKeyMappingTriggered event) {
        if (Minecraft.getInstance().screen != null || riding() == null) return;
        if (event.isAttack()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            ClientPacketDistributor.sendToServer(LacertaRideCommands.claw());
        } else if (event.isUseItem()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            if (!breathing) ClientPacketDistributor.sendToServer(LacertaRideCommands.breath());
            breathing = true;
        }
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (!breathing) return;
        var mc = Minecraft.getInstance();
        if (riding() == null || !mc.options.keyUse.isDown()) {
            breathing = false;
            if (mc.getConnection() != null) ClientPacketDistributor.sendToServer(LacertaRideCommands.breathStop());
        }
    }

    /** 测试用：强行按 {@code keys}（null 换回键盘）。 */
    public static void forceForTest(@Nullable Input keys) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (keys == null) {
            if (keyboard != null) player.input = keyboard;
            keyboard = null;
            return;
        }
        if (keyboard == null) keyboard = player.input;
        player.input = new Forced(keys);
    }

    /** 一直按着这些键的输入（移动向量照按键算，同原版键盘）。 */
    private static final class Forced extends ClientInput {
        Forced(Input keys) {
            keyPresses = keys;
            float forward = keys.forward() == keys.backward() ? 0 : keys.forward() ? 1 : -1;
            float left = keys.left() == keys.right() ? 0 : keys.left() ? 1 : -1;
            moveVector = new Vec2(left, forward).normalized();
        }
    }
}
