package dev.logos.dimension.client;

import java.util.HashSet;
import java.util.Set;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.item.UchigatanaItem;
import dev.logos.dimension.item.YumiItem;
import dev.logos.dimension.npc.DivineFox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 神狐换武器与苦力怕怕神狐的实际客户端检查（docs/NPC_RACE_DIVINE_FOX.md 第 6 节）：
 * 台阶坡上不在刀和弓之间来回换（{@code npc_divine_fox_weapon_commit}）；退不开时贴近也放箭、不每刻重播动作
 * （{@code npc_divine_fox_close_shot}）；用弓时施法不拔刀（{@code npc_divine_fox_wrap_bow}）；
 * 苦力怕看到神狐就跑、被射中也不冲过来（{@code npc_divine_fox_creeper_fear}）。
 * 敌人血量锁住（打中记一下），别的怪每刻清掉，平台四周围上屏障，免得敌人被打下台子，这一仗提前结束。
 */
public abstract class FoxWeaponViews extends FoxCombatViews {
    private static final int HIGH = 130, HILL = 18;
    private static final AABB WORLD = new AABB(-40, 90, -40, 40, 145, 40);

    static DivineFox fox;
    static Mob foe;
    static int ticks, flips, hits, moveChanges;
    static float foeHp;
    static String lastHand, lastMove;
    static boolean katanaSeen, wrappedSeen, targetedFox;
    static double closest, farthest;
    static Vec3 anchor;
    static final Set<Integer> arrows = new HashSet<>();

    static void observe(ServerLevel level) {
        if (fox == null || foe == null) return;
        ticks++;
        level.getEntitiesOfClass(Mob.class, WORLD, m -> m != fox && m != foe).forEach(Entity::discard);
        if (foe.isAlive()) {
            if (foe.getHealth() < foeHp) {
                hits++;
                foe.setHealth(foeHp);
            }
            if (anchor != null) {
                foe.setPos(anchor);
                foe.setDeltaMovement(Vec3.ZERO);
            }
            targetedFox |= foe.getTarget() instanceof DivineFox;
            double gap = fox.distanceTo(foe);
            closest = Math.min(closest, gap);
            farthest = Math.max(farthest, gap);
        }
        if (!fox.isAlive()) return;
        fox.setHealth(20);
        var item = fox.getMainHandItem().getItem();
        String hand = item instanceof BowItem ? "bow" : item instanceof UchigatanaItem ? "katana" : null;
        if (hand != null) {
            if (lastHand != null && !hand.equals(lastHand)) flips++;
            lastHand = hand;
        }
        katanaSeen |= item instanceof UchigatanaItem;
        wrappedSeen |= fox.wrapped();
        var move = fox.npcCombatMove();
        String moveKey = move == null ? "" : move.animation() + "@" + move.start();
        if (!moveKey.equals(lastMove)) {
            moveChanges++;
            lastMove = moveKey;
        }
        for (AbstractArrow a : level.getEntitiesOfClass(AbstractArrow.class, WORLD, a -> a.getOwner() == fox)) arrows.add(a.getId());
    }

    static void reset(DivineFox f, Mob m, float hp) {
        fox = f;
        foe = m;
        foeHp = hp;
        ticks = flips = hits = moveChanges = 0;
        lastHand = lastMove = null;
        katanaSeen = wrappedSeen = targetedFox = false;
        closest = 999;
        farthest = 0;
        anchor = null;
        arrows.clear();
        watch(FoxWeaponViews::observe);
    }

    static void report(String name) {
        watch(null);
        System.out.printf("[fox] %s ticks=%d flips=%d hits=%d shots=%d moveChanges=%d katana=%b wrapped=%b targetedFox=%b gap=[%.2f,%.2f] foxY=%.1f%n",
                name, ticks, flips, hits, arrows.size(), moveChanges, katanaSeen, wrappedSeen, targetedFox, closest, farthest, fox.getY());
    }

    /** 清掉上一次留下的台阶、横梁、屏障和生物。 */
    static void clearAll(ServerLevel level) {
        for (int x = -16; x <= 17; x++)
            for (int z = -16; z <= 17; z++)
                for (int y = FLOOR + 1; y <= FLOOR + HILL; y++) level.removeBlock(new BlockPos(x, y, z), false);
        for (int x = -14; x <= 12; x++) level.removeBlock(new BlockPos(x, HIGH - 1, 0), false);
        level.getEntitiesOfClass(Entity.class, WORLD, e -> !(e instanceof Player)).forEach(Entity::discard);
    }

    /** 平台四周围一圈屏障。 */
    static void ring(ServerLevel level) {
        var barrier = Blocks.BARRIER.defaultBlockState();
        for (int i = -15; i <= 16; i++)
            for (int y = FLOOR + 1; y <= FLOOR + HILL; y++) {
                level.setBlockAndUpdate(new BlockPos(i, y, -15), barrier);
                level.setBlockAndUpdate(new BlockPos(i, y, 16), barrier);
                level.setBlockAndUpdate(new BlockPos(-15, y, i), barrier);
                level.setBlockAndUpdate(new BlockPos(16, y, i), barrier);
            }
    }

    static DivineFox bowFox(ServerLevel level, double x, int y) {
        DivineFox f = fox(level, x, .5, "outfit_miko", true);
        f.giveBow(new ItemStack(YumiItem.INDIGO.get()));
        f.snapTo(x, y, .5, 90, 0);
        return f;
    }

    static Pillager pillager(ServerLevel level, double x, int y, float hp, boolean ai) {
        Pillager p = EntityType.PILLAGER.create(level, EntitySpawnReason.COMMAND);
        p.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.ZERO), EntitySpawnReason.COMMAND, null);
        p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
        p.setHealth(hp);
        p.snapTo(x, y, .5, -90, 0);
        p.setPersistenceRequired();
        p.setNoAi(!ai);
        level.addFreshEntity(p);
        return p;
    }

    /** 与玩家的截图相同：神狐站在一块悬空的原木上，敌人站在一根悬空的横梁一端，走不过去。 */
    static void perch(ServerLevel level, int foxX) {
        var log = Blocks.OAK_LOG.defaultBlockState();
        level.setBlockAndUpdate(new BlockPos(foxX, HIGH - 1, 0), log);
        for (int x = -12; x <= 0; x++) level.setBlockAndUpdate(new BlockPos(x, HIGH - 1, 0), log);
    }

    static ScenarioBuilder finish(ScenarioBuilder s) {
        return s.server("清理", ctx -> clearAll(ctx.server().overworld()))
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    /** 一级级的台阶坡（聚落山坡就是这样）：追人时不停地跳。修之前每次离地查路都会被当成走不到。 */
    @LDLRegisterClient(name = "npc_divine_fox_weapon_commit", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Commit extends FoxWeaponViews {
        private static int height(int x) {
            return Math.max(0, (x + 14) / 2);
        }

        @Override
        public void define(ScenarioBuilder s) {
            finish(begin(s).server("台阶坡上的背弓神狐与掠夺者", ctx -> {
                        var level = ctx.server().overworld();
                        arena(level);
                        clearAll(level);
                        ring(level);
                        var log = Blocks.OAK_LOG.defaultBlockState();
                        for (int x = -14; x <= 15; x++)
                            for (int z = -14; z <= 15; z++)
                                for (int y = 0; y < height(x); y++) level.setBlockAndUpdate(new BlockPos(x, FLOOR + 1 + y, z), log);
                        DivineFox f = bowFox(level, 6.5, FLOOR + 1 + height(6));
                        camera(ctx.player(), new Vec3(3, FLOOR + 14, -13), new Vec3(3, FLOOR + 8, .5));
                        reset(f, pillager(level, .5, FLOOR + 1 + height(0), 50, true), 50);
                    })
                    .waitUntilServer("打 20 秒", ctx -> ticks >= 400)
                    .server("停止观察", ctx -> report("commit"))
                    .checkServer("没有在刀和弓之间来回换（修之前同样时间要换 7 次）", ctx -> flips <= 1)
                    .checkServer("一直在打（20 秒至少砍中 10 下）", ctx -> hits >= 10));
        }
    }

    /** 敌人只隔 5 格，但神狐站在一块原木上退不开。修之前这样站着什么也不做，还每刻把执弓动作重播一遍。 */
    @LDLRegisterClient(name = "npc_divine_fox_close_shot", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class CloseShot extends FoxWeaponViews {
        @Override
        public void define(ScenarioBuilder s) {
            finish(begin(s).server("悬空原木上的神狐，敌人隔 5 格", ctx -> {
                        var level = ctx.server().overworld();
                        clearAll(level);
                        perch(level, 5);
                        DivineFox f = bowFox(level, 5.5, HIGH);
                        camera(ctx.player(), new Vec3(3, HIGH + 2, -9), new Vec3(3, HIGH + 1, .5));
                        Pillager p = pillager(level, .5, HIGH, 50, false);
                        reset(f, p, 50);
                        anchor = p.position();
                    })
                    .waitUntilServer("站 20 秒", ctx -> ticks >= 400)
                    .server("停止观察", ctx -> report("close_shot"))
                    .checkServer("退不开就站着射（至少中 4 箭）", ctx -> hits >= 4)
                    .checkServer("手里一直是弓", ctx -> flips == 0 && !katanaSeen)
                    .checkServer("动作不每刻重播（修之前约每刻一次）", ctx -> moveChanges <= 80)
                    .checkServer("没有走下原木", ctx -> fox.getY() == HIGH));
        }
    }

    /** 拉着弓时敌人变强（生命超过 50），放狐火缠绕：弓留在手上，不拔刀。 */
    @LDLRegisterClient(name = "npc_divine_fox_wrap_bow", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class WrapBow extends FoxWeaponViews {
        @Override
        public void define(ScenarioBuilder s) {
            finish(begin(s).server("悬空原木上的神狐，敌人隔 8 格", ctx -> {
                        var level = ctx.server().overworld();
                        clearAll(level);
                        perch(level, 8);
                        DivineFox f = bowFox(level, 8.5, HIGH);
                        camera(ctx.player(), new Vec3(4, HIGH + 2, -10), new Vec3(4, HIGH + 1, .5));
                        Pillager p = pillager(level, .5, HIGH, 50, false);
                        reset(f, p, 50);
                        anchor = p.position();
                    })
                    .waitUntilServer("先放一箭", ctx -> !arrows.isEmpty())
                    .server("敌人变强", ctx -> {
                        foe.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80);
                        foeHp = 80;
                        foe.setHealth(80);
                    })
                    .waitUntilServer("缠上狐火", ctx -> wrappedSeen)
                    .serverTicks(60)
                    .server("停止观察", ctx -> report("wrap_bow"))
                    .checkServer("施法时弓留在手上，没有拔刀", ctx -> flips == 0 && !katanaSeen));
        }
    }

    /** 苦力怕看到神狐就跑；背弓的神狐射它，它也不会冲过来。 */
    @LDLRegisterClient(name = "npc_divine_fox_creeper_fear", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class CreeperFear extends FoxWeaponViews {
        private static Creeper creeper(ServerLevel level, double x) {
            Creeper c = EntityType.CREEPER.create(level, EntitySpawnReason.COMMAND);
            c.snapTo(x, FLOOR + 1, .5, 0, 0);
            c.setPersistenceRequired();
            level.addFreshEntity(c);
            return c;
        }

        @Override
        public void define(ScenarioBuilder s) {
            finish(begin(s).server("没弓的神狐，苦力怕在 9 格外", ctx -> {
                        var level = ctx.server().overworld();
                        arena(level);
                        clearAll(level);
                        ring(level);
                        DivineFox f = fox(level, .5, .5, "outfit_miko", true);
                        f.giveBow(ItemStack.EMPTY);
                        camera(ctx.player(), new Vec3(0, FLOOR + 16, -22), new Vec3(0, FLOOR + 1, 0));
                        reset(f, creeper(level, 9.5), 20);
                    })
                    .waitUntilServer("看 10 秒", ctx -> ticks >= 200)
                    .server("停止观察", ctx -> report("creeper_near"))
                    .checkServer("苦力怕跑开到 12 格以外", ctx -> farthest >= 12)
                    .checkServer("没有靠近", ctx -> closest >= 7)
                    .server("背弓的神狐，苦力怕在 14 格外", ctx -> {
                        var level = ctx.server().overworld();
                        clearAll(level);
                        ring(level);
                        DivineFox f = bowFox(level, 8.5, FLOOR + 1);
                        reset(f, creeper(level, -5.5), 20);
                    })
                    .waitUntilServer("射中两箭", ctx -> hits >= 2)
                    .serverTicks(100)
                    .server("停止观察", ctx -> report("creeper_bow"))
                    .checkServer("被射中后没有把神狐当目标", ctx -> !targetedFox)
                    .checkServer("没有冲向神狐", ctx -> closest >= 9));
        }
    }
}
