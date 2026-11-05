package de.bautrupp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Hält den Gruppenstand und gleicht ihn über die REST-Schnittstelle der
 * Firebase Realtime Database ab. Alle Daten einer Gruppe liegen unter
 * /gruppen/<code>/ (aufgaben, material, orte, spieler, admin, projekte, lager, verlauf).
 *
 * Rechte: Ist ein Admin-Passwort gesetzt, dürfen nur Admins andere einteilen und
 * fremde Einträge löschen. Die Sperre gilt in Mod und Webseite, nicht in der
 * Datenbank selbst (die Firebase-Regeln bleiben offen).
 */
public class Store {
	public static final String[] STATUS = {"offen", "arbeit", "fertig"};
	public static final String[] STATUS_NAME = {"Offen", "In Arbeit", "Fertig"};
	/** So viele Verlaufseinträge bleiben gespeichert; ältere werden gelöscht. */
	private static final int MAX_LOG = 300;

	public static class Step {
		public String id, text;
		public boolean erledigt;
		public long pos;
	}

	public static class Comment {
		public String id, wer, text;
		public long zeit;
	}

	/** Zählerstand einer Person für Abbau-/Platzier-Ziele (Spiel-Statistik). */
	public static class Counter {
		public Integer start, basis;
		public String welt = "";
	}

	/** Rechteckiger Bereich (zwei Ecken); y1/y2 nur gesetzt, wenn die Höhe eine Rolle spielt. */
	public static class Area {
		public Integer x1, z1, x2, z2, y1, y2;

		public boolean valid() {
			return x1 != null && z1 != null && x2 != null && z2 != null;
		}

		public boolean contains(BlockPos p) {
			if (p.getX() < Math.min(x1, x2) || p.getX() > Math.max(x1, x2)) return false;
			if (p.getZ() < Math.min(z1, z2) || p.getZ() > Math.max(z1, z2)) return false;
			return y1 == null || y2 == null || (p.getY() >= Math.min(y1, y2) && p.getY() <= Math.max(y1, y2));
		}

		public String text() {
			return "von " + x1 + " " + z1 + " bis " + x2 + " " + z2 + (y1 == null || y2 == null ? "" : ", Höhe " + Math.min(y1, y2) + "-" + Math.max(y1, y2));
		}
	}

	/**
	 * Bauplan aus einer Schematic: Ecke mit den kleinsten Koordinaten, Breite (b, x) und Länge (l, z) der
	 * Schematic und Drehung (0-3 mal 90° im Uhrzeigersinn, -1 = erkennen). Die Positionen selbst liegen
	 * unter /gruppen/<code>__bauplan/<Aufgabe> und werden nur bei Bedarf geladen (siehe Bauplan).
	 */
	public static class Plan {
		public int x, y, z, b, l;
		public int rot = -1;
	}

	public static class Task {
		/** wer = alle Zuständigen, durch Komma getrennt ("Anna,Ben"); Minecraft-Namen enthalten keine Kommas. */
		public String id, titel, status = "offen", wer = "", dim = "";
		public Integer x, y, z;
		public long erstellt;
		/**
		 * Ziel: Art ("sammeln", "abbauen", "platzieren"; leer = sammeln), Item-ID (z. B. minecraft:dirt),
		 * Anzeigename, Zielmenge und gezählter Stand.
		 */
		public String art = "", item = "", itemName = "";

		/** Item-Name zum Anzeigen (Englisch oder Spielsprache, je nach Einstellung). */
		public String name() {
			return ItemNames.label(item, itemName);
		}
		public Integer ziel, fortschritt;
		/** Für abbauen/platzieren: Statistik-Wert beim Start, Stand zu dem Zeitpunkt, für wen und in welcher Welt. */
		public Integer start, basis;
		public String startFuer = "", startWelt = "";
		/** Bereich für abbauen/platzieren: nur Blöcke in diesem Umkreis um x/z zählen. */
		public Integer radius;
		/** Bereich als Rechteck; hat Vorrang vor dem Umkreis. */
		public Area bereich;
		/** Bauplan aus einer Schematic: dann zählt der Bestand Block für Block an den richtigen Stellen. */
		public Plan plan;
		/** Bereich kommt vom Projekt (ändert sich mit, wenn der Projekt-Bereich geändert wird). */
		public Boolean bereichVomProjekt;
		/** Sammeln: Inhalt der Lager-Kisten zählt mit (fehlt = ja). */
		public Boolean lagerZaehlt;
		/**
		 * Abbauen/Platzieren mit Bereich: "bestand" (Standard) zählt, was im Bereich steht;
		 * "platzierungen" zählt, was Leute mit der Mod setzen oder abbauen.
		 */
		public String modus = "";
		/** Bestand: zuletzt gezählte Anzahl im Bereich, beim Abbauen auch die Anzahl am Anfang. */
		public Integer bestand, bestandStart;
		public long bestandZeit;
		public String bestandVon = "";
		/** Teilziel: gehört zu dieser Aufgabe (wird dort angezeigt, nicht als eigene Karte). */
		public String oberaufgabe = "";
		/** Reihenfolge: erst freigeschaltet, wenn diese Aufgabe fertig ist (leer = sofort). */
		public String nachAufgabe = "";
		/** Zuletzt gemeldeter Zwischenstand in Prozent (0, 50, 75). */
		public Integer meilenstein;

		public boolean isChild() {
			return oberaufgabe != null && !oberaufgabe.isEmpty();
		}
		public String ersteller = "", projekt = "";
		public Map<String, Step> schritte;
		public Map<String, Comment> kommentare;
		/** Ziele mit mehreren Leuten: Beitrag je Person; zusammen ergeben sie den Fortschritt. */
		public Map<String, Integer> beitrag;
		/** Kombi-Aufgabe (Sammeln + Platzieren): gesetzte Blöcke je Person; beitrag sind dort die gesammelten. */
		public Map<String, Integer> bauBeitrag;
		public Map<String, Counter> zaehler;

		public List<String> people() {
			List<String> out = new ArrayList<>();
			if (wer != null) for (String n : wer.split(",")) if (!n.isBlank()) out.add(n.trim());
			return out;
		}

		public boolean has(String name) {
			for (String n : people()) if (n.equalsIgnoreCase(name)) return true;
			return false;
		}

		/** Für die Anzeige: "Anna", "Anna, Ben" oder "Niemand". */
		public String peopleText() {
			List<String> p = people();
			return p.isEmpty() ? "Niemand" : String.join(", ", p);
		}

		/** Gesamtstand: Summe der Beiträge (ältere Aufgaben ohne Beiträge: der gespeicherte Stand). */
		public int total() {
			if (beitrag == null || beitrag.isEmpty()) return fortschritt == null ? 0 : fortschritt;
			int n = 0;
			for (int v : beitrag.values()) n += v;
			return n;
		}

		/** Mein Anteil; bei älteren Aufgaben mit nur einer Person zählt der ganze Stand als ihrer. */
		public int share(String me) {
			if (beitrag != null && !beitrag.isEmpty()) return beitrag.getOrDefault(key(me), 0);
			List<String> p = people();
			return p.size() == 1 && p.get(0).equalsIgnoreCase(me) && fortschritt != null ? fortschritt : 0;
		}

		/** Zählerstand für Abbau/Platzieren; ältere Aufgaben haben ihn direkt an der Aufgabe. */
		public Counter counter(String me) {
			if (zaehler != null && zaehler.containsKey(key(me))) return zaehler.get(key(me));
			if (zaehler == null && start != null && me.equalsIgnoreCase(startFuer)) {
				Counter c = new Counter();
				c.start = start;
				c.basis = basis;
				c.welt = startWelt;
				return c;
			}
			return null;
		}

		public boolean hasArea() {
			return (bereich != null && bereich.valid()) || (radius != null && radius > 0 && x != null && z != null);
		}

		/** Liegt der Block im Bereich der Aufgabe (Rechteck oder Umkreis, gleiche Dimension)? */
		public boolean inArea(BlockPos p, String d) {
			if (dim != null && !dim.isEmpty() && !dim.equals(d)) return false;
			if (bereich != null && bereich.valid()) return bereich.contains(p);
			double dx = p.getX() - x, dz = p.getZ() - z;
			return dx * dx + dz * dz <= (double) radius * radius;
		}

		public String areaText() {
			if (plan != null) return "nach Bauplan";
			if (bereich != null && bereich.valid()) return "Bereich " + bereich.text();
			if (radius != null && radius > 0 && x != null) return "Umkreis " + radius + " um " + x + " " + z;
			return "";
		}

		/** Kombi: erst sammeln, dann platzieren – eine Aufgabe, zwei Fortschritte. */
		public boolean isCombo() {
			return "kombi".equals(goalKind());
		}

		/** Wird hier gesammelt (Inventar zählt)? */
		public boolean collects() {
			return "sammeln".equals(goalKind()) || isCombo();
		}

		/** Wird hier gebaut oder abgebaut (Blöcke in der Welt zählen)? */
		public boolean builds() {
			String k = goalKind();
			return "abbauen".equals(k) || "platzieren".equals(k) || isCombo();
		}

		/** Gesetzt wird bei Platzieren und beim Bau-Teil der Kombi. */
		public boolean places() {
			return "platzieren".equals(goalKind()) || isCombo();
		}

		public boolean bestandMode() {
			return hasArea() && !"platzierungen".equals(modus) && builds();
		}

		/** Gezählte Platzierungen bzw. Abbau (ohne Bestand): bei Kombi aus bauBeitrag, sonst aus beitrag. */
		public int buildCounted() {
			if (!isCombo()) return total();
			int n = 0;
			if (bauBeitrag != null) for (int v : bauBeitrag.values()) n += v;
			return n;
		}

		public int buildShare(String me) {
			if (!isCombo()) return share(me);
			return bauBeitrag == null ? 0 : bauBeitrag.getOrDefault(key(me), 0);
		}

		/** Fortschritt von Abbauen/Platzieren (und Bau-Teil der Kombi): Bestand im Bereich oder gezählt. */
		public int progress() {
			if (!bestandMode()) return buildCounted();
			if (places()) return bestand == null ? 0 : bestand;
			return bestand == null || bestandStart == null ? 0 : Math.max(0, bestandStart - bestand);
		}

		public boolean countsStorage() {
			return collects() && !Boolean.FALSE.equals(lagerZaehlt);
		}

		public String goalKind() {
			return art == null || art.isEmpty() ? "sammeln" : art;
		}

		public boolean hasGoal() {
			return ziel != null && ziel > 0 && item != null && !item.isEmpty();
		}

		public List<Step> steps() {
			List<Step> l = new ArrayList<>(schritte == null ? List.of() : schritte.values());
			l.sort(Comparator.comparingLong(s -> s.pos));
			return l;
		}

		public List<Comment> comments() {
			List<Comment> l = new ArrayList<>(kommentare == null ? List.of() : kommentare.values());
			l.sort(Comparator.comparingLong(c -> c.zeit));
			return l;
		}

		public int stepsDone() {
			int n = 0;
			if (schritte != null) for (Step s : schritte.values()) if (s.erledigt) n++;
			return n;
		}
	}

	public static class Material {
		/** item = Anzeigename; itemId = Minecraft-ID, wenn bekannt (dann zählt das Lager mit). */
		public String id, item, itemId = "", ersteller = "", projekt = "";
		public int need, have;
		/** Aus einer Schematic für Teile ohne Aufgabe: zählt zum Bedarf der Aufgaben dazu. */
		public boolean zusatz;
	}

	public static class Place {
		public String id, name, dim, ersteller = "";
		public int x, y, z;
	}

	public static class Project {
		public String id, name, ersteller = "";
		/** Spieler, denen das Projekt zugeteilt ist (durch Komma getrennt); leer = alle. */
		public String wer = "";
		/** Bereich des Projekts (Baustelle) und seine Dimension; Aufgaben ohne eigenen Bereich übernehmen ihn. */
		public Area bereich;
		public String dim = "";
		public long erstellt;
	}

	/** Eine Kiste (bei Doppelkisten beide Hälften), deren Inhalt die Mod beim Öffnen zählt. */
	public static class Storage {
		public String id, name = "", dim = "", ersteller = "", projekt = "", von = "";
		public int x, y, z;
		public Integer x2, y2, z2;
		public long aktualisiert;
		public Map<String, Integer> inhalt;

		public boolean isAt(BlockPos p, String d) {
			if (!Objects.equals(dim, d)) return false;
			if (p.getX() == x && p.getY() == y && p.getZ() == z) return true;
			return x2 != null && p.getX() == x2 && p.getY() == y2 && p.getZ() == z2;
		}
	}

	public static class LogEntry {
		public String id, wer, text, aufgabe = "";
		public long zeit;
	}

	private static final Gson GSON = new Gson();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	public final Config config;
	public List<Task> tasks = new ArrayList<>();
	public List<Material> materials = new ArrayList<>();
	public List<Place> places = new ArrayList<>();
	public List<Project> projects = new ArrayList<>();
	public List<Storage> storages = new ArrayList<>();
	public List<LogEntry> log = new ArrayList<>();
	public Set<String> players = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	/** Aus /discord: Webhook-Adresse und welche Ereignisse gepostet werden (fehlend = an). */
	public String discordWebhook = "";
	public Map<String, Boolean> discordEvents = new HashMap<>();
	/** Aus /admin: Salz und Prüfwert des Admin-Passworts; null = kein Admin festgelegt. */
	public String adminSalt, adminHash;

	/** null = alles gut, sonst eine Fehlermeldung für die Anzeige. */
	public String error = null;
	public boolean loadedOnce = false;
	private boolean busy = false;
	private long lastFetch = 0;
	private long lastHello = 0;
	/** Wird nach jeder Änderung aufgerufen, damit das Menü neu zeichnet. */
	public Runnable onChange = () -> {};

	public Store(Config config) {
		this.config = config;
	}

	private String base() {
		return config.datenbank + "/gruppen/" + config.gruppe;
	}

	public static String myName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null ? mc.player.getPlainTextName() : mc.getUser().getName();
	}

	// ---------- Abgleich ----------

	public void tick(boolean screenOpen) {
		if (!config.isComplete() || busy) return;
		long now = System.currentTimeMillis();
		// Offenes Menü oder eigene Ziele am Bildschirmrand: öfter nachsehen, damit die Zahlen frisch sind.
		boolean myGoals = false;
		for (Task t : tasks) if (t.hasGoal() && !"fertig".equals(t.status) && t.has(myName())) { myGoals = true; break; }
		long interval = screenOpen ? 3000 : myGoals ? 5000 : 15000;
		if (now - lastFetch >= interval) refresh();
		if (Minecraft.getInstance().player != null && now - lastHello >= 60000) {
			lastHello = now;
			JsonObject me = new JsonObject();
			me.addProperty("zuletzt", now);
			send("PATCH", "/spieler/" + key(myName()), me);
		}
	}

	public void refresh() {
		if (!config.isComplete()) return;
		busy = true;
		lastFetch = System.currentTimeMillis();
		HttpRequest req = HttpRequest.newBuilder(URI.create(base() + ".json"))
				.timeout(Duration.ofSeconds(10)).GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, ex) ->
				Minecraft.getInstance().execute(() -> {
					busy = false;
					if (ex != null) {
						error = "Keine Verbindung zur Datenbank";
					} else if (res.statusCode() == 401 || res.statusCode() == 403) {
						error = "Datenbank verweigert den Zugriff (Regeln prüfen)";
					} else if (res.statusCode() != 200) {
						error = "Datenbank antwortet mit Fehler " + res.statusCode();
					} else {
						try {
							apply(JsonParser.parseString(res.body()));
							error = null;
						} catch (Exception e) {
							error = "Antwort der Datenbank unlesbar";
							BautruppClient.LOG.warn("Bautrupp: Antwort unlesbar", e);
						}
					}
					onChange.run();
				}));
	}

	private void apply(JsonElement root) {
		List<Task> oldTasks = tasks;
		List<LogEntry> oldLog = log;
		List<Task> t = new ArrayList<>();
		List<Material> m = new ArrayList<>();
		List<Place> p = new ArrayList<>();
		List<Project> pr = new ArrayList<>();
		List<Storage> st = new ArrayList<>();
		List<LogEntry> lg = new ArrayList<>();
		Set<String> pl = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		adminSalt = null;
		adminHash = null;
		if (root != null && root.isJsonObject()) {
			JsonObject o = root.getAsJsonObject();
			eachChild(o, "aufgaben", (id, e) -> {
				Task x = GSON.fromJson(e, Task.class);
				x.id = id;
				if (x.schritte != null) x.schritte.forEach((k, v) -> v.id = k);
				if (x.kommentare != null) x.kommentare.forEach((k, v) -> v.id = k);
				if (x.titel != null) t.add(x);
			});
			eachChild(o, "material", (id, e) -> {
				Material x = GSON.fromJson(e, Material.class);
				x.id = id;
				if (x.item != null) m.add(x);
			});
			eachChild(o, "orte", (id, e) -> {
				Place x = GSON.fromJson(e, Place.class);
				x.id = id;
				if (x.name != null) p.add(x);
			});
			eachChild(o, "projekte", (id, e) -> {
				Project x = GSON.fromJson(e, Project.class);
				x.id = id;
				if (x.name != null) pr.add(x);
			});
			eachChild(o, "lager", (id, e) -> {
				Storage x = GSON.fromJson(e, Storage.class);
				x.id = id;
				st.add(x);
			});
			eachChild(o, "verlauf", (id, e) -> {
				LogEntry x = GSON.fromJson(e, LogEntry.class);
				x.id = id;
				if (x.text != null) lg.add(x);
			});
			if (o.has("spieler") && o.get("spieler").isJsonObject()) pl.addAll(o.getAsJsonObject("spieler").keySet());
			discordWebhook = "";
			discordEvents = new HashMap<>();
			if (o.has("discord") && o.get("discord").isJsonObject()) {
				JsonObject d = o.getAsJsonObject("discord");
				if (d.has("webhook") && d.get("webhook").isJsonPrimitive()) discordWebhook = d.get("webhook").getAsString();
				if (d.has("events") && d.get("events").isJsonObject())
					for (Map.Entry<String, JsonElement> ev : d.getAsJsonObject("events").entrySet())
						if (ev.getValue().isJsonPrimitive()) discordEvents.put(ev.getKey(), ev.getValue().getAsBoolean());
			}
			if (o.has("admin") && o.get("admin").isJsonObject()) {
				JsonObject a = o.getAsJsonObject("admin");
				if (a.has("salt") && a.has("hash")) {
					adminSalt = a.get("salt").getAsString();
					adminHash = a.get("hash").getAsString();
				}
			}
		}
		t.sort(Comparator.comparingLong(x -> x.erstellt));
		m.sort(Comparator.comparing(x -> x.item.toLowerCase(Locale.ROOT)));
		p.sort(Comparator.comparing(x -> x.name.toLowerCase(Locale.ROOT)));
		pr.sort(Comparator.comparingLong(x -> x.erstellt));
		lg.sort(Comparator.comparingLong(x -> x.zeit));
		for (Task x : t) pl.addAll(x.people());
		pl.add(myName());

		boolean wasLoaded = loadedOnce;
		if (loadedOnce) Notifier.onUpdate(this, oldTasks, t, oldLog, lg);
		tasks = t;
		materials = m;
		places = p;
		projects = pr;
		storages = st;
		log = lg;
		players = pl;
		loadedOnce = true;
		pruneLog();
		// Fortschritt, den andere (oder das Lager) gebracht haben: offene Aufgaben auf "In Arbeit"
		if (wasLoaded) markStarted();
	}

	/** Hält den Verlauf klein: alles über MAX_LOG Einträgen wird in einem Rutsch gelöscht. */
	private void pruneLog() {
		if (log.size() <= MAX_LOG + 20) return;
		JsonObject del = new JsonObject();
		for (LogEntry e : log.subList(0, log.size() - MAX_LOG)) del.add(e.id, JsonNull.INSTANCE);
		send("PATCH", "/verlauf", del);
	}

	private interface ChildFn { void accept(String id, JsonElement e); }

	private static void eachChild(JsonObject o, String key, ChildFn fn) {
		if (!o.has(key) || !o.get(key).isJsonObject()) return;
		for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
			try {
				if (e.getValue().isJsonObject()) fn.accept(e.getKey(), e.getValue());
			} catch (Exception ex) {
				BautruppClient.LOG.warn("Bautrupp: Eintrag {} übersprungen", e.getKey());
			}
		}
	}

	// ---------- Abfragen ----------

	public Task task(String id) {
		for (Task t : tasks) if (t.id.equals(id)) return t;
		return null;
	}

	public Project project(String id) {
		if (id == null || id.isEmpty()) return null;
		for (Project p : projects) if (p.id.equals(id)) return p;
		return null;
	}

	public String projectName(String id) {
		Project p = project(id);
		return p == null ? "" : p.name;
	}

	/** Leeres Projekt = "Alle" bzw. ohne Projekt. */
	public static boolean inProject(String itemProject, String selected) {
		return selected == null || selected.isEmpty() || selected.equals(itemProject);
	}

	public boolean hasStorage(String projekt) {
		for (Storage s : storages) if (s.projekt.isEmpty() || projekt.isEmpty() || s.projekt.equals(projekt)) return true;
		return false;
	}

	/**
	 * Wie viele von dem Item in den Lager-Kisten liegen, die zu diesem Projekt gehören
	 * (Kisten ohne Projekt zählen für alle). -1, wenn es kein passendes Lager gibt.
	 */
	public int storageCount(String itemId, String projekt) {
		if (itemId == null || itemId.isEmpty() || !hasStorage(projekt)) return -1;
		int n = 0;
		for (Storage s : storages) {
			if (!(s.projekt.isEmpty() || projekt.isEmpty() || s.projekt.equals(projekt))) continue;
			if (s.inhalt != null) n += s.inhalt.getOrDefault(key(itemId), 0);
		}
		return n;
	}

	/**
	 * Item-ID eines Materials: gespeichert, oder aus dem englischen Namen ("Stone Bricks" ->
	 * minecraft:stone_bricks), sobald ein Lager so ein Item enthält. Gleiche Regel wie auf der Webseite.
	 */
	public String materialItemId(Material m) {
		if (m.itemId != null && !m.itemId.isEmpty()) return m.itemId;
		String g = m.item.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
		if (!g.contains(":")) g = "minecraft:" + g;
		for (Storage s : storages) if (s.inhalt != null && s.inhalt.containsKey(key(g))) return g;
		return "";
	}

	/** Gesammelt: aus dem Lager, wenn möglich, sonst die von Hand gezählte Zahl. */
	public int materialHave(Material m) {
		int c = storageCount(materialItemId(m), m.projekt);
		return c >= 0 ? c : m.have;
	}

	public boolean materialFromStorage(Material m) {
		return storageCount(materialItemId(m), m.projekt) >= 0;
	}

	/** Sammeln: was in den Lager-Kisten des Projekts liegt (0, wenn abgeschaltet oder kein Lager). */
	public int storageFor(Task t) {
		if (!t.countsStorage()) return 0;
		return Math.max(0, storageCount(t.item, t.projekt));
	}

	/** Sammeln: was im selben Projekt bei Platzieren-Aufgaben mit demselben Block schon verbaut ist. */
	public int placedFor(Task t) {
		if (!t.collects()) return 0;
		int n = t.isCombo() ? t.progress() : 0;
		for (Task o : tasks)
			if (o != t && o.places() && Objects.equals(o.item, t.item) && Objects.equals(o.projekt, t.projekt)) n += o.progress();
		return n;
	}

	/** Gesammelt: Inventare der Leute + Lager + schon Verbautes (bei Kombi auch das eigene). */
	public int collectTotal(Task t) {
		return t.total() + storageFor(t) + placedFor(t);
	}

	/** Fortschritt, der über "fertig" entscheidet: Sammeln = gesammelt, sonst (auch Kombi) = verbaut/abgebaut. */
	public int goalTotal(Task t) {
		if ("sammeln".equals(t.goalKind())) return collectTotal(t);
		return t.progress();
	}

	/** Für die Anzeige: "Anna 100 · Lager 300 · verbaut 200" (nur wenn es mehr als einen Teil gibt). */
	public String goalParts(Task t) {
		if (t.isCombo()) return "gesammelt " + collectTotal(t) + " / " + t.ziel + " · verbaut " + t.progress() + " / " + t.ziel;
		List<String> parts = new ArrayList<>();
		if (t.beitrag != null) t.beitrag.forEach((n, v) -> { if (v > 0) parts.add(n + " " + v); });
		int l = storageFor(t), v = placedFor(t);
		if (l > 0) parts.add("Lager " + l);
		if (v > 0) parts.add("verbaut " + v);
		return parts.size() > 1 ? String.join(" · ", parts) : "";
	}

	/**
	 * Sammel-Ziele, die durch Lager oder Verbautes erreicht sind, als fertig markieren.
	 * Läuft nach eigenen Änderungen (Lager gezählt, Blöcke platziert), damit es nur einer macht.
	 */
	/**
	 * Offene Aufgaben mit erstem Fortschritt (Inventar, Lager-Kiste, verbaut oder ein Teilziel)
	 * kommen von selbst auf "In Arbeit". Gesperrte (Reihenfolge) bleiben offen.
	 */
	public void markStarted() {
		for (Task t : tasks) {
			if (!"offen".equals(t.status) || locked(t)) continue;
			boolean started = t.hasGoal() && goalTotal(t) > 0;
			for (Task c : children(t)) started |= !"offen".equals(c.status) || (c.hasGoal() && goalTotal(c) > 0);
			if (!started) continue;
			t.status = "arbeit";
			patch("/aufgaben/" + t.id, "status", "arbeit");
		}
	}

	public void checkGoalsDone() {
		markStarted();
		for (Task t : tasks) {
			if (!t.hasGoal() || !"sammeln".equals(t.goalKind()) || "fertig".equals(t.status) || locked(t)) continue;
			if (goalTotal(t) < t.ziel) continue;
			if (!children(t).isEmpty() && !allChildrenDone(t)) continue;
			t.status = "fertig";
			patch("/aufgaben/" + t.id, "status", "fertig");
			log("hat \"" + t.titel + "\" geschafft (" + t.ziel + " " + t.name() + " beisammen)", t, "erledigt");
			afterFinished(t);
		}
	}

	public boolean canEditTask(Task t) {
		return isAdmin() || t.has(myName()) || myName().equalsIgnoreCase(t.ersteller);
	}

	public void setArea(Task t, Area a) {
		for (Task c : children(t)) setArea(c, a);
		t.bereich = a;
		JsonObject o = new JsonObject();
		o.add("bereich", a == null ? JsonNull.INSTANCE : GSON.toJsonTree(a));
		send("PATCH", "/aufgaben/" + t.id, o);
		onChange.run();
	}

	public void setRadius(Task t, BlockPos center, String d, int radius) {
		for (Task c : children(t)) setRadius(c, center, d, radius);
		t.bereich = null;
		t.x = center.getX(); t.y = center.getY(); t.z = center.getZ();
		t.dim = d;
		t.radius = radius;
		JsonObject o = new JsonObject();
		o.add("bereich", JsonNull.INSTANCE);
		o.addProperty("x", t.x); o.addProperty("y", t.y); o.addProperty("z", t.z);
		o.addProperty("dim", d);
		o.addProperty("radius", radius);
		send("PATCH", "/aufgaben/" + t.id, o);
		onChange.run();
	}

	/** Kein Bereich mehr: Blöcke zählen überall. Die Koordinaten der Aufgabe bleiben. */
	public void clearArea(Task t) {
		for (Task c : children(t)) clearArea(c);
		t.bereich = null;
		t.radius = null;
		JsonObject o = new JsonObject();
		o.add("bereich", JsonNull.INSTANCE);
		o.add("radius", JsonNull.INSTANCE);
		send("PATCH", "/aufgaben/" + t.id, o);
		onChange.run();
	}

		/** Ergebnis einer Zählung im Bereich. Schreibt nur bei Änderung (oder einmal pro Minute für "gezählt vor"). */
	public void setBestand(Task t, int count) {
		long now = System.currentTimeMillis();
		boolean first = !t.places() && t.bestandStart == null;
		if (!first && t.bestand != null && t.bestand == count && now - t.bestandZeit < 60000) return;
		JsonObject o = new JsonObject();
		if (first) {
			t.bestandStart = count;
			o.addProperty("bestandStart", count);
		}
		t.bestand = count;
		t.bestandZeit = now;
		t.bestandVon = myName();
		o.addProperty("bestand", count);
		o.addProperty("bestandZeit", now);
		o.addProperty("bestandVon", t.bestandVon);
		int p = t.progress();
		boolean reached = applyGoalStatus(t, o, p);
		send("PATCH", "/aufgaben/" + t.id, o);
		flushMilestone(t);
		if (reached) {
			log("hat \"" + t.titel + "\" fertig (" + p + " " + t.name() + " " + goalDone(t.art) + " im Bereich)", t, "erledigt");
			afterFinished(t);
		}
		if (t.places()) checkGoalsDone();
		onChange.run();
	}

	public void setMode(Task t, String modus) {
		for (Task c : children(t)) setMode(c, modus);
		t.modus = modus;
		patch("/aufgaben/" + t.id, "modus", modus);
	}

	/** Abbauen: Anfangsbestand vergessen, die nächste Zählung setzt ihn neu. */
	public void resetBestandStart(Task t) {
		t.bestandStart = null;
		JsonObject o = new JsonObject();
		o.add("bestandStart", JsonNull.INSTANCE);
		send("PATCH", "/aufgaben/" + t.id, o);
		onChange.run();
	}

	public void setStorageCounts(Task t, boolean on) {
		for (Task c : children(t)) { c.lagerZaehlt = on; JsonObject co = new JsonObject(); co.addProperty("lagerZaehlt", on); send("PATCH", "/aufgaben/" + c.id, co); }
		t.lagerZaehlt = on;
		JsonObject o = new JsonObject();
		o.addProperty("lagerZaehlt", on);
		send("PATCH", "/aufgaben/" + t.id, o);
		checkGoalsDone();
		onChange.run();
	}

	// ---------- Teilziele und Reihenfolge ----------

	public List<Task> children(Task t) {
		List<Task> out = new ArrayList<>();
		for (Task o : tasks) if (t.id.equals(o.oberaufgabe)) out.add(o);
		return out;
	}

	/** Gesperrt, solange die Aufgabe, nach der sie kommt, nicht fertig ist. */
	public boolean locked(Task t) {
		Task root = t.isChild() ? task(t.oberaufgabe) : t;
		if (root == null || root.nachAufgabe == null || root.nachAufgabe.isEmpty()) return false;
		Task before = task(root.nachAufgabe);
		return before != null && !"fertig".equals(before.status);
	}

	public String lockText(Task t) {
		Task root = t.isChild() ? task(t.oberaufgabe) : t;
		Task before = root == null ? null : task(root.nachAufgabe);
		return before == null ? "" : "erst nach \"" + before.titel + "\"";
	}

	/** Anteil (0..100) eines Ziels; bei Aufgaben mit Teilzielen der Durchschnitt aller Ziele. */
	public int percent(Task t) {
		List<Integer> p = new ArrayList<>();
		if (t.hasGoal()) p.add(Math.min(100, goalTotal(t) * 100 / Math.max(1, t.ziel)));
		for (Task c : children(t)) if (c.hasGoal()) p.add("fertig".equals(c.status) ? 100 : Math.min(100, goalTotal(c) * 100 / Math.max(1, c.ziel)));
		if (p.isEmpty()) return "fertig".equals(t.status) ? 100 : 0;
		int sum = 0;
		for (int v : p) sum += v;
		return sum / p.size();
	}

	/** Teilziel anlegen: übernimmt Leute, Projekt, Bereich und Zählmodus der Aufgabe. */
	public Task addSubGoal(Task parent, String art, String item, String itemName, int ziel) {
		Task t = new Task();
		t.id = newId();
		t.titel = parent.titel + ": " + ItemNames.label(item, itemName);
		t.oberaufgabe = parent.id;
		t.art = art;
		t.item = item;
		t.itemName = itemName;
		t.ziel = ziel;
		t.fortschritt = 0;
		t.wer = parent.wer;
		t.x = parent.x; t.y = parent.y; t.z = parent.z;
		t.dim = parent.dim;
		t.radius = parent.radius;
		t.bereich = parent.bereich;
		t.modus = parent.modus;
		t.lagerZaehlt = parent.lagerZaehlt;
		t.erstellt = System.currentTimeMillis();
		t.ersteller = myName();
		t.projekt = parent.projekt;
		tasks.add(t);
		send("PUT", "/aufgaben/" + t.id, withoutId(t));
		log("hat bei \"" + parent.titel + "\" ein Ziel hinzugefügt: " + ziel + " " + ItemNames.label(item, itemName) + " " + goalVerb(art), parent, "neu");
		if ("fertig".equals(parent.status)) setStatusQuiet(parent, "arbeit");
		onChange.run();
		return t;
	}

	private void setStatusQuiet(Task t, String status) {
		t.status = status;
		patch("/aufgaben/" + t.id, "status", status);
	}

	public void setAfter(Task t, String beforeId) {
		t.nachAufgabe = beforeId == null ? "" : beforeId;
		patch("/aufgaben/" + t.id, "nachAufgabe", t.nachAufgabe);
	}

	/**
	 * Entscheidet nach einer Zählung über den Status: meldet Zwischenstände (50/75 %),
	 * setzt "fertig", wenn das Ziel erreicht ist (bei Teilzielen erst, wenn alle fertig sind).
	 * Gibt true zurück, wenn die Aufgabe dadurch fertig wurde.
	 */
	private boolean applyGoalStatus(Task t, JsonObject o, int p) {
		if (p >= t.ziel && !"fertig".equals(t.status)) {
			boolean ownOnly = children(t).isEmpty() || allChildrenDone(t);
			if (ownOnly) {
				t.status = "fertig";
				o.addProperty("status", "fertig");
				return true;
			}
		}
		if (p > 0 && "offen".equals(t.status)) {
			t.status = "arbeit";
			o.addProperty("status", "arbeit");
		}
		int pct = p * 100 / Math.max(1, t.ziel), level = pct >= 75 ? 75 : pct >= 50 ? 50 : 0;
		int before = t.meilenstein == null ? 0 : t.meilenstein;
		if (level > before && p < t.ziel) {
			t.meilenstein = level;
			o.addProperty("meilenstein", level);
			milestone = "\"" + t.titel + "\" ist " + (level == 50 ? "zur Hälfte" : "zu drei Vierteln") + " geschafft (" + p + " / " + t.ziel + " " + t.name() + ")";
		}
		return false;
	}

	/** Wird von applyGoalStatus gesetzt und nach dem Speichern als Meldung verschickt. */
	private String milestone;

	private void flushMilestone(Task t) {
		if (milestone == null) return;
		String m = milestone;
		milestone = null;
		log("meldet: " + m, t, "meilenstein");
	}

	private boolean allChildrenDone(Task t) {
		for (Task c : children(t)) if (!"fertig".equals(c.status)) return false;
		return true;
	}

	/** Nach jedem "fertig": Oberaufgabe prüfen und Aufgaben freischalten, die darauf gewartet haben. */
	private void afterFinished(Task t) {
		if (t.isChild()) {
			Task parent = task(t.oberaufgabe);
			if (parent != null && !"fertig".equals(parent.status) && allChildrenDone(parent)
					&& (!parent.hasGoal() || goalTotal(parent) >= parent.ziel)) {
				setStatusQuiet(parent, "fertig");
				log("hat \"" + parent.titel + "\" komplett geschafft (alle Ziele)", parent, "erledigt");
				afterFinished(parent);
			}
		}
		for (Task o : tasks)
			if (t.id.equals(o.nachAufgabe) && !"fertig".equals(o.status))
				log("Freigeschaltet: \"" + o.titel + "\" (\"" + t.titel + "\" ist fertig)", o, "status");
	}

	/** Material, das sich aus den Aufgaben ergibt (Sammeln/Platzieren/Kombi), je Item und Projekt. */
	public static class AutoMaterial {
		public String item, itemName, projekt;
		public int need, lager, verbaut, extra;
		/** Die Aufgaben (oberste Ebene), die dieses Material brauchen. */
		public final List<Task> tasks = new ArrayList<>();

		public int missing() {
			return Math.max(0, need - have());
		}

		public int have() {
			return lager + verbaut;
		}
	}

	public List<AutoMaterial> autoMaterials(String projektFilter) {
		Map<String, AutoMaterial> groups = new LinkedHashMap<>();
		Map<String, int[]> sums = new HashMap<>();
		for (Task t : tasks) {
			if (!t.hasGoal() || !(t.collects() || t.places()) || !inProject(t.projekt, projektFilter)) continue;
			String k = t.item + "|" + t.projekt;
			AutoMaterial a = groups.computeIfAbsent(k, x -> newAuto(t.item, t.itemName, t.projekt));
			int[] v = sums.computeIfAbsent(k, x -> new int[2]);
			if (t.collects()) v[0] += t.ziel;
			if (t.places()) { v[1] += t.ziel; a.verbaut += t.progress(); }
			Task root = t.isChild() && task(t.oberaufgabe) != null ? task(t.oberaufgabe) : t;
			if (!a.tasks.contains(root)) a.tasks.add(root);
		}
		// Zusatz aus einer Schematic (Teile ohne Aufgabe) zählt zum Bedarf dazu
		for (Material m : materials) {
			if (!m.zusatz || m.itemId == null || m.itemId.isEmpty() || !inProject(m.projekt, projektFilter)) continue;
			groups.computeIfAbsent(m.itemId + "|" + m.projekt, x -> newAuto(m.itemId, m.item, m.projekt)).extra += m.need;
		}
		List<AutoMaterial> out = new ArrayList<>();
		for (Map.Entry<String, AutoMaterial> e : groups.entrySet()) {
			AutoMaterial a = e.getValue();
			boolean manual = false;
			for (Material m : materials) if (!m.zusatz && a.item.equals(materialItemId(m)) && Objects.equals(m.projekt, a.projekt)) manual = true;
			if (manual) continue;
			int[] v = sums.getOrDefault(e.getKey(), new int[2]);
			a.need = Math.max(v[0], v[1]) + a.extra;
			a.lager = Math.max(0, storageCount(a.item, a.projekt));
			out.add(a);
		}
		return out;
	}

	private static AutoMaterial newAuto(String item, String itemName, String projekt) {
		AutoMaterial a = new AutoMaterial();
		a.item = item;
		a.itemName = itemName;
		a.projekt = projekt;
		return a;
	}

	/** Kurz fürs Ziel: "Lager 120, fehlen 30" (bezogen aufs ganze Projekt), oder "". */
	public String materialHint(Task t) {
		if (!t.hasGoal() || "fertig".equals(t.status) || !(t.collects() || t.places())) return "";
		for (AutoMaterial a : autoMaterials(""))
			if (a.item.equals(t.item) && Objects.equals(a.projekt, t.projekt))
				return "Lager " + a.lager + (a.missing() > 0 ? ", fehlen " + a.missing() : ", genug da");
		return "";
	}

	public Storage storageAt(BlockPos p, String dim) {
		for (Storage s : storages) if (s.isAt(p, dim)) return s;
		return null;
	}

	// ---------- Rechte ----------

	public boolean hasAdmin() {
		return adminHash != null;
	}

	/** Ohne festgelegtes Admin-Passwort darf jeder alles. */
	public boolean isAdmin() {
		return !hasAdmin() || adminHash.equals(config.adminHash);
	}

	public boolean canDelete(String ersteller) {
		return isAdmin() || (ersteller != null && !ersteller.isEmpty() && ersteller.equalsIgnoreCase(myName()));
	}

	/** Ohne Admin-Rechte darf man nur sich selbst dazunehmen oder austragen, andere bleiben, wie sie sind. */
	public boolean canAssign(Task t, List<String> newPeople) {
		if (isAdmin()) return true;
		String me = myName();
		Set<String> before = new TreeSet<>(String.CASE_INSENSITIVE_ORDER), after = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		before.addAll(t.people());
		after.addAll(newPeople);
		before.remove(me);
		after.remove(me);
		return before.equals(after);
	}

	private boolean deny(String msg) {
		error = msg;
		onChange.run();
		return false;
	}

	public static String hash(String salt, String password) {
		try {
			byte[] d = java.security.MessageDigest.getInstance("SHA-256")
					.digest((salt + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : d) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Legt das Admin-Passwort fest (oder ändert es, wenn man Admin ist). */
	public boolean setAdminPassword(String password) {
		if (hasAdmin() && !isAdmin()) return deny("Nur der Admin kann das Passwort ändern");
		if (password.length() < 4) return deny("Das Passwort muss mindestens 4 Zeichen haben");
		byte[] r = new byte[8];
		new java.security.SecureRandom().nextBytes(r);
		StringBuilder salt = new StringBuilder();
		for (byte b : r) salt.append(String.format("%02x", b));
		String h = hash(salt.toString(), password);
		adminSalt = salt.toString();
		adminHash = h;
		config.adminHash = h;
		config.save();
		JsonObject o = new JsonObject();
		o.addProperty("salt", adminSalt);
		o.addProperty("hash", h);
		send("PUT", "/admin", o);
		onChange.run();
		return true;
	}

	public boolean loginAdmin(String password) {
		if (!hasAdmin()) return deny("Es ist noch kein Admin-Passwort festgelegt");
		String h = hash(adminSalt, password);
		if (!h.equals(adminHash)) return deny("Falsches Admin-Passwort");
		config.adminHash = h;
		config.save();
		error = null;
		onChange.run();
		return true;
	}

	public void logoutAdmin() {
		config.adminHash = "";
		config.save();
		onChange.run();
	}

	// ---------- Schreiben ----------

	private CompletableFuture<Void> send(String method, String path, JsonElement body) {
		if (!config.isComplete()) {
			error = "Erst Datenbank und Gruppencode eintragen";
			onChange.run();
			return CompletableFuture.completedFuture(null);
		}
		HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(GSON.toJson(body));
		HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path + ".json"))
				.timeout(Duration.ofSeconds(10)).method(method, pub).build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((res, ex) -> {
			Minecraft.getInstance().execute(() -> {
				if (ex != null || res.statusCode() >= 300) {
					error = "Speichern fehlgeschlagen";
					onChange.run();
				}
				// Nach jeder Änderung frisch laden, damit Änderungen der anderen mitkommen.
				lastFetch = 0;
			});
			return null;
		});
	}

	/** Lädt den Bauplan (Positionen als Base36-Zahlen, durch Komma getrennt) einer Aufgabe. */
	public void loadPlan(String taskId, java.util.function.Consumer<String> done) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "__bauplan/" + taskId + ".json"))
				.timeout(Duration.ofSeconds(20)).GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, ex) -> Minecraft.getInstance().execute(() -> {
			String body = ex == null && res.statusCode() == 200 ? res.body() : null;
			try {
				JsonElement e = body == null ? null : JsonParser.parseString(body);
				done.accept(e != null && e.isJsonPrimitive() ? e.getAsString() : null);
			} catch (Exception x) {
				done.accept(null);
			}
		}));
	}

	/** Zeitlich sortierbare IDs, damit der Verlauf nach Schlüssel geordnet ist. */
	private static String newId() {
		return Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(1 << 20), 36);
	}

	/** Firebase-Schlüssel dürfen kein . # $ [ ] / enthalten. */
	public static String key(String s) {
		return s.replaceAll("[.#$\\[\\]/]", "_");
	}

	private static JsonObject withoutId(Object o) {
		JsonObject j = GSON.toJsonTree(o).getAsJsonObject();
		j.remove("id");
		return j;
	}

	/**
	 * Schreibt einen Eintrag in den Verlauf, den alle sehen (Webseite, Meldungen in der Mod),
	 * und schickt ihn an Discord, wenn das für diese Art (type) eingeschaltet ist.
	 */
	public void log(String text, Task t, String type) {
		LogEntry e = new LogEntry();
		e.id = newId();
		e.wer = myName();
		e.text = text;
		e.zeit = System.currentTimeMillis();
		e.aufgabe = t == null ? "" : t.id;
		log.add(e);
		Notifier.markSeen(e.id);
		send("PUT", "/verlauf/" + e.id, withoutId(e));
		Discord.post(this, e, type, t == null ? "" : t.projekt);
	}

	public void addTask(String titel, String wer, Integer x, Integer y, Integer z, String dim) {
		Task t = new Task();
		t.id = newId();
		t.titel = titel;
		t.wer = wer == null ? "" : wer;
		t.x = x; t.y = y; t.z = z;
		t.dim = dim == null ? "" : dim;
		t.erstellt = System.currentTimeMillis();
		putTask(t);
	}

	public void addGoalTask(String titel, String wer, String art, String item, String itemName, int ziel) {
		addGoalTask(titel, wer, art, item, itemName, ziel, null, "", 0);
	}

	/** Mit center != null zählen bei abbauen/platzieren nur Blöcke im Umkreis radius. */
	public void addGoalTask(String titel, String wer, String art, String item, String itemName, int ziel, BlockPos center, String dim, int radius) {
		Task t = new Task();
		t.id = newId();
		t.art = art;
		if (center != null) {
			t.x = center.getX(); t.y = center.getY(); t.z = center.getZ();
			t.dim = dim;
			if (!"sammeln".equals(art)) t.radius = radius;
		}
		t.titel = titel;
		t.wer = wer == null ? "" : wer;
		t.item = item;
		t.itemName = itemName;
		t.ziel = ziel;
		t.fortschritt = 0;
		t.erstellt = System.currentTimeMillis();
		putTask(t);
	}

	private void putTask(Task t) {
		t.ersteller = myName();
		t.projekt = config.projekt;
		// Ohne Zuständige bekommt die Aufgabe die Spieler des Projekts
		Project p = project(t.projekt);
		if ((t.wer == null || t.wer.isEmpty()) && p != null && p.wer != null) t.wer = p.wer;
		if (p != null && p.bereich != null && p.bereich.valid() && takesProjectArea(t)) useProjectArea(t, p);
		tasks.add(t);
		send("PUT", "/aufgaben/" + t.id, withoutId(t));
		log("hat die Aufgabe \"" + t.titel + "\" angelegt", t, "neu");
		onChange.run();
	}

	/** Merkt sich, ab welchem Statistik-Wert ich in dieser Welt zähle (pro Person). */
	public void setBaseline(Task t, String me, int start, String welt) {
		Counter c = new Counter();
		c.start = start;
		c.basis = t.share(me);
		c.welt = welt;
		if (t.zaehler == null) t.zaehler = new HashMap<>();
		t.zaehler.put(key(me), c);
		JsonObject o = new JsonObject();
		o.add("zaehler/" + key(me), GSON.toJsonTree(c));
		send("PATCH", "/aufgaben/" + t.id, o);
	}

	/**
	 * Mein neuer Anteil; der Gesamtstand erledigt die Aufgabe, sobald das Ziel erreicht ist.
	 * build = Bau-Teil einer Kombi-Aufgabe (eigene Liste bauBeitrag).
	 */
	public void setProgress(Task t, String me, int mine, boolean build) {
		boolean useBau = build && t.isCombo();
		Map<String, Integer> map = useBau ? t.bauBeitrag : t.beitrag;
		boolean legacy = map == null || map.isEmpty();
		if (legacy) map = new HashMap<>();
		map.put(key(me), Math.max(0, mine));
		if (useBau) t.bauBeitrag = map; else t.beitrag = map;
		String field = useBau ? "bauBeitrag" : "beitrag";
		int total = goalTotal(t);
		t.fortschritt = t.total();
		JsonObject o = new JsonObject();
		if (legacy) o.add(field, GSON.toJsonTree(map));
		else o.addProperty(field + "/" + key(me), Math.max(0, mine));
		o.addProperty("fortschritt", t.fortschritt);
		boolean reached = applyGoalStatus(t, o, total);
		send("PATCH", "/aufgaben/" + t.id, o);
		flushMilestone(t);
		if (reached) {
			log((t.people().size() > 1 ? "hat mit " + others(t, me) + " " : "hat ") + "\"" + t.titel + "\" geschafft (" + t.ziel + " " + t.name() + ")", t, "erledigt");
			afterFinished(t);
		}
		// Verbaute Blöcke können ein Sammel-Ziel im selben Projekt abschließen.
		if (t.places()) checkGoalsDone();
		onChange.run();
	}

	private static String others(Task t, String me) {
		List<String> o = new ArrayList<>();
		for (String p : t.people()) if (!p.equalsIgnoreCase(me)) o.add(p);
		return names(o);
	}

	/** "Anna", "Anna und Ben", "Anna, Ben und Cem" */
	public static String names(List<String> l) {
		if (l.isEmpty()) return "";
		if (l.size() == 1) return l.get(0);
		return String.join(", ", l.subList(0, l.size() - 1)) + " und " + l.get(l.size() - 1);
	}

	public void setTaskStatus(Task t, String status) {
		t.status = status;
		patch("/aufgaben/" + t.id, "status", status);
		String text = switch (status) {
			case "fertig" -> "hat \"" + t.titel + "\" erledigt";
			case "arbeit" -> "arbeitet an \"" + t.titel + "\"";
			default -> "hat \"" + t.titel + "\" wieder geöffnet";
		};
		log(text, t, "fertig".equals(status) ? "erledigt" : "status");
		if ("fertig".equals(status)) afterFinished(t);
	}

	/** Neue Liste der Zuständigen. */
	public void setTaskPeople(Task t, List<String> people) {
		if (!canAssign(t, people)) {
			deny("Nur der Admin kann andere einteilen");
			return;
		}
		String me = myName();
		List<String> added = new ArrayList<>(), removed = new ArrayList<>();
		for (String p : people) if (!t.has(p)) added.add(p);
		for (String p : t.people()) {
			boolean stays = false;
			for (String q : people) if (q.equalsIgnoreCase(p)) stays = true;
			if (!stays) removed.add(p);
		}
		if (added.isEmpty() && removed.isEmpty()) return;
		List<String> parts = new ArrayList<>();
		boolean onlyMe = added.size() + removed.size() == 1 && (added.isEmpty() ? removed.get(0) : added.get(0)).equalsIgnoreCase(me);
		if (onlyMe && !added.isEmpty()) parts.add(people.size() > 1 ? "ist bei \"" + t.titel + "\" dabei" : "hat \"" + t.titel + "\" übernommen");
		else if (onlyMe) parts.add("hat sich von \"" + t.titel + "\" ausgetragen");
		else {
			if (!added.isEmpty()) parts.add("hat " + names(added) + " für \"" + t.titel + "\" eingeteilt");
			if (!removed.isEmpty()) parts.add((added.isEmpty() ? "hat " : "") + names(removed) + " von \"" + t.titel + "\" ausgetragen");
		}
		t.wer = String.join(",", people);
		patch("/aufgaben/" + t.id, "wer", t.wer);
		// Teilziele gehören denselben Leuten, damit bei ihnen mitgezählt wird.
		for (Task c : children(t)) { c.wer = t.wer; patch("/aufgaben/" + c.id, "wer", c.wer); }
		log(String.join(" und ", parts), t, "uebernommen");
	}

	/** Mich dazunehmen oder austragen. */
	public void toggleMe(Task t) {
		String me = myName();
		List<String> p = t.people();
		if (t.has(me)) p.removeIf(n -> n.equalsIgnoreCase(me));
		else p.add(me);
		setTaskPeople(t, p);
	}

	public void deleteTask(Task t) {
		if (!canDelete(t.ersteller)) {
			deny("Du kannst nur Aufgaben löschen, die du selbst angelegt hast");
			return;
		}
		JsonObject plans = new JsonObject();
		for (Task c : children(t)) {
			tasks.remove(c);
			send("DELETE", "/aufgaben/" + c.id, null);
			if (c.plan != null) plans.add(c.id, JsonNull.INSTANCE);
		}
		tasks.remove(t);
		send("DELETE", "/aufgaben/" + t.id, null);
		if (t.plan != null) plans.add(t.id, JsonNull.INSTANCE);
		if (!plans.isEmpty()) send("PATCH", "__bauplan", plans);
		log("hat " + (t.isChild() ? "das Ziel" : "die Aufgabe") + " \"" + t.titel + "\" gelöscht", t, "geloescht");
		onChange.run();
	}

	public void addStep(Task t, String text) {
		Step s = new Step();
		s.id = newId();
		s.text = text;
		s.pos = System.currentTimeMillis();
		if (t.schritte == null) t.schritte = new HashMap<>();
		t.schritte.put(s.id, s);
		send("PUT", "/aufgaben/" + t.id + "/schritte/" + s.id, withoutId(s));
		onChange.run();
	}

	public void toggleStep(Task t, Step s) {
		s.erledigt = !s.erledigt;
		JsonObject o = new JsonObject();
		o.addProperty("erledigt", s.erledigt);
		send("PATCH", "/aufgaben/" + t.id + "/schritte/" + s.id, o);
		if (s.erledigt) log("hat bei \"" + t.titel + "\" abgehakt: " + s.text, t, "schritt");
		onChange.run();
	}

	public void deleteStep(Task t, Step s) {
		if (!canDelete(t.ersteller)) {
			deny("Schritte löschen kann nur, wer die Aufgabe angelegt hat, oder der Admin");
			return;
		}
		t.schritte.remove(s.id);
		send("DELETE", "/aufgaben/" + t.id + "/schritte/" + s.id, null);
		onChange.run();
	}

	public void addComment(Task t, String text) {
		Comment c = new Comment();
		c.id = newId();
		c.wer = myName();
		c.text = text;
		c.zeit = System.currentTimeMillis();
		if (t.kommentare == null) t.kommentare = new HashMap<>();
		t.kommentare.put(c.id, c);
		send("PUT", "/aufgaben/" + t.id + "/kommentare/" + c.id, withoutId(c));
		log("hat \"" + t.titel + "\" kommentiert: " + text, t, "kommentar");
		onChange.run();
	}

	public void deleteComment(Task t, Comment c) {
		if (!canDelete(c.wer)) {
			deny("Du kannst nur deine eigenen Kommentare löschen");
			return;
		}
		t.kommentare.remove(c.id);
		send("DELETE", "/aufgaben/" + t.id + "/kommentare/" + c.id, null);
		onChange.run();
	}

	public void addMaterial(String item, String itemId, int need) {
		Material m = new Material();
		m.id = newId();
		m.item = item;
		m.itemId = itemId == null ? "" : itemId;
		m.need = need;
		m.ersteller = myName();
		m.projekt = config.projekt;
		materials.add(m);
		send("PUT", "/material/" + m.id, withoutId(m));
		onChange.run();
	}

	public void setHave(Material m, int have) {
		boolean wasDone = materialHave(m) >= m.need;
		m.have = Math.max(0, have);
		if (!wasDone && materialHave(m) >= m.need) logMaterialDone(m);
		JsonObject o = new JsonObject();
		o.addProperty("have", m.have);
		send("PATCH", "/material/" + m.id, o);
		onChange.run();
	}

	public void deleteMaterial(Material m) {
		if (!canDelete(m.ersteller)) {
			deny("Du kannst nur Material löschen, das du selbst eingetragen hast");
			return;
		}
		materials.remove(m);
		send("DELETE", "/material/" + m.id, null);
		onChange.run();
	}

	public void addPlace(String name, String dim, int x, int y, int z) {
		Place p = new Place();
		p.id = newId();
		p.name = name;
		p.dim = dim;
		p.x = x; p.y = y; p.z = z;
		p.ersteller = myName();
		places.add(p);
		send("PUT", "/orte/" + p.id, withoutId(p));
		onChange.run();
	}

	public void deletePlace(Place p) {
		if (!canDelete(p.ersteller)) {
			deny("Du kannst nur Orte löschen, die du selbst gespeichert hast");
			return;
		}
		places.remove(p);
		send("DELETE", "/orte/" + p.id, null);
		onChange.run();
	}

	/** Bekommt diese Aufgabe den Bereich des Projekts? (Bauen/Abbauen ohne eigenen Bereich, Aufgaben ohne Ort) */
	private static boolean takesProjectArea(Task t) {
		if (Boolean.TRUE.equals(t.bereichVomProjekt)) return true;
		if (t.plan != null || t.hasArea()) return false;
		return t.hasGoal() ? t.builds() : t.x == null;
	}

	private static void useProjectArea(Task t, Project p) {
		t.bereich = p.bereich;
		t.radius = null;
		t.bereichVomProjekt = true;
		if (t.dim == null || t.dim.isEmpty()) t.dim = p.dim;
	}

	/** Bereich eines Projekts setzen (null = entfernen) und an die passenden Aufgaben weitergeben. */
	public void setProjectArea(Project p, Area a, String dim) {
		if (!canDelete(p.ersteller)) {
			deny("Den Bereich ändern darf nur, wer das Projekt angelegt hat, oder der Admin");
			return;
		}
		JsonObject upd = new JsonObject();
		p.bereich = a;
		p.dim = a == null ? "" : dim;
		upd.add("projekte/" + p.id + "/bereich", a == null ? JsonNull.INSTANCE : GSON.toJsonTree(a));
		upd.addProperty("projekte/" + p.id + "/dim", p.dim);
		for (Task t : tasks) {
			if (!p.id.equals(t.projekt) || !takesProjectArea(t)) continue;
			String path = "aufgaben/" + t.id + "/";
			if (a != null) {
				useProjectArea(t, p);
				upd.add(path + "bereich", GSON.toJsonTree(a));
				upd.addProperty(path + "bereichVomProjekt", true);
				upd.addProperty(path + "dim", t.dim);
			} else {
				t.bereich = null;
				t.bereichVomProjekt = null;
				upd.add(path + "bereich", JsonNull.INSTANCE);
				upd.add(path + "bereichVomProjekt", JsonNull.INSTANCE);
			}
			// Neu zählen: Bestand im neuen Bereich
			t.bestand = null;
			t.bestandStart = null;
			upd.add(path + "radius", JsonNull.INSTANCE);
			upd.add(path + "bestand", JsonNull.INSTANCE);
			upd.add(path + "bestandStart", JsonNull.INSTANCE);
		}
		send("PATCH", "", upd);
		log(a == null ? "hat den Bereich vom Projekt \"" + p.name + "\" entfernt"
				: "hat den Bereich vom Projekt \"" + p.name + "\" festgelegt (" + a.text() + ")", null, "projekt");
		onChange.run();
	}

	public Project addProject(String name) {
		Project p = new Project();
		p.id = newId();
		p.name = name;
		p.ersteller = myName();
		p.erstellt = System.currentTimeMillis();
		projects.add(p);
		send("PUT", "/projekte/" + p.id, withoutId(p));
		log("hat das Projekt \"" + name + "\" angelegt", null, "projekt");
		onChange.run();
		return p;
	}

	/** Lager-Kiste festlegen; second = zweite Hälfte einer Doppelkiste oder null. */
	public Storage addStorage(BlockPos pos, BlockPos second, String dim, String name) {
		Storage s = new Storage();
		s.id = newId();
		s.name = name;
		s.x = pos.getX(); s.y = pos.getY(); s.z = pos.getZ();
		if (second != null) { s.x2 = second.getX(); s.y2 = second.getY(); s.z2 = second.getZ(); }
		s.dim = dim;
		s.ersteller = myName();
		s.projekt = config.projekt;
		storages.add(s);
		send("PUT", "/lager/" + s.id, withoutId(s));
		log("hat eine Lager-Kiste festgelegt (" + s.x + " " + s.y + " " + s.z + ")", null, "lager");
		onChange.run();
		return s;
	}

	public void deleteStorage(Storage s) {
		if (!canDelete(s.ersteller)) {
			deny("Lager entfernen kann nur, wer es festgelegt hat, oder der Admin");
			return;
		}
		storages.remove(s);
		send("DELETE", "/lager/" + s.id, null);
		log("hat eine Lager-Kiste entfernt (" + s.x + " " + s.y + " " + s.z + ")", null, "lager");
		onChange.run();
	}

	/** Neuer gezählter Inhalt einer Lager-Kiste (Item-ID -> Anzahl). */
	public void setStorageContent(Storage s, Map<String, Integer> content) {
		Set<String> doneBefore = new HashSet<>();
		for (Material m : materials) if (materialHave(m) >= m.need) doneBefore.add(m.id);
		JsonObject inhalt = new JsonObject();
		Map<String, Integer> keyed = new HashMap<>();
		content.forEach((k, v) -> { inhalt.addProperty(key(k), v); keyed.put(key(k), v); });
		s.inhalt = keyed;
		s.aktualisiert = System.currentTimeMillis();
		s.von = myName();
		JsonObject o = new JsonObject();
		o.add("inhalt", inhalt);
		o.addProperty("aktualisiert", s.aktualisiert);
		o.addProperty("von", s.von);
		send("PATCH", "/lager/" + s.id, o);
		for (Material m : materials) if (!m.zusatz && !doneBefore.contains(m.id) && materialFromStorage(m) && materialHave(m) >= m.need) logMaterialDone(m);
		checkGoalsDone();
		onChange.run();
	}

	private void logMaterialDone(Material m) {
		String proj = projectName(m.projekt);
		log("hat das Material komplett: " + m.need + " " + m.item + (proj.isEmpty() ? "" : " (Projekt " + proj + ")"), null, "material");
	}

	private void patch(String path, String field, String value) {
		JsonObject o = new JsonObject();
		o.addProperty(field, value);
		send("PATCH", path, o);
		onChange.run();
	}

	// ---------- Hilfen ----------

	public static String stacks(int n) {
		if (n <= 0) return "0";
		int sh = n / 1728, st = (n % 1728) / 64, r = n % 64;
		List<String> out = new ArrayList<>();
		if (sh > 0) out.add(sh + " Shulker");
		if (st > 0) out.add(st + (st == 1 ? " Stack" : " Stacks"));
		if (r > 0) out.add(r + " St.");
		return String.join(" + ", out);
	}

	public static String goalVerb(String art) {
		return switch (art == null ? "" : art) {
			case "abbauen" -> "abbauen";
			case "platzieren" -> "platzieren";
			case "kombi" -> "sammeln und verbauen";
			default -> "sammeln";
		};
	}

	public static String goalDone(String art) {
		return switch (art == null ? "" : art) {
			case "abbauen" -> "abgebaut";
			case "platzieren" -> "platziert";
			case "kombi" -> "verbaut";
			default -> "dabei";
		};
	}

	public static String dimName(String dim) {
		return switch (dim == null ? "" : dim) {
			case "nether" -> "Nether";
			case "end" -> "End";
			case "overworld" -> "Oberwelt";
			default -> "";
		};
	}

	public static String ago(long time) {
		long s = (System.currentTimeMillis() - time) / 1000;
		if (s < 60) return "gerade eben";
		if (s < 3600) return "vor " + s / 60 + " Min.";
		if (s < 86400) return "vor " + s / 3600 + " Std.";
		return "vor " + s / 86400 + " Tagen";
	}
}
