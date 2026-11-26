package dev.logos.dimension.client.firearm;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.firearm.AmmoRecipeBook;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

/**
 * 服务端发来的铸弹台配方（压制、蚀刻、改装），交给 {@link AmmoRecipeBook}：铸弹台界面的配方册和 JEI 在客户端读它。
 * 抢在别人前面收，因为 JEI 在同一个事件里重新加载插件。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ClientAmmoRecipes {
    private ClientAmmoRecipes() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onReceived(RecipesReceivedEvent event) {
        AmmoRecipeBook.received(event.getRecipeMap());
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        AmmoRecipeBook.clear();
    }
}
