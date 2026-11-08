package dev.minecontrol;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Managed user files. Deletion is a move to .trash; runtime files never appear in the catalog. */
public final class MacroFiles {
    public record Entry(String name, String type, long size, long modified) {}
    private final Path directory;
    public MacroFiles(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public Path directory() { return directory; }
    public void initialize() throws IOException {
        Files.createDirectories(directory);
        Path runtime = directory.resolve(".runtime");
        Files.createDirectories(runtime);
        try (var input = MacroFiles.class.getResourceAsStream("/minecontrol/macro-runtime.jar")) {
            if (input == null) throw new IOException("Mod 缺少 Java 宏运行库");
            Files.copy(input, runtime.resolve("sdk.jar"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path runtime() { return directory.resolve(".runtime/sdk.jar"); }
    public List<Entry> list() throws IOException {
        try (var paths = Files.list(directory)) {
            var result = new ArrayList<Entry>();
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !supported(path)) continue;
                result.add(new Entry(path.getFileName().toString(), source(path) ? "Java 源文件" : "JAR 程序",
                        Files.size(path), Files.getLastModifiedTime(path).toMillis()));
            }
            result.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(result);
        }
    }
    public Path resolve(String name) throws IOException {
        Path path = directory.resolve(name).normalize();
        if (!directory.equals(path.getParent()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !supported(path)) throw new IOException("宏文件不存在或类型不支持：" + name);
        return path;
    }
    public static boolean source(Path path) { return path.toString().toLowerCase(Locale.ROOT).endsWith(".java"); }
    private static boolean supported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && (name.endsWith(".java") || name.endsWith(".jar"));
    }
    public static void validate(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !supported(path))
            throw new IOException("请选择 .java 源文件或可执行 .jar 文件");
        if (!source(path)) mainClass(path);
    }
    public static String mainClass(Path path) throws IOException {
        try (var jar = new JarFile(path.toFile())) {
            var manifest = jar.getManifest();
            String main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
            if (main == null || main.isBlank()) throw new IOException("JAR 缺少 Main-Class：" + path.getFileName());
            return main;
        }
    }
    public Entry add(Path source) throws IOException {
        source = source.toAbsolutePath().normalize();
        validate(source);
        if (source.getParent().equals(directory)) {
            return entry(resolve(source.getFileName().toString()));
        }
        Path destination = available(directory, source.getFileName().toString());
        // Copy through a hidden staging file so a partial copy never becomes a runnable macro.
        Path temporary = Files.createTempFile(directory, ".import-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, destination);
        } finally { Files.deleteIfExists(temporary); }
        return entry(destination);
    }
    public Path trash(String name) throws IOException {
        Path source = resolve(name);
        Path trash = directory.resolve(".trash");
        Files.createDirectories(trash);
        Path destination = available(trash, name);
        return Files.move(source, destination);
    }
    private static Entry entry(Path path) throws IOException {
        return new Entry(path.getFileName().toString(), source(path) ? "Java 源文件" : "JAR 程序",
                Files.size(path), Files.getLastModifiedTime(path).toMillis());
    }
    private static Path available(Path parent, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot), extension = dot < 0 ? "" : name.substring(dot);
        Path result = parent.resolve(name);
        for (int suffix = 2; Files.exists(result, LinkOption.NOFOLLOW_LINKS); suffix++)
            result = parent.resolve(base + " (" + suffix + ")" + extension);
        return result;
    }
}
