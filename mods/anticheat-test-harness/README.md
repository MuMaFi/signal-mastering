# Anti-Cheat Test Harness (Minecraft 26.2, Fabric)

Client-Mod, die typische Cheat-Verhalten nachbildet, damit du messen kannst, was
dein Anti-Cheat erkennt — und ab welcher Staerke. Gedacht fuer den eigenen
Testserver vor dem Start.

## Bewusste Einschraenkungen

Das Werkzeug ist als Pruefstand gebaut, nicht als Client:

- **Server-Freigabe.** Module wirken nur auf Adressen in `allowedServers`.
  Auf jedem anderen Server schaltet sich alles selbst ab. Standard ist
  `localhost` und `127.0.0.1`.
- **Sichtbar.** Solange etwas aktiv ist, steht es im Bildschirmtext.
- **Protokolliert.** Jede Aenderung landet mit Zeitstempel in einer CSV.
- **Keine Verschleierung.** Nichts ist darauf ausgelegt, einer Erkennung zu
  entgehen — das wuerde dem Zweck widersprechen.

## Voraussetzungen

| | |
|---|---|
| Minecraft | 26.2 |
| Java | **25** (26.x setzt das voraus) |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.160.0+26.2 |

## Bauen

```bash
./gradlew build
```

Das Ergebnis liegt in `build/libs/anticheat-test-harness-1.0.0.jar`.

Der Build laedt Minecraft, die Vanilla-Bibliotheken und Fabric direkt ueber
SHA1-gepinnte URLs (`minecraft-libraries.txt`, `fabric-libraries.txt`). Er
braucht weder Maven Central noch Fabric Loom — warum, steht unter
[Hinweise zu 26.2](#hinweise-zu-262).

## Einrichten

1. Jar nach `.minecraft/mods/` legen (zusammen mit Fabric API).
2. Auf deinen Testserver verbinden.
3. `/actest allow` — traegt die aktuelle Adresse in die Freigabeliste ein.

Konfiguration: `.minecraft/config/anticheat-test.json`
Protokolle: `.minecraft/anticheat-test-logs/session-*.csv`

## Befehle

| Befehl | Wirkung |
|---|---|
| `/actest list` | Module, Wertebereiche und Zustand |
| `/actest status` | Verbindung, Freigabe, Pfad zum Protokoll |
| `/actest <modul> on\|off` | Modul schalten |
| `/actest <modul> set <wert>` | Intensitaet setzen |
| `/actest <modul> sweep <von> <bis> <schritt> <sekunden>` | Intensitaet stufenweise hochfahren |
| `/actest stop` | Alles aus |
| `/actest allow` / `deny` | Aktuelle Adresse freigeben oder sperren |

`Entf` schaltet jederzeit alles ab.

## Module

| Modul | Intensitaet | Was der Server sehen sollte |
|---|---|---|
| `antikb` | Anteil 0–1 | Rueckstoss nach Treffern bleibt aus |
| `speed` | Faktor 1–5 | Zu schnelle waagerechte Bewegung |
| `fly` | Steigen 0–1 | Flug ohne serverseitige Freigabe |
| `nofall` | Fallrate 0–2 | Bodenkontakt gemeldet, obwohl in der Luft |
| `reach` | Bloecke 3–8 | Treffer ausserhalb der Reichweite |
| `fastbreak` | Durchlaeufe 1–10 | Bloecke brechen zu schnell |
| `autoclick` | Klicks/s 1–30 | Gleichmaessig hohe Klickrate |
| `timer` | Pakete/Tick 0–10 | Mehr Bewegungspakete als 20/s |

## Sinnvoll testen

Der interessante Wert ist nicht „erkannt ja/nein", sondern **ab wann**. Dafuer
ist `sweep` da:

```
/actest speed sweep 1.1 3.0 0.1 10
```

Faehrt den Faktor in Zehntelschritten hoch, je zehn Sekunden, und schreibt jeden
Schritt ins Protokoll. Danach gleichst du die Zeitstempel mit dem Anti-Cheat-Log
ab und siehst, bei welchem Wert er angeschlagen hat.

Ein paar Dinge, die das Ergebnis sonst verfaelschen:

- **Nicht auf localhost messen.** Viele Anti-Cheats rechnen Latenz in ihre
  Toleranzen ein. Ohne Latenz bekommst du Schwellen, die im Betrieb nicht
  gelten. Teste ueber die Adresse, die auch Spieler nutzen.
- **`antikb` und `reach` brauchen ein Gegenueber.** Ein zweiter Account oder ein
  Mob — ohne Treffer passiert nichts.
- **Auf die Reaktion achten, nicht nur auf die Erkennung.** Kick, Zurueckziehen,
  stilles Mitschreiben? Und wie lange dauert es?
- **Die Gegenprobe nicht vergessen.** Lass jemanden ganz normal spielen, mit
  schlechter Verbindung. Ein Anti-Cheat, der bei `speed 1.2` auslaest, aber auch
  bei 300 ms Ping ehrliche Spieler kickt, ist kein guter Tausch.

## Hinweise zu 26.2

Beim Bauen dieses Mods sind drei Dinge aufgefallen, die sich gegenueber 1.21.x
geaendert haben und beim Modding auf 26.x relevant sind:

1. **Minecraft 26.x wird unobfuskiert ausgeliefert.** Das Client-Jar enthaelt
   rund 10.400 lesbare `net.minecraft.*`-Klassen. Deshalb veroeffentlicht Mojang
   keine `client_mappings` mehr (1.21.8 hat sie noch, 26.1/26.2/26.3 nicht), und
   Yarn endet bei Snapshot `25w46a`. Remapping ist gegenstandslos — dieser Build
   uebersetzt direkt gegen das Vanilla-Jar.
2. **Java 25 ist Pflicht**, sowohl zur Laufzeit als auch zum Bauen.
3. **`ResourceLocation` heisst jetzt `Identifier`**, und einige Fabric-APIs sind
   umbenannt (`ClientCommandManager` → `ClientCommands`, Keybinds liegen in
   `fabric-key-mapping-api-v1`).

Fabric Loom 1.18.2 kennt unobfuskiertes Minecraft grundsaetzlich
(`NoRemapMappingConfiguration`), erwartet dafuer aber ein `annotations`-Artefakt,
das ich fuer 26.2 nicht finden konnte. Falls du den Mod spaeter in ein
Loom-Projekt ueberfuehren willst, ist das der offene Punkt; fuer das Bauen des
Jars wird Loom hier nicht gebraucht.
