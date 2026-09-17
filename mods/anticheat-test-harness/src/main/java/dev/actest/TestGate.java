package dev.actest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Prueft, ob auf der aktuellen Verbindung getestet werden darf.
 *
 * <p>Ohne Freigabe bleibt jedes Modul wirkungslos. Der eigene Testserver wird
 * einmalig in die Konfiguration eingetragen oder mit {@code /actest allow} ergaenzt.
 */
public final class TestGate {

    private TestGate() {
    }

    public static boolean isAllowed(Minecraft mc) {
        TestConfig cfg = TestConfig.get();
        ServerData server = mc.getCurrentServer();
        if (server == null) {
            // Keine Serververbindung: Einzelspieler oder LAN-Welt.
            return cfg.allowSingleplayer;
        }
        return cfg.isServerAllowed(server.ip);
    }

    /** Adresse der aktuellen Verbindung, fuer Protokoll und Meldungen. */
    public static String currentAddress(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        return server == null ? "singleplayer" : server.ip;
    }

    public static String denialReason(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server == null) {
            return "Einzelspieler ist in der Konfiguration nicht freigegeben.";
        }
        return "Server '" + server.ip + "' steht nicht in allowedServers. "
                + "Mit '/actest allow' freigeben, wenn es der eigene Testserver ist.";
    }
}
