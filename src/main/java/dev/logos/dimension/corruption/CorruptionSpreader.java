package dev.logos.dimension.corruption;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * 一个腐败网络的蔓延光标，照搬原版 {@code SculkSpreader}（催发体那一份的参数）：
 * 电荷从生物死掉的地方出发，沿着腐败方块游走，经过的脉络把贴着的方块变成腐败污秽，离核心越远电荷掉得越快。
 * 光标存在网络里而不是核心的方块实体里，所以核心只是一个记号，合并时光标整个交给大的那个网络。
 */
public final class CorruptionSpreader {
    public static final int MAX_CHARGE = 1000;
    public static final int MAX_CURSORS = 32;
    private static final int MAX_CURSOR_DISTANCE = 1024;
    /** 原版催发体的四个参数：每长一个幽匿感测体花的电荷（这里不长，只用于衰减）、核心周围不长东西的半径、两种衰减的概率分母。 */
    static final int NO_GROWTH_RADIUS = 4, CHARGE_DECAY_RATE = 10, ADDITIONAL_DECAY_RATE = 5;

    public static final Codec<List<Cursor>> CURSORS = Cursor.CODEC.sizeLimitedListOf(MAX_CURSORS);

    private List<Cursor> cursors = new ArrayList<>();

    public List<Cursor> cursors() {
        return cursors;
    }

    public void load(List<Cursor> saved) {
        cursors.clear();
        saved.forEach(this::addCursor);
    }

    public int charge() {
        int sum = 0;
        for (Cursor c : cursors) sum += c.charge;
        return sum;
    }

    public void addCursors(BlockPos start, int charge) {
        while (charge > 0) {
            int part = Math.min(charge, MAX_CHARGE);
            addCursor(new Cursor(start.immutable(), part));
            charge -= part;
        }
    }

    private void addCursor(Cursor cursor) {
        if (cursors.size() < MAX_CURSORS) cursors.add(cursor);
    }

    /** 走一刻。{@code origin} 是核心的位置；光标所在的区块没加载就等着，不去读它（读了会把区块加载起来）。 */
    public void update(ServerLevel level, BlockPos origin, RandomSource random) {
        if (cursors.isEmpty()) return;
        List<Cursor> kept = new ArrayList<>();
        Map<BlockPos, Cursor> mergeable = new HashMap<>();
        for (Cursor cursor : cursors) {
            if (cursor.pos.distChessboard(origin) > MAX_CURSOR_DISTANCE) continue;
            if (level.isLoaded(cursor.pos)) cursor.update(level, origin, random, this);
            if (cursor.charge <= 0) continue;
            Cursor existing = mergeable.get(cursor.pos);
            if (existing == null) {
                mergeable.put(cursor.pos, cursor);
                kept.add(cursor);
            } else if (cursor.charge + existing.charge <= MAX_CHARGE) {
                existing.mergeWith(cursor);
            } else {
                kept.add(cursor);
                if (cursor.charge < existing.charge) mergeable.put(cursor.pos, cursor);
            }
        }
        cursors = kept;
    }

    /** 和原版 {@code SculkSpreader.ChargeCursor} 一样走：先长脉络，再用电荷，最后挪到旁边的腐败方块上。 */
    public static final class Cursor {
        private static final ObjectArrayList<Vec3i> NON_CORNER_NEIGHBOURS = Util.make(new ObjectArrayList<>(18),
                list -> BlockPos.betweenClosedStream(new BlockPos(-1, -1, -1), new BlockPos(1, 1, 1))
                        .filter(p -> (p.getX() == 0 || p.getY() == 0 || p.getZ() == 0) && !p.equals(BlockPos.ZERO))
                        .map(BlockPos::immutable)
                        .forEach(list::add));
        private static final Codec<Set<Direction>> DIRECTION_SET = Direction.CODEC.listOf().xmap(l -> Sets.newEnumSet(l, Direction.class), Lists::newArrayList);
        public static final Codec<Cursor> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Cursor::pos),
                Codec.intRange(0, MAX_CHARGE).fieldOf("charge").orElse(0).forGetter(Cursor::charge),
                Codec.intRange(0, 1).fieldOf("decay_delay").orElse(1).forGetter(Cursor::decayDelay),
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("update_delay").orElse(0).forGetter(c -> c.updateDelay),
                DIRECTION_SET.lenientOptionalFieldOf("facings").forGetter(c -> Optional.ofNullable(c.facings))
        ).apply(i, Cursor::new));

        private BlockPos pos;
        private int charge;
        private int updateDelay;
        private int decayDelay;
        private @Nullable Set<Direction> facings;

        private Cursor(BlockPos pos, int charge, int decayDelay, int updateDelay, Optional<Set<Direction>> facings) {
            this.pos = pos;
            this.charge = charge;
            this.decayDelay = decayDelay;
            this.updateDelay = updateDelay;
            this.facings = facings.orElse(null);
        }

        public Cursor(BlockPos pos, int charge) {
            this(pos, charge, 1, 0, Optional.empty());
        }

        public BlockPos pos() { return pos; }
        public int charge() { return charge; }
        public int decayDelay() { return decayDelay; }

        private void update(ServerLevel level, BlockPos origin, RandomSource random, CorruptionSpreader spreader) {
            if (charge <= 0 || !level.shouldTickBlocksAt(origin)) return;
            if (updateDelay > 0) {
                updateDelay--;
                return;
            }
            BlockState state = level.getBlockState(pos);
            CorruptionBehaviour behaviour = behaviourOf(state);
            if (behaviour.attemptSpreadVein(level, pos, state, facings)) {
                if (behaviour.canChangeBlockStateOnSpread()) {
                    state = level.getBlockState(pos);
                    behaviour = behaviourOf(state);
                }
                level.playSound(null, pos, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.BLOCKS, 1, .8f);
            }
            charge = behaviour.attemptUseCharge(this, level, origin, random, spreader);
            if (charge <= 0) {
                behaviour.onDischarged(level, state, pos, random);
                return;
            }
            BlockPos next = validMovementPos(level, pos, random);
            if (next != null) {
                behaviour.onDischarged(level, state, pos, random);
                pos = next.immutable();
                state = level.getBlockState(next);
            }
            if (state.getBlock() instanceof CorruptionBehaviour) facings = MultifaceBlock.availableFaces(state);
            decayDelay = behaviour.updateDecayDelay(decayDelay);
            updateDelay = behaviour.spreadDelay();
        }

        private void mergeWith(Cursor other) {
            charge += other.charge;
            other.charge = 0;
            updateDelay = Math.min(updateDelay, other.updateDelay);
        }

        private static CorruptionBehaviour behaviourOf(BlockState state) {
            return state.getBlock() instanceof CorruptionBehaviour behaviour ? behaviour : CorruptionBehaviour.DEFAULT;
        }

        private static @Nullable BlockPos validMovementPos(ServerLevel level, BlockPos pos, RandomSource random) {
            BlockPos.MutableBlockPos found = pos.mutable();
            BlockPos.MutableBlockPos neighbour = pos.mutable();
            for (Vec3i offset : Util.shuffledCopy(NON_CORNER_NEIGHBOURS, random)) {
                neighbour.setWithOffset(pos, offset);
                if (!level.isLoaded(neighbour)) continue;
                BlockState state = level.getBlockState(neighbour);
                if (state.getBlock() instanceof CorruptionBehaviour && unobstructed(level, pos, neighbour)) {
                    found.set(neighbour);
                    if (CorruptionVeinBlock.hasSubstrateAccess(level, state, neighbour)) break;
                }
            }
            return found.equals(pos) ? null : found;
        }

        private static boolean unobstructed(LevelAccessor level, BlockPos from, BlockPos to) {
            if (from.distManhattan(to) == 1) return true;
            BlockPos delta = to.subtract(from);
            Direction x = Direction.fromAxisAndDirection(Direction.Axis.X, delta.getX() < 0 ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
            Direction y = Direction.fromAxisAndDirection(Direction.Axis.Y, delta.getY() < 0 ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
            Direction z = Direction.fromAxisAndDirection(Direction.Axis.Z, delta.getZ() < 0 ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
            if (delta.getX() == 0) return open(level, from, y) || open(level, from, z);
            return delta.getY() == 0 ? open(level, from, x) || open(level, from, z) : open(level, from, x) || open(level, from, y);
        }

        private static boolean open(LevelAccessor level, BlockPos from, Direction direction) {
            BlockPos test = from.relative(direction);
            return !level.getBlockState(test).isFaceSturdy(level, test, direction.getOpposite());
        }
    }
}
