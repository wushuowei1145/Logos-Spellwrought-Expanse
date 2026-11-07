package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.screenfx.GlowBloom;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.ManaLifeform;
import dev.logos.runtime.PlayerMagic;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 消散和泛光的实际客户端检查，分组 {@code dissolve}：生物、手上和地上的物品、方块，以及同一次消散关开泛光的对比。
 * 改了这部分要在 docs/SCREEN_EFFECTS.md 列的环境里各跑一遍。
 */
public abstract class DissolveViews implements UIScenario {
    private static final int TICKS = 80;
    private boolean hideGui;

    @Override public void configure(ScenarioOptions options) {
        options.tags("dissolve").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
    }

    private static Mob mob(ServerLevel level, EntityType<? extends Mob> type, double x, double z) {
        Mob mob = type.create(level, EntitySpawnReason.COMMAND);
        mob.snapTo(x, 100, z, 0, 0);
        mob.setNoAi(true);
        mob.setYBodyRot(0);
        mob.setYHeadRot(0);
        level.addFreshEntity(mob);
        return mob;
    }

    /** 中间是石头，外面是夹着泥土和圆石的草地，看得清碎片和光落在什么上面。 */
    private static void arena(ServerLevel level) {
        BlockState[] patches = {Blocks.DIRT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()};
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 40; z++) {
            int hash = Math.floorMod(x * 73856093 ^ z * 19349663, 11);
            BlockState ground = x * x + z * z <= 6 ? Blocks.STONE.defaultBlockState()
                    : hash < 3 ? patches[hash] : Blocks.GRASS_BLOCK.defaultBlockState();
            level.setBlock(new BlockPos(x, 99, z), ground, 2);
            for (int y = 100; y < 112; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** 把玩家放到 {@code from}，看向 {@code to}。 */
    private static void look(ServerPlayer player, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        player.level().getChunk(BlockPos.containing(from));
        DimensionTravel.teleport(player, player.level(), from, yaw, pitch);
    }

    protected void begin(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty normal")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    hideGui = mc.options.hideGui;
                    mc.options.hideGui = true;
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    mc.options.fov().set(70); mc.options.renderDistance().set(8); mc.options.broadcastOptions();
                })
                .server("搭场地", ctx -> arena(ctx.server().overworld()));
        s.teardown("恢复", ctx -> {
            Dissolves.clear();
            var mc = Minecraft.getInstance();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = hideGui;
        });
    }

    protected static void shots(ScenarioBuilder s, int ticks, String name) {
        s.waitUntil("客户端收到了", ctx -> Dissolves.active() > 0)
                .ticks(ticks / 4).screenshot(name + "_25")
                .ticks(ticks / 4).screenshot(name + "_50")
                .ticks(ticks / 4).screenshot(name + "_75");
    }

    /** 戴头盔拿剑的僵尸（噪波）、村民（从中间球形外扩）、苦力怕（从头往下扫）、猪（从身侧一点外扩）、魔力生命体（噪波，GeckoLib 模型）。 */
    @LDLRegisterClient(name = "dissolve_mobs", group = "dissolve", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Mobs extends DissolveViews {
        private final List<Mob> mobs = new ArrayList<>();

        @Override public void define(ScenarioBuilder s) {
            begin(s);
            s.server("站远一点", ctx -> look(ctx.player(), new Vec3(.5, 100, 6), new Vec3(.5, 100.9, 0)))
                    .server("生物", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        Mob zombie = mob(level, EntityType.ZOMBIE, -2.4, 1);
                        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                        mobs.clear();
                        mobs.addAll(List.of(zombie, mob(level, EntityType.VILLAGER, -.8, 1), mob(level, EntityType.CREEPER, .8, 1), mob(level, EntityType.PIG, 2.4, 1)));
                        // GeckoLib 模型：它把自己当自定义几何画
                        ServerPlayer p = ctx.player();
                        PlayerMagic m = PlayerMagic.of(p);
                        m.maxMana = m.mana = 100000;
                        m.trace = false;
                        Casting.cast(p, Casting.compile("(spell :mana 6000 (summon mana_lifeform :level 3 :at (pos-of caster)))"), null, null);
                        ManaLifeform lifeform = (ManaLifeform) Familiars.latest(p, null);
                        lifeform.snapTo(4.2, 100, 1, 0, 0);
                        lifeform.setNoAi(true);
                        mobs.add(lifeform);
                    })
                    .ticks(15).screenshot("dissolve_mobs_00")
                    .server("开始消散", ctx -> {
                        Mob pig = mobs.get(3);
                        Dissolving.entity(mobs.get(0), DissolveShape.noise(), TICKS, Dissolving.EMBER);
                        Dissolving.entity(mobs.get(1), DissolveShape.sphere(.5f, .5f, .5f), TICKS, Dissolving.EMBER);
                        Dissolving.entity(mobs.get(2), DissolveShape.sweep(0, -1, 0), TICKS, Dissolving.EMBER);
                        Dissolving.entity(pig, DissolveShape.point(pig.getX() + .6, pig.getY() + .5, pig.getZ()), TICKS, Dissolving.EMBER);
                        Dissolving.entity(mobs.get(4), DissolveShape.noise(), TICKS, Dissolving.EMBER);
                    });
            s.waitUntil("客户端收到了", ctx -> Dissolves.active() > 0)
                    .ticks(TICKS / 4).screenshot("dissolve_mobs_25")
                    .ticks(TICKS / 4).screenshot("dissolve_mobs_50")
                    .runCommand("time set midnight")
                    .ticks(TICKS / 8).screenshot("dissolve_mobs_62_night");
        }
    }

    /** 第一人称自己手上的剑（从尖到柄）和三叉戟（噪波），地上的掉落物（噪波），最后从背后看。 */
    @LDLRegisterClient(name = "dissolve_items", group = "dissolve", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Held extends DissolveViews {
        @Override public void define(ScenarioBuilder s) {
            begin(s);
            s.step("显示手", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("物品", ctx -> {
                        ServerPlayer p = ctx.player();
                        look(p, new Vec3(.5, 100, 3), new Vec3(.5, 100, 0));
                        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                        sword.enchant(ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 1);
                        p.setItemInHand(InteractionHand.MAIN_HAND, sword);
                        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TRIDENT));
                        ServerLevel level = ctx.server().overworld();
                        Item[] drops = {Items.GOLDEN_PICKAXE, Items.GRASS_BLOCK, Items.TRIDENT};
                        for (int i = 0; i < drops.length; i++) {
                            ItemEntity item = new ItemEntity(level, -.8 + i * 1.3, 100.2, 0, new ItemStack(drops[i]), 0, 0, 0);
                            item.setNeverPickUp();
                            item.setNoGravity(true);
                            level.addFreshEntity(item);
                        }
                    })
                    .ticks(15).screenshot("dissolve_items_00")
                    .server("开始消散", ctx -> {
                        ServerPlayer p = ctx.player();
                        Dissolving.held(p, InteractionHand.MAIN_HAND, DissolveShape.parse("hilt"), TICKS * 2, Dissolving.EMBER);
                        Dissolving.held(p, InteractionHand.OFF_HAND, DissolveShape.noise(), TICKS * 2, Dissolving.EMBER);
                        for (ItemEntity item : ctx.server().overworld().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(8))) {
                            Dissolving.entity(item, DissolveShape.noise(), TICKS * 2, Dissolving.EMBER);
                        }
                    });
            shots(s, TICKS * 2, "dissolve_items_hand");
            s.step("从背后看", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(5).screenshot("dissolve_items_back");
        }
    }

    /** 一面墙从中间球形外扩，一排草、树叶、楼梯和玻璃噪波消散，一根柱子从上往下扫。 */
    @LDLRegisterClient(name = "dissolve_blocks", group = "dissolve", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class BlockRow extends DissolveViews {
        @Override public void define(ScenarioBuilder s) {
            begin(s);
            s.server("站远一点", ctx -> look(ctx.player(), new Vec3(.5, 101, 9), new Vec3(.5, 101, 0)))
                    .server("方块", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        for (BlockPos p : wall()) level.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), 2);
                        BlockState[] row = {Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(), Blocks.GLASS.defaultBlockState()};
                        for (int i = 0; i < row.length; i++) level.setBlock(new BlockPos(i, 100, 0), row[i], 2);
                        for (BlockPos p : pillar()) level.setBlock(p, Blocks.OAK_LOG.defaultBlockState(), 2);
                    })
                    .ticks(15).screenshot("dissolve_blocks_00")
                    .server("开始消散", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        Dissolving.blocks(level, wall(), DissolveShape.sphere(.5f, .5f, .5f), TICKS, Dissolving.EMBER);
                        for (int i = 0; i < 4; i++) Dissolving.blocks(level, List.of(new BlockPos(i, 100, 0)), DissolveShape.noise(), TICKS, Dissolving.EMBER);
                        Dissolving.blocks(level, pillar(), DissolveShape.sweep(0, -1, 0), TICKS, Dissolving.EMBER);
                    });
            shots(s, TICKS, "dissolve_blocks");
        }

        private static List<BlockPos> wall() {
            List<BlockPos> all = new ArrayList<>();
            for (int x = -6; x <= -2; x++) for (int y = 100; y < 103; y++) all.add(new BlockPos(x, y, 0));
            return all;
        }

        private static List<BlockPos> pillar() {
            List<BlockPos> all = new ArrayList<>();
            for (int y = 100; y < 105; y++) all.add(new BlockPos(5, y, 0));
            return all;
        }
    }

    /** 同一次消散在白天和夜里，关和开泛光各拍一张；东西都没了之后泛光通道不再跑。 */
    @LDLRegisterClient(name = "dissolve_bloom", group = "dissolve", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Bloom extends DissolveViews {
        private static final int LONG = 240;
        private double previous;
        private long idle;

        @Override public void define(ScenarioBuilder s) {
            begin(s);
            s.teardown("恢复泛光", ctx -> ClientConfig.BLOOM.set(previous));
            s.step("记下设置", ctx -> previous = ClientConfig.BLOOM.get())
                    .server("站远一点", ctx -> look(ctx.player(), new Vec3(.5, 100, 5), new Vec3(.5, 100.9, 0)))
                    .server("生物", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        Mob zombie = mob(level, EntityType.ZOMBIE, -1.2, 1);
                        // 戴头盔，不然太阳下会着火
                        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                        Dissolving.entity(zombie, DissolveShape.noise(), LONG, Dissolving.EMBER);
                        Dissolving.entity(mob(level, EntityType.VILLAGER, 1.2, 1), DissolveShape.sweep(0, -1, 0), LONG, Dissolving.EMBER);
                    })
                    .waitUntil("客户端收到了", ctx -> Dissolves.active() > 0)
                    .ticks(LONG / 3)
                    .step("关泛光", ctx -> ClientConfig.BLOOM.set(0.0)).ticks(1).screenshot("dissolve_bloom_day_off")
                    .step("开泛光", ctx -> ClientConfig.BLOOM.set(1.0)).ticks(1).screenshot("dissolve_bloom_day_on")
                    .runCommand("time set midnight").ticks(2)
                    .step("关泛光", ctx -> ClientConfig.BLOOM.set(0.0)).ticks(1).screenshot("dissolve_bloom_night_off")
                    .step("开泛光", ctx -> ClientConfig.BLOOM.set(1.0)).ticks(1).screenshot("dissolve_bloom_night_on");
            s.waitUntil("全部结束", ctx -> Dissolves.active() == 0 && Dissolves.motes() == 0).ticks(5)
                    .step("记下通道次数", ctx -> idle = GlowBloom.passes())
                    .ticks(10)
                    .step("没有再跑", ctx -> {
                        if (GlowBloom.passes() != idle) throw new AssertionError("没东西发光时泛光还在跑");
                    });
        }
    }
}
