package dev.minecontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns external Java macro processes. No Minecraft API or blocking work on the client thread. */
public final class JavaMacroManager implements AutoCloseable {
    private final MacroFiles files;
    private final int port;
    private final Runnable release;
    private final Consumer<String> reporter;
    private final Deque<String> logs = new ArrayDeque<>();
    private String state = "未运行";
    private boolean closed;
    private MacroCoordinator coordinator;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private static final class Run {
        final String name, token;
        volatile Process process;
        volatile boolean stopping;
        String state = "正在启动", failureDetail = "";
        Run(String name, String token) { this.name = name; this.token = token; }
    }
    public synchronized void coordinator(MacroCoordinator coordinator) { this.coordinator = coordinator; coordinator.attach(this); }
    public synchronized boolean executing() { return coordinator == null ? active() : coordinator.controlling(); }
    public synchronized boolean running(String name) { return runs.containsKey(name); }
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
    public synchronized String activeFile() { return runs.isEmpty() ? null : runs.keySet().iterator().next(); }
    public synchronized String fileState(String name) {
        String role = coordinator == null ? null : coordinator.fileState(name);
        return role == null ? states.getOrDefault(name, "就绪") : role;
    }
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
                if (runs.containsKey(name)) throw new IOException("请先停止此宏，再删除文件");
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
    public synchronized boolean active() { return !runs.isEmpty(); }
    public synchronized String state() { return active() ? runs.size() + " 个宏 · " + (coordinator == null ? String.join("、", runs.keySet()) : coordinator.summary()) : state; }
    public synchronized List<String> logs() { return List.copyOf(logs); }
    private synchronized void log(String message) {
        logs.addLast(message.length() > 240 ? message.substring(0, 240) : message);
        while (logs.size() > 40) logs.removeFirst();
        reporter.accept(message);
    }
    public synchronized boolean start(String name) {
        if (closed || loading || runs.size() >= 128 || runs.containsKey(name) || entries.stream().noneMatch(entry -> entry.name().equals(name))) return false;
        String token;
        try { token = coordinator == null ? "" : coordinator.prepare(name); }
        catch (MacroCoordinator.Error e) { log(e.getMessage()); return false; }
        Run run = new Run(name, token); runs.put(name, run);
        states.put(name, run.state); log("启动：" + name);
        var worker = new Thread(() -> run(run), "MineControl macro " + name);
        worker.setDaemon(true); worker.start(); return true;
    }
    private void run(Run run) {
        try {
            synchronized (this) {
                if (run.stopping || closed) return;
                Path program = files.resolve(run.name); MacroFiles.validate(program);
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
                List<String> command = MacroFiles.source(program)
                        ? List.of(java, "-cp", files.runtime().toString(), program.toString(), Integer.toString(port))
                        : List.of(java, "-cp", files.runtime() + File.pathSeparator + program,
                                MacroFiles.mainClass(program), Integer.toString(port));
                var builder = new ProcessBuilder(command).directory(files.directory().toFile()).redirectErrorStream(true);
                if (!run.token.isEmpty()) builder.environment().put("MINECONTROL_MACRO_TOKEN", run.token);
                run.process = builder.start(); run.state = "运行中"; states.put(run.name, run.state);
            }
            try (var reader = new BufferedReader(new InputStreamReader(run.process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log("[" + run.name + "] " + line);
                    if (line.contains("Exception") || line.startsWith("Caused by:") || line.contains("error:")) run.failureDetail = line;
                }
            } catch (IOException e) { if (!run.stopping) throw e; }
            int result = run.process.waitFor();
            if (!run.stopping && result != 0) {
                run.state = "运行失败";
                if (run.failureDetail.isBlank()) run.failureDetail = "程序退出码：" + result;
            }
        } catch (Exception e) {
            if (!run.stopping) { run.state = "运行失败"; run.failureDetail = e.toString(); }
        } finally {
            Process remaining = run.process;
            if (remaining != null && remaining.isAlive()) {
                remaining.destroy();
                try { if (!remaining.waitFor(3, TimeUnit.SECONDS)) remaining.destroyForcibly().waitFor(); }
                catch (InterruptedException e) { remaining.destroyForcibly(); Thread.currentThread().interrupt(); }
            }
            synchronized (this) {
                if (run.stopping) run.state = "已停止";
                else if (!run.state.equals("运行失败")) run.state = "已结束";
                String reason = run.state.equals("运行失败") ? run.failureDetail : run.state;
                log("[" + run.name + "] " + run.state + "：" + reason);
                states.put(run.name, run.state); state = run.state; runs.remove(run.name);
                if (coordinator != null) coordinator.processExited(run.token, reason, run.state.equals("运行失败") ? "failed" : "normal");
                else release.run();
            }
        }
    }
    /** Request graceful shutdown, followed by termination after three seconds. */
    public synchronized void terminate(String name) {
        Run run = runs.get(name); if (run == null || run.stopping) return;
        run.stopping = true; states.put(name, "正在停止");
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
            Process current = run.process;
            if (current != null && current.isAlive()) {
                current.destroy();
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> { if (current.isAlive()) current.destroyForcibly(); });
            }
        });
    }
    public synchronized void stop(String name) {
        if (coordinator != null) coordinator.stopFile(name, "用户停止");
        else { terminate(name); release.run(); }
    }
    public synchronized void stop() {
        if (coordinator != null) coordinator.emergency("用户停止全部宏");
        else { for (String name : List.copyOf(runs.keySet())) terminate(name); release.run(); }
    }
    public synchronized void resume() { if (coordinator != null) coordinator.resume(); }
    @Override public synchronized void close() { closed = true; stop(); fileWorker.shutdown(); }
}
