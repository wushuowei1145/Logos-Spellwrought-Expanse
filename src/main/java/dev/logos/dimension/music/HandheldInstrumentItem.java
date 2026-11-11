package dev.logos.dimension.music;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;

/**
 * 拿在手上的乐器（小提琴、长笛、小号）。右键举起来演奏：和拉弓一样进入「使用中」（同步给附近的人，
 * 他们那里会看到演奏姿势、物品换成演奏时的模型），客户端同时打开屏幕底部的演奏条；关掉演奏条后
 * 下一 tick 原版自动放下。右键乐谱架则把乐器放到架子上（见 {@link MusicStandBlock}）。
 */
public class HandheldInstrumentItem extends Item {
    private final InstrumentKind kind;

    public HandheldInstrumentItem(InstrumentKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public InstrumentKind kind() {
        return kind;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        if (level.isClientSide()) {
            // 界面类只在客户端存在：经静态入口打开，本类不出现任何客户端类型
            dev.logos.dimension.client.music.MusicClient.openHandheld(kind, hand);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return 72_000;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.NONE;
    }
}
