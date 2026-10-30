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
            try (var manager = new JavaMacroManager(files.directory(), 25589, () -> releases[0]++)) {
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
            }
            System.out.println("Macro files/import/collision/trash/source process/stop checks passed");
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
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
