package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.paint.PaintSurfaceBlockEntity;
import dev.logos.dimension.paint.PaintSystem;
import java.util.List;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** 漆面的客户端着色：从方块实体读 RGB，乘到半透明色层上。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class PaintClient {
    private static final BlockTintSource SURFACE_TINT = new BlockTintSource() {
        @Override
        public int color(BlockState state) {
            return PaintSurfaceBlockEntity.DEFAULT_COLOR;
        }

        @Override
        public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            if (level.getBlockEntity(pos) instanceof PaintSurfaceBlockEntity be) {
                return be.paint();
            }
            return PaintSurfaceBlockEntity.DEFAULT_COLOR;
        }
    };

    @SubscribeEvent
    static void registerBlockColors(RegisterColorHandlersEvent.BlockTintSources event) {
        event.register(List.of(SURFACE_TINT), PaintSystem.SURFACE.get(), PaintSystem.PALETTE.get());
    }

    private PaintClient() {}
}
