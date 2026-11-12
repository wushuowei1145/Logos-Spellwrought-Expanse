package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.ServerConfig;
import dev.logos.dimension.explosion.ExplosionMagic;
import dev.logos.dimension.explosion.ExplosionStaffItem;
import dev.logos.dimension.explosion.ExplosionStaffPage;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.ManaVessels;
import dev.logos.runtime.PlayerMagic;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 车万女仆拿爆裂魔杖（Logos 的女仆兼容里的「施法」工作模式）：魔力池里不到 6400 时不蓄力，攒够了就对敌人放出爆裂魔法。
 * 只在装了车万女仆时登记；女仆的类全靠反射拿，附属不依赖车万女仆（CI 里没有它，这个测试不跑）。
 */
public final class ExplosionMaidGameTests {
    private static final String TLM = "touhou_little_maid";
    private static final String NAME = "explosion_maid_casts";
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    private ExplosionMaidGameTests() {}

    public static void register(IEventBus bus) {
        if (!ModList.get().isLoaded(TLM)) return;
        FUNCTIONS.register(NAME, () -> ExplosionMaidGameTests::maidCasts);
        FUNCTIONS.register(bus);
        knowMaidChannels();
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("explosion_maid"), new TestEnvironmentDefinition.AllOf());
            event.registerTest(LogosDimension.id(NAME), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(NAME)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 1400, 0, true, Rotation.NONE, false, 1, 1, false, 96)));
        });
    }

    /**
     * 测试里的假玩家没有客户端，不认得车万女仆的频道，而车万女仆在玩家进世界时不问就发同步包，测试会失败：
     * 假玩家进世界前先认得车万女仆的频道（同 Logos 的 MockClientChannels）。
     */
    @SuppressWarnings("unchecked")
    private static void knowMaidChannels() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (EntityJoinLevelEvent event) -> {
            if (!(event.getEntity() instanceof ServerPlayer p) || p.connection == null) return;
            try {
                var field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
                field.setAccessible(true);
                var all = (Map<ConnectionProtocol, Map<Identifier, ?>>) field.get(null);
                Set<Identifier> ids = all.get(ConnectionProtocol.PLAY).keySet().stream().filter(id -> id.getNamespace().equals(TLM)).collect(Collectors.toSet());
                ChannelAttributes.getOrCreateAdHocChannels(p.connection.getConnection()).addAll(ids);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("读不到已登记的网络包", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void maidCasts(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos chunk = ChunkPos.containing(h.absolutePos(BlockPos.ZERO));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setChunkForced(chunk.x() + x, chunk.z() + z, true);
        BlockPos o = h.absolutePos(new BlockPos(1, 1, 1));
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 14; z++) level.setBlockAndUpdate(o.offset(x, -1, z), Blocks.STONE.defaultBlockState());

        ServerPlayer owner = ExplosionGameTests.player(h, Vec3.atBottomCenterOf(o).add(-3, 0, 0), 0);
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        PlayerMagic.of(owner).maxMana = 10000;
        PlayerMagic.of(owner).mana = 10000;
        Mob maid;
        try {
            var type = (EntityType<Mob>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath(TLM, "maid"));
            maid = h.spawn(type, new Vec3(1.5, 1, 1.5));
            ((TamableAnimal) maid).tame(owner);
            Class<?> schedule = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule");
            // 这两个方法声明在车万女仆不公开的接口里：要 setAccessible
            var setSchedule = maid.getClass().getMethod("setSchedule", schedule);
            setSchedule.setAccessible(true);
            setSchedule.invoke(maid, Enum.valueOf((Class<Enum>) schedule, "ALL"));
            Class<?> tasks = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager");
            Object task = ((Optional<?>) tasks.getMethod("findTask", Identifier.class).invoke(null, Identifier.fromNamespaceAndPath("logos", "spellcasting"))).orElseThrow();
            var setTask = maid.getClass().getMethod("setTask", Class.forName("com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask"));
            setTask.setAccessible(true);
            setTask.invoke(maid, task);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("车万女仆的接口变了：" + (e.getCause() != null ? e.getCause() : e), e);
        }
        maid.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(ExplosionStaffItem.ITEM.get()));
        Mob husk = h.spawn(EntityType.HUSK, new Vec3(1.5, 1, 10.5));
        husk.setNoAi(true);
        h.assertTrue(ManaVessels.of(maid) instanceof CreatureMana, "被驯服的女仆有魔力池");
        CreatureMana pool = (CreatureMana) ManaVessels.of(maid);
        pool.setMana(3000);
        int before = ExplosionMagic.blasts();

        // 魔力池里只有 3000：一点都不拿去蓄（只会随时间回一点），也不放
        h.runAfterDelay(80, () -> {
            h.assertTrue(PageCheck.hasPage(maid), "女仆手里的爆裂魔杖补上了载页");
            h.assertTrue(pool.mana() >= 3000 - 1e-6 && ExplosionMagic.blasts() == before, "不到 6400 不蓄力：魔力池还剩 " + pool.mana());
            boolean savedTerrain = ServerConfig.EXPLOSION_BREAKS_TERRAIN.get();
            int savedRadius = ServerConfig.EXPLOSION_RADIUS.get();
            ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(false);
            ServerConfig.EXPLOSION_RADIUS.set(16);
            pool.setMana(pool.maxMana());
            // 攒够了：蓄满 6400 放出爆裂魔法
            h.succeedWhen(() -> {
                h.assertTrue(ExplosionMagic.blasts() > before, "攒够 6400 就对着尸壳放爆裂魔法（目标 "
                        + maid.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET).map(e -> e.getType().toShortString()).orElse("none")
                        + "，魔力池 " + pool.mana() + "）");
                ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(savedTerrain);
                ServerConfig.EXPLOSION_RADIUS.set(savedRadius);
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setChunkForced(chunk.x() + x, chunk.z() + z, false);
            });
        });
    }

    /** 女仆手里的魔杖带着那张载页。 */
    private static final class PageCheck {
        static boolean hasPage(Mob maid) {
            var page = dev.logos.item.PageContainer.get(maid.getMainHandItem(), 0);
            var spell = dev.logos.item.SpellPages.spell(page);
            return spell != null && ExplosionStaffPage.is(spell);
        }
    }
}
