package dev.logos.dimension;

import com.mojang.serialization.Codec;
import dev.logos.dimension.world.AlchemicalLayout;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Each cauldron world keeps its own permanent Boss completion flag across server restarts. */
public final class IslandProgress extends SavedData {
    public static final Codec<IslandProgress> CODEC = Codec.STRING.listOf().fieldOf("defeated_islands").codec()
            .xmap(list -> new IslandProgress(new HashSet<>(list)), data -> data.defeated.stream().sorted().toList());
    private static final SavedDataType<IslandProgress> TYPE = new SavedDataType<>(LogosDimension.id("island_progress"), IslandProgress::new, CODEC);
    private final Set<String> defeated;
    public IslandProgress() { this(new HashSet<>()); }
    private IslandProgress(Set<String> defeated) { this.defeated = defeated; }
    public static IslandProgress of(ServerLevel level) { return level.getDataStorage().computeIfAbsent(TYPE); }
    private static String key(AlchemicalLayout.Center center) { return center.gridX() + "," + center.gridZ(); }
    public boolean defeated(AlchemicalLayout.Center center) { return defeated.contains(key(center)); }
    public void setDefeated(AlchemicalLayout.Center center, boolean value) {
        boolean changed = value ? defeated.add(key(center)) : defeated.remove(key(center));
        if (changed) setDirty();
    }
}
