package dev.logos.dimension.music;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 纸带内容的服务端存储：存档目录 data/logos_dimension/rolls/&lt;UUID&gt;.lroll，用到时才读入，内存里留最近 64 卷。
 * 「直接播放 MIDI」的临时纸带只在内存里，停止播放后删掉。
 *
 * <p>文件名只由服务端生成的 UUID 组成，不接受任何玩家给的路径。内容写好后不再修改。
 */
public final class RollStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CACHE = 64;
    private static final String EXT = ".lroll";

    private static final Map<UUID, byte[]> cache = new LinkedHashMap<>(CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, byte[]> eldest) {
            return size() > CACHE;
        }
    };
    private static final Map<UUID, byte[]> temporary = new HashMap<>();
    private static @Nullable Path dir;

    private RollStore() {}

    static void open(MinecraftServer server) {
        dir = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("logos_dimension").resolve("rolls").normalize();
        cache.clear();
        temporary.clear();
    }

    static void close() {
        dir = null;
        cache.clear();
        temporary.clear();
    }

    /** 写入一卷新纸带，返回它的 ID。内容必须已经过 {@link MusicRoll#decode} 校验。 */
    public static UUID save(byte[] encoded) throws IOException {
        if (dir == null) throw new IOException("roll store is not open");
        Files.createDirectories(dir);
        UUID id = UUID.randomUUID();
        Path file = dir.resolve(id + EXT);
        Path tmp = dir.resolve(id + EXT + ".tmp");
        Files.write(tmp, encoded);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        cache.put(id, encoded);
        return id;
    }

    public static UUID saveTemporary(byte[] encoded) {
        UUID id = UUID.randomUUID();
        temporary.put(id, encoded);
        return id;
    }

    public static void dropTemporary(UUID id) {
        temporary.remove(id);
    }

    public static boolean isTemporary(UUID id) {
        return temporary.containsKey(id);
    }

    public static @Nullable byte[] load(UUID id) {
        byte[] t = temporary.get(id);
        if (t != null) return t;
        byte[] cached = cache.get(id);
        if (cached != null) return cached;
        if (dir == null) return null;
        Path file = dir.resolve(id + EXT);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.size(file) > MusicRoll.MAX_BYTES) return null;
            byte[] data = Files.readAllBytes(file);
            cache.put(id, data);
            return data;
        } catch (IOException e) {
            LOGGER.warn("Could not read music roll {}", id, e);
            return null;
        }
    }
}
