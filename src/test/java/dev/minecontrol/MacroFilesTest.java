package dev.minecontrol;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

public final class MacroFilesTest {
    public static void run() throws Exception {
        Path temp = Files.createTempDirectory("minecontrol-files-");
        try {
            var files = new MacroFiles(temp.resolve("macros"));
            files.initialize();
            require(files.list().size() == 1, "initial bundled macro");
            try (var runtime = new JarFile(files.runtime().toFile())) {
                require(runtime.getEntry("EntityClick.class") == null, "runtime must not shadow user main class");
            }
            Path source = temp.resolve("Probe.java");
            Files.writeString(source, """
                    import dev.minecontrol.sdk.MineControl;
                    public class Probe {
                        public static void main(String[] args) throws Exception {
                            System.out.println("READY " + args[0] + " " + MineControl.class.getName());
                            Thread.sleep(60000);
                        }
                    }
                    """);
            require(files.add(source).name().equals("Probe.java"), "source import");
            require(files.add(source).name().equals("Probe (2).java"), "collision renamed");
            require(Files.readString(source).contains("READY"), "original source retained");
            files.trash("Probe (2).java");
            require(files.list().size() == 2, "trash excluded");
            files.trash("entity-click-program.jar");
            files.initialize();
            require(files.list().size() == 1, "deleted bundled macro not restored");
            try { files.resolve("../Probe.java"); throw new AssertionError("path escaped"); }
            catch (IOException expected) {}
            try { files.resolve("/"); throw new AssertionError("absolute directory accepted"); }
            catch (IOException expected) {}
            Path invalid = temp.resolve("invalid.jar");
            try (var output = new JarOutputStream(Files.newOutputStream(invalid))) {}
            try { files.add(invalid); throw new AssertionError("non-executable jar accepted"); }
            catch (IOException expected) {}
            var manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class", "Example");
            Path runnable = temp.resolve("Example.jar");
            try (var output = new JarOutputStream(Files.newOutputStream(runnable), manifest)) {}
            require(files.add(runnable).type().equals("JAR 程序"), "executable jar accepted");
            Files.createSymbolicLink(files.directory().resolve("Linked.java"), source);
            require(files.list().stream().noneMatch(entry -> entry.name().equals("Linked.java")), "symlink excluded");

            int[] releases = {0};
            var reported = Collections.synchronizedList(new ArrayList<String>());
            try (var manager = new JavaMacroManager(files.directory(), 25589, () -> releases[0]++, reported::add)) {
                manager.refresh().get(5, TimeUnit.SECONDS);
                require(manager.start("Probe.java"), "source launch accepted");
                require(!manager.start("Probe.java"), "concurrent launch rejected");
                await(() -> manager.logs().stream().anyMatch(line -> line.startsWith("READY 25589")), 15_000);
                try { manager.deleteFile("Probe.java").get(); throw new AssertionError("deleted active macro"); }
                catch (ExecutionException expected) {}
                manager.stop();
                await(() -> !manager.active(), 6_000);
                require(manager.fileState("Probe.java").equals("已停止") && releases[0] > 0, "stop and cleanup");
                manager.deleteFile("Probe.java").get();
                require(manager.entries().stream().noneMatch(entry -> entry.name().equals("Probe.java")), "stopped file deleted");
                Path jar = sdkShadowJar(temp);
                manager.addFiles(List.of(jar)).get();
                require(manager.start(jar.getFileName().toString()), "custom JAR main launched");
                await(() -> manager.logs().stream().anyMatch(line -> line.startsWith("JAR_READY ") && line.endsWith("/.runtime/sdk.jar")), 15_000);
                manager.stop(); await(() -> !manager.active(), 6_000);
                require(manager.start("Example.jar"), "failed JAR launch accepted for error check");
                await(() -> !manager.active(), 6_000);
                require(manager.logs().get(manager.logs().size()-1).contains("ClassNotFoundException"), "failure reason retained in UI");
                require(reported.stream().anyMatch(line -> line.contains("ClassNotFoundException")), "child errors forwarded to client logger");
            }
            System.out.println("Macro files/import/collision/trash/source process/stop checks passed");
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static Path sdkShadowJar(Path temp) throws Exception {
        Path source = temp.resolve("EntityClick.java");
        Files.writeString(source, """
                public class EntityClick {
                    public static void main(String[] args) throws Exception {
                        System.out.println("JAR_READY " + Class.forName("dev.minecontrol.sdk.MineControl")
                            .getProtectionDomain().getCodeSource().getLocation().getPath());
                        Thread.sleep(60000);
                    }
                }
                """);
        Path stub = temp.resolve("stub/dev/minecontrol/sdk/MineControl.java");
        Files.createDirectories(stub.getParent());
        Files.writeString(stub, "package dev.minecontrol.sdk; public class MineControl {}");
        Path classes = temp.resolve("jar-classes"); Files.createDirectories(classes);
        int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", classes.toString(), source.toString(), stub.toString());
        require(result == 0, "JAR regression fixture compiled");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "EntityClick");
        Path jar = temp.resolve("sdk-shadow.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest); var walk = Files.walk(classes)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));
                Files.copy(path, output); output.closeEntry();
            }
        }
        return jar;
    }
    private static void await(java.util.function.BooleanSupplier predicate, long timeout) throws Exception {
        long deadline = System.nanoTime() + timeout * 1_000_000;
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for source process");
            Thread.sleep(20);
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
