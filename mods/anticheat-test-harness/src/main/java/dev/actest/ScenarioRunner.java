package dev.actest;

import dev.actest.module.TestModule;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Faehrt die Intensitaet eines Moduls schrittweise hoch.
 *
 * <p>Damit laesst sich die Schwelle bestimmen, ab der der Anti-Cheat reagiert,
 * statt nur zu sehen, dass er irgendwann reagiert. Jeder Schritt landet mit
 * Zeitstempel im Protokoll.
 */
public final class ScenarioRunner {

    private static final int TICKS_PER_SECOND = 20;

    private TestModule module;
    private double current;
    private double target;
    private double step;
    private int holdTicks;
    private int remaining;
    private boolean running;

    public boolean isRunning() {
        return running;
    }

    public void start(Minecraft mc, TestModule module, double from, double to, double step, int holdSeconds) {
        this.module = module;
        this.current = from;
        this.target = to;
        this.step = Math.max(step, 0.01);
        this.holdTicks = Math.max(holdSeconds, 1) * TICKS_PER_SECOND;
        this.remaining = 0;
        this.running = true;

        module.setIntensity(from, mc);
        module.setEnabled(true, mc);
        announce(mc, String.format(Locale.ROOT,
                "Sweep %s: %.2f bis %.2f in Schritten von %.2f, je %d s",
                module.id(), from, to, this.step, holdSeconds));
    }

    public void stop(Minecraft mc, String reason) {
        if (!running) {
            return;
        }
        running = false;
        if (module != null) {
            module.setEnabled(false, mc);
            announce(mc, "Sweep " + module.id() + " beendet (" + reason + ")");
        }
        module = null;
    }

    public void tick(Minecraft mc) {
        if (!running || module == null) {
            return;
        }
        if (remaining > 0) {
            remaining--;
            return;
        }
        if (current > target + 1.0E-6) {
            stop(mc, "Zielwert erreicht");
            return;
        }
        module.setIntensity(current, mc);
        announce(mc, String.format(Locale.ROOT, "Sweep %s: %.2f %s",
                module.id(), current, module.unit()));
        current += step;
        remaining = holdTicks;
    }

    private void announce(Minecraft mc, String message) {
        AntiCheatTestClient.LOGGER.info(message);
        if (mc.player != null && TestConfig.get().announceInChat) {
            mc.player.sendSystemMessage(Component.literal("[AC-Test] " + message));
        }
    }
}
