package dev.logos.dimension.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.geckolib.animatable.manager.AnimatableManager;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcEntities;
import dev.logos.dimension.npc.NpcParts;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端预览神狐：白瓷台上五只神狐，换着穿六套衣装中的五套，耳朵、尾巴、发型各不相同。
 * 待机时眨眼、耳朵抖动、尾巴轻摆三个常驻叠加层照常循环。截图：正面、背面（尾巴）、脸部近景；
 * 死亡预览里三只神狐错开时间受到致命一击，截图时从左到右是倒地停住、侧倒中、受击中；
 * 表情预览里五只神狐依次换上调侃、狐狸眯眼、开心、惊讶、愤怒。
 */
public abstract class DivineFoxViews implements UIScenario {
    /** 每只神狐的外观（只写和默认不同的槽位）。 */
    static final List<Map<String, String>> LINEUP = List.of(
            Map.of(),
            Map.of("outfit", "outfit_everyday_kosode", "ears", "ear_fox_relaxed", "tails", "tail_fox_long"),
            Map.of("outfit", "outfit_sakura_yukata", "hair", "hair_shimada_updo", "headwear", "headwear_sakura_kanzashi",
                    "ears", "ear_fox_airplane", "tails", "tail_fox_triple"),
            Map.of("outfit", "outfit_kagura", "hair", "hair_front_braids", "headwear", "headwear_fox_mask", "ears", "ear_fox_drooping"),
            Map.of("outfit", "outfit_furisode", "hair", "hair_hime_cut", "eyebrows", "eyebrows_fox_maro", "ears", "ear_fox_flared",
                    "eyes", "eyes_amber_droopy"));
    /** 表情预览里五只神狐各自换上的表情（零件表 expressions）。 */
    static final List<String> EXPRESSIONS = List.of("expression_teasing", "expression_kitsune", "expression_happy",
            "expression_surprised", "expression_angry");
    /** 常驻叠加层的控制器与动画名（NpcAnimations 的 TLM 结构）。 */
    private static final String[] LAYERS = {"pre_parallel0", "pre_parallel1", "pre_parallel2"};
    private static final double SPACING = 1.7;
    private static final int FLOOR = 99;
    private static final AABB AREA = new AABB(-9, FLOOR, -6, 10, FLOOR + 6, 9);
    /** 死亡预览截图时三只的死亡刻数（至少）：倒地停住、侧倒中、受击中（受击约 9 刻，死亡动画 31 刻，60 刻消失）。 */
    private static final int LYING = 44, FALLING = 24, HIT = 4;
    private final int[] ids = new int[LINEUP.size()];

    /** 0 正面，1 背面，2 脸部近景，3 死亡，4 五种表情。 */
    protected abstract int view();

    private boolean death() {
        return view() == 3;
    }

    private int count() {
        return death() ? 3 : LINEUP.size();
    }

    static Map<String, String> appearance(int i) {
        Map<String, String> a = new LinkedHashMap<>(NpcParts.DIVINE_FOX.defaults());
        a.putAll(LINEUP.get(i));
        return NpcParts.DIVINE_FOX.sanitize(a);
    }

    private double x(int i) {
        return (i - (count() - 1) / 2.0) * (death() ? 2.4 : view() == 4 ? 0.9 : SPACING) + 0.5;
    }

    private Vec3 eye() {
        return switch (view()) {
            case 1 -> new Vec3(0.5, FLOOR + 2.0, -5.0);
            case 2 -> new Vec3(0.5, FLOOR + 2.15, 2.0);
            case 3 -> new Vec3(3.6, FLOOR + 2.2, 4.4);
            case 4 -> new Vec3(0.5, FLOOR + 2.2, 3.6);
            default -> new Vec3(0.5, FLOOR + 1.8, 4.7);
        };
    }

    private Vec3 look() {
        return switch (view()) {
            case 2, 4 -> new Vec3(0.5, FLOOR + 2.25, 0.5);
            case 3 -> new Vec3(0.5, FLOOR + 0.8, 0.5);
            default -> new Vec3(0.5, FLOOR + 1.75, 0.5);
        };
    }

    private String shot() {
        return switch (view()) {
            case 1 -> "divine_fox_back";
            case 2 -> "divine_fox_face";
            case 3 -> "divine_fox_death";
            case 4 -> "divine_fox_expressions";
            default -> "divine_fox_lineup";
        };
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
                    mc.options.fov().set(view() == 2 ? 40 : view() == 4 ? 60 : 55);
                    mc.options.renderDistance().set(8);
                    mc.options.broadcastOptions();
                })
                .server("白瓷台与神狐", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -8; x <= 9; x++)
                        for (int z = -5; z <= 8; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                            for (int y = FLOOR + 1; y <= FLOOR + 6; y++) level.removeBlock(new BlockPos(x, y, z), false);
                        }
                    level.getEntitiesOfClass(DivineFox.class, AREA).forEach(e -> e.discard());
                    for (int i = 0; i < count(); i++) {
                        DivineFox fox = NpcEntities.DIVINE_FOX.get().create(level, EntitySpawnReason.COMMAND);
                        fox.setAppearance(appearance(death() || view() == 4 ? 0 : i));
                        if (view() == 4) fox.setExpression(EXPRESSIONS.get(i));
                        fox.snapTo(x(i), FLOOR + 1, 0.5, 0, 0);
                        fox.setYHeadRot(0);
                        fox.setYBodyRot(0);
                        fox.setNoAi(true);
                        fox.setPersistenceRequired();
                        level.addFreshEntity(fox);
                        ids[i] = fox.getId();
                    }
                    var p = ctx.player();
                    Vec3 eye = eye(), d = look().subtract(eye);
                    DimensionTravel.teleport(p, level, eye.subtract(0, p.getEyeHeight(), 0), (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                            (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                })
                .waitUntil("神狐同步到客户端", ctx -> foxes().size() == count()).ticks(200)
                .step("对准神狐", ctx -> {
                    var p = Minecraft.getInstance().player;
                    var d = look().subtract(p.getEyePosition());
                    p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                }).ticks(40)
                .check("外观同步到客户端", ctx -> {
                    List<DivineFox> foxes = foxes();
                    for (int i = 0; i < foxes.size(); i++)
                        if (!foxes.get(i).npcAppearance().equals(appearance(death() || view() == 4 ? 0 : i))) return false;
                    return true;
                })
                .check("动画文件有三个常驻叠加层", ctx -> NpcAnimations.available(foxes().get(0).npcAnimationAsset()).containsAll(List.of(LAYERS)))
                // 近景只拍到中间那只；镜头外的神狐 GeckoLib 不建动画状态
                .check("眨眼、耳朵抖动、尾巴轻摆在待机时都在循环", ctx -> (view() == 2 ? List.of(foxes().get(2)) : foxes()).stream()
                        .allMatch(DivineFoxViews::layersPlaying));
        if (view() == 4) {
            s.check("表情同步到客户端，换掉常态眼嘴", ctx -> {
                        List<DivineFox> foxes = foxes();
                        for (int i = 0; i < foxes.size(); i++) {
                            DivineFox fox = foxes.get(i);
                            var hidden = fox.npcRace().hiddenGroups(fox.npcAppearance(), fox.npcExpression());
                            if (!EXPRESSIONS.get(i).equals(fox.npcExpression()) || !hidden.containsAll(List.of("Eyelid", "Mouth"))
                                    || hidden.contains(EXPRESSIONS.get(i))) return false;
                        }
                        return true;
                    })
                    .screenshot(shot());
        } else if (death()) {
            s.server("左边的神狐受到致命一击", ctx -> kill(ctx.server().overworld(), 0))
                    .waitUntilServer("左边的神狐倒下了一阵", ctx -> deathTime(ctx.server().overworld(), 0) >= LYING - FALLING)
                    .server("中间的神狐受到致命一击", ctx -> kill(ctx.server().overworld(), 1))
                    .waitUntilServer("中间的神狐开始侧倒", ctx -> deathTime(ctx.server().overworld(), 1) >= FALLING - HIT)
                    .server("右边的神狐受到致命一击", ctx -> kill(ctx.server().overworld(), 2))
                    .waitUntilServer("右边的神狐在受击", ctx -> deathTime(ctx.server().overworld(), 2) >= HIT)
                    .screenshot(shot())
                    .check("三只神狐都还在（死亡动画播完前不消失）", ctx -> foxes().size() == 3 && foxes().stream().allMatch(e -> e.isDeadOrDying()))
                    .check("动画文件有死亡动画", ctx -> NpcAnimations.available(foxes().get(0).npcAnimationAsset()).contains(NpcAnimations.DEATH));
        } else {
            s.screenshot(shot());
        }
        s.check("离屏截图有实际分辨率", ctx -> {
                    var target = Minecraft.getInstance().getMainRenderTarget();
                    return target.width == 1600 && target.height == 900;
                })
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    /** 三个叠加层控制器当前都在播放各自的动画。 */
    private static boolean layersPlaying(DivineFox fox) {
        AnimatableManager<DivineFox> manager = fox.getAnimatableInstanceCache().getManagerForId(fox.getId());
        if (manager == null) return false;
        for (int i = 0; i < LAYERS.length; i++) {
            var controller = manager.getAnimationControllers().get(String.format("pre_parallel_%d_controller", i));
            var raw = controller == null ? null : controller.getCurrentRawAnimation();
            if (raw == null || raw.getAnimationStages().isEmpty() || !LAYERS[i].equals(raw.getAnimationStages().get(0).animationName()))
                return false;
        }
        return true;
    }

    /** 一击致命：走正常的受伤流程，不是直接移除。 */
    private void kill(ServerLevel level, int index) {
        if (level.getEntity(ids[index]) instanceof DivineFox fox) fox.hurtServer(level, level.damageSources().generic(), fox.getMaxHealth() * 10);
    }

    private int deathTime(ServerLevel level, int index) {
        return level.getEntity(ids[index]) instanceof DivineFox fox ? fox.deathTime : Integer.MAX_VALUE;
    }

    /** 客户端里的神狐，按 x 从左到右排序。 */
    private static List<DivineFox> foxes() {
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        List<DivineFox> out = new ArrayList<>(level.getEntitiesOfClass(DivineFox.class, AREA));
        out.sort((a, b) -> Double.compare(a.getX(), b.getX()));
        return out;
    }

    @LDLRegisterClient(name = "npc_divine_fox_lineup", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Lineup extends DivineFoxViews {
        @Override protected int view() { return 0; }
    }

    @LDLRegisterClient(name = "npc_divine_fox_back", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Back extends DivineFoxViews {
        @Override protected int view() { return 1; }
    }

    @LDLRegisterClient(name = "npc_divine_fox_face", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Face extends DivineFoxViews {
        @Override protected int view() { return 2; }
    }

    @LDLRegisterClient(name = "npc_divine_fox_death", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Death extends DivineFoxViews {
        @Override protected int view() { return 3; }
    }

    @LDLRegisterClient(name = "npc_divine_fox_expressions", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Expressions extends DivineFoxViews {
        @Override protected int view() { return 4; }
    }
}
