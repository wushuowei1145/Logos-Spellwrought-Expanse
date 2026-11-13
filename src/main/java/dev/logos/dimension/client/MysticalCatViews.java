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
import dev.logos.dimension.npc.MysticalCat;
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
 * 实际客户端预览灵猫（docs/MYSTICAL_CAT.md）：主世界高处的白瓷台上并排八只，毛色与搭配各不相同。
 * 灵猫关掉 AI 站定朝向镜头，待机与常驻叠加层（眨眼、抖耳、摆尾）照常播放。
 * 分开的场景：正面、3/4、脸部近景、四种姿势（坐着、被摸头、伸懒腰、洗脸）、午夜（青色灵火与菱形发光）。
 */
public abstract class MysticalCatViews implements UIScenario {
    /** 每只的毛色与外观（只写和默认不同的槽位）。 */
    record Look(String coat, Map<String, String> parts) {}

    static final List<Look> LINEUP = List.of(
            new Look("ginger", Map.of("outfit", "outfit_cat_academy", "headwear", "headwear_cat_beret", "tails", "tail_cat_ribbon")),
            new Look("calico", Map.of("outfit", "outfit_cat_townsfolk", "hair", "hair_cat_braids", "headwear", NpcParts.NONE,
                    "ears", "ear_cat_folded", "tails", "tail_cat_fluffy")),
            new Look("ink", Map.of("outfit", "outfit_cat_witch", "hair", "hair_cat_hime", "headwear", "headwear_cat_witch_hat",
                    "ears", "ear_cat_tufted", "tails", "tail_cat_twin")),
            new Look("tuxedo", Map.of("outfit", "outfit_cat_academy", "hair", "hair_cat_ponytail", "headwear", "headwear_cat_bell_ribbon")),
            new Look("snow", Map.of("outfit", "outfit_cat_townsfolk", "hair", "hair_cat_ponytail", "headwear", "headwear_cat_bell_ribbon",
                    "tails", "tail_cat_ribbon")),
            new Look("siamese", Map.of("outfit", "outfit_cat_witch", "headwear", "headwear_cat_witch_hat", "mouth", "mouth_cat_smile")),
            new Look("tabby", Map.of("outfit", "outfit_cat_academy", "hair", "hair_cat_braids", "headwear", NpcParts.NONE,
                    "ears", "ear_cat_tufted", "tails", "tail_cat_fluffy")),
            new Look("blue", Map.of("outfit", "outfit_cat_townsfolk", "hair", "hair_cat_hime", "headwear", "headwear_cat_beret",
                    "ears", "ear_cat_folded")));
    private static final double SPACING = 1.25;
    private static final int FLOOR = 99;

    /** 0 正面，1 3/4，2 脸部近景，3 姿势，4 午夜。镜头眼睛不低于台面 2.62 格，否则创造模式的玩家会落到台面上。 */
    protected abstract int view();

    protected abstract String shot();

    protected List<Look> lineup() {
        return view() == 3 ? LINEUP.subList(0, 4) : LINEUP;
    }

    static Map<String, String> appearance(Look look) {
        Map<String, String> a = new LinkedHashMap<>(NpcParts.MYSTICAL_CAT.defaults());
        a.putAll(look.parts());
        return NpcParts.MYSTICAL_CAT.sanitize(a);
    }

    private double x(int i) {
        return (i - (lineup().size() - 1) / 2.0) * (view() == 3 ? 1.6 : SPACING) + 0.5;
    }

    private Vec3 eye() {
        return switch (view()) {
            case 1 -> new Vec3(4.2, FLOOR + 2.7, -5.6);
            case 2 -> new Vec3(-1.4, FLOOR + 2.66, -1.75);
            case 3 -> new Vec3(0.5, FLOOR + 2.7, -5.0);
            // 午夜：从猫又（第三只）的左后方看尾尖的灵火，前面几只的领针也在画面里
            case 4 -> new Vec3(-4.2, FLOOR + 2.7, 3.4);
            default -> new Vec3(0.5, FLOOR + 2.7, -7.6);
        };
    }

    private Vec3 look() {
        return switch (view()) {
            case 1 -> new Vec3(-0.6, FLOOR + 1.0, 0.5);
            case 2 -> new Vec3(-1.4, FLOOR + 2.32, 0.5);
            case 4 -> new Vec3(-1.1, FLOOR + 1.4, 0.5);
            default -> new Vec3(0.5, FLOOR + 1.0, 0.5);
        };
    }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("mystical_cat").defaultTimeoutMs(60_000).scenarioTimeoutMs(120_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand(view() == 4 ? "time set midnight" : "time set 6000")
                .runCommand("difficulty peaceful").runCommand("weather clear").runCommand("gamerule doDaylightCycle false")
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
                    mc.options.fov().set(view() == 2 ? 36 : 50);
                    mc.options.renderDistance().set(8);
                    mc.options.broadcastOptions();
                })
                .server("白瓷台与灵猫", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    for (int x = -8; x <= 9; x++)
                        for (int z = -8; z <= 5; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                            for (int y = FLOOR + 1; y <= FLOOR + 6; y++) level.removeBlock(new BlockPos(x, y, z), false);
                        }
                    AABB area = new AABB(-9, FLOOR, -9, 10, FLOOR + 6, 6);
                    level.getEntitiesOfClass(MysticalCat.class, area).forEach(e -> e.discard());
                    for (int i = 0; i < lineup().size(); i++) {
                        Look look = lineup().get(i);
                        MysticalCat cat = NpcEntities.MYSTICAL_CAT.get().create(level, EntitySpawnReason.COMMAND);
                        cat.snapTo(x(i), FLOOR + 1, 0.5, 180, 0);
                        cat.setYHeadRot(180);
                        cat.setYBodyRot(180);
                        cat.setNoAi(true);
                        cat.setPersistenceRequired();
                        cat.setAppearance(appearance(look));
                        cat.setCoat(MysticalCat.COATS.indexOf(look.coat()));
                        level.addFreshEntity(cat);
                    }
                    var p = ctx.player();
                    Vec3 eye = eye(), d = look().subtract(eye);
                    DimensionTravel.teleport(p, level, eye.subtract(0, p.getEyeHeight(), 0), (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                            (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                })
                .waitUntil("灵猫同步到客户端", ctx -> cats().size() == lineup().size()).ticks(200)
                .server("摆姿势", ctx -> {
                    if (view() != 3) return;
                    List<MysticalCat> cats = serverCats(ctx.server().overworld());
                    cats.get(0).sitDown(1_000_000);
                    cats.get(1).mood(MysticalCat.HAPPY, 1_000_000);
                })
                .step("对准灵猫", ctx -> {
                    var p = Minecraft.getInstance().player;
                    var d = look().subtract(p.getEyePosition());
                    p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                }).ticks(30)
                .server("动作", ctx -> {
                    if (view() != 3) return;
                    List<MysticalCat> cats = serverCats(ctx.server().overworld());
                    cats.get(1).playAction("purr");
                    cats.get(2).playAction("stretch");
                    cats.get(3).playAction("wash");
                }).ticks(view() == 3 ? 16 : 0)
                .check("外观与毛色同步到客户端", ctx -> {
                    List<MysticalCat> cats = cats();
                    for (int i = 0; i < cats.size(); i++) {
                        Look look = lineup().get(i);
                        if (!cats.get(i).npcAppearance().equals(appearance(look))) return false;
                        if (cats.get(i).coat() != MysticalCat.COATS.indexOf(look.coat())) return false;
                    }
                    return true;
                })
                .screenshot(shot())
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    private static List<MysticalCat> serverCats(ServerLevel level) {
        List<MysticalCat> out = new ArrayList<>(level.getEntitiesOfClass(MysticalCat.class, new AABB(-9, FLOOR, -9, 10, FLOOR + 6, 6)));
        out.sort((a, b) -> Double.compare(a.getX(), b.getX()));
        return out;
    }

    /** 客户端里的灵猫，按 x 从小到大（与 LINEUP 顺序一致；镜头在 -Z 一侧看过去，画面上从右到左）。 */
    private static List<MysticalCat> cats() {
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        List<MysticalCat> out = new ArrayList<>(level.getEntitiesOfClass(MysticalCat.class, new AABB(-9, FLOOR, -9, 10, FLOOR + 6, 6)));
        out.sort((a, b) -> Double.compare(a.getX(), b.getX()));
        return out;
    }

    @LDLRegisterClient(name = "mystical_cat_lineup", group = "mystical_cat", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Lineup extends MysticalCatViews {
        @Override protected int view() { return 0; }
        @Override protected String shot() { return "mystical_cat_lineup"; }
    }

    @LDLRegisterClient(name = "mystical_cat_angle", group = "mystical_cat", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Angle extends MysticalCatViews {
        @Override protected int view() { return 1; }
        @Override protected String shot() { return "mystical_cat_angle"; }
    }

    @LDLRegisterClient(name = "mystical_cat_faces", group = "mystical_cat", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Faces extends MysticalCatViews {
        @Override protected int view() { return 2; }
        @Override protected String shot() { return "mystical_cat_faces"; }
    }

    @LDLRegisterClient(name = "mystical_cat_poses", group = "mystical_cat", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Poses extends MysticalCatViews {
        @Override protected int view() { return 3; }
        @Override protected String shot() { return "mystical_cat_poses"; }
    }

    @LDLRegisterClient(name = "mystical_cat_night", group = "mystical_cat", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Night extends MysticalCatViews {
        @Override protected int view() { return 4; }
        @Override protected String shot() { return "mystical_cat_night"; }
    }
}
