package dev.logos.dimension.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.InsideBlockEffectType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/** Source and flowing cells share precise fluid-volume contact effects. */
public abstract class AlchemicalFluid extends BaseFlowingFluid {
    private final boolean hot;
    protected AlchemicalFluid(Properties properties, boolean hot) { super(properties); this.hot = hot; }

    @Override protected void entityInside(Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects) {
        if (hot) {
            effects.apply(InsideBlockEffectType.CLEAR_FREEZE);
            effects.apply(InsideBlockEffectType.LAVA_IGNITE);
            effects.runAfter(InsideBlockEffectType.LAVA_IGNITE, Entity::lavaHurt);
        } else if (level instanceof ServerLevel) {
            effects.runAfter(InsideBlockEffectType.EXTINGUISH, e -> {
                if (!(e instanceof LivingEntity living) || !living.isAlive()) return;
                if (living instanceof Player player && (player.isCreative() || player.isSpectator())) return;
                living.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0));
                living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 0));
            });
        }
    }

    public static final class Source extends AlchemicalFluid {
        public Source(Properties properties, boolean hot) { super(properties, hot); }
        @Override public int getAmount(FluidState state) { return 8; }
        @Override public boolean isSource(FluidState state) { return true; }
    }
    public static final class Flowing extends AlchemicalFluid {
        public Flowing(Properties properties, boolean hot) {
            super(properties, hot);
            registerDefaultState(getStateDefinition().any().setValue(LEVEL, 7));
        }
        @Override protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(builder); builder.add(LEVEL);
        }
        @Override public int getAmount(FluidState state) { return state.getValue(LEVEL); }
        @Override public boolean isSource(FluidState state) { return false; }
    }
}
