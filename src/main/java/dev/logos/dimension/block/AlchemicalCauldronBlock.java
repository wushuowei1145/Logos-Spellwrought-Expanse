package dev.logos.dimension.block;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.instance.BoundCauldronBlockEntity;
import dev.logos.dimension.instance.CauldronWorlds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.InsideBlockEffectType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The liquid is a portal surface; the solid rim and hollow basin remain real collision geometry. */
public final class AlchemicalCauldronBlock extends Block implements EntityBlock {
    public static final MapCodec<AlchemicalCauldronBlock> CODEC = simpleCodec(AlchemicalCauldronBlock::new);
    public static final double MODEL_SCALE = 1.5;
    public static final double RIM_HEIGHT = 13.0 * MODEL_SCALE / 16;
    private static final VoxelShape SHAPE = Shapes.or(
            scaledBox(1, 3, 1, 15, 4, 15),
            scaledBox(1, 4, 1, 2, 11, 15), scaledBox(14, 4, 1, 15, 11, 15),
            scaledBox(2, 4, 1, 14, 11, 2), scaledBox(2, 4, 14, 14, 11, 15),
            scaledBox(0, 11, 0, 16, 13, 2), scaledBox(0, 11, 14, 16, 13, 16),
            scaledBox(0, 11, 2, 2, 13, 14), scaledBox(14, 11, 2, 16, 13, 14),
            scaledBox(2, 0, 2, 4, 3, 4), scaledBox(12, 0, 2, 14, 3, 4),
            scaledBox(2, 0, 12, 4, 3, 14), scaledBox(12, 0, 12, 14, 3, 14));
    private static final VoxelShape BASIN = scaledBox(2, 4, 2, 14, 9, 14);
    private static VoxelShape scaledBox(double x1,double y1,double z1,double x2,double y2,double z2) {
        return box(8+(x1-8)*MODEL_SCALE,y1*MODEL_SCALE,8+(z1-8)*MODEL_SCALE,
                8+(x2-8)*MODEL_SCALE,y2*MODEL_SCALE,8+(z2-8)*MODEL_SCALE);
    }
    public AlchemicalCauldronBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override public MapCodec<AlchemicalCauldronBlock> codec() { return CODEC; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BoundCauldronBlockEntity(pos, state); }
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,net.minecraft.world.phys.BlockHitResult hit){
        if(player instanceof ServerPlayer serverPlayer&&level.getBlockEntity(pos) instanceof BoundCauldronBlockEntity binding){
            var id=binding.worldId();
            serverPlayer.sendSystemMessage(id==null?net.minecraft.network.chat.Component.translatable("message.logos_dimension.unbound_cauldron"):
                    net.minecraft.network.chat.Component.translatable("message.logos_dimension.bound_dimension",CauldronWorlds.key(id).identifier().toString()));
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                                  ItemStack toolStack, boolean willHarvest, FluidState fluid) {
        if (!(level.getBlockEntity(pos) instanceof BoundCauldronBlockEntity binding)) {
            return super.onDestroyedByPlayer(state, level, pos, player, toolStack, willHarvest, fluid);
        }
        // Scope this flag to actual removal, so a cancelled mining attempt cannot suppress later destruction.
        binding.setBeingMined(true, player.preventsBlockDrops());
        try {
            return super.onDestroyedByPlayer(state, level, pos, player, toolStack, willHarvest, fluid);
        } finally {
            binding.setBeingMined(false, false);
        }
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
    @Override protected VoxelShape getEntityInsideCollisionShape(BlockState state, BlockGetter level, BlockPos pos, Entity entity) { return BASIN; }

    @Override protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                          InsideBlockEffectApplier effects, boolean isPrecise) {
        if (!(entity instanceof ServerPlayer player) || !isPrecise || !insideLiquid(player, pos)) return;
        // Defer until block collision traversal is finished, so changing levels cannot invalidate its iteration.
        effects.runAfter(InsideBlockEffectType.EXTINGUISH, e -> {
            if (e instanceof ServerPlayer p && p.level() == level && insideLiquid(p, pos)
                    && p.canUsePortal(false) && !p.isSpectator() && !p.isOnPortalCooldown()) {
                Vec3 home = safeReturn(p, pos);
                boolean moved = level.getBlockEntity(pos) instanceof BoundCauldronBlockEntity binding
                        && CauldronWorlds.enter(p, binding, home);
                p.setPortalCooldown(moved ? 80 : 20);
            }
        });
    }
    private static boolean insideLiquid(Entity entity, BlockPos pos) {
        double x = entity.getX() - pos.getX(), y = entity.getY() - pos.getY(), z = entity.getZ() - pos.getZ();
        double min=(8-6*MODEL_SCALE)/16, max=(8+6*MODEL_SCALE)/16;
        return x > min && x < max && z > min && z < max
                && y >= 4.0 * MODEL_SCALE / 16 - .01 && y < 9.0 * MODEL_SCALE / 16;
    }
    private static Vec3 safeReturn(ServerPlayer player, BlockPos pos) {
        var level = player.level();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos feet = pos.relative(direction);
            for (int dy : new int[] {0, 1, -1}) {
                BlockPos candidate = feet.above(dy);
                Vec3 at = candidate.getBottomCenter();
                if (level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), Direction.UP)
                        && level.getFluidState(candidate).isEmpty()
                        && level.noCollision(player, player.getBoundingBox().move(at.subtract(player.position())))) return at;
            }
        }
        // A cauldron on a narrow pillar still offers a solid rim to stand on; never save inside the liquid.
        return new Vec3(pos.getX() + .5, pos.getY() + RIM_HEIGHT + .001, pos.getZ() - .12);
    }
    @Override public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double angle = level.getGameTime() * .12 + random.nextDouble() * Math.PI * 2;
        level.addParticle(ParticleTypes.END_ROD, pos.getX() + .5 + Math.cos(angle) * .28 * MODEL_SCALE,
                pos.getY() + .57 * MODEL_SCALE, pos.getZ() + .5 + Math.sin(angle) * .28 * MODEL_SCALE,
                -Math.sin(angle) * .012, .012, Math.cos(angle) * .012);
    }
}
