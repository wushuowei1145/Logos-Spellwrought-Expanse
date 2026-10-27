package dev.logos.dimension.client;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;

import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端预览森林精灵的死亡：白瓷台上三个精灵错开时间受到致命一击，截图那一刻从左到右分别是
 * 倒地停住（死亡动画末帧）、跪下中（死亡动画开始约 0.3 秒，已换上茫然挂泪的表情）、最后一击的受击动画中（常态表情）。
 * 倒下时身上飘出绿色光点；左边的精灵到时间消失时化作大量绿色光点（另截一张）。两组截图：3/4 与正侧面（精灵朝东）。
 */
public abstract class NpcDeathViews implements UIScenario {
    private static final int COUNT = 3;
    private static final double SPACING = 2.4;
    private static final int FLOOR = 99;
    private static final AABB AREA = new AABB(-9, FLOOR, -6, 10, FLOOR + 6, 9);
    /** 截图时三个精灵的死亡刻数（至少）：倒地停住、跪下中、受击中（受击约 9 刻，死亡动画 31 刻，60 刻消失）。 */
    private static final int LYING = 44, KNEELING = 15, HIT = 4;
    /** 服务端三个精灵的实体编号，从左到右。 */
    private final int[] ids = new int[COUNT];
    /** 开始击杀前已生成的绿色光点数。 */
    private long spiritsBefore;
    private int burstsBefore;

    /** 0 为 3/4，1 为正侧面。 */
    protected int view() {
        return 0;
    }

    private static double x(int i) {
        return (i - (COUNT - 1) / 2.0) * SPACING + 0.5;
    }

    private Vec3 eye() {
        return view() == 1 ? new Vec3(0.5, FLOOR + 1.6, 5.6) : new Vec3(3.6, FLOOR + 2.2, 4.4);
    }

    private Vec3 look() {
        return new Vec3(0.5, FLOOR + 0.8, 0.5);
    }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("npc_preview").defaultTimeoutMs(60_000).scenarioTimeoutMs(120_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful").runCommand("weather clear")
                .step("预览镜头", ctx -> {
                    var mc = Minecraft.getInstance();
                    var window = mc.getWindow();
                    window.setWindowed(1600, 900);
                    window.setWidth(1600);
                    window.setHeight(900);
                    mc.getMainRenderTarget().resize(1600, 900);
                    mc.gameRenderer.resize(1600, 900);
                    mc.resizeGui();
                    mc.options.hideGui = true;
                    mc.options.fov().set(55);
                    mc.options.renderDistance().set(8);
                    mc.options.broadcastOptions();
                })
                .server("白瓷台与三个精灵", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -8; x <= 9; x++)
                        for (int z = -5; z <= 8; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                            for (int y = FLOOR + 1; y <= FLOOR + 6; y++) level.removeBlock(new BlockPos(x, y, z), false);
                        }
                    level.getEntitiesOfClass(ForestElf.class, AREA).forEach(e -> e.discard());
                    for (int i = 0; i < COUNT; i++) {
                        ForestElf elf = NpcEntities.FOREST_ELF.get().create(level, EntitySpawnReason.COMMAND);
                        elf.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.ZERO), EntitySpawnReason.COMMAND, null);
                        elf.setAppearance(NpcPreviewViews.appearance(0));
                        float body = view() == 1 ? -90 : 0;
                        elf.snapTo(x(i), FLOOR + 1, 0.5, body, 0);
                        elf.setYHeadRot(body);
                        elf.setYBodyRot(body);
                        elf.setNoAi(true);
                        elf.setPersistenceRequired();
                        level.addFreshEntity(elf);
                        ids[i] = elf.getId();
                    }
                    var p = ctx.player();
                    Vec3 eye = eye(), d = look().subtract(eye);
                    DimensionTravel.teleport(p, level, eye.subtract(0, p.getEyeHeight(), 0), (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                            (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                })
                .waitUntil("三个精灵同步到客户端", ctx -> elves().size() == COUNT)
                .step("对准精灵", ctx -> {
                    var p = Minecraft.getInstance().player;
                    var d = look().subtract(p.getEyePosition());
                    p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                }).ticks(40)
                .step("记下光点数", ctx -> spiritsBefore = ElfSpiritParticle.created())
                // 按服务端上实际的死亡刻数等待（步骤之间的耗时不固定）
                .server("左边的精灵受到致命一击", ctx -> kill(ctx.server().overworld(), 0))
                .waitUntilServer("左边的精灵倒下了一阵", ctx -> deathTime(ctx.server().overworld(), 0) >= LYING - KNEELING)
                .server("中间的精灵受到致命一击", ctx -> kill(ctx.server().overworld(), 1))
                .waitUntilServer("中间的精灵开始跪下", ctx -> deathTime(ctx.server().overworld(), 1) >= KNEELING - HIT)
                .server("右边的精灵受到致命一击", ctx -> kill(ctx.server().overworld(), 2))
                .waitUntilServer("右边的精灵在受击", ctx -> deathTime(ctx.server().overworld(), 2) >= HIT)
                .screenshot(view() == 1 ? "forest_elf_death_side" : "forest_elf_death")
                .check("三个精灵都还在（死亡动画播完前不消失）", ctx -> elves().size() == COUNT && elves().stream().allMatch(e -> e.isDeadOrDying()))
                .check("死亡动画开始后才换表情（客户端）", ctx -> elves().stream().allMatch(e ->
                        ForestElf.DEATH_EXPRESSION.equals(e.npcExpression()) == (e.deathTime >= ForestElf.DEATH_FACE_TICKS)))
                .check("换表情时隐藏常态眼嘴、显示挂泪表情", ctx -> {
                    ForestElf e = elves().get(1);
                    var hidden = e.npcRace().hiddenGroups(e.npcAppearance(), e.npcExpression());
                    var calm = e.npcRace().hiddenGroups(e.npcAppearance(), null);
                    return hidden.containsAll(List.of("Eyes", "Eyelid", "EyeBrow", "Mouth")) && !hidden.contains(ForestElf.DEATH_EXPRESSION)
                            && calm.contains(ForestElf.DEATH_EXPRESSION) && !calm.contains("Eyes");
                })
                .check("倒下时身上飘出绿色光点", ctx -> ElfSpiritParticle.created() - spiritsBefore >= 20)
                .check("动画文件有死亡动画", ctx -> NpcAnimations.available(elves().get(0).npcAnimationAsset()).contains(NpcAnimations.DEATH))
                .check("离屏截图有实际分辨率", ctx -> {
                    var target = Minecraft.getInstance().getMainRenderTarget();
                    return target.width == 1600 && target.height == 900;
                })
                // 左边的精灵到时间消失：化作大量绿色光点（不放原版白烟）
                .step("记下消失前的光点数", ctx -> {
                    spiritsBefore = ElfSpiritParticle.created();
                    burstsBefore = ForestElf.bursts();
                })
                // 左边的精灵一化作光点就截图（光点约 2 秒内飘散）
                .waitUntil("左边的精灵化作光点", ctx -> ForestElf.bursts() > burstsBefore)
                .screenshot(view() == 1 ? "forest_elf_death_burst_side" : "forest_elf_death_burst")
                .check("消失时没有被原版白烟代替，散出了大量光点", ctx -> ElfSpiritParticle.created() - spiritsBefore >= ForestElf.BURST / 2)
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    /** 一击致命：走正常的受伤流程（受击计时、受伤与死亡音效），不是直接移除。 */
    private void kill(ServerLevel level, int index) {
        if (level.getEntity(ids[index]) instanceof ForestElf elf) elf.hurtServer(level, level.damageSources().generic(), elf.getMaxHealth() * 10);
    }

    private int deathTime(ServerLevel level, int index) {
        return level.getEntity(ids[index]) instanceof ForestElf elf ? elf.deathTime : Integer.MAX_VALUE;
    }

    /** 客户端里的精灵，按 x 从左到右排序。 */
    private static List<ForestElf> elves() {
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        List<ForestElf> out = new ArrayList<>(level.getEntitiesOfClass(ForestElf.class, AREA));
        out.sort((a, b) -> Double.compare(a.getX(), b.getX()));
        return out;
    }

    @LDLRegisterClient(name = "npc_forest_elf_death", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Angle extends NpcDeathViews {}

    @LDLRegisterClient(name = "npc_forest_elf_death_side", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Side extends NpcDeathViews {
        @Override protected int view() { return 1; }
    }
}
