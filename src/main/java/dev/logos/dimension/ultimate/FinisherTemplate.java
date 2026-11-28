package dev.logos.dimension.ultimate;

import dev.logos.dimension.firearm.Firearms;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * 玩家最后一击按主手拿的东西分的武器大类（规范 5.3）。演哪一段由玩家的档案 {@link HeroUltimate} 定：
 * 档案里没写这一类时落到 {@link #UNARMED}，所以加一类只要在档案里加一条，不动连携。
 */
public enum FinisherTemplate {
    /** 空手、没归类的东西、工具。 */
    UNARMED,
    /** 剑、刀、长剑。 */
    BLADE,
    /** 弓、弩。 */
    BOW,
    /** 法杖、短杖。 */
    STAFF,
    /** 长枪、三叉戟。 */
    POLEARM,
    /** 铳械。 */
    FIREARM;

    public final String id = name().toLowerCase(Locale.ROOT);

    /** 主手拿的东西属于哪一类。 */
    public static FinisherTemplate of(ItemStack stack) {
        if (stack.isEmpty()) return UNARMED;
        if (Firearms.firearm(stack) != null) return FIREARM;
        if (stack.getItem() instanceof ProjectileWeaponItem) return BOW;
        if (stack.is(Items.TRIDENT) || stack.has(DataComponents.PIERCING_WEAPON)) return POLEARM;
        if (stack.is(ItemTags.SWORDS)) return BLADE;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("staff") || path.contains("wand")) return STAFF;
        if (path.contains("sword") || path.contains("blade") || path.contains("katana") || path.contains("uchigatana")) return BLADE;
        return UNARMED;
    }

    public static FinisherTemplate byId(String id) {
        for (FinisherTemplate t : values()) if (t.id.equals(id)) return t;
        return UNARMED;
    }
}
