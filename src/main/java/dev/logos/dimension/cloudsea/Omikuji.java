package dev.logos.dimension.cloudsea;

import com.mojang.serialization.Codec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;
import java.util.function.Supplier;

/**
 * 抽签：每位玩家每个游戏日能在签箱抽一支。吉签带一段福运，凶签带一段霉运；
 * 把签纸系到结签架上会解掉霉运（{@link OmikujiRackBlock}）。
 */
public final class Omikuji {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);
    /** 上次抽签是第几个游戏日；死亡不清除，免得靠重生多抽。 */
    private static final Supplier<AttachmentType<Long>> LAST_DAY = ATTACHMENTS.register("omikuji_day",
        () -> AttachmentType.builder(() -> -1L).serialize(Codec.LONG.fieldOf("day"), day -> day >= 0).copyOnDeath().build());
    private static final long DAY_TICKS = 24000;

    /** 一种签运：抽中权重；amplifier ≥ 0 时带效果，秒数为效果时长。 */
    public enum Fortune {
        DAIKICHI("daikichi", 8, true, 1, 600),
        CHUKICHI("chukichi", 16, true, 0, 480),
        KICHI("kichi", 20, true, 0, 360),
        SHOKICHI("shokichi", 20, true, 0, 240),
        SUEKICHI("suekichi", 16, true, 0, 120),
        KYO("kyo", 15, false, 0, 300),
        DAIKYO("daikyo", 5, false, 1, 600);

        public final String id;
        public final int weight;
        public final boolean good;
        public final int amplifier;
        public final int seconds;

        Fortune(String id, int weight, boolean good, int amplifier, int seconds) {
            this.id = id;
            this.weight = weight;
            this.good = good;
            this.amplifier = amplifier;
            this.seconds = seconds;
        }

        public String key() {
            return "fortune.logos_dimension." + id;
        }
    }

    private Omikuji() {
    }

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }

    /** 按权重挑一支；roll 落在 [0, 总权重) 内。 */
    public static Fortune pick(int roll) {
        for (Fortune f : Fortune.values()) {
            roll -= f.weight;
            if (roll < 0) return f;
        }
        return Fortune.KICHI;
    }

    public static int totalWeight() {
        int total = 0;
        for (Fortune f : Fortune.values()) total += f.weight;
        return total;
    }

    /** 今天已经抽过了吗。 */
    public static boolean drawnToday(ServerPlayer player) {
        return player.getData(LAST_DAY) == player.level().getGameTime() / DAY_TICKS;
    }

    /** 抽一支：记下今天，上效果，返回签纸。 */
    public static ItemStack draw(ServerPlayer player, RandomSource random) {
        player.setData(LAST_DAY, player.level().getGameTime() / DAY_TICKS);
        Fortune fortune = pick(random.nextInt(totalWeight()));
        player.addEffect(new MobEffectInstance(fortune.good ? MobEffects.LUCK : MobEffects.UNLUCK, fortune.seconds * 20, fortune.amplifier));
        ItemStack paper = new ItemStack(FoxWildsBlocks.OMIKUJI.get());
        paper.set(DataComponents.ITEM_NAME, Component.translatable(fortune.key()));
        paper.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable(fortune.key() + ".verse"))));
        return paper;
    }
}
