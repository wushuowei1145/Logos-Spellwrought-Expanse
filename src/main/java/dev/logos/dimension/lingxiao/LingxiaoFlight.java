package dev.logos.dimension.lingxiao;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.longsword.LongswordCombat;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 御剑生命周期；输入沿用原版骑乘包，位置与魔力均由服务端结算。 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class LingxiaoFlight {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);
    public static final Supplier<EntityType<FlyingSword>> TYPE = TYPES.register("flying_lingxiao", () ->
            EntityType.Builder.<FlyingSword>of(FlyingSword::new, MobCategory.MISC).sized(.75f, 2.0f)
                    .clientTrackingRange(10).updateInterval(1)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("flying_lingxiao"))));
    public static void register(IEventBus bus) { TYPES.register(bus); }

    public static boolean start(ServerPlayer player) {
        if (!(player.getMainHandItem().getItem() instanceof dev.logos.dimension.item.LingxiaoSwordItem)
                || !player.isAlive() || player.isSpectator() || player.isPassenger() || player.isFallFlying()
                || player.isShiftKeyDown() || !"idle".equals(LongswordCombat.move(player))) return false;
        if (!player.hasInfiniteMaterials() && Double.isNaN(FlightRules.drain(LogosNatureBridge.mana(player)))) {
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.lingxiao.no_mana")); return false;
        }
        var sword = TYPE.get().create(player.level(), net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        if (sword == null) return false;
        sword.bind(player); sword.setPos(player.position()); sword.setYRot(player.getYRot());
        // 碰撞体覆盖骑手的头顶，低矮空间不能起飞，也不能借剑钻进天花板。
        if (!player.level().noCollision(player, sword.getBoundingBox()) || !LongswordCombat.charge(player)) { sword.discard(); return false; }
        if (!player.level().addFreshEntity(sword) || !player.startRiding(sword, true, true)) {
            sword.discard(); LongswordCombat.force(player, "idle"); return false;
        }
        return true;
    }

    @SubscribeEvent static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().getVehicle() instanceof FlyingSword sword) sword.stop();
    }
    @SubscribeEvent static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity().getVehicle() instanceof FlyingSword sword) sword.stop();
    }
    private LingxiaoFlight() {}
}
