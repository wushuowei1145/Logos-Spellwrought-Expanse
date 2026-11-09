package dev.logos.dimension.client.trail;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 刀光样式表：{@code assets/<ns>/combat/trail_styles/<名字>.json}，用到时读、重载时清。
 * 每种伤害类型一种（{@code logos_dimension:physical}、{@code fire}、{@code frost}、{@code lightning}、{@code void}、
 * {@code arcane}、{@code life}，以及附属的 {@code foxfire}），武器也可以有自己的样式。缺失或写错时退回物理。
 */
public final class TrailStyles {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Identifier PHYSICAL = LogosDimension.id("physical");
    private static final Map<Identifier, Optional<TrailStyle>> CACHE = new HashMap<>();

    private TrailStyles() {}

    /** 样式；读不到时为物理样式（物理也读不到时为 null，刀光不画）。 */
    public static @Nullable TrailStyle get(Identifier id) {
        TrailStyle style = load(id);
        return style != null || id.equals(PHYSICAL) ? style : load(PHYSICAL);
    }

    /** 伤害类型对应的样式名：{@code logos_dimension:<元素小写>}。 */
    public static Identifier of(Element element) {
        return LogosDimension.id(element.name().toLowerCase(Locale.ROOT));
    }

    /**
     * 实际要用的样式名：{@code fixed} 为假且持刀者缠着魔力时，换成那种元素的刀光（有这个样式文件的话），否则就是 {@code base}。
     */
    public static Identifier resolve(Identifier base, boolean fixed, @Nullable LivingEntity holder) {
        if (fixed || holder == null) return base;
        ManaWrap wrap = ManaWrap.of(holder);
        if (wrap == null) return base;
        Identifier element = of(wrap.element());
        return exists(element) ? element : base;
    }

    /** 这个样式文件存在且能读。 */
    public static boolean exists(Identifier id) { return load(id) != null; }

    private static @Nullable TrailStyle load(Identifier id) {
        return CACHE.computeIfAbsent(id, key -> {
            Identifier file = Identifier.fromNamespaceAndPath(key.getNamespace(), "combat/trail_styles/" + key.getPath() + ".json");
            var resource = Minecraft.getInstance().getResourceManager().getResource(file);
            if (resource.isEmpty()) return Optional.empty();
            try (var reader = resource.get().openAsReader()) {
                return Optional.of(TrailStyle.parse(key, JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception e) {
                LOGGER.error("刀光样式 {} 读取失败", file, e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    static void clear() { CACHE.clear(); }
}
