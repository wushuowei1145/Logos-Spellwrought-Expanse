package dev.logos.dimension.music;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** 打孔纸带：提示文字显示曲名、作者、时长、音符数与声部。内容在服务端，物品只带 {@link RollInfo}。 */
public class MusicRollItem extends Item {
    public MusicRollItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        RollInfo info = stack.get(Instruments.MUSIC_ROLL.get());
        if (info == null) {
            out.accept(Component.translatable("item.logos_dimension.music_roll.empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!info.title().isEmpty()) out.accept(Component.literal(info.title()).withStyle(ChatFormatting.GOLD));
        if (!info.author().isEmpty()) {
            out.accept(Component.translatable("item.logos_dimension.music_roll.author", info.author()).withStyle(ChatFormatting.GRAY));
        }
        out.accept(Component.translatable("item.logos_dimension.music_roll.length", RollInfo.clock(info.durationMs()), info.noteCount())
            .withStyle(ChatFormatting.GRAY));
        if (info.parts().size() > 1) {
            out.accept(Component.translatable("item.logos_dimension.music_roll.parts", String.join("、", info.parts()))
                .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
