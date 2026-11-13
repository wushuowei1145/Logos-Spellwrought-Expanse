package dev.logos.dimension.music.orchestra;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 指挥棒（docs/ORCHESTRA.md 第 3、4 节）：右键指挥台绑定它（绑定后再右键就开始 / 停止演奏）；右键乐器把它加进或移出那个乐团；
 * 站在指挥台上拿着它是指挥（docs/ORCHESTRA.md 第 17 节）：左键打拍子（按住拖动是画拍子），右键准星指着的乐器选中它的声部
 * （再点一次取消；选中的声部在画面里描边，只有自己看得到），滚轮调选中声部（没选中时全体）的响度。这时左键不攻击、不挖方块。
 */
public class BatonItem extends Item {
    public BatonItem(Properties properties) {
        super(properties);
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
