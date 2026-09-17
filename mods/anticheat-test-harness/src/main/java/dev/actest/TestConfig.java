package dev.actest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Konfiguration des Testmods.
 *
 * <p>Das wichtigste Feld ist {@link #allowedServers}. Die Testmodule greifen nur auf
 * Servern, die hier eingetragen sind. Damit bleibt das Werkzeug an die eigene
 * Testumgebung gebunden und ist kein beliebig einsetzbarer Client.
 */
public final class TestConfig {

    /** Adressen, auf denen Testmodule aktiv werden duerfen (ohne Port). */
    public List<String> allowedServers = new ArrayList<>(List.of("localhost", "127.0.0.1"));

    /** Einzelspieler-Welten gelten immer als eigene Testumgebung. */
    public boolean allowSingleplayer = true;

    /** Jede Modulaktivierung zusaetzlich in den Chat schreiben. */
    public boolean announceInChat = true;

    /** CSV-Protokoll fuer den Abgleich mit den Anti-Cheat-Logs schreiben. */
    public boolean writeCsvLog = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static TestConfig instance;

    public static TestConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("anticheat-test.json");
    }

    private static TestConfig load() {
        Path file = path();
        if (Files.exists(file)) {
            try {
                TestConfig cfg = GSON.fromJson(Files.readString(file), TestConfig.class);
                if (cfg != null) {
                    if (cfg.allowedServers == null) {
                        cfg.allowedServers = new ArrayList<>();
                    }
                    return cfg;
                }
            } catch (IOException | RuntimeException e) {
                AntiCheatTestClient.LOGGER.warn("Konfiguration nicht lesbar, nutze Standardwerte", e);
            }
        }
        TestConfig cfg = new TestConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            AntiCheatTestClient.LOGGER.warn("Konfiguration konnte nicht gespeichert werden", e);
        }
    }

    public boolean isServerAllowed(String address) {
        if (address == null) {
            return false;
        }
        String host = normalise(address);
        for (String allowed : allowedServers) {
            if (normalise(allowed).equals(host)) {
                return true;
            }
        }
        return false;
    }

    /** Schneidet den Port ab und vereinheitlicht Gross-/Kleinschreibung. */
    static String normalise(String address) {
        String host = address.trim().toLowerCase(Locale.ROOT);
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        return host;
    }
}
