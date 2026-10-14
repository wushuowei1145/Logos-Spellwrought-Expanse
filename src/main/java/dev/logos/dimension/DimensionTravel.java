package dev.logos.dimension;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.world.AlchemicalChunkGenerator;
import dev.logos.dimension.world.AlchemicalLayout;
import dev.logos.dimension.world.AlchemicalTerrain;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/** Round trips save the departure dimension/position and never build a platform on the reserved sky island. */
public final class DimensionTravel {
    public record ReturnPoint(String dimension, double x, double y, double z, float yaw, float pitch) {
        public static final Codec<ReturnPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dimension").forGetter(ReturnPoint::dimension),
                Codec.DOUBLE.fieldOf("x").forGetter(ReturnPoint::x), Codec.DOUBLE.fieldOf("y").forGetter(ReturnPoint::y),
                Codec.DOUBLE.fieldOf("z").forGetter(ReturnPoint::z), Codec.FLOAT.fieldOf("yaw").forGetter(ReturnPoint::yaw),
                Codec.FLOAT.fieldOf("pitch").forGetter(ReturnPoint::pitch)).apply(i, ReturnPoint::new));
    }
    public static final class Returns extends SavedData {
        static final Codec<Returns> CODEC = Codec.unboundedMap(Codec.STRING, ReturnPoint.CODEC).fieldOf("homes").codec().xmap(Returns::new, r -> r.homes);
        static final SavedDataType<Returns> TYPE = new SavedDataType<>(LogosDimension.id("returns"), Returns::new, CODEC);
        private final Map<String, ReturnPoint> homes;
        public Returns() { this(Map.of()); }
        private Returns(Map<String, ReturnPoint> homes) { this.homes = new HashMap<>(homes); }
        public void put(String id, ReturnPoint point) { homes.put(id, point); setDirty(); }
        public ReturnPoint get(String id) { return homes.get(id); }
        public void remove(String id) { homes.remove(id); setDirty(); }
    }
    private DimensionTravel() {}

    public static boolean enter(ServerPlayer player) {
        return enter(player, player.position());
    }

    /** A physical entrance may save a safe position beside its basin instead of inside its portal. */
    public static boolean enter(ServerPlayer player, Vec3 returnPosition) {
        if (player.level().dimension().equals(LogosDimension.CRUCIBLE)) return leave(player);
        var server = player.level().getServer();
        var target = server.getLevel(LogosDimension.CRUCIBLE);
        if (target == null || !(target.getChunkSource().getGenerator() instanceof AlchemicalChunkGenerator generator)) {
            player.sendSystemMessage(Component.translatable("message.logos_dimension.missing_dimension")); return false;
        }
        var home = new ReturnPoint(player.level().dimension().identifier().toString(), returnPosition.x, returnPosition.y, returnPosition.z, player.getYRot(), player.getXRot());
        var center = generator.terrain().layout().center(0, 0);
        var landing = findLanding(target, center, AlchemicalLayout.Region.AETHER);
        if (landing == null) return false;
        if (!teleport(player, target, landing, player.getYRot(), 0)) return false;
        server.overworld().getDataStorage().computeIfAbsent(Returns.TYPE).put(player.getUUID().toString(), home);
        return true;
    }

    public static boolean leave(ServerPlayer player) {
        var server = player.level().getServer();
        var returns = server.overworld().getDataStorage().computeIfAbsent(Returns.TYPE);
        var home = returns.get(player.getUUID().toString());
        if (home != null) {
            Identifier id = Identifier.tryParse(home.dimension);
            var target = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (target != null && teleport(player, target, new Vec3(home.x, home.y, home.z), home.yaw, home.pitch)) {
                returns.remove(player.getUUID().toString()); return true;
            }
        }
        boolean moved = player.teleport(TeleportTransition.createDefault(player, TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET))) != null;
        if (moved) returns.remove(player.getUUID().toString());
        return moved;
    }

    public static Vec3 findLanding(ServerLevel target, AlchemicalLayout.Center center, AlchemicalLayout.Region region) {
        var generator = (AlchemicalChunkGenerator) target.getChunkSource().getGenerator();
        var terrain = generator.terrain();
        double radius = terrain.layout().spacing() * switch (region) {
            case SULFUR -> .09; case MERCURY -> .23; case SALT -> .375; default -> .165;
        };
        for (int angle = 0; angle < 32; angle++) for (int offset = 0; offset < 9; offset++) {
            double a = angle * Math.PI / 16;
            int x = center.x() + (int) ((radius + offset * 24) * Math.cos(a));
            int z = center.z() + (int) ((radius + offset * 24) * Math.sin(a));
            var c = terrain.column(x, z);
            if (c.geography().region() != region || c.liquid() != AlchemicalTerrain.Liquid.NONE) continue;
            target.getChunk(x >> 4, z >> 4);
            BlockPos feet = new BlockPos(x, c.ground() + 1, z);
            if (target.isEmptyBlock(feet) && target.isEmptyBlock(feet.above())) return feet.getBottomCenter();
        }
        return null;
    }
    public static boolean teleport(ServerPlayer player, ServerLevel target, Vec3 pos, float yaw, float pitch) {
        target.getChunk(BlockPos.containing(pos));
        return player.teleport(new TeleportTransition(target, pos, Vec3.ZERO, yaw, pitch,
                TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET))) != null;
    }
}
