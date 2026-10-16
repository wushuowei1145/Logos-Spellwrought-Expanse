package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.instance.BoundCauldronBlockEntity;
import dev.logos.dimension.instance.CauldronBinding;
import dev.logos.dimension.instance.CauldronWorlds;
import dev.logos.dimension.world.AlchemicalChunkGenerator;
import dev.logos.dimension.world.AlchemicalLayout;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Exercise real level creation, item mining/placement and damage destruction, not just UUID math. */
public final class CauldronInstanceGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final List<String> NAMES = List.of("cauldron_uuid_worlds", "cauldron_mining_binding", "cauldron_world_destruction", "cauldron_return_storage");

    public static void register(IEventBus bus) {
        FUNCTIONS.register(NAMES.get(0), () -> CauldronInstanceGameTests::worlds);
        FUNCTIONS.register(NAMES.get(1), () -> CauldronInstanceGameTests::mining);
        FUNCTIONS.register(NAMES.get(2), () -> CauldronInstanceGameTests::destruction);
        FUNCTIONS.register(NAMES.get(3), () -> CauldronInstanceGameTests::returns);
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(LogosDimension.id("cauldron_instances"), new TestEnvironmentDefinition.AllOf());
            for (String name : NAMES) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 600, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }

    private static BoundCauldronBlockEntity place(GameTestHelper h, BlockPos pos) {
        h.getLevel().setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        h.getLevel().setBlockAndUpdate(pos, DimensionBlocks.ALCHEMICAL_CAULDRON.get().defaultBlockState());
        var binding = h.getLevel().getBlockEntity(pos);
        h.assertTrue(binding instanceof BoundCauldronBlockEntity, "Cauldron has no persistent binding block entity");
        return (BoundCauldronBlockEntity) binding;
    }

    /** Unlike GameTestHelper's mock, this player's gameMode() really follows setGameMode(SURVIVAL). */
    private static ServerPlayer player(GameTestHelper h) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "cauldron-test"), false);
        var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.connection.markClientLoaded();
        player.setGameMode(GameType.CREATIVE);
        return player;
    }

    private static void dispose(MinecraftServer server, ServerPlayer player, UUID... ids) {
        if (LogosDimension.isCrucible(player.level().dimension())) DimensionTravel.leave(player);
        server.getPlayerList().remove(player);
        for (UUID id : ids) if (id != null) CauldronWorlds.requestDestroy(server, id);
        CauldronWorlds.processPending(server);
    }

    private static void worlds(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var first = place(h, h.absolutePos(new BlockPos(3, 2, 3)));
        var second = place(h, h.absolutePos(new BlockPos(10, 2, 3)));
        h.assertTrue(first.worldId() == null && second.worldId() == null, "Placing unused cauldrons eagerly allocates UUID worlds");
        int count = CauldronWorlds.data(server).snapshot().size();
        var p = player(h);
        Vec3 home = h.absoluteVec(new Vec3(6.5, 3, 10.5));
        try {
            h.assertTrue(CauldronWorlds.data(server).snapshot().size() == count, "Unused cauldron created a world before entry");
            h.assertTrue(CauldronWorlds.enter(p, first, home), "First cauldron could not create its world");
            UUID a = first.worldId();
            var target = p.level();
            h.assertTrue(a != null && target.dimension().equals(CauldronWorlds.key(a)), "World name is not its binding UUID");
            h.assertTrue(Math.abs(target.getWorldBorder().getSize() - 20_000) < .01, "Instance does not have a 20,000 block border");
            h.assertTrue(target.getWorldBorder().getCenterX() == 0 && target.getWorldBorder().getCenterZ() == 0, "Border is not centered on the sole island");
            h.assertTrue(target.getWorldBorder().isWithinBounds(p.blockPosition()), "Entrance placed player beyond the border");
            var gen = (AlchemicalChunkGenerator) target.getChunkSource().getGenerator();
            h.assertTrue(Math.hypot(p.getX(), p.getZ()) > 8500 && gen.terrain().column(p.blockPosition().getX(), p.blockPosition().getZ()).geography().region() == AlchemicalLayout.Region.AETHER,
                    "Default arrival is not in the outer Aether wilderness");
            long seed = target.getSeed();
            BlockPos marker = p.blockPosition().below();
            target.setBlockAndUpdate(marker, Blocks.LODESTONE.defaultBlockState());
            dev.logos.dimension.IslandProgress.of(target).setDefeated(gen.terrain().layout().center(0, 0), true);
            h.assertTrue(DimensionTravel.leave(p) && p.level() == h.getLevel() && p.position().distanceToSqr(home) < .01, "First round trip lost the original home");
            h.assertTrue(CauldronWorlds.enter(p, second, home), "Second cauldron entrance failed");
            UUID b = second.worldId();
            h.assertTrue(b != null && !b.equals(a) && p.level() != target, "Separate cauldrons share a world");
            h.assertTrue(p.level().getSeed() != seed, "Separate UUIDs share the same terrain seed");
            var secondGen = (AlchemicalChunkGenerator) p.level().getChunkSource().getGenerator();
            h.assertTrue(!dev.logos.dimension.IslandProgress.of(p.level()).defeated(secondGen.terrain().layout().center(0, 0)), "Boss completion leaked into a different UUID world");
            h.assertTrue(DimensionTravel.leave(p), "Second cauldron return failed");
            h.assertTrue(CauldronWorlds.enter(p, first, home) && first.worldId().equals(a) && p.level() == target && p.level().getSeed() == seed,
                    "Reentry generated a different instance or seed");
            h.assertTrue(target.getBlockState(marker).is(Blocks.LODESTONE), "Reentry reset existing chunks");
            h.assertTrue(dev.logos.dimension.IslandProgress.of(target).defeated(gen.terrain().layout().center(0, 0)), "Reentry lost this world's Boss completion");
            var encoded = CauldronWorlds.Worlds.CODEC.encodeStart(JsonOps.INSTANCE, CauldronWorlds.data(server)).getOrThrow();
            var decoded = CauldronWorlds.Worlds.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            h.assertTrue(decoded.snapshot().get(a.toString()).seed() == seed && !decoded.snapshot().get(a.toString()).deleted(), "Saved UUID metadata lost the seed or live state");
        } finally {
            dispose(server, p, first.worldId(), second.worldId());
        }
        h.succeed();
    }

    private static void mining(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var p = player(h);
        UUID id = UUID.randomUUID();
        var world = CauldronWorlds.load(server, id);
        try {
            for (GameType mode : List.of(GameType.SURVIVAL, GameType.CREATIVE)) {
                BlockPos pos = h.absolutePos(new BlockPos(mode == GameType.SURVIVAL ? 3 : 10, 2, 8));
                var binding = place(h, pos);
                binding.setWorldId(id);
                p.setGameMode(mode);
                p.setPos(pos.getCenter().add(0, 0, 2.5));
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                h.assertTrue(p.gameMode.destroyBlock(pos), "Actual " + mode + " mining failed");
                h.assertTrue(h.getLevel().isEmptyBlock(pos), "Mining left the cauldron block behind");
                var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2),
                        e -> id.equals(e.getItem().get(CauldronBinding.WORLD_ID.get())));
                h.assertTrue(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "Mining failed to drop exactly one UUID-bound cauldron in " + mode);
                CauldronWorlds.processPending(server);
                h.assertTrue(server.getLevel(CauldronWorlds.key(id)) == world && !CauldronWorlds.isDestroyed(server, id), "Mining destroyed the bound world");
                var drop = drops.getFirst();
                drop.setNoPickUpDelay();
                drop.playerTouch(p);
                h.assertTrue(drop.isRemoved(), "Mined bound item cannot be picked up");
                ItemStack stack = ItemStack.EMPTY;
                for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) {
                    var item = p.getInventory().getItem(slot);
                    if (id.equals(item.get(CauldronBinding.WORLD_ID.get()))) { stack = p.getInventory().removeItemNoUpdate(slot); break; }
                }
                h.assertTrue(!stack.isEmpty(), "Pickup lost the world component");
                p.setItemInHand(InteractionHand.MAIN_HAND, stack);
                var context = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack,
                        new BlockHitResult(pos.getBottomCenter(), net.minecraft.core.Direction.UP, pos.below(), false));
                ((BlockItem) stack.getItem()).place(context);
                h.assertTrue(h.getLevel().getBlockEntity(pos) instanceof BoundCauldronBlockEntity replacement && id.equals(replacement.worldId()), "Replacing a mined cauldron lost its world binding");
                CauldronWorlds.processPending(server);
                h.assertTrue(!CauldronWorlds.isDestroyed(server, id), "Pickup or placement counted as item destruction");
                // Clear by actual creative mining too, then discard the test drop without damage.
                p.setGameMode(GameType.CREATIVE);
                p.gameMode.destroyBlock(pos);
                for (var item : h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2), e -> id.equals(e.getItem().get(CauldronBinding.WORLD_ID.get())))) item.discard();
                CauldronWorlds.processPending(server);
                h.assertTrue(!CauldronWorlds.isDestroyed(server, id), "Non-damage item removal destroyed a world");
            }
        } finally {
            dispose(server, p, id);
        }
        h.succeed();
    }

    private static void destruction(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var originalCrucible = server.getLevel(LogosDimension.CRUCIBLE);
        var originalContinent = server.getLevel(LogosDimension.CLOUDSEA);
        var binding = place(h, h.absolutePos(new BlockPos(4, 2, 4)));
        var p = player(h);
        UUID survivorId = UUID.randomUUID();
        var survivor = CauldronWorlds.load(server, survivorId);
        var survivorMarker = new BlockPos(0, 420, 0);
        survivor.getChunk(survivorMarker);
        survivor.setBlockAndUpdate(survivorMarker, Blocks.LODESTONE.defaultBlockState());
        survivor.save(null, true, false);
        Vec3 home = h.absoluteVec(new Vec3(10.5, 3, 10.5));
        try {
            h.assertTrue(CauldronWorlds.enter(p, binding, home), "Cannot enter world before destruction");
            UUID id = binding.worldId();
            var doomed = p.level();
            h.assertTrue(doomed.players().contains(p) && server.getPlayerList().getPlayers().contains(p), "Evacuation test player is not online in the doomed level");
            doomed.save(null, true, false);
            h.assertTrue(Files.isDirectory(CauldronWorlds.directory(server, id)), "Test created no real instance directory");
            h.assertTrue(Files.isDirectory(CauldronWorlds.directory(server, id).resolve("region")), "Test created no real region storage before deletion");
            var item = new ItemEntity(h.getLevel(), home.x, home.y, home.z, binding.boundItem());
            h.getLevel().addFreshEntity(item);
            h.assertTrue(item.hurtServer(h.getLevel(), h.getLevel().damageSources().lava(), 100), "Lava damage did not reach the cauldron item");
            h.assertTrue(item.isRemoved() && CauldronWorlds.isDestroyed(server, id), "Destroyed item did not tombstone its UUID world");
            CauldronWorlds.processPending(server);
            h.assertTrue(p.level() == h.getLevel() && p.position().distanceToSqr(home) < .01, "World destruction stranded its online player");
            h.assertTrue(server.getLevel(CauldronWorlds.key(id)) == null && !Files.exists(CauldronWorlds.directory(server, id)), "Destroyed world remains loaded or on disk");
            h.assertTrue(CauldronWorlds.load(server, id) == null && !CauldronWorlds.enter(p, binding, home), "Stale bound cauldron resurrected a destroyed UUID");
            h.assertTrue(server.getLevel(CauldronWorlds.key(survivorId)) == survivor && !CauldronWorlds.isDestroyed(server, survivorId), "Destruction affected a different UUID world");
            h.assertTrue(Files.isDirectory(CauldronWorlds.directory(server, survivorId).resolve("region")) && survivor.getBlockState(survivorMarker).is(Blocks.LODESTONE),
                    "Deleting one UUID removed another instance's saved region or blocks");
            h.assertTrue(server.getLevel(LogosDimension.CRUCIBLE) == originalCrucible && server.getLevel(LogosDimension.CLOUDSEA) == originalContinent,
                    "Destruction unloaded a static dimension");
            UUID unknown = UUID.randomUUID();
            CauldronWorlds.requestDestroy(server, unknown);
            CauldronWorlds.processPending(server);
            h.assertTrue(!CauldronWorlds.data(server).snapshot().containsKey(unknown.toString()), "Unknown UUID deletion created an owned tombstone");
        } finally {
            dispose(server, p, binding.worldId(), survivorId);
        }
        h.succeed();
    }

    private static void returns(GameTestHelper h) {
        var data = new DimensionTravel.Returns();
        String player = UUID.randomUUID().toString();
        var home = new DimensionTravel.ReturnPoint("minecraft:overworld", 1, 65, 3, 45, 10);
        var nested = new DimensionTravel.ReturnPoint(CauldronWorlds.key(UUID.randomUUID()).identifier().toString(), 9300, 95, 20, 180, 0);
        data.put(player, home); data.put(player, nested);
        var encoded = DimensionTravel.Returns.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var restored = DimensionTravel.Returns.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        h.assertTrue(nested.equals(restored.get(player)), "Nested return destination did not survive saving");
        restored.remove(player);
        h.assertTrue(home.equals(restored.get(player)), "Leaving a nested UUID world lost the outer return point");
        restored.remove(player);
        h.assertTrue(restored.get(player) == null, "Completed return stack was not emptied");
        var legacy = encoded.getAsJsonObject().deepCopy(); legacy.remove("paths");
        h.assertTrue(nested.equals(DimensionTravel.Returns.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow().get(player)), "Legacy homes-only save cannot be loaded");
        h.succeed();
    }

    private CauldronInstanceGameTests() {}
}
