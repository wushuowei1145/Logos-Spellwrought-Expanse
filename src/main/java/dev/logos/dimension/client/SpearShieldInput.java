package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.SpearShieldGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** 在原版举盾分支清空左键之前处理限定搭配，发送原版戳刺包，不松开盾。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class SpearShieldInput {
    @SubscribeEvent static void tick(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance(); var player = mc.player;
        if (mc.screen != null || player == null || mc.gameMode == null || !SpearShieldGuard.paired(player)
                || !player.isAlive() || player.isSpectator()) return;
        boolean reverse = SpearShieldGuard.reversed(player);
        if (reverse && mc.options.keyUse.isDown()) {
            // 两键同刻按下时也让右键优先，不先打出一次副手戳刺或主手盾普攻。
            while (mc.options.keyAttack.consumeClick()) {}
            return;
        }
        if (!reverse && !SpearShieldGuard.guarding(player)) return;
        while (mc.options.keyAttack.consumeClick()) {
            // 松开右键与左键发生在同一刻时，先按原版方式收盾，再发送副手戳刺，避免输入被吞。
            if (reverse && player.isUsingItem()) mc.gameMode.releaseUsingItem(player);
            SpearShieldGuard.withSpearAttributes(player, () -> {
                var hand = SpearShieldGuard.spearHand(player); var stack = player.getItemInHand(hand);
                if (player.isHandsBusy() || player.cannotAttackWithItem(stack, 0)) return;
                var input = net.neoforged.neoforge.client.ClientHooks.onClickInput(0, mc.options.keyAttack, hand);
                if (!input.isCanceled()) {
                    var piercing = stack.get(DataComponents.PIERCING_WEAPON);
                    if (piercing != null) mc.gameMode.piercingAttack(piercing);
                }
                if (input.shouldSwingHand()) player.swing(hand);
            });
        }
    }
    private SpearShieldInput() {}
}
