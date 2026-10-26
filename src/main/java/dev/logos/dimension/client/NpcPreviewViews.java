package dev.logos.dimension.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;

import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.dimension.npc.NpcEntities;
import dev.logos.dimension.npc.NpcParts;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端预览森林精灵的零件组合：在主世界高处的白瓷台上并排放五个精灵，分别是默认外观、
 * 短发、短发配垂眼与猫猫嘴、长直发配垂眼与猫猫嘴（不戴头饰）、素体（不显示发型、头饰、服装）。
 * 精灵关掉 AI 站定朝向镜头，待机动画照常播放。三张截图：正面、3/4、脸部近景。
 */
public abstract class NpcPreviewViews implements UIScenario {
    /** 每个精灵的外观（只写和默认不同的槽位）。 */
    static final List<Map<String, String>> LINEUP = List.of(
            Map.of(),
            Map.of("hair", "hair_short_bob", "headwear", NpcParts.NONE),
            Map.of("hair", "hair_short_bob", "eyes", "eyes_droopy", "mouth", "mouth_cat"),
            Map.of("eyes", "eyes_droopy", "mouth", "mouth_cat", "headwear", NpcParts.NONE),
            Map.of("hair", NpcParts.NONE, "headwear", NpcParts.NONE, "outfit", NpcParts.NONE));
    private static final double SPACING = 1.7;
    private static final int FLOOR = 99;

    /** 0 正面，1 3/4，2 脸部近景。 */
    protected int view() {
        return 0;
    }

    static Map<String, String> appearance(int i) {
        Map<String, String> a = new LinkedHashMap<>(NpcParts.FOREST_ELF.defaults());
        a.putAll(LINEUP.get(i));
        return NpcParts.FOREST_ELF.sanitize(a);
    }

    private static double x(int i) {
        return (i - (LINEUP.size() - 1) / 2.0) * SPACING + 0.5;
    }

    private Vec3 eye() {
        return switch (view()) {
            case 1 -> new Vec3(4.4, FLOOR + 2.3, 4.2);
            case 2 -> new Vec3(0.5, FLOOR + 2.15, 2.0);
            default -> new Vec3(0.5, FLOOR + 1.8, 4.7);
        };
    }

    private Vec3 look() {
        return view() == 2 ? new Vec3(0.5, FLOOR + 2.25, 0.5) : new Vec3(0.5, FLOOR + 1.75, 0.5);
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
                    mc.options.fov().set(view() == 2 ? 40 : 55);
                    mc.options.renderDistance().set(8);
                    mc.options.broadcastOptions();
                })
                .server("白瓷台与五个精灵", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -8; x <= 9; x++)
                        for (int z = -5; z <= 8; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                            for (int y = FLOOR + 1; y <= FLOOR + 6; y++) level.removeBlock(new BlockPos(x, y, z), false);
                        }
                    AABB area = new AABB(-9, FLOOR, -6, 10, FLOOR + 6, 9);
                    level.getEntitiesOfClass(ForestElf.class, area).forEach(e -> e.discard());
                    for (int i = 0; i < LINEUP.size(); i++) {
                        ForestElf elf = NpcEntities.FOREST_ELF.get().create(level, EntitySpawnReason.COMMAND);
                        elf.snapTo(x(i), FLOOR + 1, 0.5, 0, 0);
                        elf.setYHeadRot(0);
                        elf.setYBodyRot(0);
                        elf.setNoAi(true);
                        elf.setPersistenceRequired();
                        elf.setAppearance(appearance(i));
                        level.addFreshEntity(elf);
                    }
                    var p = ctx.player();
                    Vec3 eye = eye(), d = look().subtract(eye);
                    DimensionTravel.teleport(p, level, eye.subtract(0, p.getEyeHeight(), 0), (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                            (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                })
                .waitUntil("五个精灵同步到客户端", ctx -> elves().size() == LINEUP.size()).ticks(200)
                .step("对准精灵", ctx -> {
                    var p = Minecraft.getInstance().player;
                    var d = look().subtract(p.getEyePosition());
                    p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                }).ticks(40)
                .check("外观同步到客户端", ctx -> {
                    List<ForestElf> elves = elves();
                    for (int i = 0; i < elves.size(); i++) if (!elves.get(i).npcAppearance().equals(appearance(i))) return false;
                    return true;
                })
                .check("离屏截图有实际分辨率", ctx -> {
                    var target = Minecraft.getInstance().getMainRenderTarget();
                    return target.width == 1600 && target.height == 900;
                })
                .screenshot(view() == 0 ? "forest_elf_lineup" : view() == 1 ? "forest_elf_lineup_angle" : "forest_elf_faces")
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    /** 客户端里的精灵，按 x 从左到右排序（与 LINEUP 顺序一致）。 */
    private static List<ForestElf> elves() {
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        List<ForestElf> out = new ArrayList<>(level.getEntitiesOfClass(ForestElf.class, new AABB(-9, FLOOR, -6, 10, FLOOR + 6, 9)));
        out.sort((a, b) -> Double.compare(a.getX(), b.getX()));
        return out;
    }

    @LDLRegisterClient(name = "npc_forest_elf_lineup", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Lineup extends NpcPreviewViews {}

    @LDLRegisterClient(name = "npc_forest_elf_angle", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Angle extends NpcPreviewViews {
        @Override protected int view() { return 1; }
    }

    @LDLRegisterClient(name = "npc_forest_elf_faces", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Faces extends NpcPreviewViews {
        @Override protected int view() { return 2; }
    }
}
