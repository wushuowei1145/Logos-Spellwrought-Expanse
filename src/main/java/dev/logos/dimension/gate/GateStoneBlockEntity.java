package dev.logos.dimension.gate;

import dev.logos.runtime.ManaVessel;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * 启门石里的魔力和开门过程。开门分几刻把门洞一点点填满；中途卸载区块也没关系，
 * {@code igniting} 存盘，读回来重新泛洪（已经填好的传送面也算能填），接着填。
 */
public class GateStoneBlockEntity extends BlockEntity implements ManaVessel {
    public static final double MAX_MANA = 1000;
    /** 大约多少刻填满门洞。 */
    private static final int FILL_TICKS = 24;
    /** 特效发给多远以内的玩家（格）。 */
    private static final double VIEW = 96;

    private double mana;
    private boolean igniting;
    private @Nullable List<BlockPos> queue;
    private Direction.Axis axis = Direction.Axis.X;
    private int cursor;

    public GateStoneBlockEntity(BlockPos pos, BlockState state) {
        super(Gate.GATE_STONE_BE.get(), pos, state);
    }

    @Override public double mana() { return mana; }
    @Override public double maxMana() { return MAX_MANA; }
    public boolean igniting() { return igniting; }

    @Override
    public double receiveMana(double amount) {
        double taken = Math.clamp(amount, 0, MAX_MANA - mana);
        if (taken <= 0) return 0;
        mana += taken;
        // 术式给的量是小数，差一丝也算满
        if (mana >= MAX_MANA - 1e-6) mana = MAX_MANA;
        setChanged();
        syncCharge();
        if (mana >= MAX_MANA) tryIgnite(null);
        return taken;
    }

    private void syncCharge() {
        if (level == null || level.isClientSide()) return;
        int charge = (int) Math.floor(mana / MAX_MANA * 4);
        BlockState state = getBlockState();
        if (state.hasProperty(GateStoneBlock.CHARGE) && state.getValue(GateStoneBlock.CHARGE) != charge)
            level.setBlock(worldPosition, state.setValue(GateStoneBlock.CHARGE, charge), Block.UPDATE_CLIENTS);
    }

    public void showStatus(Player player) {
        player.sendOverlayMessage(Component.translatable("message.logos_dimension.gate.status", (int) mana, (int) MAX_MANA));
    }

    /** 没有玩家在操作时（术式、存储罐灌满）提示给最近的玩家。 */
    private void tell(@Nullable Player player, String key) {
        Player to = player != null ? player : level.getNearestPlayer(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5, 16, false);
        if (to != null) to.sendOverlayMessage(Component.translatable("message.logos_dimension.gate." + key));
    }

    /** 魔力已满时尝试开门。 */
    public void tryIgnite(@Nullable Player player) {
        if (level == null || level.isClientSide() || igniting) return;
        if (!GateTravel.linked(level.dimension())) { tell(player, "wrong_dimension"); return; }
        if (level.getBlockState(worldPosition.above()).is(Gate.GATE_PORTAL.get())) { tell(player, "already_open"); return; }
        GateShape.Found found = GateShape.find(level, worldPosition);
        if (found == null) { tell(player, "no_frame"); return; }
        igniting = true;
        queue = found.cells();
        axis = found.axis();
        cursor = 0;
        setChanged();
        ServerLevel server = (ServerLevel) level;
        server.playSound(null, worldPosition, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.6f, .75f);
        server.playSound(null, worldPosition, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.4f, .7f);
        send(server, found);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GateStoneBlockEntity stone) {
        if (stone.igniting) stone.fillStep(level);
    }

    private void fillStep(Level level) {
        if (queue == null) {
            // 开门途中存盘重进：重新泛洪接着填
            GateShape.Found found = GateShape.find(level, worldPosition);
            if (found == null) { igniting = false; setChanged(); return; }
            queue = found.cells();
            axis = found.axis();
            cursor = 0;
        }
        int step = Math.max(1, (int) Math.ceil(queue.size() / (double) FILL_TICKS));
        for (int i = 0; i < step && cursor < queue.size(); i++, cursor++) {
            BlockPos cell = queue.get(cursor);
            if (GateShape.fillable(level.getBlockState(cell))) level.setBlock(cell, Gate.GATE_PORTAL.get().at(cell, axis), Block.UPDATE_ALL);
        }
        if (cursor < queue.size()) return;
        finishFill(level);
    }

    private void finishFill(Level level) {
        List<BlockPos> cells = queue;
        igniting = false;
        queue = null;
        setChanged();
        GateShape.Found check = GateShape.find(level, worldPosition);
        if (check == null || check.axis() != axis) {
            // 填的途中门框被拆了：收掉已经填的
            for (BlockPos cell : cells) if (level.getBlockState(cell).is(Gate.GATE_PORTAL.get())) level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            tell(null, "no_frame");
            return;
        }
        GateShape.refreshEdges(level, check.cells());
        level.playSound(null, worldPosition, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, .8f, 1.3f);
    }

    /** 自动生成的门：魔力直接灌满，当场点亮，不播开门特效。 */
    public boolean igniteNow() {
        if (level == null) return false;
        mana = MAX_MANA;
        setChanged();
        syncCharge();
        GateShape.Found found = GateShape.find(level, worldPosition);
        if (found == null) return false;
        for (BlockPos cell : found.cells()) level.setBlock(cell, Gate.GATE_PORTAL.get().at(cell, found.axis()), Block.UPDATE_ALL);
        GateShape.refreshEdges(level, found.cells());
        return true;
    }

    private void send(ServerLevel server, GateShape.Found found) {
        GateIgnitionPayload payload = GateIgnitionPayload.of(found);
        var center = worldPosition.getCenter();
        for (var player : server.players())
            if (player instanceof ServerPlayer sp && sp.distanceToSqr(center) <= VIEW * VIEW && sp.connection.hasChannel(GateIgnitionPayload.TYPE))
                sp.connection.send(payload);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putDouble("mana", mana);
        output.putBoolean("igniting", igniting);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        mana = input.getDoubleOr("mana", 0);
        igniting = input.getBooleanOr("igniting", false);
    }
}
