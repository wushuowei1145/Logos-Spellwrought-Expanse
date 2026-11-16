package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 白梅吃什么（docs/SHIRAUME_COMPANION.md 第 8.7 节）。喜欢和不吃的都是物品标签，以后做出油揚げ、稲荷寿司、狐うどん，
 * 往 {@code shiraume_favorite_food} 里加一行就行。
 */
public final class CompanionFood {
    /** 最喜欢的：现在是甜浆果和林地蓝莓。 */
    public static final TagKey<Item> FAVORITE = TagKey.create(Registries.ITEM, LogosDimension.id("shiraume_favorite_food"));
    /** 不吃的：腐肉、毒马铃薯、蜘蛛眼、河豚、生鸡肉。 */
    public static final TagKey<Item> REFUSED = TagKey.create(Registries.ITEM, LogosDimension.id("shiraume_refused_food"));

    public enum Taste { FAVORITE, LIKED, REFUSED, NOT_FOOD }

    private CompanionFood() {}

    public static Taste taste(ItemStack stack) {
        if (stack.isEmpty()) return Taste.NOT_FOOD;
        if (stack.is(FAVORITE)) return Taste.FAVORITE;
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return Taste.NOT_FOOD;
        return stack.is(REFUSED) ? Taste.REFUSED : Taste.LIKED;
    }

    /** 这一口有多少饱腹（至少 1）。 */
    public static int nutrition(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 1 : Math.max(1, food.nutrition());
    }

    /** 吃下去回多少血：玩家吃它回的饱食度加饱和度（甜浆果 2.4，面包 11，牛排 20.8，金苹果 13.6）。 */
    public static float heal(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 1 : Math.max(1, food.nutrition() + food.saturation());
    }

    /** 饱着也吃得下（同玩家：金苹果、紫颂果这类随时能吃的）。 */
    public static boolean always(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food != null && food.canAlwaysEat();
    }

    /**
     * 吃下去的效果和玩家吃它一样：金苹果的生命恢复和伤害吸收、迷之炖菜的状态效果、蜂蜜瓶解毒、紫颂果随机传送……
     * （物品的食用组件里写的那些）。饱食度那一项只对玩家有用，她的饱腹另算，不走它（也就不再多放一次咀嚼声）。
     */
    public static void effects(net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.LivingEntity eater, ItemStack stack) {
        net.minecraft.world.item.component.Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) return;
        ItemStack one = stack.copyWithCount(1);
        one.getAllOfType(net.minecraft.world.item.component.ConsumableListener.class).forEach(l -> {
            if (!(l instanceof FoodProperties)) l.onConsume(level, eater, one, consumable);
        });
        consumable.onConsumeEffects().forEach(effect -> effect.apply(level, one, eater));
    }
}
