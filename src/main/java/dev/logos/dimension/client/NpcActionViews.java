package dev.logos.dimension.client;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;

import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.item.NaturesForceItem;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端预览森林精灵的动作，白瓷台上并排三个精灵，关掉 AI 站定：
 * <ul>
 * <li>弓（3/4 与正侧面两张）：从左到右是持弓、平射拉弓、抬头 30° 拉弓（3/4 那张再向左偏 25°），弓应跟着视线；</li>
 * <li>施法：抚生之触、生息之环、行礼（不在战斗中，弓收着）。剧情动作错开触发，让截图那一刻都在动作的停顿处。</li>
 * </ul>
 */
public abstract class NpcActionViews implements UIScenario {
    private static final int COUNT = 3;
    private static final double SPACING = 2.2;
    private static final int FLOOR = 99;
    private static final AABB AREA = new AABB(-9, FLOOR, -6, 10, FLOOR + 6, 9);

    /** 0 为弓 3/4，1 为弓正侧面（精灵朝东），2 为施法 3/4。 */
    protected int view() {
        return 0;
    }

    private boolean bow() {
        return view() != 2;
    }

    private static double x(int i) {
        return (i - (COUNT - 1) / 2.0) * SPACING + 0.5;
    }

    private Vec3 eye() {
        return view() == 1 ? new Vec3(0.5, FLOOR + 1.7, 5.2) : new Vec3(3.4, FLOOR + 2.1, 4.0);
    }

    private Vec3 look() {
        return new Vec3(0.5, FLOOR + 1.15, 0.5);
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
                    mc.options.fov().set(view() == 1 ? 60 : 55);
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
                        boolean up = bow() && i == 2;
                        elf.snapTo(x(i), FLOOR + 1, 0.5, body, up ? -30 : 0);
                        // 正侧面只抬头（看清弓随俯仰抬起），3/4 再加偏航
                        elf.setYHeadRot(body + (up && view() == 0 ? 25 : 0));
                        elf.setYBodyRot(body);
                        elf.setNoAi(true);
                        elf.setPersistenceRequired();
                        // 平时弓收着；预览里在加入世界前直接放到手上（加入后再换装备会打断拉弓）。
                        // 收着的那把还在，所以不会被收回去
                        if (bow()) elf.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(NaturesForceItem.ITEM.get()));
                        level.addFreshEntity(elf);
                        if (bow() && i > 0) elf.startUsingItem(InteractionHand.MAIN_HAND);
                    }
                    var p = ctx.player();
                    Vec3 eye = eye(), d = look().subtract(eye);
                    DimensionTravel.teleport(p, level, eye.subtract(0, p.getEyeHeight(), 0), (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                            (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                })
                .waitUntil("三个精灵同步到客户端", ctx -> elves().size() == COUNT).ticks(200)
                .step("对准精灵", ctx -> {
                    var p = Minecraft.getInstance().player;
                    var d = look().subtract(p.getEyePosition());
                    p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                }).ticks(40)
                // 生息之环在 0.75 秒展开到最大、行礼在 0.7–1.1 秒欠身，抚生之触在 0.18–0.55 秒伸手
                .server("生息之环与行礼", ctx -> {
                    if (bow()) return;
                    act(ctx.server().overworld(), 1, ForestElf.HEAL_RING);
                    act(ctx.server().overworld(), 2, ForestElf.GREET);
                }).ticks(6)
                .server("抚生之触", ctx -> {
                    if (!bow()) act(ctx.server().overworld(), 0, ForestElf.HEAL_TOUCH);
                }).ticks(3)
                // 先截图再检查，免得检查占用的时间让动作播过了停顿处
                .screenshot(switch (view()) {
                    case 1 -> "forest_elf_bow_side";
                    case 2 -> "forest_elf_spells";
                    default -> "forest_elf_bow";
                })
                .check("动画文件有现有行为的全部动作", ctx -> NpcAnimations.available(elves().get(0).npcAnimationAsset()).containsAll(List.of(
                        "idle", "walk", "jump", "attacked", "ladder_up", "ladder_stillness", "ladder_down", "swim_stand", "swing_hand",
                        "hold_mainhand:bow", "use_mainhand:bow", ForestElf.HEAL_TOUCH, ForestElf.HEAL_RING, ForestElf.GREET, ForestElf.REFUSE)))
                .check(bow() ? "拉弓的精灵在客户端处于瞄准" : "剧情动作同步到客户端", ctx -> {
                    List<ForestElf> e = elves();
                    if (bow()) return e.get(1).isUsingItem() && e.get(1).npcAiming() && e.get(2).npcAiming() && !e.get(0).npcAiming();
                    return ForestElf.HEAL_TOUCH.equals(e.get(0).npcMiscAnimation()) && e.get(0).npcAiming()
                            && ForestElf.HEAL_RING.equals(e.get(1).npcMiscAnimation()) && ForestElf.GREET.equals(e.get(2).npcMiscAnimation());
                })
                .check("站着不动的精灵不播跳跃", ctx -> elves().stream().noneMatch(e ->
                        "jump".equals(NpcAnimations.mainState(NpcAnimations.Snapshot.of(e, false), n -> true))))
                .check("离屏截图有实际分辨率", ctx -> {
                    var target = Minecraft.getInstance().getMainRenderTarget();
                    return target.width == 1600 && target.height == 900;
                })
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    private static void act(ServerLevel level, int index, String action) {
        List<ForestElf> out = new ArrayList<>(level.getEntitiesOfClass(ForestElf.class, AREA));
        out.sort((a, b) -> Double.compare(a.getX(), b.getX()));
        out.get(index).playAction(action);
    }

    /** 客户端里的精灵，按 x 从左到右排序。 */
    private static List<ForestElf> elves() {
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        List<ForestElf> out = new ArrayList<>(level.getEntitiesOfClass(ForestElf.class, AREA));
        out.sort((a, b) -> Double.compare(a.getX(), b.getX()));
        return out;
    }

    @LDLRegisterClient(name = "npc_forest_elf_bow", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Bow extends NpcActionViews {}

    @LDLRegisterClient(name = "npc_forest_elf_bow_side", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class BowSide extends NpcActionViews {
        @Override protected int view() { return 1; }
    }

    @LDLRegisterClient(name = "npc_forest_elf_spells", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Spells extends NpcActionViews {
        @Override protected int view() { return 2; }
    }
}
