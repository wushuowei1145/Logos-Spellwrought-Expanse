package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import java.util.ArrayList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import java.util.List;

/**
 * 长风草皮上的原版短草与高草换成宽叶草簇。仍画进区块网格，光影包的阴影、光照和它自带的草摆动照常生效；
 * 物品、破坏粒子和别处的草保留原模型。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public record ChangfengGrassModel(BlockStateModel original) implements BlockStateModel {
    private record Form(int part, int variant, int rotation, StandaloneModelKey<BlockStateModel> key) {}
    private static final List<Form> FORMS = forms();
    private static volatile List<BlockStateModel> blades = List.of();

    private static List<Form> forms() {
        var forms = new ArrayList<Form>();
        for (int part = 0; part < 3; part++) for (int variant = 0; variant < 3; variant++) for (int rotation = 0; rotation < 4; rotation++) {
            String name = "changfeng_" + part + "_" + variant + "_" + rotation;
            forms.add(new Form(part, variant, rotation, new StandaloneModelKey<>(() -> name)));
        }
        return List.copyOf(forms);
    }

    @SubscribeEvent static void register(ModelEvent.RegisterStandalone event) {
        for (var form : FORMS) {
            String name = form.part() == 0 ? "short_" + form.variant() : "tall_" + form.variant() + (form.part() == 1 ? "_lower" : "_upper");
            var rotation = new net.minecraft.client.renderer.block.dispatch.Variant.SimpleModelState(com.mojang.math.Quadrant.R0,
                    com.mojang.math.Quadrant.values()[form.rotation()], com.mojang.math.Quadrant.R0, false).asModelState();
            event.register(form.key(), SimpleUnbakedStandaloneModel.blockStateModel(LogosDimension.id("block/changfeng/" + name), rotation));
        }
    }

    @SubscribeEvent static void bake(ModelEvent.ModifyBakingResult event) {
        blades = FORMS.stream().map(form -> event.getBakingResult().standaloneModels().get(form.key())).toList();
        event.getBakingResult().blockStateModels().replaceAll((state, model) ->
                grass(state) ? new ChangfengGrassModel(model) : model);
    }

    /** 双层草从共同根点检查长风草皮，也用根点的随机数选株型，上下两半对得上。可从区块编译线程调用。 */
    static BlockStateModel appearance(BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel original) {
        if (blades.isEmpty() || !grass(state)) return original;
        int part = state.is(Blocks.SHORT_GRASS) ? 0 : state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? 1 : 2;
        BlockPos root = part == 2 ? pos.below() : pos;
        if (!level.getBlockState(root.below()).is(CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get())) return original;
        long seed = net.minecraft.util.Mth.getSeed(root.getX(), root.getY(), root.getZ());
        int choice = Math.floorMod(seed, 12);
        var model = blades.get(part * 12 + choice);
        return model == null ? original : model;
    }

    static boolean grass(BlockState state) {
        return state.is(Blocks.TALL_GRASS) || state.is(Blocks.SHORT_GRASS);
    }

    @Override public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        appearance(level, pos, state, original).collectParts(level, pos, state, random, parts);
    }
    @Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts) { original.collectParts(random, parts); }
    @Override public Material.Baked particleMaterial() { return original.particleMaterial(); }
    @Override public int materialFlags() { return original.materialFlags(); }
    @Override public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) { return original.particleMaterial(level, pos, state); }
    @Override public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) { return original.materialFlags(level, pos, state); }
}
