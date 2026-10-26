package dev.logos.dimension.npc;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * NPC 施法接入 Logos（docs/NPC_MAGIC.md 第 3 节）。附属模组对 Logos 只有运行期依赖，
 * 所以同 {@link dev.logos.dimension.item.LogosNatureBridge} 一样用反射，句柄与编译好的术式都缓存。
 *
 * <p>任何一步失败（Logos 版本缺接口、源码编译不过）只记一次日志并让这项法术不可用，不影响 NPC 其它行为。
 */
public final class LogosMagicBridge {
    /** Logos 示例「抚生之触」：沿视线治疗第一个非玩家生物 10 点。当前 Logos 没有这个示例时用这份同样的源码。 */
    public static final String NPC_HEAL = "npc-heal";
    /** Logos 示例「生息之环」：治疗施法者周围 16 格内的非玩家生物各 6 点，不分敌我。 */
    public static final String NPC_GROUP_HEAL = "npc-group-heal";

    /** 与 Logos {@code dev.logos.lang.Examples} 里的同名示例逐字一致；上游同步时核对（docs/UPSTREAM_SYNC.md）。 */
    private static final Map<String, String> FALLBACK = Map.of(
            NPC_HEAL, """
                    ; 抚生之触：沿视线治疗第一个非玩家生物，恢复 10 点生命（5 颗心）；也能治疗使魔与宠物
                    (spell :name "抚生之触" :mana 20
                      (ray :range 24
                        (on-hit (heal target 10))))
                    """,
            NPC_GROUP_HEAL, """
                    ; 生息之环：治疗施法者周围 16 格内的非玩家生物，每个恢复 6 点生命；不区分敌我
                    ; 100 魔力包含选择器的 2 点和最多 10 个目标的治疗费用（每个 9 点）
                    (spell :name "生息之环" :mana 100
                      (heal (entities :at (pos-of caster) :radius 16 :filter (kind living)) 6))
                    """);

    private static volatile boolean resolved;
    private static Method compile, castAs, masterOf;
    private static Map<?, ?> examples;
    private static final Map<String, Object> SPELLS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> BROKEN = new ConcurrentHashMap<>();

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> casting = Class.forName("dev.logos.runtime.Casting");
            Class<?> spell = Class.forName("dev.logos.lang.Spell");
            compile = casting.getMethod("compile", String.class);
            castAs = casting.getMethod("castAs", LivingEntity.class, spell, double.class, double.class, Entity.class, Vec3.class);
        } catch (ReflectiveOperationException error) {
            LogUtils.getLogger().warn("NPC 施法：当前 Logos 没有 Casting.compile / castAs，NPC 不会施法", error);
        }
        try {
            examples = (Map<?, ?>) Class.forName("dev.logos.lang.Examples").getField("ALL").get(null);
        } catch (ReflectiveOperationException error) {
            examples = Map.of();
        }
        try {
            masterOf = Class.forName("dev.logos.runtime.Familiars").getMethod("masterOf", Entity.class);
        } catch (ReflectiveOperationException error) {
            LogUtils.getLogger().info("NPC 施法：当前 Logos 没有 Familiars.masterOf，NPC 不会识别使魔身份");
        }
    }

    /** 示例术式的源码：优先取当前 Logos 的示例，没有就用附属模组存的同一份。 */
    public static String exampleSource(String id) {
        resolve();
        Object src = examples.get(id);
        return src instanceof String s ? s : FALLBACK.get(id);
    }

    /**
     * 编译好的术式（按 key 缓存）。forPlayers 为真时在源码上加 {@code :players true}，
     * Logos 需要这个开关才会选择、影响玩家（作为使魔时治疗玩家用，docs/NPC_MAGIC.md 第 6 节）。
     */
    private static Object spell(String id, boolean forPlayers) {
        String key = id + (forPlayers ? "+players" : "");
        Object cached = SPELLS.get(key);
        if (cached != null || BROKEN.containsKey(key)) return cached;
        resolve();
        String src = exampleSource(id);
        if (compile == null || src == null) {
            BROKEN.put(key, true);
            return null;
        }
        if (forPlayers) src = src.replaceFirst("\\(spell ", "(spell :players true ");
        try {
            Object compiled = compile.invoke(null, src);
            SPELLS.put(key, compiled);
            return compiled;
        } catch (ReflectiveOperationException | RuntimeException error) {
            BROKEN.put(key, true);
            LogUtils.getLogger().warn("NPC 施法：示例 {} 编译失败，这项法术停用", key, error);
            return null;
        }
    }

    /** 这项法术能不能用（Logos 接口在、源码编译通过）。 */
    public static boolean available(String id, boolean forPlayers) {
        return spell(id, forPlayers) != null;
    }

    /**
     * 以 caster 的身份施法，注入 mana 点魔力（由调用方先从 NPC 的魔力池扣除）。
     * 沿视线生效的术式需要调用方先让施法者转向目标。
     */
    public static boolean cast(LivingEntity caster, String id, boolean forPlayers, double mana, Entity target) {
        Object compiled = spell(id, forPlayers);
        if (compiled == null || castAs == null) return false;
        try {
            castAs.invoke(null, caster, compiled, mana, 0d, target, null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            BROKEN.put(id + (forPlayers ? "+players" : ""), true);
            SPELLS.remove(id + (forPlayers ? "+players" : ""));
            LogUtils.getLogger().warn("NPC 施法：施放 {} 失败，这项法术停用", id, error);
            return false;
        }
    }

    /** 这个生物是不是某个玩家的 Logos 使魔；是的话返回主人。 */
    public static UUID masterOf(Entity entity) {
        resolve();
        if (masterOf == null) return null;
        try {
            return (UUID) masterOf.invoke(null, entity);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private LogosMagicBridge() {}
}
