package dev.logos.dimension;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.world.AlchemicalLayout;
import java.util.*;
import java.util.function.Predicate;
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
        public static final Codec<Returns> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING, ReturnPoint.CODEC).fieldOf("homes").forGetter((Returns r) -> r.homes),
                Codec.unboundedMap(Codec.STRING, ReturnPoint.CODEC.listOf()).optionalFieldOf("paths", Map.of()).forGetter((Returns r) -> r.paths)
        ).apply(i, Returns::new));
        static final SavedDataType<Returns> TYPE = new SavedDataType<>(LogosDimension.id("returns"), Returns::new, CODEC);
        private final Map<String, ReturnPoint> homes;
        private final Map<String, List<ReturnPoint>> paths;
        public Returns() { this(Map.of(), Map.of()); }
        private Returns(Map<String, ReturnPoint> homes, Map<String, List<ReturnPoint>> paths) {
            this.homes = new HashMap<>(homes); this.paths = new HashMap<>();
            paths.forEach((id, points) -> this.paths.put(id, new ArrayList<>(points)));
        }
        public void put(String id, ReturnPoint point) {
            var previous = homes.put(id, point);
            if (previous != null) paths.computeIfAbsent(id, key -> new ArrayList<>()).add(previous);
            setDirty();
        }
        public ReturnPoint get(String id) { return homes.get(id); }
        public void remove(String id) {
            var path = paths.get(id);
            if (path == null || path.isEmpty()) homes.remove(id);
            else { homes.put(id, path.removeLast()); if (path.isEmpty()) paths.remove(id); }
            setDirty();
        }
        public void clear(String id) { homes.remove(id); paths.remove(id); setDirty(); }
    }
    private DimensionTravel() {}

    public static boolean enter(ServerPlayer player) {
        return enter(player, player.position());
    }

    /** A physical entrance may save a safe position beside its basin instead of inside its portal. */
    public static boolean enter(ServerPlayer player, Vec3 returnPosition) {
        if (LogosDimension.isCrucible(player.level().dimension())) return leave(player);
        var origin=player.blockPosition();
        for(var pos:BlockPos.betweenClosed(origin.offset(-6,-4,-6),origin.offset(6,4,6)))
            if(player.level().getBlockEntity(pos) instanceof dev.logos.dimension.instance.BoundCauldronBlockEntity binding)
                return dev.logos.dimension.instance.CauldronWorlds.enter(player,binding,returnPosition);
        player.sendSystemMessage(Component.translatable("message.logos_dimension.use_cauldron"));return false;
    }

    /** Enter a cauldron's own world and retain the actual departure point for the return trip. */
    public static boolean enterTarget(ServerPlayer player, ServerLevel target, Vec3 returnPosition) {
        if (target == null || !NativeWorlds.vanilla(target) || !LogosDimension.isCrucible(target.dimension())) {
            player.sendSystemMessage(Component.translatable("message.logos_dimension.missing_dimension")); return false;
        }
        var server = player.level().getServer();
        var home = new ReturnPoint(player.level().dimension().identifier().toString(), returnPosition.x, returnPosition.y, returnPosition.z, player.getYRot(), player.getXRot());
        var gate = ReturnGate.prepare(target);
        if (gate == null) return false;
        if (!teleport(player, target, gate.feet().getBottomCenter(), player.getYRot(), 0)) return false;
        server.overworld().getDataStorage().computeIfAbsent(Returns.TYPE).put(player.getUUID().toString(), home);
        ReturnGate.tell(player, gate);
        return true;
    }

    public static boolean leave(ServerPlayer player) {
        var server = player.level().getServer();
        var returns = server.overworld().getDataStorage().computeIfAbsent(Returns.TYPE);
        String playerId = player.getUUID().toString();
        ReturnPoint home;
        while ((home = returns.get(playerId)) != null) {
            Identifier id = Identifier.tryParse(home.dimension);
            var target = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            boolean deleted = id != null && LogosDimension.isCrucible(ResourceKey.create(Registries.DIMENSION, id))
                    && !id.equals(LogosDimension.CRUCIBLE.identifier())
                    && dev.logos.dimension.instance.CauldronWorlds.isDestroyed(server, UUID.fromString(id.getPath()));
            if (target != null && target != player.level() && !deleted) {
                if (!teleport(player, target, new Vec3(home.x, home.y, home.z), home.yaw, home.pitch)) return false;
                returns.remove(playerId); return true;
            }
            returns.remove(playerId);
        }
        boolean moved = player.teleport(TeleportTransition.createDefault(player, TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET))) != null;
        if (moved) returns.clear(playerId);
        return moved;
    }

    public static Vec3 findLanding(ServerLevel target, AlchemicalLayout.Center center, AlchemicalLayout.Region region) {
        return findLanding(target,center,region,feet->true);
    }

    /** Reject unsuitable surroundings as well as unsafe feet, so callers can keep searching. */
    public static Vec3 findLanding(ServerLevel target, AlchemicalLayout.Center center, AlchemicalLayout.Region region,Predicate<BlockPos> suitable) {
        double radius = 20_000 * switch (region) {
            case SULFUR -> .09; case MERCURY -> .23; case SALT -> .375; default -> .465;
        };
        var key=ResourceKey.create(Registries.BIOME,LogosDimension.id(region.name().toLowerCase(java.util.Locale.ROOT)));
        for (int angle = 0; angle < 64; angle++) for (int offset = 0; offset < 12; offset++) {
            double a = angle * Math.PI / 32;
            int x = center.x() + (int) ((radius + offset * 36) * Math.cos(a));
            int z = center.z() + (int) ((radius + offset * 36) * Math.sin(a));
            if (Math.abs(x)>=9995||Math.abs(z)>=9995)continue;
            var biome=target.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(x>>2,24,z>>2,target.getChunkSource().randomState().sampler());
            if(!biome.is(key))continue;
            target.getChunk(x >> 4, z >> 4);
            int height=target.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            BlockPos feet = new BlockPos(x,height,z);
            if(target.getFluidState(feet.below()).isEmpty()&&target.getBlockState(feet.below()).isFaceSturdy(target,feet.below(),net.minecraft.core.Direction.UP)
                    && target.isEmptyBlock(feet) && target.isEmptyBlock(feet.above()) && suitable.test(feet)) return feet.getBottomCenter();
        }
        return null;
    }
    public static boolean teleport(ServerPlayer player, ServerLevel target, Vec3 pos, float yaw, float pitch) {
        target.getChunk(BlockPos.containing(pos));
        return player.teleport(new TeleportTransition(target, pos, Vec3.ZERO, yaw, pitch,
                TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET))) != null;
    }
}
