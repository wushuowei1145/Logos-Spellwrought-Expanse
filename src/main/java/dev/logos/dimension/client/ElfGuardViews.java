package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.item.AncientEmbraceItem;
import dev.logos.dimension.item.ElfSpearItem;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcEntities;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 矛盾动作客户端预览：持矛、古木盾防御、原版盾防御，以及举盾中的右臂刺击。 */
@LDLRegisterClient(name = "npc_forest_elf_guard", group = "npc_guard", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public final class ElfGuardViews implements UIScenario {
    private static final AABB AREA = new AABB(-8, 99, -5, 9, 106, 10);

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("npc_guard").defaultTimeoutMs(60_000).scenarioTimeoutMs(120_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear")
                .step("设置预览镜头", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = true;
                    mc.options.fov().set(55);
                })
                .server("持矛和两种盾牌", ctx -> {
                    var level = ctx.server().overworld();
                    level.getEntitiesOfClass(ForestElf.class, AREA).forEach(e -> e.discard());
                    for (int x = -8; x <= 9; x++) for (int z = -5; z <= 10; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.SMOOTH_QUARTZ.defaultBlockState());
                        for (int y = 100; y < 106; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    for (int i = 0; i < 3; i++) {
                        double x = .5 + (i - 1) * 2.4;
                        ForestElf elf = NpcEntities.FOREST_ELF.get().create(level, EntitySpawnReason.COMMAND);
                        elf.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.ZERO), EntitySpawnReason.COMMAND, null);
                        elf.setAppearance(NpcPreviewViews.appearance(0));
                        elf.setWeapon(new ItemStack(i == 2 ? Items.IRON_SPEAR : ElfSpearItem.ITEM.get()));
                        elf.snapTo(x, 100, .5, 0, 0);
                        elf.setYHeadRot(0);
                        elf.setYBodyRot(0);
                        elf.setNoAi(true);
                        if (i > 0) {
                            elf.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(i == 2 ? Items.SHIELD : AncientEmbraceItem.ITEM.get()));
                        }
                        level.addFreshEntity(elf);
                    }
                    Vec3 eye = new Vec3(3.8, 102.1, 5.0), d = new Vec3(.5, 100.9, .5).subtract(eye);
                    var player = ctx.player();
                    DimensionTravel.teleport(player, level, eye.subtract(0, player.getEyeHeight(), 0),
                            (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance())));
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                })
                .waitUntil("三位精灵同步", ctx -> elves().size() == 3).ticks(5)
                .check("平静时两手空着", ctx -> elves().stream().allMatch(e -> e.getMainHandItem().isEmpty() && e.getOffhandItem().isEmpty()))
                .screenshot("forest_elf_guard_idle")
                .server("装备同步后再举盾", ctx -> {
                    var level = ctx.server().overworld();
                    for (var elf : level.getEntitiesOfClass(ForestElf.class, AREA)) {
                        var target = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
                        target.snapTo(elf.getX(), 100, 9.5);
                        target.setInvisible(true);
                        target.setNoAi(true);
                        level.addFreshEntity(target);
                        elf.setTarget(target);
                        elf.setAggressive(true);
                    }
                })
                .ticks(10)
                .checkServer("服务端两种盾都在格挡", ctx -> ctx.server().overworld().getEntitiesOfClass(ForestElf.class, AREA).stream()
                        .filter(e -> !e.shield().isEmpty()).allMatch(ForestElf::isBlocking))
                .check("两种盾的格挡状态同步", ctx -> elves().get(1).isBlocking() && elves().get(2).isBlocking())
                .ticks(40).screenshot("forest_elf_guard_hold")
                .check("客户端有长矛和盾牌动作", ctx -> NpcAnimations.available(elves().getFirst().npcAnimationAsset())
                        .containsAll(List.of("hold_mainhand:spear", "swing:spear", "use_offhand:shield")))
                .server("举盾时用右臂戳刺", ctx -> ctx.server().overworld().getEntitiesOfClass(ForestElf.class, AREA)
                        .forEach(elf -> elf.swing(InteractionHand.MAIN_HAND)))
                .ticks(3).screenshot("forest_elf_guard_stab")
                .check("刺击时两种盾都仍在格挡", ctx -> elves().get(1).isBlocking() && elves().get(2).isBlocking())
                .check("盾牌使用与长矛挥手分别匹配", ctx -> {
                    var elf = elves().get(1);
                    var names = NpcAnimations.available(elf.npcAnimationAsset());
                    return "swing:spear".equals(NpcAnimations.swingAnimation(true, NpcAnimations.HandItem.of(elf.getMainHandItem()), names))
                            && "use_offhand:shield".equals(NpcAnimations.useAnimation(false, NpcAnimations.HandItem.of(elf.getOffhandItem()), names));
                })
                .server("侧面核对矛尖朝向", ctx -> camera(ctx.player(), new Vec3(6.5, 101.4, .5)))
                .ticks(5).screenshot("forest_elf_guard_side")
                .server("正面核对盾面朝向", ctx -> camera(ctx.player(), new Vec3(.5, 101.5, 6.5)))
                .ticks(5).screenshot("forest_elf_guard_front")
                .server("水平近景核对盾牌竖直", ctx -> camera(ctx.player(), new Vec3(.5, 100.9, 3.2)))
                .ticks(5).screenshot("forest_elf_guard_front_close")
                .server("近景核对矛盾分开", ctx -> ctx.server().overworld().getEntitiesOfClass(ForestElf.class, AREA)
                        .forEach(elf -> elf.swing(InteractionHand.MAIN_HAND)))
                .ticks(3).screenshot("forest_elf_guard_front_close_stab")
                .ticks(4)
                .server("结束战斗", ctx -> ctx.server().overworld().getEntitiesOfClass(ForestElf.class, AREA).forEach(e -> e.setTarget(null)))
                .ticks(10)
                .check("警戒期保留盾但不持续格挡", ctx -> !elves().get(1).getOffhandItem().isEmpty() && !elves().get(1).isUsingItem())
                .screenshot("forest_elf_guard_alert_front")
                .server("侧面核对放下盾的横向持握", ctx -> camera(ctx.player(), new Vec3(6.5, 101.4, .5)))
                .ticks(5).screenshot("forest_elf_guard_alert_side")
                .server("恢复正面镜头", ctx -> camera(ctx.player(), new Vec3(.5, 100.9, 3.2)))
                .ticks(95)
                .check("警戒结束后客户端收起矛盾", ctx -> elves().stream().allMatch(e -> e.getMainHandItem().isEmpty() && e.getOffhandItem().isEmpty() && !e.isUsingItem()))
                .screenshot("forest_elf_guard_stowed")
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    private static void camera(net.minecraft.server.level.ServerPlayer player, Vec3 eye) {
        Vec3 d = new Vec3(.5, 100.9, .5).subtract(eye);
        DimensionTravel.teleport(player, player.level(), eye.subtract(0, player.getEyeHeight(), 0),
                (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance())));
    }

    private static List<ForestElf> elves() {
        var level = Minecraft.getInstance().level;
        return level == null ? List.of() : level.getEntitiesOfClass(ForestElf.class, AREA).stream()
                .sorted(Comparator.comparingDouble(ForestElf::getX)).toList();
    }
}
