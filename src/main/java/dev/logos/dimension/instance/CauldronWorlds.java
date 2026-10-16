package dev.logos.dimension.instance;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.*;
import dev.logos.dimension.mixin.MinecraftServerAccess;
import dev.logos.dimension.world.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Server-thread-only UUID world lifecycle. A tombstone is retained after physical data deletion. */
@EventBusSubscriber(modid=LogosDimension.ID)
public final class CauldronWorlds {
    private static final Logger LOGGER=LogUtils.getLogger();
    public static final int SIZE=20_000;
    public record Entry(long seed,boolean deleted,boolean purged){
        public static final Codec<Entry> CODEC=RecordCodecBuilder.create(i->i.group(
                Codec.LONG.fieldOf("seed").forGetter(Entry::seed),
                Codec.BOOL.optionalFieldOf("deleted",false).forGetter(Entry::deleted),
                Codec.BOOL.optionalFieldOf("purged",false).forGetter(Entry::purged)).apply(i,Entry::new));
    }
    public static final class Worlds extends SavedData {
        public static final Codec<Worlds> CODEC=Codec.unboundedMap(Codec.STRING,Entry.CODEC).fieldOf("worlds").codec().xmap(Worlds::new,w->w.entries);
        public static final SavedDataType<Worlds> TYPE=new SavedDataType<>(LogosDimension.id("cauldron_worlds"),Worlds::new,CODEC);
        private final Map<String,Entry> entries;
        private final Set<UUID> pending=new LinkedHashSet<>();
        private final Set<UUID> warned=new HashSet<>();
        private final Map<UUID,ServerLevel> closing=new HashMap<>();
        public Worlds(){this(Map.of());}
        private Worlds(Map<String,Entry> entries){this.entries=new HashMap<>(entries);}
        public Map<String,Entry> snapshot(){return Map.copyOf(entries);}
        private void put(UUID id,Entry entry){entries.put(id.toString(),entry);setDirty();}
    }
    public static Worlds data(MinecraftServer server){return server.getDataStorage().computeIfAbsent(Worlds.TYPE);}
    public static ResourceKey<Level> key(UUID id){return ResourceKey.create(Registries.DIMENSION,LogosDimension.id(id.toString()));}
    public static boolean isDestroyed(MinecraftServer server,UUID id){var entry=data(server).entries.get(id.toString());return entry!=null&&entry.deleted();}
    public static boolean enter(ServerPlayer player,BoundCauldronBlockEntity binding,Vec3 returnPosition){
        var server=player.level().getServer();UUID id=binding.worldId();
        if(id!=null&&isDestroyed(server,id))return false;
        if(id==null){id=UUID.randomUUID();binding.setWorldId(id);}
        if(player.level().dimension().equals(key(id)))return DimensionTravel.leave(player);
        var target=load(server,id);
        return target!=null&&DimensionTravel.enterTarget(player,target,returnPosition);
    }
    /** Reads previous chunks; creating a level never overwrites an existing instance. */
    @SuppressWarnings("deprecation")
    public static ServerLevel load(MinecraftServer server,UUID id){
        if(!server.isSameThread())throw new IllegalStateException("Cauldron worlds must load on the server thread");
        var worlds=data(server);var entry=worlds.entries.get(id.toString());
        if(entry!=null&&entry.deleted())return null;
        var existing=server.getLevel(key(id));if(existing!=null)return existing;
        var original=server.getLevel(LogosDimension.CRUCIBLE);
        if(original==null||!(original.getChunkSource().getGenerator().getBiomeSource() instanceof AlchemicalBiomeSource source))return null;
        if(entry==null){entry=new Entry(AlchemicalLayout.mix(id.getMostSignificantBits()^id.getLeastSignificantBits()^original.getSeed()),false,false);worlds.put(id,entry);}
        var generator=new AlchemicalChunkGenerator(source.copyForInstance());
        var type=original.dimensionTypeRegistration();
        var stem=new LevelStem(type,generator,OptionalLong.of(entry.seed()));
        var level=new ServerLevel(server,Util.backgroundExecutor(),((MinecraftServerAccess)server).logosDimensionStorage(),
                new DerivedLevelData(server.getWorldData(),server.getWorldData().overworldData()),key(id),stem,false,
                BiomeManager.obfuscateSeed(entry.seed()),List.of(),false);
        configureBorder(level);
        server.forgeGetWorldMap().put(key(id),level);server.markWorldsDirty();
        server.getPlayerList().addWorldborderListener(level);
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        return level;
    }
    public static void configureBorder(ServerLevel level){
        var border=level.getWorldBorder();border.setCenter(0,0);border.setSize(SIZE);border.setWarningBlocks(16);
        border.setSafeZone(0);border.setDamagePerBlock(.5);
    }
    /** Queue after the current world's block/entity traversal; pickup and chunk unload do not call this. */
    public static void requestDestroy(MinecraftServer server,UUID id){
        if(!server.isSameThread()){server.execute(()->requestDestroy(server,id));return;}
        var worlds=data(server);var entry=worlds.entries.get(id.toString());
        if(entry==null||entry.purged())return;
        worlds.put(id,new Entry(entry.seed(),true,false));worlds.pending.add(id);
    }
    @SuppressWarnings("deprecation")
    public static void processPending(MinecraftServer server){
        var worlds=data(server);
        if(!worlds.pending.isEmpty())server.getDataStorage().saveAndJoin();
        for(UUID id:List.copyOf(worlds.pending)){
            var entry=worlds.entries.get(id.toString());if(entry==null||!entry.deleted()){worlds.pending.remove(id);continue;}
            var level=server.getLevel(key(id));
            try{
                if(level!=null){
                    for(var player:List.copyOf(level.players()))if(!DimensionTravel.leave(player))throw new IOException("Cannot evacuate "+player.getUUID());
                    if(!level.players().isEmpty())throw new IOException("Players still occupy the world");
                    level.save(null,true,false);
                    NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
                    server.forgeGetWorldMap().remove(key(id));server.markWorldsDirty();
                    worlds.closing.put(id,level);
                }
                var closing=worlds.closing.get(id);
                if(closing!=null){closing.close();worlds.closing.remove(id);}
                deleteOwnedDirectory(server,id);
                worlds.put(id,new Entry(entry.seed(),true,true));worlds.pending.remove(id);worlds.warned.remove(id);
            }catch(IOException error){
                if(worlds.warned.add(id))LOGGER.warn("Cauldron world {} cleanup will retry; no unrelated world files were touched",id,error);
            }
        }
    }
    public static Path directory(MinecraftServer server,UUID id){return server.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(LogosDimension.ID).resolve(id.toString()).toAbsolutePath().normalize();}
    private static void deleteOwnedDirectory(MinecraftServer server,UUID id)throws IOException{
        var worlds=data(server);var entry=worlds.entries.get(id.toString());
        if(entry==null||!entry.deleted())throw new IOException("Cannot delete an unowned or live dimension");
        Path root=server.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(LogosDimension.ID).toAbsolutePath().normalize();
        Path target=directory(server,id);
        if(!target.getParent().equals(root)||!target.getFileName().toString().equals(id.toString()))throw new IOException("Invalid cauldron world path");
        if(Files.isSymbolicLink(root)||Files.isSymbolicLink(target))throw new IOException("Refusing a symbolic world path");
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;
        Path save=server.getWorldPath(LevelResource.ROOT).toRealPath();
        Path realRoot=root.toRealPath(), realTarget=target.toRealPath();
        if(!realRoot.startsWith(save)||!realTarget.equals(realRoot.resolve(id.toString())))throw new IOException("World path escapes its owned UUID directory");
        Files.walkFileTree(target,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
    @SubscribeEvent static void starting(ServerStartingEvent event){
        var server=event.getServer();var worlds=data(server);
        for(var record:worlds.snapshot().entrySet()){
            UUID id=UUID.fromString(record.getKey());if(record.getValue().deleted()){if(!record.getValue().purged())worlds.pending.add(id);}else load(server,id);
        }
        var original=server.getLevel(LogosDimension.CRUCIBLE);if(original!=null)configureBorder(original);
        processPending(server);
    }
    @SubscribeEvent static void tick(ServerTickEvent.Post event){processPending(event.getServer());}
    @SubscribeEvent static void stopping(ServerStoppingEvent event){processPending(event.getServer());}
    private CauldronWorlds(){}
}
