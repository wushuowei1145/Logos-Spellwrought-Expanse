package dev.logos.dimension.firearm;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlockEntity;
import dev.logos.block.ManaVesselBlockEntity;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.runtime.Limits;
import dev.logos.runtime.PlayerMagic;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.RangedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * 铸弹台，三件事（docs/FIREARMS.md 第 6、9、10 节）：
 * <ul>
 *   <li><b>压制</b>：弹壳、火药、弹头三格对上一条压制配方（{@link AmmoPressRecipe}），出货格放得下，就一批一批地压，
 *       每批压满配方的刻数后各扣一份、出一批弹。</li>
 *   <li><b>铭刻</b>：放一张写好的弹药图纸（不消耗）、一叠同弹种的普通弹，催化和导体对上一档蚀刻配方（{@link RoundEtchRecipe}），
 *       图纸的注入不超过这一档、台子里的魔力够，就一次刻一批（最多八发），扣一份催化、一份导体和每发的注入魔力。</li>
 *   <li><b>改装</b>：一叠有弹丸的普通弹加一份添加物，对上一条改装配方（{@link RoundModRecipe}）就改一批（最多八发），
 *       改成燃烧弹、水银弹这类没有术式的弹（{@link RoundEffects}）。</li>
 * </ul>
 * 台子存魔力（Logos 的魔力容器：魔力散布器、渡魔符文都能灌），补魔物品放进燃料格整个烧掉。
 * 稳定度按摆放它的人算（像术式发射器）：图纸上的术式比那人能驾驭的复杂就不刻；主人每次打开界面都会更新。
 * 东西拿走、出货格满了或魔力不够就停，进度归零。漏斗和管道：材料从任何一面送进对应的格子，成品从出货格取走。
 */
public class AmmoBenchBlockEntity extends ManaVesselBlockEntity {
    public static final int CASING = 0, POWDER = 1, HEAD = 2;
    public static final int BLUEPRINT = 0, ROUNDS = 1, CATALYST = 2, CONDUCTOR = 3;
    public static final int MOD_ROUNDS = 0, ADDITIVE = 1;
    /** 一批最多刻几发：一份催化、一份导体管八发。 */
    public static final int BATCH = 8;
    /** 刻一批要几刻。 */
    public static final int ETCH_TIME = 60;
    /** 改装一批要几刻。 */
    public static final int MOD_TIME = 40;
    /** 台子能存的魔力：够刻一批第七档的弹。 */
    public static final double MAX_MANA = 10240;

    private final ItemStacksResourceHandler inputs = new ItemStacksResourceHandler(3) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return level == null || level.isClientSide() || AmmoPressRecipe.usable(level, index, resource.toStack());
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    /** 只出不进：压好的弹药。 */
    private final ItemStacksResourceHandler output = outputSlot();
    /** 铭刻的四格：图纸、待刻的弹、催化、导体。 */
    private final ItemStacksResourceHandler etching = new ItemStacksResourceHandler(4) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            ItemStack stack = resource.toStack();
            return switch (index) {
                case BLUEPRINT -> stack.getItem() instanceof AmmoBlueprintItem;
                case ROUNDS -> SpellRounds.word(stack.getItem()) != null && RoundEffects.plain(stack);
                default -> level == null || level.isClientSide() || RoundEtchRecipe.usable(level, index == CATALYST, stack);
            };
        }

        @Override
        protected int getCapacity(int index, ItemResource resource) {
            return index == BLUEPRINT ? 1 : super.getCapacity(index, resource);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    /** 只出不进：刻好的术式弹。 */
    private final ItemStacksResourceHandler etched = outputSlot();
    /** 改装的两格：要改的普通弹（空弹头没有弹丸，不改装）、添加物。 */
    private final ItemStacksResourceHandler modding = new ItemStacksResourceHandler(2) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            ItemStack stack = resource.toStack();
            if (index == MOD_ROUNDS) return moddable(stack);
            return level == null || level.isClientSide() || AmmoRecipeBook.mods(level).stream().anyMatch(r -> r.additive().test(stack));
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    /** 只出不进：改装好的弹。 */
    private final ItemStacksResourceHandler modded = outputSlot();
    /** 补魔物品：放进来整个烧掉，台子放得下才烧。 */
    private final ItemStacksResourceHandler fuel = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return AlchemyTableBlockEntity.fuelValue(resource.toStack()) > 0;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    private final ResourceHandler<ItemResource> automation = new CombinedResourceHandler<>(
            new OneWay(inputs, 0, 3, true), new OneWay(etching, 0, 4, true), new OneWay(modding, 0, 2, true), new OneWay(fuel, 0, 1, true),
            new OneWay(output, 0, 1, false), new OneWay(etched, 0, 1, false), new OneWay(modded, 0, 1, false));

    /** 压制：这一批压了几刻，和它要几刻（没在压时为 0）。 */
    private int progress, time;
    /** 铭刻、改装：这一批做了几刻。 */
    private int etchProgress, modProgress;
    /** 摆放它的人和那人的稳定度（-1：还没人摆过，按初始稳定度算）。 */
    private @Nullable UUID owner;
    private int ownerStability = -1;

    public AmmoBenchBlockEntity(BlockPos pos, BlockState state) {
        super(AmmoBench.BENCH_ENTITY.get(), pos, state, MAX_MANA);
    }

    private ItemStacksResourceHandler outputSlot() {
        return new ItemStacksResourceHandler(1) {
            @Override
            public boolean isValid(int index, ItemResource resource) {
                return false;
            }

            @Override
            protected void onContentsChanged(int index, ItemStack previousContents) {
                setChanged();
            }
        };
    }

    public ItemStacksResourceHandler inputs() { return inputs; }
    public ItemStacksResourceHandler output() { return output; }
    public ItemStacksResourceHandler etching() { return etching; }
    public ItemStacksResourceHandler etched() { return etched; }
    public ItemStacksResourceHandler fuel() { return fuel; }
    public ItemStacksResourceHandler modding() { return modding; }
    public ItemStacksResourceHandler modded() { return modded; }

    /** 能改装的弹：有弹丸的普通弹（霰弹、步枪弹），没刻术式、没改装过。 */
    public static boolean moddable(ItemStack stack) {
        return (stack.getItem() instanceof BuckshotItem || stack.getItem() instanceof RifleRoundItem) && RoundEffects.plain(stack);
    }
    public ResourceHandler<ItemResource> automation() { return automation; }

    /** 这一批压到哪了，0 到 1。 */
    public float progress() { return time <= 0 ? 0 : Math.min(1f, progress / (float) time); }

    /** 这一批刻到哪了，0 到 1。 */
    public float etchProgress() { return Math.min(1f, etchProgress / (float) ETCH_TIME); }

    /** 这一批改装到哪了，0 到 1。 */
    public float modProgress() { return Math.min(1f, modProgress / (float) MOD_TIME); }

    /** 摆放它的人能驾驭的稳定度：没人摆过时按初始值。 */
    public int stability() { return ownerStability < 0 ? Limits.DEFAULT_STABILITY : ownerStability; }

    /** 摆放它的人（或者第一个打开它的人）成了主人；主人打开时更新他现在的稳定度。 */
    public void noteUser(ServerPlayer player) {
        if (owner == null) owner = player.getUUID();
        if (!owner.equals(player.getUUID())) return;
        ownerStability = PlayerMagic.of(player).stability;
        setChanged();
    }

    /** 现在三格能压出什么（服务端；不能压就是空）。 */
    public ItemStack pressing() {
        var recipe = level == null ? null : AmmoPressRecipe.find(level, input());
        return recipe == null ? ItemStack.EMPTY : recipe.result().create();
    }

    private AmmoPressRecipe.Input input() {
        return new AmmoPressRecipe.Input(stack(inputs, CASING), stack(inputs, POWDER), stack(inputs, HEAD));
    }

    private static ItemStack stack(ItemStacksResourceHandler h, int i) {
        return h.getResource(i).toStack(h.getAmountAsInt(i));
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, AmmoBenchBlockEntity bench) {
        bench.burnFuel();
        bench.press(level, pos);
        bench.etch(level, pos);
        bench.modify(level, pos);
    }

    private void burnFuel() {
        ItemStack f = stack(fuel, 0);
        int value = AlchemyTableBlockEntity.fuelValue(f);
        // 整个放得下才烧，不浪费。
        if (value > 0 && mana + value <= MAX_MANA) {
            fuel.set(0, f.getCount() > 1 ? fuel.getResource(0) : ItemResource.EMPTY, f.getCount() - 1);
            mana += value;
            setChanged();
        }
    }

    private void press(Level level, BlockPos pos) {
        var recipe = AmmoPressRecipe.find(level, input());
        var result = recipe == null ? ItemStack.EMPTY : recipe.result().create();
        if (recipe == null || !fits(output, result)) {
            if (progress != 0 || time != 0) { progress = 0; time = 0; setChanged(); }
            return;
        }
        time = recipe.time();
        if (++progress < time) return;
        progress = 0;
        for (int i = 0; i < 3; i++) take(inputs, i, 1);
        put(output, result);
        level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, .35f, 1.6f);
        level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, .6f, 1.2f);
        setChanged();
    }

    /** 现在铭刻做得了多少，做不了的话为什么。 */
    public record Etch(@Nullable Component problem, int count, @Nullable ItemStack result, double each) {
        static Etch no(String key, Object... args) {
            return new Etch(Component.translatable("logos_dimension.ammo_bench.etch." + key, args), 0, null, 0);
        }
    }

    /** 按四格里的东西、魔力和稳定度，算出这一批能刻几发、刻成什么样（服务端）。 */
    public Etch etchPlan() {
        if (level == null) return Etch.no("idle");
        ItemStack blueprint = stack(etching, BLUEPRINT), rounds = stack(etching, ROUNDS);
        if (blueprint.isEmpty() && rounds.isEmpty()) return Etch.no("idle");
        if (blueprint.isEmpty()) return Etch.no("no_blueprint");
        String word = rounds.isEmpty() ? null : SpellRounds.word(rounds.getItem());
        if (word == null) return Etch.no("no_rounds");
        String source = blueprint.get(LogosMod.SPELL_SOURCE.get());
        Spell spell = SpellRounds.spellFor(source, word);
        if (spell == null) return Etch.no("wrong_round", Component.translatable("logos.rune." + word));
        var recipe = RoundEtchRecipe.find(level, new RoundEtchRecipe.Input(stack(etching, CATALYST), stack(etching, CONDUCTOR)));
        if (recipe == null) return Etch.no("no_materials");
        int capacity = SpellRounds.capacity(word, recipe.capacity());
        if (spell.mana() > capacity) return Etch.no("over_capacity", recipe.tier(), capacity, Node.fmt(spell.mana()));
        if (spell.stability() > stability()) return Etch.no("unstable", Node.fmt(spell.stability()), stability());
        double each = spell.mana();
        int count = Math.min(BATCH, rounds.getCount());
        if (each > 0) count = Math.min(count, (int) Math.floor(mana / each + 1e-9));
        if (count <= 0) return Etch.no("no_mana", Node.fmt(each));
        ItemStack result = rounds.copyWithCount(count);
        // 原文原样刻进去：同一张图纸刻出来的弹组件一样，叠得起来。
        result.set(Firearms.ETCHED.get(), new EtchedSpell(source, recipe.tier(), each));
        // 外观随档位：晶辉的紫水晶尖、贤者的红晶尖……（RoundEffects.spell）
        result.set(Firearms.FINISH.get(), RoundEffects.spell(recipe.tier()));
        if (!fits(etched, result)) return Etch.no("full");
        return new Etch(null, count, result, each);
    }

    private void etch(Level level, BlockPos pos) {
        Etch plan = etchPlan();
        if (plan.result() == null) {
            if (etchProgress != 0) { etchProgress = 0; setChanged(); }
            return;
        }
        if (++etchProgress < ETCH_TIME) return;
        etchProgress = 0;
        take(etching, ROUNDS, plan.count());
        if (!stack(etching, CATALYST).isEmpty()) take(etching, CATALYST, 1);
        take(etching, CONDUCTOR, 1);
        mana -= plan.each() * plan.count();
        put(etched, plan.result());
        level.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, .8f, 1.3f);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1f, 1.1f);
        setChanged();
    }

    /** 现在改装做得了多少，做不了的话为什么。 */
    public record Mod(@Nullable Component problem, int count, @Nullable ItemStack result, @Nullable RoundModRecipe recipe) {
        static Mod no(String key) {
            return new Mod(Component.translatable("logos_dimension.ammo_bench.mod." + key), 0, null, null);
        }
    }

    /** 按两格里的东西，算出这一批能改装几发、改成什么样（服务端）。 */
    public Mod modPlan() {
        if (level == null) return Mod.no("idle");
        ItemStack rounds = stack(modding, MOD_ROUNDS), additive = stack(modding, ADDITIVE);
        if (rounds.isEmpty() && additive.isEmpty()) return Mod.no("idle");
        if (rounds.isEmpty()) return Mod.no("no_rounds");
        if (additive.isEmpty()) return Mod.no("no_additive");
        var input = new RoundModRecipe.Input(rounds, additive);
        var recipe = AmmoRecipeBook.mods(level).stream().filter(r -> r.matches(input, level)).findFirst().orElse(null);
        if (recipe == null) return Mod.no("unknown");
        int count = Math.min(BATCH, rounds.getCount());
        ItemStack result = recipe.assemble(input).copyWithCount(count);
        if (!fits(modded, result)) return Mod.no("full");
        return new Mod(null, count, result, recipe);
    }

    private void modify(Level level, BlockPos pos) {
        Mod plan = modPlan();
        if (plan.result() == null) {
            if (modProgress != 0) { modProgress = 0; setChanged(); }
            return;
        }
        if (++modProgress < MOD_TIME) return;
        modProgress = 0;
        take(modding, MOD_ROUNDS, plan.count());
        take(modding, ADDITIVE, 1);
        put(modded, plan.result());
        // 添加物用完剩下的（水银瓶剩空瓶）放在台子上。
        plan.recipe().remainder().ifPresent(r -> Block.popResource(level, pos.above(), r.create()));
        level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, .7f, 1.2f);
        setChanged();
    }

    private static void take(ItemStacksResourceHandler h, int slot, int n) {
        int left = h.getAmountAsInt(slot) - n;
        h.set(slot, left > 0 ? h.getResource(slot) : ItemResource.EMPTY, Math.max(left, 0));
    }

    private static void put(ItemStacksResourceHandler h, ItemStack stack) {
        h.set(0, ItemResource.of(stack), h.getAmountAsInt(0) + stack.getCount());
    }

    /** 出货格放不放得下这一批：空着，或者是同一种东西（连组件一起）而且还有地方。 */
    private static boolean fits(ItemStacksResourceHandler h, ItemStack result) {
        int held = h.getAmountAsInt(0);
        if (held == 0) return true;
        return h.getResource(0).equals(ItemResource.of(result)) && held + result.getCount() <= result.getMaxStackSize();
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        inputs.serialize(out.child("inputs"));
        output.serialize(out.child("output"));
        etching.serialize(out.child("etching"));
        etched.serialize(out.child("etched"));
        fuel.serialize(out.child("fuel"));
        out.putInt("progress", progress);
        out.putInt("time", time);
        out.putInt("etch_progress", etchProgress);
        out.putInt("mod_progress", modProgress);
        modding.serialize(out.child("modding"));
        modded.serialize(out.child("modded"));
        out.putDouble("mana", mana);
        if (owner != null) out.store("owner", UUIDUtil.CODEC, owner);
        out.putInt("owner_stability", ownerStability);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        in.child("inputs").ifPresent(inputs::deserialize);
        in.child("output").ifPresent(output::deserialize);
        in.child("etching").ifPresent(etching::deserialize);
        in.child("etched").ifPresent(etched::deserialize);
        in.child("fuel").ifPresent(fuel::deserialize);
        progress = in.getIntOr("progress", 0);
        time = in.getIntOr("time", 0);
        etchProgress = in.getIntOr("etch_progress", 0);
        modProgress = in.getIntOr("mod_progress", 0);
        in.child("modding").ifPresent(modding::deserialize);
        in.child("modded").ifPresent(modded::deserialize);
        mana = Math.clamp(in.getDoubleOr("mana", 0), 0, MAX_MANA);
        owner = in.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerStability = in.getIntOr("owner_stability", -1);
    }

    /** 拆掉时材料、图纸、燃料和成品都掉出来；存的魔力散掉。 */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null) return;
        for (var h : List.of(inputs, output, etching, etched, modding, modded, fuel)) {
            for (int i = 0; i < h.size(); i++) {
                var s = stack(h, i);
                if (!s.isEmpty()) Block.popResource(level, pos, s);
            }
        }
    }

    /** 漏斗、管道看到的一段格子：只进（材料）或只出（成品）。 */
    private static final class OneWay extends RangedResourceHandler<ItemResource> {
        private final boolean in;

        OneWay(ResourceHandler<ItemResource> delegate, int start, int end, boolean in) {
            super(delegate, start, end);
            this.in = in;
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return in && super.isValid(index, resource);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return in ? super.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            return in ? super.insert(resource, amount, transaction) : 0;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return in ? 0 : super.extract(index, resource, amount, transaction);
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            return in ? 0 : super.extract(resource, amount, transaction);
        }
    }
}
