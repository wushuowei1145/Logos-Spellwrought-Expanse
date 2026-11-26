package dev.logos.dimension.firearm;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.RangedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * 铸弹台：弹壳、火药、弹头三格对上一条压制配方（{@link AmmoPressRecipe}），出货格放得下，就一批一批地压，
 * 每批压满配方的刻数后各扣一份、出一批弹。东西拿走或出货格满了就停，进度归零。
 * 漏斗和管道：三种材料从任何一面送进对应的格子，弹药从出货格取走。
 */
public class AmmoBenchBlockEntity extends BlockEntity {
    public static final int CASING = 0, POWDER = 1, HEAD = 2;

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
    private final ItemStacksResourceHandler output = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return false;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    private final ResourceHandler<ItemResource> automation = new CombinedResourceHandler<>(new OneWay(inputs, 0, 3, true), new OneWay(output, 0, 1, false));

    /** 这一批压了几刻，和它要几刻（没在压时为 0）。 */
    private int progress, time;

    public AmmoBenchBlockEntity(BlockPos pos, BlockState state) {
        super(AmmoBench.BENCH_ENTITY.get(), pos, state);
    }

    public ItemStacksResourceHandler inputs() { return inputs; }
    public ItemStacksResourceHandler output() { return output; }
    public ResourceHandler<ItemResource> automation() { return automation; }

    /** 这一批压到哪了，0 到 1。 */
    public float progress() { return time <= 0 ? 0 : Math.min(1f, progress / (float) time); }

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
        var recipe = AmmoPressRecipe.find(level, bench.input());
        var result = recipe == null ? ItemStack.EMPTY : recipe.result().create();
        if (recipe == null || !bench.fits(result)) {
            if (bench.progress != 0 || bench.time != 0) { bench.progress = 0; bench.time = 0; bench.setChanged(); }
            return;
        }
        bench.time = recipe.time();
        if (++bench.progress < bench.time) return;
        bench.progress = 0;
        for (int i = 0; i < 3; i++) {
            int left = bench.inputs.getAmountAsInt(i) - 1;
            bench.inputs.set(i, left > 0 ? bench.inputs.getResource(i) : ItemResource.EMPTY, Math.max(left, 0));
        }
        int held = bench.output.getAmountAsInt(0);
        bench.output.set(0, ItemResource.of(result), held + result.getCount());
        level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, .35f, 1.6f);
        level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, .6f, 1.2f);
        bench.setChanged();
    }

    /** 出货格放不放得下这一批：空着，或者是同一种弹而且还有地方。 */
    private boolean fits(ItemStack result) {
        int held = output.getAmountAsInt(0);
        if (held == 0) return true;
        return output.getResource(0).equals(ItemResource.of(result)) && held + result.getCount() <= result.getMaxStackSize();
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        inputs.serialize(out.child("inputs"));
        output.serialize(out.child("output"));
        out.putInt("progress", progress);
        out.putInt("time", time);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        in.child("inputs").ifPresent(inputs::deserialize);
        in.child("output").ifPresent(output::deserialize);
        progress = in.getIntOr("progress", 0);
        time = in.getIntOr("time", 0);
    }

    /** 拆掉时材料和压好的弹都掉出来。 */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null) return;
        for (var h : List.of(inputs, output)) {
            for (int i = 0; i < h.size(); i++) {
                var s = stack(h, i);
                if (!s.isEmpty()) Block.popResource(level, pos, s);
            }
        }
    }

    /** 漏斗、管道看到的一段格子：只进（材料）或只出（弹药）。 */
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
