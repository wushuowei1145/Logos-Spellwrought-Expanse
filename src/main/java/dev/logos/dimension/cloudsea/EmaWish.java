package dev.logos.dimension.cloudsea;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** 绘马上写下的愿望与写下它的玩家；像书与笔一样存在物品上，挂出去也跟着走。 */
public record EmaWish(String text, String author) {
    /** 绘马板面最多写这么多行、这么多字（客户端按板面宽度再限制每行）。 */
    public static final int MAX_LINES = 9;
    public static final int MAX_CHARS = 320;
    public static final Codec<EmaWish> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.string(0, MAX_CHARS).fieldOf("text").forGetter(EmaWish::text),
            Codec.string(0, 64).optionalFieldOf("author", "").forGetter(EmaWish::author)).apply(i, EmaWish::new));
    public static final StreamCodec<ByteBuf, EmaWish> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_CHARS * 4), EmaWish::text, ByteBufCodecs.stringUtf8(256), EmaWish::author, EmaWish::new);

    /** 服务端收到的文字是否能写上去：行数、字数都不超，且不含换行以外的控制字符。 */
    public static boolean fits(String text) {
        if (text.length() > MAX_CHARS || text.split("\n", -1).length > MAX_LINES) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && Character.isISOControl(c)) return false;
        }
        return true;
    }
}
