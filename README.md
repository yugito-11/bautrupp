# Bautrupp

Werkzeug für Gruppenprojekte in Minecraft: Aufgaben verteilen, Material sammeln, Lager-Kisten zählen,
Koordinaten teilen und Schematics in Aufgaben umwandeln. Besteht aus zwei Teilen, die sich eine
gemeinsame Firebase Realtime Database teilen:

| Teil | Ordner | Was |
|---|---|---|
| Mod | [`mod/`](mod) | Client-Mod für **Minecraft 26.3 mit Fabric** (nur bei denen nötig, die mitmachen) |
| Website | [`website/`](website) | Eine einzelne `index.html` – z. B. bei Netlify per Drag & Drop hosten |

Einrichtung, Bedienung und alle Befehle stehen in der [ANLEITUNG.md](ANLEITUNG.md).

## Funktionen (Auswahl)

- Aufgaben mit Status (Offen / In Arbeit / Fertig), mehreren Leuten, Schritten, Kommentaren und Reihenfolge
- Ziele: Sammeln, Abbauen, Platzieren, Sammeln + Platzieren – zählt die Mod automatisch mit
- Bereiche für Aufgaben und Projekte, Zählung „was dort steht“ (Bestand)
- Lager-Kisten, Materialliste aus den Aufgaben, Suche und Sortierung
- Schematic-Import (`.litematic`, `.schem`, `.nbt`) nach Bauteilen, Material oder Ebenen, mit Bauplan-Zählung
- Projekte mit Spielern und Bereich, Admin-Passwort, Discord-Webhook, Verlauf
- Anzeige der eigenen Aufgaben am Bildschirmrand mit Wegweiser

## Mod bauen

Benötigt **JDK 25**.

```bash
cd mod
./gradlew build
```

Die fertige Datei liegt danach in `mod/build/libs/bautrupp-<version>.jar`.

## Website

`website/index.html` ist komplett eigenständig (keine Abhängigkeiten, kein Build).
Datenbank-Adresse und Gruppencode werden beim ersten Öffnen eingegeben und nur im Browser gespeichert.
