package dev.logos.dimension.npc;

import java.util.Map;
import java.util.Set;

/** 有可换零件外观的 NPC；NpcRenderer 据此隐藏没选中的零件组。 */
public interface NpcPartsHolder {
    NpcParts.Race npcRace();

    /** 当前外观（槽位 → 零件 ID），已经过 {@link NpcParts.Race#sanitize}。 */
    Map<String, String> npcAppearance();

    /** 外观之外、这一刻也要隐藏的骨骼组（例如收起来的武器），没有为空集。 */
    default Set<String> npcHiddenExtras() {
        return Set.of();
    }
}
