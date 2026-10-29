package dev.minecontrol.sdk;

/** Read-only connection and client tick check; does not move the player. */
public final class Smoke {
    public static void main(String[] args) throws Exception {
        try (var mc = MineControl.connect(args.length == 0 ? 25580 : Integer.parseInt(args[0]))) {
            var before = mc.status();
            mc.waitTicks(3);
            var after = mc.status();
            if (after.tick() - before.tick() < 3) throw new AssertionError("Tick wait returned early");
            System.out.println("Java SDK connected: ticks " + before.tick() + " -> " + after.tick()
                    + ", worldLoaded=" + after.worldLoaded());
            System.out.println("Observation: " + mc.observe());
        }
    }
}
