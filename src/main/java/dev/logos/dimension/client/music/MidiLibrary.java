package dev.logos.dimension.client.music;

import dev.logos.dimension.music.MidiReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;

/**
 * 玩家自己的 MIDI 文件夹：&lt;游戏目录&gt;/logos_music/。界面只列出这个目录（不含子目录）里的 .mid / .midi，
 * 玩家从列表里选，不提供输入路径的地方。
 *
 * <p>安全边界：路径规范化后必须仍在这个目录里；不跟随符号链接，只接受普通文件；超过 1 MiB 不读入。
 * 服务端从不读 MIDI，只接收客户端解析好的纸带，再按纸带的上限校验一遍。
 */
final class MidiLibrary {
    static final String FOLDER = "logos_music";

    record Entry(String name, long size) {
        boolean tooBig() {
            return size > MidiReader.MAX_FILE_BYTES;
        }
    }

    private MidiLibrary() {}

    static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(FOLDER).toAbsolutePath().normalize();
    }

    static Path ensureFolder() throws IOException {
        Path dir = folder();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(dir);
        return dir;
    }

    private static boolean isMidi(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mid") || n.endsWith(".midi");
    }

    static List<Entry> list() throws IOException {
        Path dir = ensureFolder();
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p)) continue;
                String name = p.getFileName().toString();
                if (!isMidi(name)) continue;
                out.add(new Entry(name, Files.size(p)));
            }
        }
        out.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** 按文件名读一个文件；名字必须是列表里出现过的那种（不含路径分隔符），读到的路径也必须仍在文件夹里。 */
    static byte[] read(String name) throws IOException {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || !isMidi(name)) {
            throw new IOException("bad file name");
        }
        Path dir = ensureFolder();
        Path file = dir.resolve(name).normalize();
        if (!file.getParent().equals(dir)) throw new IOException("outside the music folder");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a file");
        Path realDir = dir.toRealPath();
        Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.getParent().equals(realDir)) throw new IOException("outside the music folder");
        if (Files.size(file) > MidiReader.MAX_FILE_BYTES) throw new IOException("too big");
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = in.readNBytes(MidiReader.MAX_FILE_BYTES + 1);
            if (data.length > MidiReader.MAX_FILE_BYTES) throw new IOException("too big");
            return data;
        }
    }

    /** 去掉扩展名，作为纸带的默认曲名。 */
    static String title(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
