package dev.logos.dimension.firearm;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.longsword.LongswordCombat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 铳与弹药的公共流程（docs/FIREARMS.md）：装弹状态、背包里的弹药、换弹结算、扣扳机。
 * <ul>
 *   <li>弹药不分口径：任何铳（{@link Firearm}）都能装任何弹药物品（{@link Ammo.Item}），除非铳自己不收。</li>
 *   <li>一个转轮（弹匣）只装一种弹药；换种类、或者没装满时按 R，都是整个换下来：没打完的退回背包，再从背包装满。</li>
 *   <li>选中的弹药用完时，换弹自动改用背包里的另一种（同时变成选中的）。</li>
 *   <li>打空最后一发、或者空膛扣扳机，自动换弹（背包里有弹药时）。</li>
 *   <li>创造模式不消耗、也不退回弹药。</li>
 * </ul>
 * 什么时候扣扳机、什么时候装到位由招式文件的 {@code fire}、{@code feed} 时刻决定（服务端），这里只结算。
 */
public final class Firearms {
    private Firearms() {}

    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);
    /** 铳的装弹状态（存档并同步：别人也看得见转轮里还剩几发）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GunLoad>> LOAD = COMPONENTS.registerComponentType(
            "gun_load", b -> b.persistent(GunLoad.CODEC).networkSynchronized(GunLoad.STREAM_CODEC));

    /** 转移的前提（招式文件的 {@code require}）：膛里有子弹、膛空了、可以换弹。 */
    public static final String LOADED = "loaded", EMPTY = "empty", RELOADABLE = "reloadable";

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1")
                .playToClient(FirearmPayloads.Shot.TYPE, FirearmPayloads.Shot.CODEC)
                .playToServer(FirearmPayloads.Reload.TYPE, FirearmPayloads.Reload.CODEC, (p, ctx) -> {
                    if (ctx.player() instanceof ServerPlayer player) requestReload(player);
                })
                .playToServer(FirearmPayloads.Select.TYPE, FirearmPayloads.Select.CODEC, (p, ctx) -> {
                    if (ctx.player() instanceof ServerPlayer player) select(player, p.ammo());
                }));
    }

    // ---------------------------------------------------------------- 状态

    public static boolean isAmmo(ItemStack stack) { return !stack.isEmpty() && stack.getItem() instanceof Ammo.Item; }
    public static Firearm firearm(ItemStack stack) { return stack.getItem() instanceof Firearm f ? f : null; }
    public static GunLoad load(ItemStack gun) { return gun.getOrDefault(LOAD.get(), GunLoad.EMPTY); }
    private static void store(ItemStack gun, GunLoad load) { gun.set(LOAD.get(), load); }

    /** 背包里的一种弹药（物品样子，数量 1）与总数。 */
    public record Kind(ItemStack ammo, int count) {}

    /** 背包里的各种弹药，按物品和组件分开，顺序同背包格子；{@code gun} 不收的不列。 */
    public static List<Kind> kinds(Player player, ItemStack gun) {
        var firearm = firearm(gun);
        List<ItemStack> types = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!isAmmo(stack) || firearm != null && !firearm.accepts(stack)) continue;
            int at = -1;
            for (int j = 0; j < types.size(); j++) if (ItemStack.isSameItemSameComponents(types.get(j), stack)) { at = j; break; }
            if (at < 0) { types.add(stack.copyWithCount(1)); counts.add(stack.getCount()); }
            else counts.set(at, counts.get(at) + stack.getCount());
        }
        List<Kind> out = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) out.add(new Kind(types.get(i), counts.get(i)));
        return out;
    }

    /** 背包里这种弹药一共几发。 */
    public static int count(Player player, ItemStack ammo) {
        int n = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (ItemStack.isSameItemSameComponents(inventory.getItem(i), ammo)) n += inventory.getItem(i).getCount();
        return n;
    }

    /** 换弹要装哪种：选中的（背包里还有，或者创造模式）；否则背包里第一种能装的；都没有为 null。 */
    public static ItemStack choose(Player player, ItemStack gun) {
        var selected = load(gun).selected();
        if (!selected.isEmpty() && (player.hasInfiniteMaterials() || count(player, selected) > 0)) return selected;
        var kinds = kinds(player, gun);
        return kinds.isEmpty() ? null : kinds.getFirst().ammo();
    }

    /** 现在按 R 有没有意义：有弹药可装，并且没装满或者要换种类。 */
    public static boolean canReload(Player player, ItemStack gun) {
        var firearm = firearm(gun);
        if (firearm == null) return false;
        var chosen = choose(player, gun);
        if (chosen == null) return false;
        var load = load(gun);
        return load.rounds() < firearm.capacity() || !ItemStack.isSameItemSameComponents(load.loaded(), chosen);
    }

    /** 招式文件里转移的前提。 */
    public static boolean allows(Player player, ItemStack gun, String requirement) {
        return switch (requirement) {
            case LOADED -> load(gun).rounds() > 0;
            case EMPTY -> load(gun).rounds() <= 0;
            case RELOADABLE -> canReload(player, gun);
            default -> false;
        };
    }

    // ---------------------------------------------------------------- 输入

    /** 短按 R：手上是铳、而且换弹有意义时，交给招式状态机（换弹动作播到 {@code feed} 再结算）。 */
    public static void requestReload(ServerPlayer player) {
        if (firearm(player.getMainHandItem()) != null && canReload(player, player.getMainHandItem())) LongswordCombat.reload(player);
    }

    /** 轮盘里选了一种弹药：记为选中，再换弹（装的已经是它并且满了就不换）。 */
    public static void select(ServerPlayer player, ItemStack ammo) {
        var gun = player.getMainHandItem();
        var firearm = firearm(gun);
        if (firearm == null || !isAmmo(ammo) || !firearm.accepts(ammo)) return;
        if (!player.hasInfiniteMaterials() && count(player, ammo) <= 0) return;
        store(gun, load(gun).withSelected(ammo));
        requestReload(player);
    }

    // ---------------------------------------------------------------- 结算

    /**
     * 换弹装到位（招式的 {@code feed} 时刻）：没打完的退回背包（背包满了掉在脚下），再装满要装的那种。
     * 选中的用完了就改用背包里的另一种，并记为选中。
     */
    public static void feed(Player player, ItemStack gun) {
        var firearm = firearm(gun);
        var chosen = firearm == null ? null : choose(player, gun);
        if (chosen == null) return;
        var load = load(gun);
        boolean creative = player.hasInfiniteMaterials();
        if (!creative && load.rounds() > 0) give(player, load.loaded().copyWithCount(load.rounds()));
        int n = creative ? firearm.capacity() : take(player, chosen, firearm.capacity());
        store(gun, new GunLoad(chosen, chosen, n));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.PLAYERS, .55f, 1.7f);
    }

    /**
     * 扣一次扳机（招式的 {@code fire} 时刻）：膛里有子弹就扣掉一发交给弹药打出去，返回 true；
     * 空膛只“咔哒”一声，返回 false。打空最后一发或空膛时自动换弹（玩家、背包里有弹药时）。
     * {@code power} 是这一招的倍数（招式文件里的 {@code damage}），和铳的威力倍数相乘后交给弹药；
     * {@code spread} 乘在铳的散布上（扇击越扇越散）。
     */
    public static boolean fire(LivingEntity shooter, ItemStack gun, boolean aimed, double power, double spread) {
        var firearm = firearm(gun);
        if (firearm == null || !(shooter.level() instanceof ServerLevel level)) return false;
        var load = load(gun);
        if (load.rounds() <= 0 || !(load.loaded().getItem() instanceof Ammo.Item ammo)) {
            level.playSound(null, shooter.getX(), shooter.getEyeY(), shooter.getZ(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, .7f, 1.6f);
            autoReload(shooter);
            return false;
        }
        var round = load.loaded();
        store(gun, load.withRounds(load.rounds() - 1));
        var shot = new Ammo.Shot(level, shooter, gun, round, shooter.getEyePosition(), shooter.getLookAngle(),
                firearm.spread(aimed) * spread, firearm.damage() * power, aimed);
        var result = ammo.ammo().fire(shot);
        broadcast(shooter, new FirearmPayloads.Shot(shooter.getId(), BuiltInRegistries.ITEM.getKey(round.getItem()), shot.origin(), result.traces()));
        if (!(shooter instanceof Player p && p.hasInfiniteMaterials())) gun.hurtAndBreak(1, shooter, EquipmentSlot.MAINHAND);
        if (load.rounds() - 1 <= 0) autoReload(shooter);
        return true;
    }

    private static void autoReload(LivingEntity shooter) {
        if (shooter instanceof ServerPlayer player) requestReload(player);
    }

    /** 从背包里扣掉至多 {@code max} 发这种弹药，返回扣了几发。 */
    private static int take(Player player, ItemStack ammo, int max) {
        int taken = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && taken < max; i++) {
            var stack = inventory.getItem(i);
            if (!ItemStack.isSameItemSameComponents(stack, ammo)) continue;
            int n = Math.min(max - taken, stack.getCount());
            stack.shrink(n);
            taken += n;
        }
        if (taken > 0) inventory.setChanged();
        return taken;
    }

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false);
    }

    /** 发给射手自己和看得见他的玩家（只发给装了本附属的客户端）。 */
    private static void broadcast(LivingEntity shooter, FirearmPayloads.Shot payload) {
        for (var viewer : ((ServerLevel) shooter.level()).players()) {
            boolean sees = viewer == shooter || viewer.getChunkTrackingView().contains(shooter.chunkPosition());
            if (sees && NetworkRegistry.hasChannel(viewer.connection, FirearmPayloads.Shot.TYPE.id())) PacketDistributor.sendToPlayer(viewer, payload);
        }
    }
}
