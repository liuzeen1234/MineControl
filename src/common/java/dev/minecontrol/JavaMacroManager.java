package dev.minecontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns the external Java program. No Minecraft API or blocking work on the client thread. */
public final class JavaMacroManager implements AutoCloseable {
    private final MacroFiles files;
    private final int port;
    private final Runnable release;
    private final Consumer<String> reporter;
    private final Deque<String> logs = new ArrayDeque<>();
    private String state = "未运行";
    private boolean active, stopping, closed;
    private Process process;
    private String activeFile;
    private String failureDetail = "";
    private final Map<String, String> states = new HashMap<>();
    private volatile List<MacroFiles.Entry> entries = List.of();
    private volatile boolean loading = true;
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "MineControl macro files");
        thread.setDaemon(true);
        return thread;
    });

    public JavaMacroManager(Path directory, int port, Runnable release) {
        this(directory, port, release, line -> {});
    }
    public JavaMacroManager(Path directory, int port, Runnable release, Consumer<String> reporter) {
        this.files = new MacroFiles(directory);
        this.port = port;
        this.release = release;
        this.reporter = reporter;
        fileWorker.execute(() -> {
            try { files.initialize(); entries = files.list(); }
            catch (IOException e) { log("无法读取宏目录：" + e.getMessage()); }
            finally { loading = false; }
        });
    }
    public Path directory() { return files.directory(); }
    public List<MacroFiles.Entry> entries() { return entries; }
    public boolean loading() { return loading; }
    public synchronized String activeFile() { return activeFile; }
    public synchronized String fileState(String name) { return states.getOrDefault(name, "就绪"); }
    public CompletableFuture<Void> refresh() {
        return fileOperation(() -> { entries = files.list(); });
    }
    public CompletableFuture<Void> addFiles(List<Path> paths) {
        return fileOperation(() -> {
            IOException failed = null;
            for (Path path : paths) {
                try { log("已添加：" + files.add(path).name()); }
                catch (IOException e) { failed = e; log("添加失败：" + path.getFileName() + "：" + e.getMessage()); }
            }
            entries = files.list();
            if (failed != null) throw failed;
        });
    }
    public CompletableFuture<Void> deleteFile(String name) {
        return fileOperation(() -> {
            synchronized (this) {
                if (active && name.equals(activeFile)) throw new IOException("请先停止此宏，再删除文件");
                Path destination = files.trash(name);
                states.remove(name);
                log("已移入回收站：" + destination.getFileName());
            }
            entries = files.list();
        });
    }
    @FunctionalInterface private interface FileOperation { void run() throws IOException; }
    private CompletableFuture<Void> fileOperation(FileOperation operation) {
        return CompletableFuture.runAsync(() -> {
            loading = true;
            try { operation.run(); }
            catch (IOException e) { log(e.getMessage()); throw new CompletionException(e); }
            finally { loading = false; }
        }, fileWorker);
    }
    public synchronized boolean active() { return active; }
    public synchronized String state() { return state; }
    public synchronized List<String> logs() { return List.copyOf(logs); }
    private synchronized void log(String message) {
        logs.addLast(message.length() > 240 ? message.substring(0, 240) : message);
        while (logs.size() > 40) logs.removeFirst();
        reporter.accept(message);
    }
    public synchronized boolean start(String name) {
        if (active || closed || loading || entries.stream().noneMatch(entry -> entry.name().equals(name))) return false;
        active = true;
        stopping = false;
        activeFile = name;
        failureDetail = "";
        state = "正在启动";
        states.put(name, state);
        log("启动：" + name);
        var worker = new Thread(this::run, "MineControl Java macro");
        worker.setDaemon(true);
        worker.start();
        return true;
    }
    private void run() {
        try {
            synchronized (this) {
                if (stopping || closed) return;
                Path program = files.resolve(activeFile);
                MacroFiles.validate(program);
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
                List<String> command = MacroFiles.source(program)
                        ? List.of(java, "-cp", files.runtime().toString(), program.toString(), Integer.toString(port))
                        : List.of(java, "-cp", files.runtime() + File.pathSeparator + program,
                                MacroFiles.mainClass(program), Integer.toString(port));
                process = new ProcessBuilder(command).directory(files.directory().toFile()).redirectErrorStream(true).start();
                state = "运行中";
                states.put(activeFile, state);
            }
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log(line);
                    if (line.contains("Exception") || line.startsWith("Caused by:") || line.contains("error:"))
                        failureDetail = line;
                }
            } catch (IOException e) {
                synchronized (this) { if (!stopping) throw e; }
            }
            int result = process.waitFor();
            synchronized (this) {
                if (!stopping && result != 0) {
                    state = "运行失败";
                    if (failureDetail.isBlank()) failureDetail = "程序退出码：" + result;
                    log("程序退出码：" + result + "。请检查日志和控制端口。");
                }
            }
        } catch (Exception e) {
            synchronized (this) {
                if (!stopping) { state = "运行失败"; failureDetail = e.toString(); log("运行失败：" + e.getMessage()); }
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
                log(state.equals("运行失败") ? state + "：" + failureDetail : state);
                states.put(activeFile, state);
                process = null;
                active = false;
            }
        }
    }
    public synchronized void stop() {
        if (!active || stopping) return;
        stopping = true;
        state = "正在停止";
        states.put(activeFile, state);
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
    @Override public synchronized void close() { closed = true; stop(); fileWorker.shutdown(); }
}
