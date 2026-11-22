package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
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

/** 保留原模型和物品外观；只有已缓存的草丛改交风动管线，避免静态草重影。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public record GrassWindModel(BlockStateModel original) implements BlockStateModel {
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
        WindGrass.modelsChanged();
        blades = FORMS.stream().map(form -> event.getBakingResult().standaloneModels().get(form.key())).toList();
        event.getBakingResult().blockStateModels().replaceAll((state, model) ->
                grass(state) ? new GrassWindModel(model) : model);
    }

    /** 只换长风草皮上的草片；关闭风动后也保留新株型，双层共用根点随机数。 */
    static BlockStateModel appearance(BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel original) {
        if (blades.isEmpty() || !WindGrass.eligible(level, pos, state) || !(state.is(Blocks.TALL_GRASS) || state.is(Blocks.SHORT_GRASS))) return original;
        int part = state.is(Blocks.SHORT_GRASS) ? 0 : state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? 1 : 2;
        BlockPos root = part == 2 ? pos.below() : pos;
        long seed = net.minecraft.util.Mth.getSeed(root.getX(), root.getY(), root.getZ());
        int choice = Math.floorMod(seed, 12);
        var model = blades.get(part * 12 + choice);
        return model == null ? original : model;
    }

    static boolean grass(BlockState state) {
        return state.is(Blocks.TALL_GRASS) || state.is(Blocks.SHORT_GRASS)
                || state.is(CloudseaBlocks.MEADOW_GRASS.get()) || state.is(CloudseaBlocks.MEADOW_GRASS_YOUNG.get());
    }

    @Override public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        if (!WindGrass.replace(level, pos, state)) appearance(level, pos, state, original).collectParts(level, pos, state, random, parts);
    }
    @Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts) { original.collectParts(random, parts); }
    @Override public Material.Baked particleMaterial() { return original.particleMaterial(); }
    @Override public int materialFlags() { return original.materialFlags(); }
    @Override public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) { return original.particleMaterial(level, pos, state); }
    @Override public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) { return original.materialFlags(level, pos, state); }
}
