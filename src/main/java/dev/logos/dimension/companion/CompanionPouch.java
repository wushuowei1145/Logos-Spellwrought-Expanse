package dev.logos.dimension.companion;

import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 白梅的包袱（docs/SHIRAUME_COMPANION.md 第 8.8 节）：两行 18 格，挂在主人身上（玩家附件），
 * 所以她倒下、被收回、换维度都不会丢，也不会掉出来。主人给的、她捡的、她摘的都在这里。
 */
public final class CompanionPouch extends SimpleContainer {
    public static final int SIZE = 18;
    static final Codec<CompanionPouch> CODEC = ItemStack.OPTIONAL_CODEC.listOf().xmap(CompanionPouch::of, p -> List.copyOf(p.getItems()));

    public CompanionPouch() {
        super(SIZE);
    }

    private static CompanionPouch of(List<ItemStack> items) {
        CompanionPouch p = new CompanionPouch();
        for (int i = 0; i < Math.min(SIZE, items.size()); i++) p.setItem(i, items.get(i));
        return p;
    }

    /** 主人潜行、空手右键她：打开包袱。 */
    static void open(ServerPlayer owner) {
        CompanionPouch pouch = Companions.pouch(owner);
        owner.openMenu(new SimpleMenuProvider((id, inventory, player) -> new ChestMenu(MenuType.GENERIC_9x2, id, inventory, pouch, 2),
                Component.translatable("container.logos_dimension.shiraume_pouch")));
    }

    /** 包袱里一样她愿意吃的东西所在的格子，喜欢的优先；没有返回 -1。 */
    int foodSlot() {
        int liked = -1;
        for (int i = 0; i < getContainerSize(); i++) {
            CompanionFood.Taste t = CompanionFood.taste(getItem(i));
            if (t == CompanionFood.Taste.FAVORITE) return i;
            if (t == CompanionFood.Taste.LIKED && liked < 0) liked = i;
        }
        return liked;
    }

    /** 放进去；放不下的部分返回。 */
    ItemStack put(ItemStack stack) {
        return addItem(stack);
    }
}
