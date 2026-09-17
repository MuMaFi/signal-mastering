package dev.actest.module;

import dev.actest.TestGate;
import dev.actest.TestLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Ein einzelnes Testverhalten.
 *
 * <p>Jedes Modul hat eine Intensitaet mit definiertem Bereich. Der Sinn ist nicht
 * "erkannt ja/nein", sondern ab welchem Wert der Anti-Cheat anschlaegt.
 */
public abstract class TestModule {

    private final String id;
    private final String description;
    private final double min;
    private final double max;
    private final double defaultIntensity;
    private final String unit;

    private boolean enabled;
    private double intensity;

    protected TestModule(String id, String description, double min, double max, double defaultIntensity, String unit) {
        this.id = id;
        this.description = description;
        this.min = min;
        this.max = max;
        this.defaultIntensity = defaultIntensity;
        this.intensity = defaultIntensity;
        this.unit = unit;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double defaultIntensity() {
        return defaultIntensity;
    }

    public String unit() {
        return unit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double intensity() {
        return intensity;
    }

    public void setIntensity(double value, Minecraft mc) {
        this.intensity = Math.clamp(value, min, max);
        if (enabled) {
            log(mc, "intensitaet");
        }
    }

    public void setEnabled(boolean value, Minecraft mc) {
        if (this.enabled == value) {
            return;
        }
        this.enabled = value;
        if (value) {
            onEnable(mc);
        } else {
            onDisable(mc);
        }
        log(mc, value ? "an" : "aus");
    }

    private void log(Minecraft mc, String action) {
        LocalPlayer player = mc.player;
        String position = player == null ? "" : String.format(java.util.Locale.ROOT, "%.1f/%.1f/%.1f",
                player.getX(), player.getY(), player.getZ());
        TestLog.record(TestGate.currentAddress(mc), id, action, intensity, position);
    }

    /** Wird jeden Client-Tick aufgerufen, solange das Modul aktiv und freigegeben ist. */
    public abstract void tick(Minecraft mc, LocalPlayer player);

    /** Zustand sichern, wenn das Modul eingeschaltet wird. */
    public void onEnable(Minecraft mc) {
    }

    /** Aufraeumen, wenn das Modul abgeschaltet wird. */
    public void onDisable(Minecraft mc) {
    }
}
