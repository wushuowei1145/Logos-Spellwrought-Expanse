package dev.logos.dimension.gametest;

import dev.logos.dimension.elven.blight.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.*;

/** 用真实实体命中跑完整断链流程，并检查净化保留玩家改建与结局存档。 */
public final class QueenBattleTests {
    private QueenBattleTests() {}
    public static void cut(CorruptedElfQueen q, ServerPlayer player) {
        var world = player.level();
        for (var root : List.copyOf(q.tethers(world))) if (root.slot() < 3 && root.spawnPhase() == q.phase()) {
            root.invulnerableTime = 0; root.hurtServer(world, world.damageSources().playerAttack(player), 100);
        }
    }
    static void battle(GameTestHelper h) {
        var f = ElvenBlightGameTests.fixture(h, new BlockPos(100000, 200, 100000)); var world = h.getLevel();
        var q = Objects.requireNonNull(f.heart().awaken(f.player()));
        h.assertTrue(q.getMaxHealth() == 400 && dev.logos.runtime.SpellModifiers.taken(q, dev.logos.lang.Element.LIFE) == .25, "三条血的初始生命或 Logos 魔抗不是 400 / 75%");
        f.player().getAbilities().invulnerable = true;
        boolean[] checked = new boolean[7];
        h.onEachTick(() -> {
            if (q.entering() || !q.isAlive()) return;
            f.player().setPos(q.arena().add(0, 0, 6));
            if (q.transitioning() && !checked[q.phase() + 3]) {
                int phase = q.phase(); float age = q.phaseAge(0);
                var snapshot = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess()); q.saveWithoutId(snapshot);
                var tag = snapshot.buildResult(); tag.putLong("PhaseTime", -40000);
                q.load(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), tag));
                h.assertTrue(q.phase() == phase && q.transitioning() && !q.rootsOpen() && q.phaseAge(0) == age, "重载没有保留阶段过渡及其相对时钟");
                checked[phase + 3] = true;
            }
            if (q.phase() < 3) {
                float hp = q.getHealth(); q.invulnerableTime = 0;
                q.hurtServer(world, world.damageSources().playerAttack(f.player()), 12);
                h.assertTrue(q.getHealth() == hp, "直接攻击女王扣除了核心生命");
                if (q.rootsOpen()) cut(q, f.player());
            } else if (q.phase() == 3 && !q.transitioning()) {
                checked[0] = true;
                if (!checked[1]) {
                    checked[1] = true;
                    h.assertTrue(!q.damageCore(world, world.damageSources().playerAttack(f.player()), 100), "供养血管没断就能伤核心");
                    // 反复读同一条断链事件不能跨阶段多次伤害核心。
                    q.sever(0, world.damageSources().playerAttack(f.player()));
                    int mask = q.brokenRoots(); q.sever(0, world.damageSources().playerAttack(f.player()));
                    h.assertTrue(mask == q.brokenRoots(), "同一条血管被重复结算");
                }
                cut(q, f.player());
                if (QueenBattleRules.coreExposed(q.phase(), q.brokenRoots())) {
                    var proxy = q.tethers(world).stream().filter(t -> t.slot() == 3).findFirst().orElse(null);
                    if (proxy != null) { q.invulnerableTime = 0; checked[2] = proxy.hurtServer(world, world.damageSources().playerAttack(f.player()), 100); }
                }
            }
            if (q.phase() == 4 && !checked[3]) {
                var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess()); q.saveWithoutId(saved);
                q.load(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), saved.buildResult()));
                h.assertTrue(q.phase() == 4 && q.isAlive(), "存档重载丢失结局或提前杀死女王"); checked[3] = true;
            }
            if (q.phase() == 4 && q.endingTick() >= 100) {
                checked[4] = WorldTreePurification.get(world).started(f.pos());
                h.assertTrue(world.getBlockState(f.pos()).getCollisionShape(world, f.pos()).isEmpty(), "已消失的核心仍有隐形碰撞");
            }
        });
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(Arrays.equals(checked, new boolean[]{true,true,true,true,true,true,true}), "等待三阶段、存档与净化启动");
            try { h.assertTrue(f.heart().getBlockState().getValue(QueenHeartBlock.DEFEATED), "自爆后核心没有永久击败标记"); h.succeed(); }
            finally { f.close(h); }
        }));
    }
    static void spells(GameTestHelper h) {
        var f = ElvenBlightGameTests.fixture(h); var world = h.getLevel();
        try {
            var q = Objects.requireNonNull(f.heart().awaken(f.player()));
            for (String id : List.of("fan", "ray", "rain")) h.assertTrue(QueenSpells.spell(world.getServer(), id) != null, "载页不能编译：" + id);
            var to = f.player().getEyePosition().subtract(q.getEyePosition()).normalize();
            q.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z))); q.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
            float before = f.player().getHealth(); double mana = q.mana();
            h.assertTrue(QueenSpells.cast(q, "ray", q.getEyePosition(), f.player()), "无法通过 Logos 运行时施法");
            h.assertTrue(f.player().getHealth() < before && q.mana() == mana - 40, "真实载页未伤到玩家或没有付费");
            q.payMana(q.mana());
            h.assertTrue(!QueenSpells.cast(q, "fan", q.getEyePosition(), f.player()) && q.mana() == 0, "魔力不足仍然施法");
            h.succeed();
        } finally { f.close(h); }
    }
    static void restoration(GameTestHelper h) {
        var world = h.getLevel();
        // 开发测试会复用存档；每轮选择新区域，不能把上一轮已完成的净化区当成新任务。
        var center = new BlockPos(2_000_000 + (int) (world.getGameTime() % 100000) * 32, 250, -2_000_000);
        var remembered = ElvenBlight.MEMORY.get().defaultBlockState();
        var original = BlightMemoryBlock.original(remembered);
        BlockPos p = center.east(3), built = center.east(4), outside = center.east(30);
        world.setBlockAndUpdate(p, remembered); world.setBlockAndUpdate(built, Blocks.DIAMOND_BLOCK.defaultBlockState()); world.setBlockAndUpdate(outside, remembered);
        // 模拟从结构区块读取、尚未实例化的 DUMMY 核心，复现净化后延迟 NBT 残留。
        BlockPos pending = center.west(3);
        world.setBlockAndUpdate(pending, ElvenBlight.NODE.get().defaultBlockState());
        var chunk = world.getChunkAt(pending); chunk.removeBlockEntity(pending);
        var delayed = new net.minecraft.nbt.CompoundTag(); delayed.putString("id", "DUMMY");
        delayed.putInt("x", pending.getX()); delayed.putInt("y", pending.getY()); delayed.putInt("z", pending.getZ());
        chunk.setBlockEntityNbt(delayed);
        h.assertTrue(chunk.getBlockEntityNbt(pending) != null, "没有准备延迟加载核心");
        var job = WorldTreePurification.get(world); job.start(world, center, 16); job.start(world, center, 16);
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(world.getBlockState(p) == original, "等待区块批量还原");
            h.assertTrue(world.getBlockState(built).is(Blocks.DIAMOND_BLOCK), "覆盖了玩家建筑");
            h.assertTrue(world.getBlockState(pending).isAir(), "等待核心方块被净化：" + world.getBlockState(pending));
            h.assertTrue(chunk.getBlockEntityNbt(pending) == null && chunk.getBlockEntity(pending) == null, "已移除核心仍有延迟方块实体数据");
            h.assertTrue(world.getBlockState(outside) == remembered, "清理超出球形范围");
            var saved = WorldTreePurification.CODEC.encodeStart(NbtOps.INSTANCE, job).getOrThrow();
            var loaded = WorldTreePurification.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
            h.assertTrue(loaded.started(center), "净化地区没有持久化");
            h.succeed();
        }));
    }
}
