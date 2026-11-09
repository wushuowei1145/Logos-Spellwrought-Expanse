package dev.logos.dimension.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;

import dev.logos.block.InscriptionTableBlock;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.reifu.Reifu;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 霊符写好后还是同品级的霊符；符札书案和编纂台一样放页、写入、取页，并在方块状态上显示放着的页。 */
public final class ReifuGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final BlockPos DESK = new BlockPos(2, 1, 2);
    private static final String SPELL = "(spell :name \"符\" :mana 5 (heal caster 1))";

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
            "reifu_write_keeps_look", ReifuGameTests::writeKeepsLook,
            "fuda_desk_inscribes", ReifuGameTests::deskInscribes);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("reifu"), new TestEnvironmentDefinition.AllOf());
            for (var name : tests.keySet()) {
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 100, 0, true, Rotation.NONE, false, 1, 1, false, 8)));
            }
        });
    }

    /** 每个品级：空白霊符写入后是同品级写好的霊符，再改写还是它；品级、能否挂杖的判断都和载页一样。 */
    private static void writeKeepsLook(GameTestHelper h) {
        for (PageTier tier : PageTier.values()) {
            var pair = Reifu.REIFU.get(tier);
            ItemStack blank = new ItemStack(pair.blank().get());
            h.assertTrue(SpellPages.isBlank(blank) && SpellPages.tier(blank) == tier, "blank " + tier.id + " reifu should be a blank page of its grade");
            ItemStack written = SpellPages.write(blank, SPELL, null);
            h.assertTrue(written.is(pair.inscribed().get()), "writing a " + tier.id + " reifu should keep it a reifu, got " + written);
            h.assertTrue(SpellPages.isInscribed(written) && SpellPages.tier(written) == tier, "written reifu should be an inscribed page of its grade");
            h.assertTrue(SpellPages.isHoldable(written), "a written reifu should go on wands and into codices");
            ItemStack again = SpellPages.write(written, "(spell :name \"再\" :mana 5 (heal caster 1))", null);
            h.assertTrue(again.is(pair.inscribed().get()), "rewriting a " + tier.id + " reifu should keep it");
        }
        h.succeed();
    }

    /** 右键放上空白霊符、打开工坊、写入、潜行取回，与编纂台相同；书案上显示的页跟着变。 */
    private static void deskInscribes(GameTestHelper h) {
        h.setBlock(DESK, Reifu.FUDA_DESK.get().defaultBlockState());
        BlockPos at = h.absolutePos(DESK);
        h.assertTrue(h.getLevel().getBlockEntity(at) instanceof InscriptionTableBlockEntity, "the desk should carry the inscription table's block entity");
        ServerPlayer p = player(h);
        try {
            var hit = new BlockHitResult(at.getCenter(), Direction.UP, at, false);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Reifu.REIFU.get(PageTier.CRYSTAL).blank().get(), 2));
            h.getBlockState(DESK).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(p.getMainHandItem().getCount() == 1, "laying a reifu down should take one");
            h.assertTrue(h.getBlockState(DESK).getValue(InscriptionTableBlock.PAGE) == InscriptionTableBlock.Page.BLANK
                && h.getBlockState(DESK).getValue(InscriptionTableBlock.PAGE_TIER) == PageTier.CRYSTAL, "the desk should show a blank crystal sheet");
            // 空手右键：服务端记下打开的编辑器，写入才被允许
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            h.getBlockState(DESK).useWithoutItem(h.getLevel(), p, hit);
            h.assertTrue(InscriptionTableBlock.inscribe(p, at, SPELL, new CompoundTag()) == PageTier.CRYSTAL, "inscribing at the desk should succeed");
            var table = (InscriptionTableBlockEntity) h.getLevel().getBlockEntity(at);
            h.assertTrue(table.page().is(Reifu.REIFU.get(PageTier.CRYSTAL).inscribed().get()), "the desk should hold a written crystal reifu, got " + table.page());
            h.assertTrue(h.getBlockState(DESK).getValue(InscriptionTableBlock.PAGE) == InscriptionTableBlock.Page.INSCRIBED, "the desk should show the written sheet");
            p.setShiftKeyDown(true);
            h.getBlockState(DESK).useWithoutItem(h.getLevel(), p, hit);
            h.assertTrue(table.page().isEmpty() && h.getBlockState(DESK).getValue(InscriptionTableBlock.PAGE) == InscriptionTableBlock.Page.NONE, "sneak-using should take the page back");
            h.assertTrue(p.getInventory().contains(s -> s.is(Reifu.REIFU.get(PageTier.CRYSTAL).inscribed().get())), "the written reifu should be in the inventory");
        } finally {
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeed();
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "reifu-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(h.absoluteVec(new Vec3(2.5, 1, 0.8)));
        return p;
    }

    private ReifuGameTests() {}
}
