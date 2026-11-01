package dev.minecontrol;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
public final class Config {
    public boolean enabled = true;
    public int port = 25580;
    public boolean backgroundControl = true;
    public boolean blockPhysicalInputDuringMacros = true;
    public int maxHoldTicks = 1200;
    public int requestTimeoutMs = 15000;
    public int maxRequestBytes = 65536;
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "minecontrol-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(this));
            try {
                Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
    public static Config load(Path path) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        Config c;
        if (Files.exists(path)) {
            c = gson.fromJson(Files.readString(path), Config.class);
            if (c == null) throw new IOException("Configuration must be a JSON object");
        } else {
            c = new Config();
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(c));
        }
        if (c.port < 1 || c.port > 65535 || c.maxHoldTicks < 1 || c.requestTimeoutMs < 100 || c.requestTimeoutMs > 300000 || c.maxRequestBytes < 256 || c.maxRequestBytes > 1048576)
            throw new IOException("Invalid MineControl configuration limits");
        return c;
    }
}
