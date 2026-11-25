package dev.logos.dimension.firearm;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 铳与弹药的网络包。 */
public final class FirearmPayloads {
    private FirearmPayloads() {}

    /** 客户端 → 服务端：短按 R，用选中的弹药换弹。 */
    public record Reload() implements CustomPacketPayload {
        public static final Type<Reload> TYPE = new Type<>(LogosDimension.id("firearm_reload"));
        public static final StreamCodec<ByteBuf, Reload> CODEC = StreamCodec.unit(new Reload());
        @Override public Type<Reload> type() { return TYPE; }
    }

    /** 客户端 → 服务端：在弹药轮盘里选了这种弹药（物品样子），选中并换弹。 */
    public record Select(ItemStack ammo) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(LogosDimension.id("firearm_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Select> CODEC =
                ItemStack.OPTIONAL_STREAM_CODEC.map(Select::new, Select::ammo);
        @Override public Type<Select> type() { return TYPE; }
    }

    /**
     * 服务端 → 客户端：{@code shooter} 打出一发 {@code ammo}（弹药物品编号，客户端按它找画法）。{@code origin} 是服务端算弹道的起点
     * （射手眼睛），客户端画的时候从铳口画起；{@code traces} 是各条弹道的终点。
     */
    public record Shot(int shooter, Identifier ammo, Vec3 origin, List<Ammo.Trace> traces) implements CustomPacketPayload {
        public static final Type<Shot> TYPE = new Type<>(LogosDimension.id("firearm_shot"));
        public static final StreamCodec<ByteBuf, Shot> CODEC = StreamCodec.of((b, p) -> {
            b.writeInt(p.shooter);
            Identifier.STREAM_CODEC.encode(b, p.ammo);
            vec(b, p.origin);
            b.writeShort(p.traces.size());
            for (var t : p.traces) { vec(b, t.end()); b.writeByte(t.kind()); b.writeInt(t.block()); }
        }, b -> {
            int shooter = b.readInt();
            var ammo = Identifier.STREAM_CODEC.decode(b);
            var origin = vec(b);
            int n = b.readShort();
            List<Ammo.Trace> traces = new ArrayList<>(n);
            for (int i = 0; i < n; i++) traces.add(new Ammo.Trace(vec(b), b.readByte(), b.readInt()));
            return new Shot(shooter, ammo, origin, List.copyOf(traces));
        });
        @Override public Type<Shot> type() { return TYPE; }
        private static void vec(ByteBuf b, Vec3 v) { b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z); }
        private static Vec3 vec(ByteBuf b) { return new Vec3(b.readDouble(), b.readDouble(), b.readDouble()); }
    }
}
