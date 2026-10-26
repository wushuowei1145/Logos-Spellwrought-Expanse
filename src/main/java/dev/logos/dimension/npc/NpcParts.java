package dev.logos.dimension.npc;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.RandomSource;

/**
 * 种族的可换零件（docs/NPC_APPEARANCE.md 第 3 节）：每个槽位若干零件，每个零件是模型里的一个或几个骨骼组。
 *
 * <p>外观记录是「槽位 → 零件 ID」的映射，存的是 ID 而不是序号，增删零件不会让已有 NPC 变脸；
 * 渲染时把没选中的零件组整组隐藏。可选槽位还可以选 {@link #NONE}，整槽都不显示（素体用）。
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

    public record Race(String asset, List<Slot> slots) {
        public Slot slot(String name) {
            for (Slot s : slots) if (s.name().equals(name)) return s;
            return null;
        }

        /** 每个槽位都取默认零件。 */
        public Map<String, String> defaults() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Slot s : slots) out.put(s.name(), s.parts().getFirst().id());
            return out;
        }

        /** 每个槽位均匀随机抽一个零件（权重以后再加）。可选槽位不抽 {@link #NONE}：普通居民都穿戴整齐。 */
        public Map<String, String> random(RandomSource random) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Slot s : slots) out.put(s.name(), s.parts().get(random.nextInt(s.parts().size())).id());
            return out;
        }

        /** 补齐缺的槽位、换掉未知的 ID（零件被删除或改名时回退到默认），丢掉不认识的槽位。 */
        public Map<String, String> sanitize(Map<String, String> in) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Slot s : slots) {
                String id = in.get(s.name());
                boolean ok = id != null && (s.find(id) != null || s.optional() && NONE.equals(id));
                out.put(s.name(), ok ? id : s.parts().getFirst().id());
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

    private static Part part(String id, String... groups) {
        return new Part(id, List.of(groups));
    }

    /** 森林精灵：组名与 geckolib/models/entity/forest_elf.geo.json 一致（NpcPartsTest 会核对）。 */
    public static final Race FOREST_ELF = new Race("forest_elf", List.of(
            new Slot("hair", true, List.of(part("hair_straight_long", "hair_straight_long"), part("hair_short_bob", "hair_short_bob"))),
            new Slot("headwear", true, List.of(part("headwear_twig_crown", "headwear_twig_crown"))),
            new Slot("ears", false, List.of(part("ear_long", "ear_long_left", "ear_long_right"))),
            new Slot("eyes", false, List.of(
                    part("eyes_square", "eyes_square_left", "eyes_square_left_pupil", "eyes_square_right", "eyes_square_right_pupil"),
                    part("eyes_droopy", "eyes_droopy_left", "eyes_droopy_left_pupil", "eyes_droopy_right", "eyes_droopy_right_pupil"))),
            new Slot("mouth", false, List.of(part("mouth_smile", "mouth_smile"), part("mouth_cat", "mouth_cat"))),
            new Slot("outfit", true, List.of(part("outfit_villager", "outfit_villager_neck", "outfit_villager_body",
                    "outfit_villager_leftarm", "outfit_villager_leftforearm", "outfit_villager_rightarm", "outfit_villager_rightforearm",
                    "outfit_villager_skirt", "outfit_villager_leftshin", "outfit_villager_leftfoot", "outfit_villager_rightshin",
                    "outfit_villager_rightfoot")))));

    private NpcParts() {}
}
