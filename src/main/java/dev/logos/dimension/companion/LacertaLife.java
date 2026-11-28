package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 拉塞塔的日常小事（docs/LACERTA_COMPANION.md 第 8.2、8.3、8.5 节，接入方案第七节第 2、4、5 条）：
 * <ul>
 *   <li>馋：你身边的营火、烟熏炉、熔炉正烧着（或你手里举着熟食，{@link LacertaMood}）——她闻着味凑过来；</li>
 *   <li>递吃的：你饿了或伤着、她包袱里有吃的时，走到跟前别别扭扭塞给你一块（隔一阵一次）；</li>
 *   <li>龙息现烤：对话里「帮我烤一下」，对着包袱里的一组生肉吹一口龙炎；「熟悉」以前两成烤成木炭还甩锅，之后火候正好，自己先尝一口；</li>
 *   <li>龙温：在冰雪地里（或踩着雪、陷在细雪里）她收起翅膀裹住自己、贴着你走，嘴硬说一句；她在你身边 2 格内，你在细雪里冻得慢一半；</li>
 *   <li>藏宝癖：捡到亮晶晶的（物品标签 {@code logos_dimension:lacerta_shiny}，或附了魔的）喊一声「我的！」；一场打完偶尔把打完以后捡到的
 *       最闪的一件捧出来献给你（等她捡完）。</li>
 * </ul>
 */
final class LacertaLife {
    /** 亮晶晶的东西。 */
    static final TagKey<Item> SHINY = TagKey.create(Registries.ITEM, LogosDimension.id("lacerta_shiny"));
    /** 递吃的：隔多久判一次、两次之间至少多久、你饿到多少算饿、她离你多近才递。 */
    static final int GIFT_ROLL = 200, GIFT_GAP = 6000, GIFT_HUNGRY = 6;
    static final double GIFT_NEAR = 4;
    /** 闻到炊烟：烧着的营火、烟熏炉、熔炉离你多近。 */
    static final int COOK_FIND = 6;
    /** 冷：生物群系的基础温度低于多少；冷的那一句隔多久说一次；她离你多近你冻得慢一半。 */
    static final float COLD_BELOW = .15f;
    static final int COLD_LINE_GAP = 12_000;
    static final double WARM_NEAR = 2;
    /** 烤肉：「熟悉」以前烤糊的几率。 */
    static final float BURN = .2f;
    /** 一场打完献宝的几率；打完以后多久里捡到的算这一场的；捡完多久没再捡就献。 */
    static final float PRESENT = .35f;
    static final int PRESENT_WINDOW = 600, PRESENT_AFTER = 60;
    /** 「我的！」隔多久喊一次。 */
    static final int MINE_GAP = 600;

    /** 测试用：烤肉强行烤糊 / 不糊（null 照几率）；献宝必定献。 */
    static Boolean forceBurn;
    static boolean alwaysPresent;
    /** 测试用：递过几次吃的、烤过几次、烤糊几次、献过几次宝、喊过几次「我的」、你在细雪里少冻了几刻。 */
    static int gifts, roasts, burnt, presents, mines, warmed;

    private final LacertaCompanion c;
    private long nextGiftRoll, lastGift = -100_000, coldLineAt = -100_000, mineAt = -10_000, fightEnd = -100_000, lastPick;
    private int ownerFrozen;
    private boolean fighting, presenting;
    private ItemStack shiniest = ItemStack.EMPTY;

    LacertaLife(LacertaCompanion c) {
        this.c = c;
    }

    // ---------------------------------------------------------------- 每刻

    /** 每刻：你在细雪里冻得慢一半。 */
    void tick(ServerPlayer p, long now) {
        warm(p, now);
    }

    /** 每次心智想完：冷了说一句、要不要递吃的、一场打完献宝。 */
    void think(ServerLevel level, ServerPlayer p, long now, boolean calm) {
        watchFight(p, !calm, now);
        if (cold() && now - coldLineAt >= COLD_LINE_GAP && c.distanceTo(p) < 8 && calm) {
            coldLineAt = now;
            CompanionLines.say(c, p, "cold_snuggle", 60);
        }
        if (calm && now >= nextGiftRoll) {
            nextGiftRoll = now + GIFT_ROLL;
            gift(level, p, now);
        }
    }

    // ---------------------------------------------------------------- 冷

    /** 她觉得冷：人形，在冰雪地里，或陷在细雪里、踩着雪。 */
    boolean cold() {
        if (c.isDragonForm()) return false;
        BlockPos feet = c.blockPosition();
        if (c.isInPowderSnow || c.wasInPowderSnow) return true;
        BlockState at = c.level().getBlockState(feet), below = c.level().getBlockState(feet.below());
        if (at.is(Blocks.SNOW) || below.is(Blocks.SNOW_BLOCK) || below.is(Blocks.POWDER_SNOW) || below.is(BlockTags.ICE)) return true;
        return c.level().getBiome(feet).value().getBaseTemperature() < COLD_BELOW;
    }

    /** 她在你身边 2 格内：你在细雪里冻得慢一半（冻结每涨两刻抵掉一刻）。 */
    private void warm(ServerPlayer p, long now) {
        int frozen = p.getTicksFrozen();
        boolean near = c.distanceTo(p) <= WARM_NEAR + c.getBbWidth() / 2;
        if (near && p.isInPowderSnow && frozen > ownerFrozen && now % 2 == 0) {
            p.setTicksFrozen(frozen - 1);
            frozen--;
            warmed++;
        }
        ownerFrozen = frozen;
    }

    // ---------------------------------------------------------------- 吃

    /** 你身边有烧着的营火、烟熏炉、熔炉（冒着炊烟）。 */
    static boolean cooking(ServerLevel level, BlockPos around) {
        for (BlockPos pos : BlockPos.betweenClosed(around.offset(-COOK_FIND, -2, -COOK_FIND), around.offset(COOK_FIND, 2, COOK_FIND))) {
            BlockState s = level.getBlockState(pos);
            if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) return true;
            if (s.getBlock() instanceof AbstractFurnaceBlock && s.getValue(AbstractFurnaceBlock.LIT)) return true;
        }
        return false;
    }

    /** 你饿了或伤着、她包袱里有吃的、离你够近：塞给你一块（别别扭扭的）。 */
    private void gift(ServerLevel level, ServerPlayer p, long now) {
        if (now - lastGift < GIFT_GAP || c.distanceTo(p) > GIFT_NEAR + c.getBbWidth() / 2 || c.talking()) return;
        if (p.getFoodData().getFoodLevel() > GIFT_HUNGRY && p.getHealth() >= p.getMaxHealth() * .35f) return;
        CompanionPouch pouch = c.pouch(p);
        int slot = pouch.foodSlot(LacertaCompanion.ID);
        if (slot < 0) return;
        lastGift = now;
        give(p, pouch.removeItem(slot, 1));
        c.getLookControl().setLookAt(p, 40, 40);
        c.mood.act(c.isDragonForm() ? "act_sniff_nudge" : "act_hmph");
        CompanionLines.say(c, p, "give_food", 60);
        gifts++;
    }

    /** 对话「帮我烤一下」的结果：包袱里没生肉、烤糊了、烤好了（她先尝了一口）。 */
    enum Roast { NOTHING, BURNT, DONE }

    /**
     * 对着包袱里的第一组生肉（烟熏炉烤得熟的）吹一口龙炎，烤好的给你（背包满了掉在你脚边）。「熟悉」以前两成烤成木炭；
     * 之后不糊，她先尝一口（这一组少一个，她饱一点）。
     */
    Roast roast(ServerLevel level, ServerPlayer p, long now) {
        CompanionPouch pouch = c.pouch(p);
        for (int i = 0; i < pouch.getContainerSize(); i++) {
            ItemStack raw = pouch.getItem(i);
            if (raw.isEmpty()) continue;
            SingleRecipeInput input = new SingleRecipeInput(raw.copyWithCount(1));
            var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMOKING, input, level);
            if (recipe.isEmpty()) continue;
            ItemStack cooked = recipe.get().value().assemble(input);
            if (cooked.isEmpty()) continue;
            int n = raw.getCount();
            pouch.setItem(i, ItemStack.EMPTY);
            boolean familiar = CompanionBond.Stage.of(c.bond(p).affection()).ordinal() >= CompanionBond.Stage.FAMILIAR.ordinal();
            boolean burn = forceBurn != null ? forceBurn : !familiar && c.getRandom().nextFloat() < BURN;
            breathe(level, now);
            roasts++;
            if (burn) {
                burnt++;
                give(p, new ItemStack(Items.CHARCOAL, n));
                return Roast.BURNT;
            }
            ItemStack out = cooked.copyWithCount(n * cooked.getCount());
            // 她先尝一口
            out.shrink(1);
            var b = c.bond(p);
            c.setBond(p, b.withSatiety(CompanionBond.fed(b.satiety(), CompanionFood.nutrition(cooked))));
            if (!out.isEmpty()) give(p, out);
            return Roast.DONE;
        }
        return Roast.NOTHING;
    }

    /** 吹一口龙炎：人形掌心燃起一簇（{@code act_ember}）在身前烧开，巨龙从嘴里。 */
    private void breathe(ServerLevel level, long now) {
        Vec3 at = c.isDragonForm() ? LacertaDragon.mouth(c.position(), c.yBodyRot)
                : c.position().add(Vec3.directionFromRotation(0, c.yBodyRot).scale(.6)).add(0, 1, 0);
        FoxfireCue.burst(level, at, FoxfireFlame.DRAGON, c.isDragonForm() ? 1.2f : .5f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .6f, 1.3f);
        if (!c.isDragonForm()) c.mood.act("act_ember");
    }

    // ---------------------------------------------------------------- 藏宝癖

    static boolean shiny(ItemStack s) {
        return s.is(SHINY) || s.isEnchanted();
    }

    /** 有多闪：下界合金、钻石、绿宝石、金、紫水晶，附了魔的最闪。 */
    static int shine(ItemStack s) {
        int v = s.isEnchanted() ? 100 : 0;
        if (s.is(Items.NETHERITE_INGOT) || s.is(Items.NETHERITE_SCRAP)) v += 60;
        else if (s.is(Items.DIAMOND)) v += 50;
        else if (s.is(Items.EMERALD)) v += 40;
        else if (s.is(Items.GOLD_INGOT) || s.is(Items.RAW_GOLD) || s.is(Items.GOLDEN_APPLE)) v += 30;
        else if (s.is(SHINY)) v += 10;
        return v;
    }

    /** 她捡起了 {@code stack}（已放进包袱）：亮晶晶的就喊「我的！」；一场刚打完（要献宝的话）记下捡到的最闪的一件。 */
    void picked(ServerPlayer p, ItemStack stack, long now) {
        if (!shiny(stack)) return;
        if (now - mineAt >= MINE_GAP) {
            mineAt = now;
            mines++;
            if (!c.isDragonForm()) c.mood.act("pick");
            CompanionLines.say(c, p, "mine", 40);
        }
        if (!presenting || now - fightEnd > PRESENT_WINDOW) return;
        lastPick = now;
        if (shiniest.isEmpty() || shine(stack) > shine(shiniest)) shiniest = stack.copyWithCount(1);
    }

    /** 每次心智想完：记下一场什么时候打完（这一场要不要献宝）；打完以后捡到了亮晶晶的、捡完一会儿了，捧出最闪的一件给你。 */
    private void watchFight(ServerPlayer p, boolean foe, long now) {
        if (foe) {
            fighting = true;
            return;
        }
        if (fighting) {
            fighting = false;
            fightEnd = now;
            presenting = alwaysPresent || c.getRandom().nextFloat() < PRESENT;
            shiniest = ItemStack.EMPTY;
            return;
        }
        if (shiniest.isEmpty() || now - lastPick < PRESENT_AFTER) return;
        ItemStack best = shiniest;
        shiniest = ItemStack.EMPTY;
        presenting = false;
        CompanionPouch pouch = c.pouch(p);
        for (int i = 0; i < pouch.getContainerSize(); i++) {
            if (!ItemStack.isSameItemSameComponents(pouch.getItem(i), best)) continue;
            give(p, pouch.removeItem(i, 1));
            c.getLookControl().setLookAt(p, 40, 40);
            CompanionLines.say(c, p, "present", 60);
            presents++;
            return;
        }
    }

    /** 塞给你（背包满了掉在你脚边）。 */
    private static void give(ServerPlayer p, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!p.getInventory().add(stack) && !stack.isEmpty()) p.drop(stack, false);
    }
}
