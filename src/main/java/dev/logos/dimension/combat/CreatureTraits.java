package dev.logos.dimension.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 生物的抗性表，读自数据包 {@code data/<命名空间>/creature_traits/<名>.json}（格式见 docs/COMBAT_SKELETON.md「生物抗性表」）。
 * 不写死在某种生物身上：Boss 和别的 NPC 都照着填一份。
 *
 * <ul>
 * <li>{@code attributes}：属性 ID → 基础值，例如 Logos 的各元素法术抗性、易伤。Logos 的法术和生物伤害在命中前按这些算。
 * <li>{@code damage}：按条件再乘的倍数，补 Logos 属性分不开的情况（狐火和普通奥秘、原版的火和魔法）。
 * 条件都满足才乘：{@code element}（正在结算的 Logos 元素）、{@code spell}（是不是 Logos 的伤害）、{@code tag}、{@code type}、
 * {@code unless_type}（伤害类型标签或 ID）。
 * <li>{@code blight_ward}：可选，周围腐败核心给的减伤（腐化女王用，像末影龙的水晶）。
 * </ul>
 * 按资源管理器缓存，{@code /reload} 后重新读。
 */
public final class CreatureTraits {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CreatureTraits NONE = new CreatureTraits(Map.of(), List.of(), null);

    /** 一条按条件乘的倍数。 */
    public record Rule(@Nullable Element element, @Nullable Boolean spell, @Nullable TagKey<DamageType> tag,
                       @Nullable ResourceKey<DamageType> type, @Nullable ResourceKey<DamageType> unlessType, double multiply) {
        boolean matches(DamageSource source, @Nullable Element dealing) {
            if (element != null && dealing != element) return false;
            if (spell != null && spell != (dealing != null)) return false;
            if (tag != null && !source.is(tag)) return false;
            if (type != null && !source.is(type)) return false;
            return unlessType == null || !source.is(unlessType);
        }
    }

    /** 周围每个还在的腐败核心减 {@code perCore}，最多 {@code max}；{@code radius} 是水平距离。 */
    public record Ward(double radius, double perCore, double max) {
        public double reduction(int cores) { return Math.min(max, perCore * Math.max(0, cores)); }
    }

    private final Map<Identifier, Double> attributes;
    private final List<Rule> rules;
    private final @Nullable Ward ward;

    private CreatureTraits(Map<Identifier, Double> attributes, List<Rule> rules, @Nullable Ward ward) {
        this.attributes = attributes;
        this.rules = rules;
        this.ward = ward;
    }

    public Map<Identifier, Double> attributes() { return attributes; }
    public List<Rule> rules() { return rules; }
    public @Nullable Ward ward() { return ward; }

    /** 把表里的属性基础值写到 {@code entity} 上（表里没写的属性不动）。 */
    public void apply(LivingEntity entity) {
        attributes.forEach((id, value) -> BuiltInRegistries.ATTRIBUTE.get(id).ifPresentOrElse(holder -> {
            var instance = entity.getAttribute(holder);
            if (instance != null) instance.setBaseValue(value);
        }, () -> LOGGER.warn("生物抗性表里的属性 {} 不存在", id)));
    }

    /** 这一下伤害按表再乘多少；正在结算的 Logos 元素由 {@link Effects#dealing} 给出。 */
    public float adjust(DamageSource source, float amount) {
        Element dealing = Effects.dealing();
        double scale = 1;
        for (Rule rule : rules) if (rule.matches(source, dealing)) scale *= rule.multiply;
        return (float) (amount * scale);
    }

    // ---- 读表 ----

    private static ResourceManager cachedFor;
    private static final Map<Identifier, Optional<CreatureTraits>> CACHE = new HashMap<>();

    /** {@code id} 的抗性表；文件缺失或写错时返回 {@link #NONE}（并记一条错误），不让生物因此无法结算伤害。 */
    public static synchronized CreatureTraits of(MinecraftServer server, Identifier id) {
        var manager = server.getResourceManager();
        if (manager != cachedFor) { CACHE.clear(); cachedFor = manager; }
        return CACHE.computeIfAbsent(id, key -> load(manager, key)).orElse(NONE);
    }

    private static Optional<CreatureTraits> load(ResourceManager manager, Identifier id) {
        var path = id.withPath(p -> "creature_traits/" + p + ".json");
        try (var reader = manager.getResourceOrThrow(path).openAsReader()) {
            return Optional.of(parse(JsonParser.parseReader(reader).getAsJsonObject()));
        } catch (Exception e) {
            LOGGER.error("生物抗性表 {} 读取失败", id, e);
            return Optional.empty();
        }
    }

    public static CreatureTraits parse(JsonObject json) {
        Map<Identifier, Double> attributes = new LinkedHashMap<>();
        if (json.has("attributes")) for (var entry : json.getAsJsonObject("attributes").entrySet()) {
            attributes.put(Identifier.parse(entry.getKey()), entry.getValue().getAsDouble());
        }
        List<Rule> rules = new ArrayList<>();
        if (json.has("damage")) for (JsonElement element : json.getAsJsonArray("damage")) {
            JsonObject r = element.getAsJsonObject();
            rules.add(new Rule(
                    r.has("element") ? Element.valueOf(r.get("element").getAsString().toUpperCase(Locale.ROOT)) : null,
                    r.has("spell") ? r.get("spell").getAsBoolean() : null,
                    r.has("tag") ? TagKey.create(Registries.DAMAGE_TYPE, Identifier.parse(r.get("tag").getAsString())) : null,
                    r.has("type") ? ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(r.get("type").getAsString())) : null,
                    r.has("unless_type") ? ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(r.get("unless_type").getAsString())) : null,
                    r.get("multiply").getAsDouble()));
        }
        Ward ward = null;
        if (json.has("blight_ward")) {
            JsonObject w = json.getAsJsonObject("blight_ward");
            ward = new Ward(w.get("radius").getAsDouble(), w.get("per_core").getAsDouble(), w.get("max").getAsDouble());
        }
        return new CreatureTraits(Map.copyOf(attributes), List.copyOf(rules), ward);
    }
}
