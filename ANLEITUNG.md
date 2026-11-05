# Bautrupp – Anleitung

Bautrupp ist eine Fabric-Mod für Minecraft 26.3. Nur wer sie installiert, hat sie.
Alle mit der Mod teilen sich Aufgaben, eine Materialliste und Koordinaten.
Wer die Mod nicht hat, merkt davon nichts.

## 1. Mod installieren (jeder, der mitmachen will)

1. Modrinth App öffnen und euer 26.3-Fabric-Profil auswählen.
2. Prüfen, ob **Fabric API** installiert ist. Falls nicht: in Modrinth nach „Fabric API“ suchen und installieren.
3. `bautrupp-2.15.0.jar` ins Profil holen: entweder über „Inhalt hinzufügen“ → Datei auswählen,
   oder Profil-Ordner öffnen und die Datei in den Ordner `mods` ziehen.
4. Spiel starten.

## 2. Gemeinsame Datenbank einrichten (nur EINER aus der Gruppe, einmalig, ca. 10 Minuten)

Die Mod speichert eure Liste in einer kostenlosen Firebase-Datenbank von Google.
Dafür braucht man ein Google-Konto.

1. Auf https://console.firebase.google.com gehen und **Projekt erstellen** klicken.
   Name z. B. `bautrupp`. Google Analytics kann man ausschalten.
2. Links im Menü unter **Erstellen** (Build) auf **Realtime Database** klicken, dann **Datenbank erstellen**.
3. Als Standort **Belgien (europe-west1)** wählen. Dann **Im gesperrten Modus starten**.
4. Oben auf den Reiter **Regeln** gehen, alles löschen und das hier einfügen:

   ```json
   {
     "rules": {
       "gruppen": {
         "$gruppe": {
           ".read": true,
           ".write": true
         }
       }
     }
   }
   ```

   Dann auf **Veröffentlichen** klicken. Firebase zeigt eine Warnung, dass die Regeln offen sind.
   Das ist hier gewollt: Lesen und schreiben kann nur, wer Adresse **und** Gruppencode kennt.
5. Zurück auf den Reiter **Daten**. Oben steht die Adresse, z. B.
   `https://bautrupp-1a2b3-default-rtdb.europe-west1.firebasedatabase.app`.
   Diese Adresse an alle in der Gruppe schicken.

## 3. In Minecraft verbinden (jeder)

1. In einer Welt oder auf einem Server **J** drücken.
2. Im Reiter **Einstellungen** die Datenbank-Adresse einfügen (Strg+V) und einen **Gruppencode** eintragen.
   Alle nehmen denselben Code. Nehmt etwas Schwer-zu-Erratendes, z. B. `drachenburg-4821`.
3. **Speichern** klicken. Unten steht dann grün „Verbunden“.

## Bedienung

**J** öffnet das Menü (die Taste kann man unter Optionen → Steuerung ändern).

- **Aufgaben:** Text eingeben, dann **+** (ohne Ort) oder **+ hier** (mit deiner Position).
  Der erste Knopf ändert den Status (Offen → In Arbeit → Fertig).
  Der Knopf mit dem Namen gibt die Aufgabe dem nächsten Spieler; einfach so oft klicken, bis der richtige Name dasteht.
  „Alle/Meine“ filtert auf deine eigenen Aufgaben.
- **Material:** Item und Anzahl in Stück eintragen. Mit -64 / +1 / +64 zählt ihr hoch, was gesammelt ist.
  Die Mod rechnet aus, wie viele Stacks und Shulker noch fehlen.
- **Orte:** Namen eingeben und **Hier speichern**. Bei Oberwelt-Orten steht dabei, wo das Nether-Portal hin muss.
  **Kopieren** legt die Koordinaten in die Zwischenablage.

**Sammel-Aufgaben:** Trag im Menü bei „Anz.“ eine Zahl ein, nimm das Item in die Hand und klick auf **+**.
Oder schreib `/bt sammeln 640` (Item in der Hand) bzw. `/bt sammeln 640 dirt`.
Die Mod zählt bei dem Spieler, dem die Aufgabe gehört, wie viele davon er dabei hat, auch in Shulker-Kisten.
Sobald genug da ist, wird die Aufgabe automatisch fertig. Auf der Webseite sieht man den Fortschritt live.
Auf der Webseite gibt man das Item mit seinem englischen Namen an, z. B. `dirt` oder `oak_log`.

**Abbauen und Platzieren:** Im Menü mit dem Knopf links neben „Anz.“ zwischen Sammeln, Abbauen und Platzieren wechseln.
Oder `/bt abbauen 500 stone` bzw. `/bt platzieren 200 stone_bricks`. Gezählt wird über die Spiel-Statistik,
erst ab dem Moment, in dem die Aufgabe dem Spieler gehört. Wechselt man den Server oder die Welt, zählt die Mod dort weiter,
ohne die alte Zahl zu verlieren.

**Nur an der Baustelle zählen:** Leg die Aufgabe mit **+ hier** statt **+** an (oder hänge `hier` an den Befehl,
z. B. `/bt platzieren 200 stone_bricks hier 40`). Dann zählen nur Blöcke im Umkreis von 32 Blöcken
(bzw. der angegebenen Zahl) um die Stelle, an der du beim Anlegen stehst, in derselben Dimension.
Ohne „hier“ zählt jeder Block, egal wo.

**Anzeige am Bildschirmrand:** Im Spiel siehst du links deine offenen Aufgaben (die in Arbeit zuerst, gelb).
Haben sie Koordinaten, steht dahinter, wie weit sie entfernt sind. Mit `/bt anzeige rechts` kommt die Liste
nach rechts, mit `/bt anzeige aus` verschwindet sie. Das geht auch im Menü unter Einstellungen.

In der Spielerliste tauchen alle auf, die die Mod mit eurem Gruppencode benutzen,
und alle, denen schon eine Aufgabe gegeben wurde.

Wenn dir jemand eine Aufgabe gibt, bekommst du eine Nachricht im Chat (nur du siehst sie).

## Neu in Version 2.0

### Projekte
Aufgaben, Material und Lager-Kisten lassen sich in Projekte aufteilen (z. B. „Rathaus“, „Weizenfarm“).
- **Webseite:** oben bei „Projekt“ auswählen oder ein neues anlegen. „Alle Projekte“ zeigt alles.
- **Mod:** Knopf oben rechts im Menü (J) wechselt das Projekt, **+** daneben legt ein neues an. Per Befehl: `/bt projekt neu Rathaus`,
  auswählen: `/bt projekt Rathaus`, alles zeigen: `/bt projekt alle`.
- Neue Aufgaben und neues Material landen im gerade gewählten Projekt.
- Wird ein Projekt gelöscht (Admin oder wer es angelegt hat), bleiben die Aufgaben erhalten, nur ohne Projekt.

### Lager-Kiste
1. Stell eine Kiste (oder ein Fass, eine Shulker-Kiste) als Projekt-Lager hin.
2. Öffne sie. Oben links erscheint der Knopf **„Bautrupp: Als Lager festlegen“**. Klicken.
3. Ab jetzt zählt die Mod bei **jedem Öffnen** durch jeden mit der Mod, was drin liegt,
   auch was in Shulker-Kisten in der Kiste steckt, und speichert es.
4. In der Materialliste steht dann bei passenden Items **„aus Lager“**, und die Menge wird automatisch eingetragen.
   Niemand muss mehr von Hand zählen.

Damit ein Material zum Lager passt, muss klar sein, welches Item gemeint ist. Dafür einfach den
**Namen wie im Spiel** eintragen, z. B. „Diamantblock“ (Englisch geht auch). Das Item muss man nicht dabeihaben.
- In der Mod: Namen ins Feld schreiben und **+** klicken. Unten steht dann „Erkannt: Diamantblock“.
  Alternativ **„+ Hand“** mit dem Item in der Hand oder `/bt material 1728 diamond_block` (mit Vorschlägen beim Tippen).
- Auf der Webseite: beim Tippen erscheinen Vorschläge, einfach einen auswählen.
Namen, die kein Item sind (z. B. „Holz“, weil es viele Holzarten gibt), werden weiter von Hand gezählt.

**Mehrere Kisten:** Ihr könnt so viele Lager-Kisten festlegen, wie ihr wollt. Ihre Inhalte werden zusammengezählt.

Auch bei Zielen (Sammeln/Abbauen/Platzieren) reicht im Menü jetzt der Name im Feld, z. B. „Erde“ und bei Anz. „640“.

Gut zu wissen: Gezählt wird nur, wenn jemand mit der Mod die Kiste öffnet. Was ein Trichter
hineinschiebt, steht erst nach dem nächsten Öffnen in der Liste.
Lager ohne Projekt zählen für alle Projekte.

### Details: Schritte und Kommentare
- **Webseite:** auf der Karte auf **„Details“** klicken. Dort Schritte anlegen und abhaken und Kommentare schreiben.
- **Mod:** in der Aufgabenliste auf **„...“** klicken. Links Schritte, rechts Kommentare.
- Schritte abhaken und kommentieren darf jeder. Schritte löschen: wer die Aufgabe angelegt hat, oder der Admin.
  Kommentare löschen: wer ihn geschrieben hat, oder der Admin.

### Mehrere Leute pro Aufgabe
Eine Aufgabe kann mehreren gehören.
- **Admin:** in der Mod auf den Namens-Knopf klicken → Liste aller Spieler, anklicken zum Ein-/Austragen.
  Auf der Webseite über „+ Person“ hinzufügen und mit × austragen.
- **Alle anderen:** sich selbst dazunehmen („Mitmachen“) oder austragen. Andere einteilen kann nur der Admin.
- **Ziele zählen gemeinsam:** Bei „640 Erde sammeln“ mit Anna und Ben zählt die Mod, was Anna dabeihat,
  plus was Ben dabeihat. Auf der Webseite steht, wer wie viel beigetragen hat (z. B. „Anna 300 · Ben 200“).
  Bei Abbauen/Platzieren zählt jeder ab dem Moment, in dem er dazukommt.

### Ziele: Lager, Verbautes und Bereiche (neu in 2.4)
- **Schneller:** Die Mod zählt jetzt jede halbe Sekunde und schickt Änderungen spätestens nach einer Sekunde.
- **Sammeln + Lager:** Bei Sammel-Aufgaben zählt auch, was in den Lager-Kisten des Projekts liegt
  (und in Kisten ohne Projekt). Abschaltbar pro Aufgabe: Details → „Lager zählt mit“.
- **Sammeln + Verbautes:** Gibt es im selben Projekt eine Platzieren-Aufgabe mit demselben Block,
  zählt das schon Verbaute beim Sammeln mit. Beispiel: „640 Steinziegel sammeln“ und „640 Steinziegel
  platzieren“ im Projekt Rathaus. Sind 200 verbaut, müssen nur noch 440 gesammelt werden.
  Anzeige z. B. „600 / 640: Anna 100 · Lager 300 · verbaut 200“.
- **Bereich für Abbauen/Platzieren:**
  - Mod: Details („...“) → „Ecke 1 hier“ an einer Ecke, „Ecke 2 hier“ an der gegenüberliegenden.
    Stehen die Ecken auf verschiedener Höhe, zählt nur zwischen diesen Höhen.
    „Umkreis 32 hier“ für einen Kreis, „Überall“ entfernt den Bereich.
  - Webseite: Details → Rechteck (zwei Ecken, Höhe optional) oder Umkreis mit beliebiger Größe.
  - Ändern dürfen: wer die Aufgabe hat, wer sie angelegt hat, oder der Admin.

### Bestand im Bereich (neu in 2.5)
Hat eine Abbauen- oder Platzieren-Aufgabe einen Bereich, zählt die Mod standardmäßig den **Bestand**:
- **Platzieren:** wie viele der Blöcke gerade im Bereich stehen, egal wer sie gesetzt hat (auch ohne Mod)
  und wann. Wird einer abgebaut, geht die Zahl runter.
- **Abbauen:** Beim ersten Zählen merkt sich die Mod, wie viel im Bereich ist. Fortschritt = wie viel davon weg ist.
  „Anfang neu zählen“ setzt den Anfang auf das, was bei der nächsten Zählung dort steht.
- Gezählt wird alle 5 Sekunden, aber nur, wenn jemand mit der Mod so nah ist, dass der ganze Bereich geladen ist.
  Sonst bleibt der letzte Stand stehen („gezählt vor … von …“).
- Umschalten auf „Platzierungen“ (alte Zählweise: nur was Leute mit der Mod setzen/abbauen):
  Mod → Details → „Zählt: …“, Webseite → Details → „Zählt“.

### Kombi-Aufgabe „Sammeln + Platzieren“ (neu in 2.6)
Eine Aufgabe, zwei Fortschritte, z. B. „640 Steinziegel sammeln und verbauen“:
- **Gesammelt:** Inventare der Eingetragenen + Lager-Kisten + was schon verbaut ist.
- **Verbaut:** Bestand im Bereich (mit Bereich) oder was die Eingetragenen setzen (ohne Bereich).
- **Fertig**, sobald alles verbaut ist.
- Anlegen: Mod → Knopf neben „Anz.“ auf „Samm.+Bau“ stellen, oder `/bt kombi 640 stone_bricks`.
  Webseite → Ziel „Sammeln + Platzieren“.

### Anzeige verschieben und vergrößern (neu in 2.6)
Menü (J) → Einstellungen → **Verschieben** (oder `/bt anzeige bearbeiten`):
- Kasten mit der Maus ziehen = verschieben
- grüne Ecke unten rechts ziehen = breiter/schmaler
- Mausrad oder „Größer/Kleiner“ = alles größer/kleiner
- „Zeilen +/-“ = wie viele Aufgaben angezeigt werden; „Zurücksetzen“ = wie am Anfang
Lange Aufgabennamen werden in der Anzeige umgebrochen (bis zu 3 Zeilen). Im Menü zeigt die Maus
über einem abgeschnittenen Namen den ganzen Namen an.

### Neu in 2.7

**Mehrere Ziele pro Aufgabe:** z. B. „Mauer“ mit 500 Steinziegeln + 80 Treppen + 40 Stufen.
Mod: Details („...“) → „Weiteres Ziel“: Name, Anzahl, Art, +. Webseite: Details → „Weiteres Ziel“.
Jedes Ziel hat einen eigenen Balken; die Aufgabe ist fertig, wenn alle Ziele fertig sind.
Weitere Ziele übernehmen Leute, Projekt, Bereich und Zählmodus der Aufgabe.

**Materialliste aus Aufgaben:** Sammeln-, Platzieren- und Kombi-Ziele erscheinen automatisch in der
Materialliste („aus Aufgaben“): benötigt, im Lager, schon verbaut und was noch fehlt (auch in Stacks).
Trägt man dasselbe Item von Hand ein, gilt der eigene Eintrag.

**Wegweiser:** In der Anzeige am Bildschirmrand steht ein Pfeil zur Baustelle mit Entfernung (z. B. „↗ 120m“).
Ausschalten: Menü → Einstellungen → „Wegweiser“.

**Reihenfolge (optional):** Details → „Erst nach“: die Aufgabe bleibt gesperrt (🔒), bis die gewählte fertig ist.
Gesperrte Aufgaben zählen noch nicht und stehen nicht in der Anzeige. Wird sie frei, kommt eine Meldung.

**Zwischenstände:** Bei 50 % und 75 % eines Ziels kommt eine Meldung im Chat (nur mit Mod) und auf Discord
(abschaltbar unter Discord → „Zwischenstände“).

**Wo liegt …?** `/bt suche Diamantblock` zeigt, in welchen Lager-Kisten wie viel liegt und wie weit weg.
Auf der Webseite: Material → Lager-Kisten → Suchfeld.

### Neu in 2.8

**Schematic importieren (Webseite):** Oben neben „Projekt“ auf **Schematic importieren** klicken und eine Datei wählen:
Litematica (`.litematic`), WorldEdit (`.schem`) oder Strukturblock (`.nbt`). Die Webseite zeigt vorher, wie groß
der Bau ist und welches Material gebraucht wird. Mit einem Klick entsteht ein neues Projekt:
- Eine Aufgabe pro Bauebene (Standard: 4 Blockreihen hoch, einstellbar von 1 bis „alles in einer Aufgabe“).
- Jede Blockart der Ebene ist ein Ziel mit der genauen Anzahl (Platzieren oder Sammeln + Platzieren).
- Optional „von unten nach oben“: die nächste Ebene ist gesperrt, bis die darunter fertig ist.
- Optional „Wo wird gebaut?“: die untere Ecke mit den kleinsten Koordinaten (X, Y, Z) eintragen. Dann zählt die
  Mod per Bestand, was in jeder Ebene wirklich steht, egal wer es gesetzt hat. Ist der Bau um 90° gedreht,
  „90° gedreht“ wählen.
- Türen und Betten zählen einmal, doppelte Stufen zweimal, Wandfackeln als Fackeln. Wasser, Luft und
  Strukturblöcke werden weggelassen.
Alte `.schematic`-Dateien (vor 1.13) gehen nicht; die in Litematica oder WorldEdit neu speichern.

**Material hängt an den Aufgaben:** Die Materialliste entsteht aus den Zielen. Bei jedem Ziel steht direkt,
was noch zu verbauen ist, wie viel im Lager liegt und was dem Projekt noch fehlt (rot) oder „genug da ✔“.
In der Materialliste steht bei jedem Item, für welche Aufgaben es gebraucht wird; ein Klick springt hin
(in der Mod über den „...“-Knopf).

**Suchen und Sortieren im Material:** Suchfeld (Englisch, Deutsch oder ID) und Sortierung nach Name,
„fehlt am meisten“, „wenigster Fortschritt“, „am meisten benötigt“ oder „am meisten im Lager“ – auf der
Webseite und in der Mod (Reiter Material).

**Englische Item-Namen als Standard:** Webseite und Mod zeigen Items jetzt auf Englisch. Umstellen:
Webseite → Einstellungen → „Item-Namen“; Mod → Einstellungen → „Item-Namen: Englisch / Spielsprache“.
Eintippen kann man immer beides (z. B. „Oak Planks“ oder „Eichenholzbretter“).

### Neu in 2.9: Schematic nach Bauteilen

Beim Schematic-Import schaut sich die Webseite den Bau jetzt an und teilt ihn in **Bauteile** statt in Ebenen
(„Aufteilen: Nach Bauteilen“, Standard):
- **Fundament** (unterste Lage), **Wände** (äußerer Rand), **Böden, Decken & Innenwände**, **Dach**
  (Treppen/Stufen und oberste Blöcke in der oberen Hälfte), **Zäune & Mauern**
- **Fenster** (Glas, Scheiben, Gitter), **Türen & Tore**, **Beleuchtung**, **Redstone**,
  **Einrichtung & Deko** (Betten, Kisten, Öfen, Teppiche ...), **Garten & Natur** (Erde, Gras, Blumen, Blätter ...)
- Sehr große Bauteile werden nach Höhe geteilt (einstellbar, Standard ab 1.000 Blöcken), z. B. „Wände 1/3“.
- Mit „In sinnvoller Reihenfolge freischalten“: Wände erst nach dem Fundament, Dach erst nach Böden/Decken,
  Fenster und Türen nach den Wänden, Licht und Einrichtung nach dem Dach.
- Die Vorschau zeigt alle Aufgaben mit Anzahl, Hauptmaterial und Reihenfolge, bevor etwas angelegt wird.
  Die Erkennung ist eine Schätzung; passt sie nicht, kann man weiter „Nach Höhe (Ebenen)“ wählen.

**Nach Material aufteilen:** „Aufteilen: Nach Material“ macht eine Aufgabe je Blockart (z. B. „Oak Stairs“ mit 78 Stück).
Was weiter unten verbaut wird, steht zuerst. Sehr große Mengen werden nach Höhe geteilt („Oak Planks 1/3“),
einstellbar über „Große Bauteile teilen ab“.

**Nur bestimmte Aufgaben – oder gar keine:** In der Vorschau hat jede Aufgabe einen Haken, dazu „Alle an“ und
„Alle aus“. Haken weg = diese Aufgabe wird nicht angelegt; ihr Material kommt trotzdem in die Materialliste
(„… ohne Aufgabe (aus Schematic)“, zählt zum Bedarf der Aufgaben dazu). „Erst nach“ springt automatisch auf die
Aufgabe davor. Alles aus (oder „Aufteilen: Keine Aufgaben, nur Materialliste“) = nur Projekt mit Materialliste.

**Zählen nach Bauplan:** Trägt man beim Import die Ecke der Baustelle ein, prüft die Mod Block für Block,
ob an den Stellen aus der Schematic das richtige Item steht. So zählen z. B. Eichenbretter in der Wand und
im Boden getrennt richtig. Die Drehung kann man angeben oder „erkennt die Mod“ lassen (sie probiert alle
vier Richtungen). Gezählt wird, sobald jemand mit der Mod in der Nähe ist und die Baustelle geladen ist.
Die Baupläne liegen in der Datenbank neben eurer Gruppe (`<Gruppencode>__bauplan`); an den Firebase-Regeln
muss nichts geändert werden.

### Neu in 2.10: Projekte Spielern zuteilen

Ist ein Projekt gewählt, steht oben neben „Projekt löschen“ **Spieler:**. Der Admin teilt dort Spieler zu
(„+ Spieler“) oder nimmt sie mit × wieder heraus; alle anderen können nur selbst „Mitmachen“ oder sich austragen.
- Zugeteilte Spieler werden bei allen offenen Aufgaben des Projekts eingetragen (und beim Herausnehmen ausgetragen).
- Neue Aufgaben im Projekt, bei denen niemand gewählt ist, bekommen automatisch die Spieler des Projekts.
- Beim Schematic-Import kann man unter „Wer baut mit?“ direkt Spieler für das neue Projekt anhaken.
- In der Mod zeigt der Projekt-Knopf (Maus drüber) die Spieler des Projekts.

### Neu in 2.11: Bereich für ein Projekt

Ein Projekt kann einen Bereich (die Baustelle) bekommen:
- **Webseite:** Projekt wählen → oben „Bereich festlegen“ → zwei Ecken (X / Z), Höhe optional. Nach „Anlegen“
  eines neuen Projekts geht das Formular gleich auf (kann man auch schließen).
- **Im Spiel:** Projekt wählen, an eine Ecke stellen → `/bt projekt ecke1`, zur gegenüberliegenden Ecke →
  `/bt projekt ecke2`. Entfernen: `/bt projekt bereich aus`.
- Abbauen-, Platzieren- und Kombi-Aufgaben des Projekts ohne eigenen Bereich (auch neue) zählen dann nur dort,
  standardmäßig per Bestand. Aufgaben ohne Ort bekommen ihn auch, damit der Wegweiser hinzeigt.
  Sammel-Aufgaben und Aufgaben mit eigenem Bereich oder Bauplan bleiben, wie sie sind.
- Ändert man den Bereich, ändert er sich bei diesen Aufgaben mit; sie werden neu gezählt.
- Beim Schematic-Import mit Koordinaten wird der Bereich des Bauwerks automatisch der Projekt-Bereich.

### Neu in 2.12: Automatisch „In Arbeit“

Eine offene Aufgabe mit Ziel springt von selbst auf **In Arbeit**, sobald es den ersten Fortschritt gibt:
Items im Inventar eines Eingetragenen, in einer Lager-Kiste (zählt beim Öffnen der Kiste), schon verbaute
Blöcke oder ein Teilziel, an dem gearbeitet wird. Dafür muss jemand mit der Mod online sein.
Gesperrte Aufgaben (Reihenfolge) bleiben offen, bis sie freigeschaltet sind.

### Neu in 2.15: Automatische Updates

Die Mod aktualisiert sich ab jetzt selbst:
- Beim Start von Minecraft sieht sie auf GitHub nach, ob es eine neuere Version gibt, und lädt sie herunter.
  Im Chat steht dann „Bautrupp … wurde heruntergeladen. Nach dem nächsten Neustart von Minecraft ist sie aktiv.“
- Beim **Beenden** von Minecraft wird die alte Datei im `mods`-Ordner durch die neue ersetzt
  (vorher geht das nicht, weil die Datei benutzt wird). Beim nächsten Start läuft die neue Version.
- Von Hand nachsehen: `/bt update`. Abschalten: Menü (J) → Einstellungen → „Updates: An/Aus“.
- Bis zum Tausch liegt die neue Datei als `bautrupp-….jar.neu` im `mods`-Ordner – die ignoriert Minecraft.
- Falls etwas schiefgeht: im `mods`-Ordner alle `bautrupp-…`-Dateien löschen und die gewünschte Version
  von https://github.com/yugito-11/bautrupp/releases neu hineinlegen (ältere Versionen gibt es dort auch).

### Neu in 2.14: Anzeige leichter verschieben

Öffnen: Menü (J) → Einstellungen → **Verschieben**, oder `/bt anzeige bearbeiten`. Dann:
- Kasten mit der Maus ziehen – oder einfach **irgendwo hinklicken**, der Kasten springt dorthin
- **Pfeiltasten** schieben ihn (mit Shift in großen Schritten)
- Knopf **„Position: …“** setzt ihn nacheinander links/rechts in die Mitte, nach oben oder nach unten
- grüne Ecke unten rechts ziehen = breiter/schmaler, Mausrad = größer/kleiner
- **Esc** oder **Fertig** speichert
Im Spiel ist der Kasten nur zu sehen, wenn dir offene Aufgaben zugeteilt sind (und F3 zu ist).

### Neu in 2.13: Warum zählt mein Ziel nicht?

- In den Details einer Aufgabe („...“) steht unter dem Ziel jetzt **„Zählung: …“**, z. B. „zählt nicht: niemand
  eingetragen“, „gesperrt, erst nach …“, „zählt, sobald die ganze Baustelle geladen ist (näher hingehen)“ oder
  „zählt nach Bauplan, zuletzt vor 3 s“.
- `/bt pruefen` listet alle offenen Ziele im gewählten Projekt mit Stand und Grund im Chat.
- Aufgaben mit vielen Zielen (z. B. aus einer Schematic) werden im Bereich jetzt deutlich schneller nacheinander
  gezählt (vorher 1 Sekunde Pause zwischen jedem Ziel).

So zählen gebaute Blöcke:
- **Mit Bereich oder Bauplan** (Bestand): zählt bei jedem mit der Mod, der in der Nähe ist – auch ohne eingetragen
  zu sein. Ab 2.14 merkt sich die Mod jeden Teil der Baustelle 15 Minuten lang; bei großen Baustellen (größer als die
  Sichtweite) einmal alles ablaufen, danach zählt es auch, wenn nur ein Teil geladen ist.
- **Ohne Bereich**: zählt nur bei Leuten, die bei der Aufgabe eingetragen sind, und erst ab dem Eintragen.
  Haben mehrere offene Ziele dasselbe Item, zählt ein gesetzter Block bei allen – dann besser mit Bereich arbeiten.

### Verlauf
Auf der Webseite gibt es den Reiter **Verlauf**: Wer hat wann was angelegt, übernommen, erledigt, abgehakt, kommentiert.
Gespeichert bleiben die letzten 300 Einträge.

### Meldungen im Spiel
Wenn jemand etwas ändert, steht es bei dir im Chat, z. B. „[Bautrupp] Ben hat "Brücke" erledigt“.
Diese Meldungen erzeugt **deine Mod auf deinem PC**. Es wird nichts in den Server-Chat geschickt,
deshalb sieht sie **nur, wer die Mod hat**. Meldungen zu deinen Aufgaben sind gelb.
Einstellbar im Menü unter Einstellungen → „Meldungen“ oder mit `/bt meldungen alle|meine|aus`.

## Discord

Änderungen landen automatisch in einem Kanal auf eurem Discord-Server, z. B. „**Ben** hat "Brücke" erledigt“.

**Einrichten (Admin, einmalig):**
1. In Discord: beim Kanal auf das Zahnrad → **Integrationen** → **Webhooks** → **Neuer Webhook**.
   Optional Namen und Bild ändern, dann **„Webhook-URL kopieren“**.
2. Auf der Webseite: **Einstellungen** → **Discord** → Adresse einfügen → **Speichern**.
3. **Testnachricht senden** klicken. Im Kanal sollte jetzt eine Nachricht stehen.

Mit den Häkchen legt ihr fest, was gepostet wird (neue Aufgaben, erledigt, in Arbeit, übernommen,
Kommentare, Schritte, Material komplett, gelöscht, Projekte, Lager-Kisten). Wer etwas ändert, schickt die
Nachricht selbst, aus der Mod oder von der Webseite. Ein eigener Server ist nicht nötig.
Erwähnungen wie @everyone in Aufgabentexten pingen niemanden an.

**Absendername:** Gehört die Änderung zu einem Projekt, heißt der Absender im Kanal wie das Projekt
(z. B. „Rathaus“), sonst „Bautrupp“. Discord verbietet ein paar Dinge im Namen (z. B. das Wort „Discord“, @ oder #),
die werden dabei weggelassen.

Hinweis: Die Webhook-Adresse liegt in eurer Datenbank. Wer die Datenbank-Adresse kennt, könnte sie
auslesen und in den Kanal schreiben. Falls das passiert: In Discord den Webhook löschen und einen neuen anlegen.

## Admin

Solange kein Admin-Passwort festgelegt ist, darf jeder alles. Sobald einer eins festlegt
(Menü → Einstellungen → Admin, oder auf der Webseite unter Einstellungen), gilt:

| | Admin | Alle anderen |
|---|---|---|
| Aufgaben anlegen, Status ändern | ✔ | ✔ |
| Sich selbst eintragen/austragen | ✔ | ✔ |
| Andere einteilen | ✔ | ✘ |
| Aufgaben, Material, Orte löschen | alle | nur selbst angelegte |
| Spieler aus dem Team entfernen | ✔ | ✘ |

Weitere Admins melden sich mit demselben Passwort an. Auf der Webseite muss jeder unter
Einstellungen seinen Minecraft-Namen eintragen.

Die Sperre gilt in Mod und Webseite. Wer eure Datenbank-Adresse kennt und sich auskennt,
könnte sie umgehen, indem er direkt in die Datenbank schreibt. Gebt die Adresse also nur
an Leute aus eurer Gruppe weiter.

Tipp für Streams: Im Menü wird das Passwort als Sternchen angezeigt. Den Befehl `/bt admin`
lieber nicht benutzen, während ihr streamt, da sieht man das Passwort im Chat-Feld.

### Befehle (gehen auch, ohne das Menü zu öffnen)

| Befehl | Was er macht |
|---|---|
| `/bt` | Menü öffnen |
| `/bt aufgabe <Text>` | Aufgabe anlegen |
| `/bt sammeln <Anzahl> [Item]` | Sammel-Aufgabe für dich anlegen (ohne Item: das in deiner Hand) |
| `/bt abbauen <Anzahl> [Block]` | Abbau-Aufgabe für dich anlegen |
| `/bt platzieren <Anzahl> [Block]` | Bau-Aufgabe für dich anlegen |
| `… hier [Radius]` | an `abbauen`/`platzieren` angehängt: nur im Umkreis deiner Position zählen (Standard 32) |
| `/bt suche <Item>` | In welchen Lager-Kisten liegt wie viel davon? |
| `/bt ort <Name>` | Deine aktuelle Position als Ort speichern |
| `/bt meine` | Deine offenen Aufgaben im Chat anzeigen |
| `/bt anzeige links/rechts/aus` | Aufgaben-Anzeige an den linken/rechten Rand setzen oder ausschalten |
| `/bt update` | Nach einer neuen Bautrupp-Version sehen und sie herunterladen |
| `/bt pruefen` | Offene Ziele im Projekt: Stand und warum sie (nicht) zählen |
| `/bt projekt ecke1` / `ecke2` | Bereich des gewählten Projekts mit zwei Ecken festlegen |
| `/bt projekt bereich aus` | Bereich des Projekts entfernen |
| `/bt anzeige bearbeiten` | Anzeige mit der Maus verschieben und Größe ändern |
| `/bt kombi <Anzahl> [Block] [hier]` | Kombi-Aufgabe: sammeln und verbauen |
| `/bt admin <Passwort>` | Als Admin anmelden (oder, wenn noch keins existiert, Passwort festlegen) |
| `/bt admin neu <Passwort>` | Admin-Passwort ändern (nur Admin) |
| `/bt admin abmelden` | Admin-Rechte auf diesem PC wieder abgeben |
| `/bt projekt neu <Name>` | Projekt anlegen und auswählen |
| `/bt projekt <Name>` / `/bt projekt alle` | Projekt auswählen / alle anzeigen |
| `/bt material <Anzahl> [Item]` | Material eintragen (ohne Item: das in deiner Hand), zählt im Lager mit |
| `/bt meldungen alle/meine/aus` | Meldungen im Chat einstellen |
| `/bt gruppe <Code>` | Gruppencode setzen |
| `/bt datenbank <Adresse>` | Datenbank-Adresse setzen |

Statt `/bt` geht auch `/bautrupp`. Die Befehle laufen nur bei dir, der Server bekommt nichts davon mit.

## Probleme?

- **Unten steht rot „Keine Verbindung zur Datenbank“:** Adresse prüfen, sie muss mit `https://` anfangen.
- **„Datenbank verweigert den Zugriff“:** Die Regeln aus Schritt 2.4 sind nicht veröffentlicht.
- **Die anderen sehen meine Aufgaben nicht:** Alle müssen exakt denselben Gruppencode haben (Groß- und Kleinschreibung zählt).
