package dev.logos.dimension.elven.blight;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** 一棵世界树一次战斗。保存实体 UUID，卸载区块不会把暂时查不到的女王当成死亡并重生。 */
public class QueenHeart extends BlockEntity {
    private @Nullable UUID queen;
    public static final int GUARDIAN_DROP = 7;
    private int guardianDrop = GUARDIAN_DROP;
    public QueenHeart(BlockPos p, BlockState s) { super(ElvenBlight.HEART_ENTITY.get(), p, s); }
    public @Nullable UUID queen() { return queen; }
    public Vec3 guardianPosition() { return Vec3.atBottomCenterOf(worldPosition).add(0, -guardianDrop, 0); }

    void tick() {
        if (level instanceof ServerLevel world) BlightReclamation.get(world).source(world, worldPosition, getBlockState().getValue(QueenHeartBlock.DEFEATED));
        if (!(level instanceof ServerLevel world) || world.getGameTime() % 10 != 0 || queen != null
                || getBlockState().getValue(QueenHeartBlock.DEFEATED)) return;
        var player = world.getNearestPlayer(worldPosition.getX() + .5, worldPosition.getY(), worldPosition.getZ() + .5, 40,
                e -> e instanceof ServerPlayer p && p.isAlive() && !p.isSpectator());
        if (player instanceof ServerPlayer p) guardian(p);
    }

    /** 触发与测试共用的入口，重复调用始终返回同一场战斗。 */
    public @Nullable CorruptedElfQueen awaken(ServerPlayer player) {
        CorruptedElfQueen boss = guardian(player);
        if (boss != null) boss.engage(player);
        return boss;
    }

    /** 远处可见坐在核心下的守护者，创造模式只展示坐姿，不自动开战。 */
    private @Nullable CorruptedElfQueen guardian(ServerPlayer player) {
        if (!(level instanceof ServerLevel world) || getBlockState().getValue(QueenHeartBlock.DEFEATED)) return null;
        if (queen != null) return world.getEntity(queen) instanceof CorruptedElfQueen q ? q : null;
        CorruptedElfQueen boss = ElvenBlight.QUEEN.get().create(world, EntitySpawnReason.TRIGGERED);
        if (boss == null) return null;
        Vec3 dir = player.position().subtract(Vec3.atBottomCenterOf(worldPosition)).multiply(1, 0, 1).normalize();
        if (dir.lengthSqr() < .01) dir = new Vec3(0, 0, 1);
        boss.setPos(guardianPosition());
        boss.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        boss.yBodyRot = boss.getYRot();
        boss.guard(worldPosition);
        if (!world.addFreshEntity(boss)) return null;
        queen = boss.getUUID();
        setChanged();
        return boss;
    }

    public void engaged(UUID id) {
        if (level instanceof ServerLevel world && id.equals(queen))
            world.setBlock(worldPosition, getBlockState().setValue(QueenHeartBlock.AWAKENED, true), Block.UPDATE_CLIENTS);
    }

    public void defeated(UUID id) {
        if (!(level instanceof ServerLevel world) || !id.equals(queen)) return;
        world.setBlock(worldPosition, getBlockState().setValue(QueenHeartBlock.DEFEATED, true), Block.UPDATE_CLIENTS);
        BlightReclamation.get(world).source(world, worldPosition, true);
        setChanged();
    }

    @Override protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (queen != null) out.putString("Queen", queen.toString());
        out.putInt("GuardianDrop", guardianDrop);
    }
    @Override protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        // 旧版核心在地面，缺少高度标记时让女王留在原地，避免沉进地下。
        guardianDrop = in.getIntOr("GuardianDrop", 0);
        String saved = in.getStringOr("Queen", "");
        try { queen = saved.isEmpty() ? null : UUID.fromString(saved); }
        catch (IllegalArgumentException ex) { queen = null; }
    }
}
