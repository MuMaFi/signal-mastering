package dev.actest;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Schreibt jede Zustandsaenderung als CSV-Zeile mit.
 *
 * <p>Der Zeitstempel ist der eigentliche Zweck: damit laesst sich im Nachhinein
 * zuordnen, welcher Eintrag im Anti-Cheat-Log zu welchem Testschritt gehoert.
 */
public final class TestLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static Path file;

    private TestLog() {
    }

    private static synchronized Path file() {
        if (file == null) {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("anticheat-test-logs");
            try {
                Files.createDirectories(dir);
                file = dir.resolve("session-" + LocalDateTime.now().format(FILE_STAMP) + ".csv");
                Files.writeString(file, "zeit,server,modul,aktion,intensitaet,position\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                AntiCheatTestClient.LOGGER.warn("Protokolldatei konnte nicht angelegt werden", e);
            }
        }
        return file;
    }

    public static void record(String server, String module, String action, double intensity, String position) {
        if (!TestConfig.get().writeCsvLog) {
            return;
        }
        Path target = file();
        if (target == null) {
            return;
        }
        String line = String.join(",",
                LocalDateTime.now().format(STAMP),
                escape(server),
                escape(module),
                escape(action),
                String.format(java.util.Locale.ROOT, "%.3f", intensity),
                escape(position)) + "\n";
        try {
            Files.writeString(target, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AntiCheatTestClient.LOGGER.warn("Protokollzeile konnte nicht geschrieben werden", e);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(",") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    public static Path currentFile() {
        return file();
    }
}
