package dev.minecontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns the external Java program. No Minecraft API or blocking work on the client thread. */
public final class JavaMacroManager implements AutoCloseable {
    private final Path directory;
    private final int port;
    private final Runnable release;
    private final Deque<String> logs = new ArrayDeque<>();
    private String state = "未运行";
    private boolean active, stopping, closed;
    private Process process;

    public JavaMacroManager(Path directory, int port, Runnable release) {
        this.directory = directory;
        this.port = port;
        this.release = release;
    }
    public synchronized boolean active() { return active; }
    public synchronized String state() { return state; }
    public synchronized List<String> logs() { return List.copyOf(logs); }
    private synchronized void log(String message) {
        logs.addLast(message.length() > 240 ? message.substring(0, 240) : message);
        while (logs.size() > 40) logs.removeFirst();
    }
    public synchronized void start() {
        if (active || closed) return;
        active = true;
        stopping = false;
        state = "正在启动";
        log("启动实体延迟点击：10–30 tick 后直接左键一次。");
        var worker = new Thread(this::run, "MineControl Java macro");
        worker.setDaemon(true);
        worker.start();
    }
    private void run() {
        try {
            Files.createDirectories(directory);
            Path jar = directory.resolve("entity-click-program.jar");
            try (var input = JavaMacroManager.class.getResourceAsStream("/minecontrol/entity-click-program.jar")) {
                if (input == null) throw new IOException("Mod 缺少 Java 宏程序，请重新构建安装");
                Files.copy(input, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            synchronized (this) {
                if (stopping || closed) return;
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
                process = new ProcessBuilder(java, "-jar", jar.toAbsolutePath().toString(), Integer.toString(port))
                        .redirectErrorStream(true).start();
                state = "运行中";
            }
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) log(line);
            } catch (IOException e) {
                synchronized (this) { if (!stopping) throw e; }
            }
            int result = process.waitFor();
            synchronized (this) {
                if (!stopping && result != 0) {
                    state = "运行失败";
                    log("程序退出码：" + result + "。请检查日志和控制端口。");
                }
            }
        } catch (Exception e) {
            synchronized (this) {
                if (!stopping) { state = "运行失败"; log("运行失败：" + e.getMessage()); }
            }
        } finally {
            Process remaining;
            synchronized (this) { remaining = process; }
            if (remaining != null && remaining.isAlive()) {
                remaining.destroy();
                try { if (!remaining.waitFor(3, TimeUnit.SECONDS)) remaining.destroyForcibly().waitFor(); }
                catch (InterruptedException e) { remaining.destroyForcibly(); Thread.currentThread().interrupt(); }
            }
            release.run();
            synchronized (this) {
                if (stopping) state = "已停止";
                else if (!state.equals("运行失败")) state = "已结束";
                log(state);
                process = null;
                active = false;
            }
        }
    }
    public synchronized void stop() {
        if (!active || stopping) return;
        stopping = true;
        state = "正在停止";
        log("停止程序并释放输入。");
        if (process != null) {
            Process current = process;
            current.destroy();
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
                if (current.isAlive()) current.destroyForcibly();
            });
        }
        release.run();
    }
    @Override public synchronized void close() { closed = true; stop(); }
}
