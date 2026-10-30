package dev.minecontrol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
public final class ProtocolTest {
    public static void main(String[] args) throws Exception {
        MacroTest.run();
        RegionQueryTest.run();
        MacroFilesTest.run();
        require(!SimulatedInput.active(), "normal input origin");
        try {
            SimulatedInput.run(() -> {
                require(SimulatedInput.active(), "simulated input origin");
                SimulatedInput.run(() -> require(SimulatedInput.active(), "nested input origin"));
                throw new IllegalArgumentException("expected");
            });
        } catch (IllegalArgumentException expected) {}
        require(!SimulatedInput.active(), "input origin restored after exception");
        var input = new ByteArrayInputStream("{\"text\":\"测试\"}\r\n{}\n".getBytes(StandardCharsets.UTF_8));
        require(LocalServer.readLine(input, 100).equals("{\"text\":\"测试\"}\r"), "UTF-8 / CRLF");
        require(LocalServer.readLine(input, 100).equals("{}"), "multiple lines");
        require(LocalServer.readLine(input, 100) == null, "clean EOF");
        require(LocalServer.readLine(new ByteArrayInputStream("abcd\n".getBytes()), 4).equals("abcd"), "exact limit");
        expectIOException("abcde\n", 4, "oversized line");
        expectIOException("{}", 100, "truncated line");
        var directory = Files.createTempDirectory("minecontrol-config-test");
        var file = directory.resolve("config.json");
        try {
            var c = Config.load(file);
            require(c.port == 25580 && Files.exists(file), "default config persisted");
            Files.writeString(file, "{\"port\":25581,\"maxHoldTicks\":5}");
            c = Config.load(file);
            require(c.port == 25581 && c.maxHoldTicks == 5 && c.requestTimeoutMs == 15000, "custom config and missing-field defaults");
            Files.writeString(file, "{\"port\":0}");
            try { Config.load(file); throw new AssertionError("invalid port accepted"); } catch (IOException expected) {}
            Files.writeString(file, "null");
            try { Config.load(file); throw new AssertionError("null config accepted"); } catch (IOException expected) {}
        } finally { Files.deleteIfExists(file); Files.delete(directory); }
        try (var occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var config = new Config(); config.port = occupied.getLocalPort();
            try (var server = new LocalServer(config, null)) {
                try { server.start(); throw new AssertionError("occupied port accepted"); }
                catch (java.net.BindException expected) {}
            }
        }
        System.out.println("MineControl protocol/config/port checks passed");
    }
    private static void expectIOException(String value, int limit, String check) throws Exception {
        try { LocalServer.readLine(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), limit); throw new AssertionError(check); }
        catch (IOException expected) {}
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
