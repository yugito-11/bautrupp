# Bautrupp

Werkzeug für Gruppenprojekte in Minecraft: Aufgaben verteilen, Material sammeln, Lager-Kisten zählen,
Koordinaten teilen und Schematics in Aufgaben umwandeln. Besteht aus zwei Teilen, die sich eine
gemeinsame Firebase Realtime Database teilen:

| Teil | Ordner | Was |
|---|---|---|
| Mod | [`mod/`](mod) | Client-Mod für **Minecraft 26.3 mit Fabric** (nur bei denen nötig, die mitmachen) |
| Website | [`website/`](website) | Eine einzelne `index.html` – z. B. bei Netlify per Drag & Drop hosten |

## Mod herunterladen

**[Neueste Version herunterladen](https://github.com/yugito-11/bautrupp/releases/latest)** (Minecraft 26.3, Fabric) –
dort unter „Assets“ die `bautrupp-….jar`. In Modrinth: Profil → „Inhalt hinzufügen“ → Datei auswählen.
Ab Version 2.15 aktualisiert sich die Mod danach selbst.

Einrichtung, Bedienung und alle Befehle stehen in der [ANLEITUNG.md](ANLEITUNG.md).

## Funktionen (Auswahl)

- Aufgaben mit Status (Offen / In Arbeit / Fertig), mehreren Leuten, Schritten, Kommentaren und Reihenfolge
- Ziele: Sammeln, Abbauen, Platzieren, Sammeln + Platzieren – zählt die Mod automatisch mit
- Bereiche für Aufgaben und Projekte, Zählung „was dort steht“ (Bestand)
- Lager-Kisten, Materialliste aus den Aufgaben, Suche und Sortierung
- Schematic-Import (`.litematic`, `.schem`, `.nbt`) nach Bauteilen, Material oder Ebenen, mit Bauplan-Zählung
- Projekte mit Spielern und Bereich, Admin-Passwort, Discord-Webhook, Verlauf
- Anzeige der eigenen Aufgaben am Bildschirmrand mit Wegweiser
- Automatische Updates der Mod über GitHub-Releases

## Mod bauen

Benötigt **JDK 25**.

```bash
cd mod
./gradlew build
```

Die fertige Datei liegt danach in `mod/build/libs/bautrupp-<version>.jar`.

**Neue Version veröffentlichen:** `version=` in `mod/gradle.properties` erhöhen, in der `ANLEITUNG.md` einen
Abschnitt `### Neu in <x.y>` schreiben und hochladen. Die GitHub Action baut die Mod und legt das Release an.

## Website

`website/index.html` ist komplett eigenständig (keine Abhängigkeiten, kein Build).
Datenbank-Adresse und Gruppencode werden beim ersten Öffnen eingegeben und nur im Browser gespeichert.
