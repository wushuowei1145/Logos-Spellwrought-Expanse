package dev.logos.dimension.client;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.npc.ElfBowAttackGoal;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.dimension.item.NatureAttachments;
import dev.logos.dimension.item.NatureCharge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.client.renderer.item.properties.numeric.UseDuration;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;

/** 按服务端同步的魔力档位限制蓄力外观；客户端不猜测 Logos 的未同步魔力池。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public record NatureChargeProperty() implements RangeSelectItemModelProperty {
    public static final MapCodec<NatureChargeProperty> CODEC = MapCodec.unit(new NatureChargeProperty());

    @SubscribeEvent static void register(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(LogosDimension.id("nature_charge"), CODEC);
    }

    @Override public float get(ItemStack stack, ClientLevel level, ItemOwner owner, int seed) {
        var entity = owner == null ? null : owner.asLivingEntity();
        if (entity == null || entity.getUseItem() != stack) return 0;
        int ticks = UseDuration.useDuration(stack, entity);
        // 森林精灵拉弓更快（ElfBowAttackGoal.DRAW_TICKS 刻满弦），贴图按同样的比例推进
        if (entity instanceof ForestElf) ticks = ticks * NatureCharge.FULL_CHARGE / ElfBowAttackGoal.DRAW_TICKS;
        return Math.min(NatureCharge.stage(ticks), entity.getData(NatureAttachments.CHARGE_LIMIT));
    }

    @Override public MapCodec<NatureChargeProperty> type() { return CODEC; }
}
