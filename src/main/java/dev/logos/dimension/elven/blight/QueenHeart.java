package dev.logos.dimension.elven.blight;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 一棵世界树一次战斗。保存女王和精灵公主的 UUID，卸载区块不会把暂时查不到的她们当成不在了而重新生成。
 * 公主由这里在世界树门口生成（有玩家走近门口时），每棵树一位；只有在她最后出现的地方已经加载、却找不到她时，才在门口重新生成。
 */
public class QueenHeart extends BlockEntity {
    /** 客户端已加载的腐败之心（画供养血管时找最近的世界树用）；只放坐标，不放客户端类。 */
    public static final Set<BlockPos> LOADED_CLIENT = ConcurrentHashMap.newKeySet();
    private @Nullable UUID queen, princess;
    private @Nullable BlockPos princessSeen;
    private int princessMissing;
    public static final int GUARDIAN_DROP = 7;
    /** 有玩家走进门口这么近时生成公主。 */
    public static final double PRINCESS_RANGE = 48;
    private int guardianDrop = GUARDIAN_DROP;
    public QueenHeart(BlockPos p, BlockState s) { super(ElvenBlight.HEART_ENTITY.get(), p, s); }
    public @Nullable UUID queen() { return queen; }
    public @Nullable UUID princessId() { return princess; }
    public Vec3 guardianPosition() { return Vec3.atBottomCenterOf(worldPosition).add(0, -guardianDrop, 0); }

    @Override public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) LOADED_CLIENT.add(worldPosition.immutable());
    }
    @Override public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) LOADED_CLIENT.remove(worldPosition);
    }

    void tick() {
        if (level instanceof ServerLevel world) BlightReclamation.get(world).source(world, worldPosition, getBlockState().getValue(QueenHeartBlock.DEFEATED));
        if (!(level instanceof ServerLevel world) || world.getGameTime() % 10 != 0 || getBlockState().getValue(QueenHeartBlock.DEFEATED)) return;
        tickPrincess(world);
        if (queen != null) return;
        var player = world.getNearestPlayer(worldPosition.getX() + .5, worldPosition.getY(), worldPosition.getZ() + .5, 40,
                e -> e instanceof ServerPlayer p && p.isAlive() && !p.isSpectator());
        if (player instanceof ServerPlayer p) guardian(p);
    }

    private Direction facing() {
        return getBlockState().hasProperty(QueenHeartBlock.FACING) ? getBlockState().getValue(QueenHeartBlock.FACING) : Direction.SOUTH;
    }

    private Vec3 gate(ServerLevel world) {
        return QueenArena.at(worldPosition, facing(), QueenArena.get(world.getServer()).gate());
    }

    private void tickPrincess(ServerLevel world) {
        if (princess != null) {
            if (world.getEntity(princess) instanceof ElfPrincess p && p.isAlive()) {
                princessMissing = 0;
                princessSeen = p.blockPosition();
                return;
            }
            // 她最后出现的地方没加载时不算不在；加载着却连续找不到她（例如被命令清掉）才重新生成。
            if (princessSeen == null || world.isPositionEntityTicking(princessSeen)) {
                if (++princessMissing >= 10) { princess = null; princessSeen = null; princessMissing = 0; setChanged(); }
            }
            return;
        }
        Vec3 gate = gate(world);
        var near = world.getNearestPlayer(gate.x, gate.y, gate.z, PRINCESS_RANGE, e -> e instanceof ServerPlayer p && p.isAlive() && !p.isSpectator());
        if (near != null) spawnPrincess(world);
    }

    private @Nullable ElfPrincess spawnPrincess(ServerLevel world) {
        ElfPrincess p = ElvenBlight.PRINCESS.get().create(world, EntitySpawnReason.TRIGGERED);
        if (p == null) return null;
        p.home(worldPosition);
        Vec3 gate = gate(world);
        p.setPos(gate);
        purifyGate(world, BlockPos.containing(gate));
        if (!world.addFreshEntity(p)) return null;
        p.returnToGate(world);
        princess = p.getUUID();
        princessSeen = p.blockPosition();
        princessMissing = 0;
        setChanged();
        return p;
    }

    /** 公主站的那一小片地面已经净化过：门口脚下几格的腐败污秽、腐败脉光还原成原来的样子。 */
    private static void purifyGate(ServerLevel world, BlockPos gate) {
        for (BlockPos at : BlockPos.betweenClosed(gate.offset(-3, -2, -3), gate.offset(3, 1, 3))) {
            double dx = at.getX() - gate.getX(), dz = at.getZ() - gate.getZ();
            if (dx * dx + dz * dz > 3.5 * 3.5) continue;
            BlockState state = world.getBlockState(at);
            if (!(state.getBlock() instanceof BlightMemoryBlock) && !state.is(ElvenBlight.LUMEN.get())) continue;
            BlockState original = WorldTreePurification.original(world, at, state);
            if (original != null) world.setBlock(at, original, Block.UPDATE_ALL);
        }
    }

    /** 这棵树的公主（已加载时）。 */
    public @Nullable ElfPrincess princess(ServerLevel world) {
        return princess != null && world.getEntity(princess) instanceof ElfPrincess p && p.isAlive() ? p : null;
    }

    /** 公主到场了（在大厅入口等着，或已经在战斗里）。 */
    public boolean princessReady(ServerLevel world) {
        var p = princess(world);
        return p != null && p.ready();
    }

    /** 触发与测试共用的入口，重复调用始终返回同一场战斗；公主不在时直接请到大厅入口。 */
    public @Nullable CorruptedElfQueen awaken(ServerPlayer player) {
        CorruptedElfQueen boss = guardian(player);
        if (boss != null && level instanceof ServerLevel world) {
            var p = princess(world);
            if (p == null && princess == null) p = spawnPrincess(world);
            if (p != null) p.arrive(world);
            boss.engage(player);
        }
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
        if (princess != null) out.putString("Princess", princess.toString());
        if (princessSeen != null) out.putLong("PrincessSeen", princessSeen.asLong());
        out.putInt("GuardianDrop", guardianDrop);
    }
    @Override protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        // 旧版核心在地面，缺少高度标记时让女王留在原地，避免沉进地下。
        guardianDrop = in.getIntOr("GuardianDrop", 0);
        queen = uuid(in.getStringOr("Queen", ""));
        princess = uuid(in.getStringOr("Princess", ""));
        princessSeen = in.getLong("PrincessSeen").map(BlockPos::of).orElse(null);
    }
    private static @Nullable UUID uuid(String saved) {
        try { return saved.isEmpty() ? null : UUID.fromString(saved); }
        catch (IllegalArgumentException ex) { return null; }
    }
}
