package dev.logos.dimension.npc;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.RandomSource;

/**
 * 种族的可换零件（docs/NPC_APPEARANCE.md 第 3 节）：每个槽位若干零件，每个零件是模型里的一个或几个骨骼组。
 *
 * <p>零件表由模型的合并脚本生成（{@code data/logos_dimension/npc_races/<种族>.json}），加零件只需重新合并、放进资源，不用改代码。
 * 外观记录是「槽位 → 零件 ID」的映射，存的是 ID 而不是序号，增删零件不会让已有 NPC 变脸；
 * 渲染时把没选中的零件组整组隐藏。可选槽位还可以选 {@link #NONE}，整槽都不显示（素体用）。
 * 互斥的零件（例如兜帽与枝冠会穿插）不会同时出现。
 */
public final class NpcParts {
    /** 可选槽位里「什么都不戴」的零件 ID。 */
    public static final String NONE = "none";

    public record Part(String id, List<String> groups) {}

    /** optional：是否允许选 {@link #NONE}；parts 的第一个是默认零件。 */
    public record Slot(String name, boolean optional, List<Part> parts) {
        public Part find(String id) {
            for (Part p : parts) if (p.id().equals(id)) return p;
            return null;
        }
    }

    /** conflicts：每组里的零件两两不能同时出现。 */
    public record Race(String asset, List<Slot> slots, List<Set<String>> conflicts) {
        public Slot slot(String name) {
            for (Slot s : slots) if (s.name().equals(name)) return s;
            return null;
        }

        /** 两个零件是否互斥。 */
        public boolean conflicts(String a, String b) {
            for (Set<String> group : conflicts) if (!a.equals(b) && group.contains(a) && group.contains(b)) return true;
            return false;
        }

        private boolean clashesWith(String id, Map<String, String> chosen) {
            for (String other : chosen.values()) if (conflicts(id, other)) return true;
            return false;
        }

        /** 每个槽位都取默认零件。 */
        public Map<String, String> defaults() {
            return sanitize(Map.of());
        }

        /**
         * 每个槽位均匀随机抽一个零件（权重以后再加），按槽位顺序抽，跳过和已抽中零件互斥的；
         * 可选槽位只有在没有可用零件时才是 {@link #NONE}：普通居民都穿戴整齐。
         */
        public Map<String, String> random(RandomSource random) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Slot s : slots) {
                List<Part> ok = new ArrayList<>();
                for (Part p : s.parts()) if (!clashesWith(p.id(), out)) ok.add(p);
                out.put(s.name(), ok.isEmpty() ? fallback(s, out) : ok.get(random.nextInt(ok.size())).id());
            }
            return out;
        }

        private String fallback(Slot s, Map<String, String> chosen) {
            if (s.optional()) return NONE;
            for (Part p : s.parts()) if (!clashesWith(p.id(), chosen)) return p.id();
            return s.parts().getFirst().id();
        }

        /**
         * 补齐缺的槽位、换掉未知的 ID（零件被删除或改名时回退到默认）、丢掉不认识的槽位；
         * 和前面槽位互斥的零件换成本槽位第一个不冲突的零件（可选槽位换成 {@link #NONE}）。
         */
        public Map<String, String> sanitize(Map<String, String> in) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Slot s : slots) {
                String id = in.get(s.name());
                boolean known = id != null && (s.find(id) != null || s.optional() && NONE.equals(id));
                if (!known) id = s.parts().getFirst().id();
                if (!NONE.equals(id) && clashesWith(id, out)) id = fallback(s, out);
                out.put(s.name(), id);
            }
            return out;
        }

        /** 需要隐藏的骨骼组：每个槽位里没选中的零件的所有组。 */
        public Set<String> hiddenGroups(Map<String, String> appearance) {
            Set<String> out = new HashSet<>();
            for (Slot s : slots) {
                String id = appearance.get(s.name());
                for (Part p : s.parts()) if (!p.id().equals(id)) out.addAll(p.groups());
            }
            return out;
        }

        /** 读零件表 JSON（合并脚本生成的 parts.json 格式）；默认零件排到每个槽位第一个。 */
        public static Race parse(String asset, JsonObject root) {
            List<Slot> slots = new ArrayList<>();
            for (var e : root.getAsJsonObject("slots").entrySet()) {
                JsonObject so = e.getValue().getAsJsonObject();
                List<Part> parts = new ArrayList<>();
                for (JsonElement pe : so.getAsJsonArray("parts")) {
                    JsonObject po = pe.getAsJsonObject();
                    List<String> groups = new ArrayList<>();
                    for (JsonElement g : po.getAsJsonArray("groups")) groups.add(g.getAsString());
                    Part part = new Part(po.get("id").getAsString(), List.copyOf(groups));
                    if (po.has("default") && po.get("default").getAsBoolean()) parts.addFirst(part);
                    else parts.add(part);
                }
                slots.add(new Slot(e.getKey(), so.has("optional") && so.get("optional").getAsBoolean(), List.copyOf(parts)));
            }
            List<Set<String>> conflicts = new ArrayList<>();
            if (root.has("conflicts")) {
                for (JsonElement ce : root.getAsJsonArray("conflicts")) {
                    Set<String> group = new HashSet<>();
                    for (JsonElement id : ce.getAsJsonArray()) group.add(id.getAsString());
                    conflicts.add(Set.copyOf(group));
                }
            }
            return new Race(asset, List.copyOf(slots), List.copyOf(conflicts));
        }

        /** 从模组资源读零件表；服务端（抽外观）与客户端（隐藏零件）读同一份。 */
        public static Race load(String asset) {
            String path = "/data/logos_dimension/npc_races/" + asset + ".json";
            try (InputStream in = NpcParts.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("找不到零件表 " + path);
                return parse(asset, JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (IOException error) {
                throw new IllegalStateException("读零件表失败 " + path, error);
            }
        }
    }

    /** 外观记录同步给客户端用的文本：{@code slot=id;slot=id}。 */
    public static String encode(Map<String, String> appearance) {
        StringBuilder sb = new StringBuilder();
        appearance.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    public static Map<String, String> decode(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return out;
        for (String pair : text.split(";")) {
            int i = pair.indexOf('=');
            if (i > 0) out.put(pair.substring(0, i), pair.substring(i + 1));
        }
        return out;
    }

    /** 森林精灵：零件表来自 forest-elf-model/forest_elf 的合并结果；NpcPartsTest 核对它和模型的组一一对应。 */
    public static final Race FOREST_ELF = Race.load("forest_elf");

    private NpcParts() {}
}
