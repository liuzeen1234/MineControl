package dev.minecontrol;

/** Identifies Mod-injected callbacks without granting the same bypass to physical input. */
public final class SimulatedInput {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private SimulatedInput() {}
    public static boolean active() { return DEPTH.get() > 0; }
    public static void run(Runnable action) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try { action.run(); }
        finally {
            if (previous == 0) DEPTH.remove();
            else DEPTH.set(previous);
        }
    }
}
