package dev.minecontrol.sdk;

/** Opt-in live input check, restricted to the title/menu with no world loaded. */
public final class LiveInputCheck {
    public static void main(String[] args) throws Exception {
        try (var mc = MineControl.connect()) {
            var initial = mc.status();
            if (initial.worldLoaded() || !initial.heldKeys().isEmpty() || !initial.heldButtons().isEmpty())
                throw new IllegalStateException("Run with no world loaded and no simulated inputs held");
            var held = mc.key().hold("left_shift", 6);
            if (!held.heldKeys().contains(340)) throw new AssertionError("Key was not held");
            mc.waitTicks(7);
            if (!mc.status().heldKeys().isEmpty()) throw new AssertionError("Key did not expire");
            mc.key().down("left_shift");
        }
        try (var mc = MineControl.connect()) {
            if (!mc.status().heldKeys().isEmpty()) throw new AssertionError("close did not release key");
            if (mc.ui().list().isEmpty()) throw new AssertionError("Menu widgets unavailable");
            System.out.println("Live SDK key hold/expiry/close cleanup/UI checks passed");
        }
    }
}
