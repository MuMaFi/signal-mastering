package dev.actest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.actest.module.Modules;
import dev.actest.module.TestModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Einstiegspunkt des Testmods.
 *
 * <p>Der Mod bildet typische Cheat-Verhalten nach, um zu pruefen, ob der eigene
 * Anti-Cheat sie erkennt. Er ist bewusst laut: solange etwas aktiv ist, steht es
 * im Bildschirmtext, und jede Aenderung landet im CSV-Protokoll. Verschleierung
 * ist nicht eingebaut, weil sie fuer den Zweck nichts beitraegt.
 */
public final class AntiCheatTestClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("anticheat-test");

    private static final ScenarioRunner SWEEP = new ScenarioRunner();

    private KeyMapping panicKey;
    private boolean warnedAboutGate;

    public static ScenarioRunner sweep() {
        return SWEEP;
    }

    @Override
    public void onInitializeClient() {
        TestConfig.get();
        TestCommands.register();

        panicKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.actest.panic",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_DELETE,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);

        LOGGER.info("Anti-Cheat-Testmod geladen. Freigegebene Server: {}",
                TestConfig.get().allowedServers);
    }

    private void onEndTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        while (panicKey.consumeClick()) {
            disableAll(mc);
        }

        if (!TestGate.isAllowed(mc)) {
            enforceGate(mc, player);
            return;
        }
        warnedAboutGate = false;

        SWEEP.tick(mc);

        List<String> active = new ArrayList<>();
        for (TestModule module : Modules.all()) {
            if (!module.isEnabled()) {
                continue;
            }
            try {
                module.tick(mc, player);
            } catch (RuntimeException e) {
                LOGGER.error("Modul {} hat einen Fehler ausgeloest, wird abgeschaltet", module.id(), e);
                module.setEnabled(false, mc);
                continue;
            }
            active.add(module.id() + " " + trim(module.intensity()));
        }

        if (!active.isEmpty()) {
            player.sendOverlayMessage(Component.literal("AC-TEST AKTIV: " + String.join("  ", active)));
        }
    }

    /** Auf nicht freigegebenen Servern bleibt nichts aktiv. */
    private void enforceGate(Minecraft mc, LocalPlayer player) {
        boolean somethingWasOn = false;
        for (TestModule module : Modules.all()) {
            if (module.isEnabled()) {
                module.setEnabled(false, mc);
                somethingWasOn = true;
            }
        }
        SWEEP.stop(mc, "Server nicht freigegeben");

        if ((somethingWasOn || !warnedAboutGate)) {
            warnedAboutGate = true;
            player.sendSystemMessage(Component.literal("[AC-Test] " + TestGate.denialReason(mc)));
        }
    }

    public static void disableAll(Minecraft mc) {
        SWEEP.stop(mc, "abgebrochen");
        for (TestModule module : Modules.all()) {
            module.setEnabled(false, mc);
        }
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[AC-Test] Alle Module aus."));
        }
    }

    public static String trim(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
