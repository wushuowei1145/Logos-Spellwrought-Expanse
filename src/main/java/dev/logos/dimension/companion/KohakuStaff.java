package dev.logos.dimension.companion;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.item.BladeStaffItem;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.item.WandItem;
import dev.logos.runtime.Charging;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 琥珀的长杖（docs/KOHAKU_COMPANION.md 第 4 节）：平时拄着走，打起来举起来施法。
 * <ul>
 *   <li>她自己的：紫晶长杖起，契印多了自己换更好的，到贤者长杖为止（{@link #OWN}）。</li>
 *   <li>包袱里你放的法杖（长杖、短杖、杖剑）她优先用，哪怕档位比她自己的低；几根时拿最好的那根：先比档位，再比槽里挂了几页术式。</li>
 *   <li>长杖存着魔力（{@link #store}）：打起来先用杖里的，瞬发；杖里不够才从她自己身上蓄，按杖的注入流量和汇集加速算多久
 *       （同玩家的蓄力公式）。不打的时候她慢慢往杖里充。</li>
 *   <li>她自带的术式按手里那根杖的档位注入（同档载页的容量）；你给的杖上挂着的载页按那一页自己的档位。</li>
 * </ul>
 * 手里拿的是一份拷贝：杖还在包袱里，你随时能拿回去（拿走了她换回自己的）。
 */
final class KohakuStaff {
    /** 她自己的长杖，按契印（0–9 枚）：紫晶、回响、回响、炼金、炼金、白垩、白垩、天穹、天穹、贤者。 */
    static final List<DeferredItem<StaffItem>> OWN = List.of(LogosMod.CRYSTAL_STAFF, LogosMod.ECHO_STAFF, LogosMod.ECHO_STAFF,
            LogosMod.TRANSMUTED_STAFF, LogosMod.TRANSMUTED_STAFF, LogosMod.CHALK_STAFF, LogosMod.CHALK_STAFF, LogosMod.FIRMAMENT_STAFF,
            LogosMod.FIRMAMENT_STAFF, LogosMod.SAGE_STAFF);
    /** 一口气充满一根长杖要多少秒，按档（第 1–8 档，Logos CONTRIBUTING「法杖数值」）。 */
    static final double[] FILL_SECONDS = {3, 4.5, 6, 7.5, 9, 10.5, 12, 6};
    /** 多久看一次包袱（刻）。 */
    static final int CHECK_EVERY = 20;

    private final KohakuCompanion c;
    private ItemStack held = ItemStack.EMPTY;
    /** 手里这根是你给的（包袱里的那根）。 */
    private boolean yours;
    /** 杖里存着的魔力。 */
    private double store;
    /** 你给的这根杖上她能放的页（{@link KohakuSpells#yours}），换杖、Logos 重新编译术式以后重算。 */
    private java.util.List<KohakuSpells.Yours> yoursPages = java.util.List.of();
    private int pagesGeneration = -1;

    KohakuStaff(KohakuCompanion c) {
        this.c = c;
    }

    ItemStack held() {
        return held;
    }

    boolean yours() {
        return yours;
    }

    /** 你给的这根杖上她能放的页；她自己的杖上没有（她自己的术式不挂在杖上）。 */
    java.util.List<KohakuSpells.Yours> yoursPages() {
        if (!yours) return java.util.List.of();
        if (pagesGeneration != dev.logos.runtime.Casting.generation()) {
            pagesGeneration = dev.logos.runtime.Casting.generation();
            yoursPages = KohakuSpells.yours(held);
        }
        return yoursPages;
    }

    double store() {
        return store;
    }

    void setStore(double v) {
        store = Math.max(0, Math.min(v, capacity()));
    }

    /** 杖里最多存多少（短杖、杖剑不存）。 */
    double capacity() {
        return held.getItem() instanceof StaffItem s ? s.capacity() : 0;
    }

    /** 她自己盖了 {@code seals} 枚爪之契时拿的长杖。 */
    static ItemStack own(int seals) {
        return new ItemStack(OWN.get(Math.clamp(seals, 0, OWN.size() - 1)).get());
    }

    /** {@code stack} 是第几档的法杖（1–8）；不是法杖为 0。 */
    static int tier(ItemStack stack) {
        Item item = stack.getItem();
        if (!(item instanceof WandItem wand)) return 0;
        int i = indexOf(LogosMod.TIER_STAFFS, item);
        if (i >= 0) return i + 2;
        i = indexOf(LogosMod.TIER_WANDS, item);
        if (i >= 0) return i + 2;
        i = indexOf(LogosMod.BLADE_STAFFS, item);
        if (i >= 0) return i + 1;
        if (indexOf(LogosMod.WOOD_STAFFS, item) >= 0 || indexOf(LogosMod.WOOD_WANDS, item) >= 0) return 1;
        // 别的模组的法杖：按槽数估一个档
        return Math.clamp((wand.slots() + 1) / 2, 1, 8);
    }

    private static int indexOf(List<? extends DeferredItem<? extends Item>> list, Item item) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).get() == item) return i;
        return -1;
    }

    /** 她自带的术式按这一档的载页注入。 */
    PageTier pageTier() {
        int t = Math.max(1, tier(held));
        return PageTier.values()[Math.min(t, PageTier.values().length) - 1];
    }

    /** 杖上挂着几页写好的术式。 */
    static int pages(ItemStack wand) {
        int n = 0;
        for (int i = 0; i < PageContainer.size(wand); i++) if (SpellPages.isInscribed(PageContainer.get(wand, i))) n++;
        return n;
    }

    /** 包袱里她最想用的那根：先比档位，再比挂了几页；没有为空。 */
    static ItemStack best(CompanionPouch pouch) {
        ItemStack best = ItemStack.EMPTY;
        int bestTier = 0, bestPages = -1;
        for (int i = 0; i < pouch.getContainerSize(); i++) {
            ItemStack s = pouch.getItem(i);
            int t = tier(s);
            if (t <= 0) continue;
            int n = pages(s);
            if (t > bestTier || t == bestTier && n > bestPages) {
                best = s;
                bestTier = t;
                bestPages = n;
            }
        }
        return best;
    }

    /** 每隔一阵看一眼包袱：你放了（或拿走了）杖、契印多了，就换手里那根。正在施法、吃东西时不换。 */
    void tick(ServerLevel level, long now, ServerPlayer p) {
        if (now % CHECK_EVERY == 0 && !c.magic.casting() && !c.eating()) {
            ItemStack fromPouch = best(c.pouch(p));
            ItemStack want = fromPouch.isEmpty() ? own(c.seals()) : fromPouch.copyWithCount(1);
            if (!ItemStack.isSameItemSameComponents(want, held)) swap(level, want, !fromPouch.isEmpty(), !held.isEmpty());
        }
        // 不打的时候慢慢往杖里充（同一口气充满的速度），从她自己身上出
        double cap = capacity();
        if (cap > 0 && store < cap && !c.magic.casting() && c.mana() > 0) {
            double rate = cap / FILL_SECONDS[Math.clamp(tier(held), 1, 8) - 1] / 20;
            double want = Math.min(rate, cap - store);
            // 自己身上留三成，护膜要用
            double spare = c.mana() - c.maxMana() * KohakuMagic.LOW;
            if (spare > 0) store += c.spendMana(Math.min(want, spare));
        }
    }

    /** 换上 {@code stack}：旧杖化成光收进去，新杖从一小圈光里抽出来。 */
    private void swap(ServerLevel level, ItemStack stack, boolean fromYou, boolean show) {
        double ratio = capacity() > 0 ? store / capacity() : 0;
        held = stack;
        yours = fromYou;
        pagesGeneration = -1;
        // 换杖时杖里的魔力照比例带过去（她自己往新杖里倒），免得一换就空
        store = Math.min(capacity(), ratio * capacity());
        c.holdStaff();
        if (!show) return;
        Vec3 hand = c.getEyePosition().add(Vec3.directionFromRotation(0, c.yBodyRot + 40).scale(.4)).add(0, -.5, 0);
        level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1f), hand.x, hand.y, hand.z, 14, .15, .35, .15, 0);
        level.sendParticles(ParticleTypes.END_ROD, hand.x, hand.y, hand.z, 6, .1, .3, .1, .02);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .8f, 1.5f);
    }

    /** 测试用：直接换上 {@code stack}（不看包袱）。 */
    void force(ItemStack stack, boolean fromYou) {
        held = stack.copyWithCount(1);
        yours = fromYou;
        pagesGeneration = -1;
        store = Math.min(store, capacity());
        c.holdStaff();
    }

    // ---------------------------------------------------------------- 蓄力

    /** 手里这根杖主手给的 {@code attribute}（注入流量、汇集加速）。 */
    double mainHand(net.minecraft.core.Holder<Attribute> attribute) {
        ItemAttributeModifiers mods = held.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double sum = 0;
        for (var e : mods.modifiers()) if (e.attribute().value() == attribute.value()) sum += e.modifier().amount();
        return sum;
    }

    /**
     * 从她自己身上蓄 {@code mana} 要几刻：同玩家的蓄力公式（汇集加速按她的魔力上限放大），最短 {@code chargeMinTicks}。
     * 杖剑拿在手里另有缠绕的加成，但蓄力一样。
     */
    int chargeTicks(double mana) {
        double flow = Math.max(1, mainHand(LogosMod.CHANNEL_FLOW)), surge = mainHand(LogosMod.CHANNEL_SURGE)
                * (1 + c.maxMana() / LogosConfig.chargeManaScale());
        double s = Charging.seconds(mana, flow, surge);
        return (int) Math.max(LogosConfig.chargeMinTicks(), Math.ceil(s * 20));
    }

    /** 是不是杖剑（凝辉直接用剑刃砍，缠绕更省更痛）。 */
    boolean blade() {
        return held.getItem() instanceof BladeStaffItem;
    }
}
