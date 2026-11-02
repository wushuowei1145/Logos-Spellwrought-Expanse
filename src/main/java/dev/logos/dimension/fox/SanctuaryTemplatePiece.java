package dev.logos.dimension.fox;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** 聚落里的一块 NBT 模板：核心分块或可替换地块上的建筑，带聚落整体的镜像与旋转。 */
public class SanctuaryTemplatePiece extends TemplateStructurePiece {
    public SanctuaryTemplatePiece(StructureTemplateManager manager, String template, BlockPos position, Mirror mirror, Rotation rotation) {
        super(FoxSanctuary.TEMPLATE.get(), 0, manager, Identifier.parse(template), template, settings(mirror, rotation), position);
    }

    public SanctuaryTemplatePiece(StructureTemplateManager manager, CompoundTag tag) {
        super(FoxSanctuary.TEMPLATE.get(), tag, manager, location -> settings(
            Mirror.valueOf(tag.getStringOr("Mirror", "NONE")), Rotation.valueOf(tag.getStringOr("Rot", "NONE"))));
    }

    private static StructurePlaceSettings settings(Mirror mirror, Rotation rotation) {
        return new StructurePlaceSettings()
            .setMirror(mirror)
            .setRotation(rotation)
            .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK)
            .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
            // 与原版拼接一样不做形状更新：生成时还没有光照，作物与水稻会被判成“无法存活”而消失；
            // 栅栏连接、楼梯形状等已在设计脚本里写好
            .setKnownShape(true);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        super.addAdditionalSaveData(context, tag);
        tag.putString("Mirror", placeSettings.getMirror().name());
        tag.putString("Rot", placeSettings.getRotation().name());
    }

    @Override
    protected void handleDataMarker(String markerId, BlockPos position, ServerLevelAccessor level, RandomSource random, BoundingBox chunkBB) {
    }
}
