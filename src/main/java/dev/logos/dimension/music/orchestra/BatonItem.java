package dev.logos.dimension.music.orchestra;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * 指挥棒（docs/ORCHESTRA.md 第 3、4 节）：右键指挥台绑定它（绑定后再右键就开始 / 停止演奏）；右键乐器把它加进或移出那个乐团；
 * 右键空中换选中的声部（选中的声部在画面里描边，只有自己看得到），不用潜行。站在指挥台上拿着它，乐团演奏时就按拍子指挥；
 * 这时左键不攻击、不挖方块。
 */
public class BatonItem extends Item {
    public BatonItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) dev.logos.dimension.client.music.Conducting.cycleSelection();
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        GlobalPos p = stack.get(Orchestra.BATON_PODIUM.get());
        if (p == null) {
            out.accept(Component.translatable("item.logos_dimension.hexen_baton.unbound").withStyle(ChatFormatting.GRAY));
        } else {
            out.accept(Component.translatable("item.logos_dimension.hexen_baton.bound", p.pos().getX(), p.pos().getY(), p.pos().getZ())
                .withStyle(ChatFormatting.GRAY));
        }
    }
}
