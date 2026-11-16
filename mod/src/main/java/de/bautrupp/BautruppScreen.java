package de.bautrupp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Das Menü, das sich mit J öffnet: Aufgaben, Material, Orte und Einstellungen.
 * Oben Projekt-Auswahl und Reiter, darunter eine Werkzeugzeile und eine schlanke Liste:
 * je Zeile Titel, Fortschritt und höchstens ein, zwei Knöpfe. Ein Klick auf eine Aufgabe öffnet sie.
 * Alles richtet sich nach der Fensterbreite.
 */
public class BautruppScreen extends Screen {
	private enum Tab { AUFGABEN, MATERIAL, ORTE, EINSTELLUNGEN }

	private static Tab tab = Tab.AUFGABEN;
	private static boolean onlyMine = false, showDone = false;

	/** Für die Vorschau beim Entwickeln: Menü mit einem bestimmten Reiter öffnen ("aufgaben", "material", ...). */
	static BautruppScreen withTab(Store store, String name) {
		for (Tab t : Tab.values()) if (t.name().equalsIgnoreCase(name)) tab = t;
		return new BautruppScreen(store);
	}

	static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GREEN = 0xFF7BD35A, YELLOW = 0xFFF2C94C, RED = 0xFFFF6B5E, BLUE = 0xFF6FB0E0;
	private static final int ROW_H = 24, TOOLBAR_Y = 50, LIST_TOP = 76;

	private final Store store;
	private int scroll = 0, settingsScroll = 0, settingsHeight = 0;
	private int left, panelW;
	private String lastSignature = "";
	private int activeTabX, activeTabW;
	private String noticeText;
	private long noticeUntil;

	// Eingaben überleben das Neuaufbauen der Knöpfe
	private String inText = "";
	private EditBox mainBox, searchBox;
	private String dbText, groupText;

	/** Eine Listenzeile: Titel, zweite Zeile, optional Balken (0-100, -1 = keiner), Klick öffnet die Aufgabe. */
	private record Row(int y, String title, int color, String sub, int subColor, int pct, String taskId, int textW) {}
	private record Label(int y, String text, int color) {}
	private final List<Row> rows = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();
	private final List<String> hints = new ArrayList<>();

	public BautruppScreen(Store store) {
		super(Component.literal("Bautrupp"));
		this.store = store;
		this.dbText = store.config.datenbank;
		this.groupText = store.config.gruppe;
	}

	@Override
	protected void init() {
		store.onChange = this::dataChanged;
		lastSignature = signature();
		panelW = Math.min(width - 16, 520);
		left = (width - panelW) / 2;
		rows.clear();
		labels.clear();
		hints.clear();
		mainBox = searchBox = null;

		// Projekt-Auswahl oben rechts: öffnet eine Liste
		int projW = Math.min(160, panelW / 3);
		Store.Project cur = store.project(store.config.projekt);
		button(font.plainSubstrByWidth("Projekt: " + projectLabel(), projW - 22) + " ▾", left + panelW - projW, 2, projW,
				b -> minecraft.gui.setScreen(new ProjectScreen(store, this)))
				.setTooltip(Tooltip.create(Component.literal("Projekt wählen oder neu anlegen. Neue Aufgaben und Material landen im gewählten Projekt."
						+ (cur != null && cur.wer != null && !cur.wer.isEmpty() ? "\nSpieler: " + cur.wer.replace(",", ", ") : "")
						+ (cur != null && cur.bereich != null && cur.bereich.valid() ? "\nBereich: " + cur.bereich.text() : ""))));

		// Reiter
		Tab[] tabs = Tab.values();
		String[] names = {"Aufgaben", "Material", "Orte", "Einstellungen"};
		int tw = (panelW - 3 * 4) / 4;
		for (int i = 0; i < tabs.length; i++) {
			Tab t = tabs[i];
			Button b = button(names[i], left + i * (tw + 4), 26, tw, x -> switchTab(t));
			b.active = tab != t;
			if (tab == t) { activeTabX = left + i * (tw + 4); activeTabW = tw; }
		}

		if (!store.config.isComplete() && tab != Tab.EINSTELLUNGEN) {
			// Ohne Datenbank gibt es nichts zu zeigen; erklären statt umleiten.
			hints.add("Noch nicht verbunden.");
			hints.add("Trag im Reiter Einstellungen die Datenbank-Adresse");
			hints.add("und euren Gruppencode ein (siehe ANLEITUNG.md).");
			button("Zu den Einstellungen", left, LIST_TOP + 44, 140, b -> switchTab(Tab.EINSTELLUNGEN));
			return;
		}
		switch (tab) {
			case AUFGABEN -> initTasks();
			case MATERIAL -> initMaterial();
			case ORTE -> initPlaces();
			case EINSTELLUNGEN -> initSettings();
		}
	}

	/** Kurze grüne Rückmeldung unten links. */
	void notice(String text) {
		noticeText = text;
		noticeUntil = System.currentTimeMillis() + 6000;
		store.error = null;
	}

	private String projectLabel() {
		Store.Project p = store.project(store.config.projekt);
		return p == null ? "Alle" : p.name;
	}

	private void switchTab(Tab t) {
		tab = t;
		scroll = 0;
		inText = "";
		rebuildWidgets();
	}

	private int listBottom() {
		return height - 20;
	}

	private int visibleRows() {
		return Math.max(1, (listBottom() - LIST_TOP) / ROW_H);
	}

	private EditBox box(int x, int y, int w, String value, String hint, int max, java.util.function.Consumer<String> onChange) {
		EditBox b = new EditBox(font, x, y, w, 20, Component.literal(hint));
		b.setMaxLength(max);
		b.setValue(value);
		b.setHint(Component.literal(hint));
		b.setResponder(onChange);
		return addRenderableWidget(b);
	}

	private Button button(String label, int x, int y, int w, Button.OnPress onPress) {
		return addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(x, y, w, 20).build());
	}

	private Button tip(Button b, String text) {
		b.setTooltip(Tooltip.create(Component.literal(text)));
		return b;
	}

	// ---------- Aufgaben ----------

	private void initTasks() {
		int x = left;
		tip(button("+ Neue Aufgabe", x, TOOLBAR_Y, 100, b -> minecraft.gui.setScreen(new NewTaskScreen(store, this))),
				"Neue Aufgabe anlegen – mit oder ohne Ziel (Sammeln, Abbauen, Platzieren)");
		x += 104;
		tip(button(onlyMine ? "Zeige: Meine" : "Zeige: Alle", x, TOOLBAR_Y, 84, b -> { onlyMine = !onlyMine; scroll = 0; rebuildWidgets(); }),
				"Zwischen allen und nur deinen Aufgaben wechseln");
		x += 88;
		tip(button(showDone ? "Fertige: an" : "Fertige: aus", x, TOOLBAR_Y, 84, b -> { showDone = !showDone; scroll = 0; rebuildWidgets(); }),
				"Erledigte Aufgaben ein- oder ausblenden");

		String me = Store.myName();
		List<Store.Task> list = new ArrayList<>();
		// In Arbeit zuerst, dann offen, dann (wenn gewünscht) fertig
		for (String st : showDone ? new String[]{"arbeit", "offen", "fertig"} : new String[]{"arbeit", "offen"})
			for (Store.Task t : store.tasks)
				// Teilziele erscheinen bei ihrer Aufgabe, nicht als eigene Zeile
				if (st.equals(t.status) && !t.isChild() && (!onlyMine || t.has(me)) && Store.inProject(t.projekt, store.config.projekt)) list.add(t);
		clampScroll(list.size());

		int right = left + panelW, y = LIST_TOP;
		for (int i = scroll; i < list.size() && i < scroll + visibleRows(); i++, y += ROW_H) {
			Store.Task t = list.get(i);
			int si = statusIndex(t.status);
			boolean locked = store.locked(t);
			boolean button = !(locked && si < 2);
			if (button) {
				String next = Store.STATUS[(si + 1) % 3];
				tip(button(si == 0 ? "Start" : si == 1 ? "Fertig" : "Öffnen", right - 56, y + 1, 56, b -> store.setTaskStatus(t, next)),
						"Status ändern auf: " + Store.STATUS_NAME[(si + 1) % 3]);
			}
			int color = si == 2 ? GREY : si == 1 ? YELLOW : locked ? GREY : WHITE;
			rows.add(new Row(y, t.titel, color, taskSub(t, locked), locked ? GREY : si == 1 ? YELLOW : GREY,
					taskPercent(t), t.id, panelW - (button ? 64 : 4)));
		}
		if (list.isEmpty()) hints.add(onlyMine ? "Du hast keine offenen Aufgaben. Stell oben auf \"Zeige: Alle\"."
				: "Keine offenen Aufgaben. Leg mit \"+ Neue Aufgabe\" eine an.");
	}

	/** Zweite Zeile einer Aufgabe: Stand, Leute, Projekt – kurz. */
	private String taskSub(Store.Task t, boolean locked) {
		List<String> parts = new ArrayList<>();
		if (locked) parts.add("Gesperrt, " + store.lockText(t));
		List<Store.Task> kids = store.children(t);
		if (!kids.isEmpty()) parts.add((kids.size() + (t.hasGoal() ? 1 : 0)) + " Ziele");
		else if (t.hasGoal()) parts.add((t.isCombo() ? t.progress() : store.goalTotal(t)) + "/" + t.ziel + " " + t.name());
		else if (t.schritte != null && !t.schritte.isEmpty()) parts.add("Schritte " + t.stepsDone() + "/" + t.schritte.size());
		List<String> people = t.people();
		parts.add(people.isEmpty() ? "niemand" : people.size() == 1 ? people.get(0) : people.get(0) + " +" + (people.size() - 1));
		if (store.config.projekt.isEmpty() && !t.projekt.isEmpty()) parts.add(store.projectName(t.projekt));
		if (t.kommentare != null && !t.kommentare.isEmpty()) parts.add(t.kommentare.size() + " Komm.");
		return String.join("  ·  ", parts);
	}

	/** Fortschritt in Prozent für den Balken, -1 = nichts zu zeigen. */
	private int taskPercent(Store.Task t) {
		if ("fertig".equals(t.status)) return 100;
		if (!store.children(t).isEmpty()) return store.percent(t);
		if (t.hasGoal()) return Math.min(100, (t.isCombo() ? t.progress() : store.goalTotal(t)) * 100 / Math.max(1, t.ziel));
		if (t.schritte != null && !t.schritte.isEmpty()) return t.stepsDone() * 100 / t.schritte.size();
		return -1;
	}

	private void deleteButton(int x, int y, String ersteller, Runnable onDelete) {
		Button b = button("x", x, y, 20, z -> onDelete.run());
		if (store.canDelete(ersteller)) tip(b, "Löschen");
		else {
			b.active = false;
			tip(b, "Löschen kann nur, wer es angelegt hat, oder der Admin");
		}
	}

	static int statusIndex(String s) {
		for (int i = 0; i < 3; i++) if (Store.STATUS[i].equals(s)) return i;
		return 0;
	}

	// ---------- Material ----------

	private static String matSearch = "";
	private static int matSort = 0;
	private static final String[] MAT_SORT = {"Name", "Fehlt meiste", "Wenigster Stand", "Meiste benötigt", "Meiste im Lager"};

	/** Eine Zeile im Material-Reiter: von Hand eingetragen (m) oder aus den Aufgaben (a). */
	private record MatRow(Store.Material m, Store.AutoMaterial a, String id, String name, int need, int have, int lager) {
		int missing() { return Math.max(0, need - have); }
		int pct() { return need > 0 ? Math.min(100, have * 100 / need) : 0; }
	}

	private void initMaterial() {
		int right = left + panelW;
		tip(button("+ Material", left, TOOLBAR_Y, 76, b -> minecraft.gui.setScreen(new AddMaterialScreen(store, this))),
				"Material von Hand auf die Liste setzen. Material aus Aufgaben mit Ziel steht automatisch drauf.");
		int sortW = Math.min(124, panelW / 3);
		EditBox search = box(left + 80, TOOLBAR_Y, panelW - 80 - sortW - 4, matSearch, "Suchen ...", 40, q -> {
			matSearch = q;
			scroll = 0;
			rebuildWidgets();
			setFocused(searchBox);
		});
		searchBox = search;
		button(font.plainSubstrByWidth("Sortieren: " + MAT_SORT[matSort], sortW - 8), right - sortW, TOOLBAR_Y, sortW, b -> {
			matSort = (matSort + 1) % MAT_SORT.length;
			scroll = 0;
			rebuildWidgets();
		});

		List<MatRow> list = new ArrayList<>();
		for (Store.Material m : store.materials) {
			if (!Store.inProject(m.projekt, store.config.projekt) || (m.zusatz && !m.itemId.isEmpty())) continue;
			String id = store.materialItemId(m);
			int have = store.materialHave(m);
			list.add(new MatRow(m, null, id, id.isEmpty() ? m.item : ItemNames.label(id, m.item), m.need, have, store.materialFromStorage(m) ? have : 0));
		}
		for (Store.AutoMaterial a : store.autoMaterials(store.config.projekt))
			list.add(new MatRow(null, a, a.item, ItemNames.label(a.item, a.itemName), a.need, a.have(), a.lager));
		list.removeIf(r -> !ItemNames.matchesSearch(r.id, r.name, matSearch));
		java.util.Comparator<MatRow> byName = java.util.Comparator.comparing(r -> r.name.toLowerCase(java.util.Locale.ROOT));
		java.util.Comparator<MatRow> order = switch (matSort) {
			case 1 -> java.util.Comparator.comparingInt(MatRow::missing).reversed();
			case 2 -> java.util.Comparator.comparingInt(MatRow::pct);
			case 3 -> java.util.Comparator.comparingInt(MatRow::need).reversed();
			case 4 -> java.util.Comparator.comparingInt(MatRow::lager).reversed();
			default -> byName;
		};
		list.sort(order.thenComparing(byName));

		clampScroll(list.size());
		int y = LIST_TOP;
		for (int i = scroll; i < list.size() && i < scroll + visibleRows(); i++, y += ROW_H) {
			MatRow r = list.get(i);
			boolean done = r.have >= r.need;
			List<String> parts = new ArrayList<>();
			parts.add(r.have + "/" + r.need);
			if (!done) parts.add("fehlt " + Store.stacks(r.missing()));
			if (r.lager > 0) parts.add("Lager " + r.lager);
			String taskId = null;
			int buttons = 0;
			if (r.a != null) {
				if (r.a.verbaut > 0) parts.add("verbaut " + r.a.verbaut);
				if (!r.a.tasks.isEmpty()) {
					parts.add("für " + r.a.tasks.get(0).titel + (r.a.tasks.size() > 1 ? " +" + (r.a.tasks.size() - 1) : ""));
					taskId = r.a.tasks.get(0).id;
				}
				if (r.a.extra > 0) parts.add(r.a.extra + " ohne Aufgabe");
			} else {
				Store.Material m = r.m;
				if (!store.materialFromStorage(m)) {
					tip(button("+64", right - 52, y + 1, 30, b -> store.setHave(m, m.have + 64)), "Einen Stack (64) dazu");
					buttons += 34;
				} else parts.add("aus dem Lager");
				deleteButton(right - 20, y + 1, m.ersteller, () -> store.deleteMaterial(m));
				buttons += 24;
			}
			rows.add(new Row(y, r.name, done ? GREEN : WHITE, String.join("  ·  ", parts), done ? GREEN : GREY, r.pct(), taskId, panelW - buttons - 4));
		}
		if (list.isEmpty()) hints.add(matSearch.isBlank() ? "Noch kein Material. Es kommt aus Aufgaben mit Ziel – oder über \"+ Material\"." : "Nichts gefunden.");
	}

	// ---------- Orte ----------

	private void initPlaces() {
		int right = left + panelW;
		mainBox = box(left, TOOLBAR_Y, panelW - 104, inText, "Name, z. B. Basis oder Dorf", 60, s -> inText = s);
		tip(button("Hier speichern", right - 100, TOOLBAR_Y, 100, b -> {
			if (inText.isBlank() || minecraft.player == null) return;
			BlockPos p = minecraft.player.blockPosition();
			store.addPlace(inText.trim(), BautruppClient.currentDim(), p.getX(), p.getY(), p.getZ());
			inText = "";
			rebuildWidgets();
		}), "Speichert deine aktuelle Position unter diesem Namen");

		clampScroll(store.places.size());
		int y = LIST_TOP;
		for (int i = scroll; i < store.places.size() && i < scroll + visibleRows(); i++, y += ROW_H) {
			Store.Place p = store.places.get(i);
			String sub = Store.dimName(p.dim) + "  ·  " + p.x + " " + p.y + " " + p.z;
			if ("overworld".equals(p.dim)) sub += "  ·  Nether: " + Math.floorDiv(p.x, 8) + " " + Math.floorDiv(p.z, 8);
			if ("nether".equals(p.dim)) sub += "  ·  Oberwelt: " + p.x * 8 + " " + p.z * 8;
			int dimColor = "nether".equals(p.dim) ? RED : "end".equals(p.dim) ? YELLOW : GREEN;
			String coords = p.x + " " + p.y + " " + p.z;
			tip(button("Kopieren", right - 88, y + 1, 64, b -> {
				minecraft.keyboardHandler.setClipboard(coords);
				notice("Koordinaten kopiert: " + coords);
			}), "Koordinaten in die Zwischenablage kopieren");
			deleteButton(right - 20, y + 1, p.ersteller, () -> store.deletePlace(p));
			rows.add(new Row(y, p.name, WHITE, sub, dimColor, -1, null, panelW - 96));
		}
		if (store.places.isEmpty()) hints.add("Noch keine Orte. Stell dich hin, gib einen Namen ein und klick auf \"Hier speichern\".");
	}

	// ---------- Einstellungen ----------

	/** Einstellungen in Abschnitten; passt es nicht in die Höhe, kann man mit dem Mausrad scrollen. */
	private void initSettings() {
		int top = TOOLBAR_Y - settingsScroll, y = top, third = (panelW - 8) / 3;

		labels.add(new Label(y, "Verbindung", GREEN));
		y += 12;
		box(left, y, panelW, dbText, "Datenbank-Adresse, https://...firebasedatabase.app", 300, s -> dbText = s);
		y += 24;
		box(left, y, panelW - 104, groupText, "Gruppencode, z. B. drachenburg-4821", 40, s -> groupText = s);
		tip(button("Speichern", left + panelW - 100, y, 100, b -> {
			store.config.datenbank = Config.normalizeUrl(dbText);
			store.config.gruppe = Config.normalizeGroup(groupText);
			dbText = store.config.datenbank;
			groupText = store.config.gruppe;
			store.config.save();
			store.loadedOnce = false;
			store.error = null;
			store.refresh();
			if (store.config.isComplete()) tab = Tab.AUFGABEN;
			rebuildWidgets();
		}), "Alle in der Gruppe tragen dieselbe Adresse und denselben Gruppencode ein.");
		y += 30;

		labels.add(new Label(y, "Anzeige im Spiel", GREEN));
		y += 12;
		boolean hudOn = !"aus".equals(store.config.anzeige);
		tip(button("Anzeige: " + (hudOn ? "An" : "Aus"), left, y, third, b -> {
			store.config.anzeige = hudOn ? "aus" : "links";
			store.config.save();
			rebuildWidgets();
		}), "Deine offenen Aufgaben im Spiel am Bildschirmrand");
		tip(button("Verschieben", left + third + 4, y, third, b -> minecraft.gui.setScreen(new HudEditScreen(this))),
				"Anzeige mit der Maus verschieben, breiter machen und vergrößern");
		boolean arrow = store.config.wegweiser;
		tip(button("Wegweiser: " + (arrow ? "An" : "Aus"), left + 2 * (third + 4), y, third, b -> {
			store.config.wegweiser = !arrow;
			store.config.save();
			rebuildWidgets();
		}), "Pfeil und Entfernung zur Baustelle in der Anzeige");
		y += 30;

		labels.add(new Label(y, "Chat, Namen und Updates", GREEN));
		y += 12;
		String mode = store.config.meldungen;
		tip(button("Meldungen: " + ("meine".equals(mode) ? "Meine" : "aus".equals(mode) ? "Aus" : "Alle"), left, y, third, b -> {
			store.config.meldungen = "alle".equals(mode) ? "meine" : "meine".equals(mode) ? "aus" : "alle";
			store.config.save();
			rebuildWidgets();
		}), "Meldungen über Änderungen der anderen im Chat (Alle / nur zu deinen Aufgaben / Aus). Sie sieht nur, wer die Mod hat.");
		boolean english = !"spiel".equals(store.config.itemSprache);
		tip(button("Namen: " + (english ? "Englisch" : "Spiel"), left + third + 4, y, third, b -> {
			store.config.itemSprache = english ? "spiel" : "englisch";
			store.config.save();
			rebuildWidgets();
		}), "Wie Items in der Mod heißen. Englisch passt zur Webseite. Eintippen kannst du immer beides.");
		boolean updates = store.config.autoUpdate;
		tip(button("Updates: " + (updates ? "An" : "Aus"), left + 2 * (third + 4), y, third, b -> {
			store.config.autoUpdate = !updates;
			store.config.save();
			if (!updates) Updater.check(msg -> minecraft.execute(() -> notice(msg)));
			rebuildWidgets();
		}), "Neue Bautrupp-Versionen automatisch laden (aktiv nach dem nächsten Neustart). Deine Version: " + Updater.currentVersion() + ". Von Hand: /bt update");
		y += 30;

		y = initAdmin(y);
		labels.add(new Label(y, "Hilfe: /bt pruefen, /bt suche <Item>, /bt projekt ecke1/ecke2, /bt update", GREY));
		y += 12;
		labels.add(new Label(y, "Alles Weitere steht in der Anleitung auf GitHub (yugito-11/bautrupp).", GREY));
		y += 12;
		settingsHeight = y - top;
		// Was beim Scrollen über den Reitern oder unter der Statuszeile landen würde, ausblenden
		for (var w : children())
			if (w instanceof net.minecraft.client.gui.components.AbstractWidget aw && aw.getY() >= TOOLBAR_Y - 200 && aw.getY() != 26 && aw.getY() != 2
					&& (aw.getY() < TOOLBAR_Y || aw.getY() + aw.getHeight() > listBottom())) aw.visible = false;
	}

	private int initAdmin(int y) {
		if (!store.config.isComplete() || !store.loadedOnce) return y;
		boolean admin = store.hasAdmin() && store.isAdmin();
		labels.add(new Label(y, !store.hasAdmin() ? "Admin (noch kein Passwort – solange darf jeder alles)"
				: admin ? "Admin: Du bist angemeldet" : "Admin (zum Einteilen anderer und Löschen)", GREEN));
		y += 12;
		EditBox pw = new EditBox(font, left, y, Math.max(80, panelW - 2 * 84), 20, Component.literal("Admin-Passwort"));
		pw.setMaxLength(64);
		pw.setHint(Component.literal("Admin-Passwort"));
		// Passwort verdeckt anzeigen, z. B. für Streams
		pw.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
		addRenderableWidget(pw);
		int bx = left + pw.getWidth() + 4, bw = (left + panelW - bx - 4) / 2;
		if (!store.hasAdmin()) {
			button("Festlegen", bx, y, bw * 2 + 4, b -> { if (store.setAdminPassword(pw.getValue())) rebuildWidgets(); });
		} else if (admin) {
			button("Ändern", bx, y, bw, b -> { if (store.setAdminPassword(pw.getValue())) rebuildWidgets(); });
			button("Abmelden", bx + bw + 4, y, bw, b -> { store.logoutAdmin(); rebuildWidgets(); });
		} else {
			button("Anmelden", bx, y, bw * 2 + 4, b -> { if (store.loginAdmin(pw.getValue())) rebuildWidgets(); });
		}
		return y + 30;
	}

	// ---------- Allgemein ----------

	private void clampScroll(int size) {
		scroll = Math.max(0, Math.min(scroll, size - visibleRows()));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (tab == Tab.EINSTELLUNGEN) {
			int max = Math.max(0, settingsHeight - (listBottom() - TOOLBAR_Y));
			int before = settingsScroll;
			settingsScroll = Math.max(0, Math.min(max, settingsScroll - (int) Math.signum(dy) * 16));
			if (settingsScroll != before) rebuildWidgets();
			return true;
		}
		int before = scroll;
		scroll = Math.max(0, scroll - (int) Math.signum(dy));
		if (scroll != before) rebuildWidgets();
		return true;
	}

	/** Klick auf eine Zeile (nicht auf einen Knopf): Aufgabe öffnen. */
	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (e.button() != 0) return false;
		for (Row r : rows) {
			if (r.taskId == null) continue;
			if (e.x() >= left && e.x() < left + r.textW && e.y() >= r.y && e.y() < r.y + ROW_H - 1) {
				minecraft.gui.setScreen(new TaskScreen(store, r.taskId, this));
				return true;
			}
		}
		return false;
	}

	/** Neu aufbauen, wenn sich die Daten geändert haben; die Eingabe behält den Fokus. */
	private void dataChanged() {
		String sig = signature();
		if (sig.equals(lastSignature)) return;
		boolean hadFocus = mainBox != null && getFocused() == mainBox;
		boolean searching = searchBox != null && getFocused() == searchBox;
		rebuildWidgets();
		if (hadFocus && mainBox != null) setFocused(mainBox);
		if (searching && searchBox != null) setFocused(searchBox);
	}

	private String signature() {
		StringBuilder sb = new StringBuilder(Objects.toString(store.error)).append(store.adminHash).append(store.isAdmin())
				.append(store.projects.size()).append(store.storages.size()).append(store.loadedOnce);
		for (Store.Storage st : store.storages) sb.append(st.aktualisiert);
		for (Store.Task t : store.tasks) sb.append(store.goalTotal(t)).append(t.schritte == null ? 0 : t.schritte.size()).append(t.stepsDone()).append(t.kommentare == null ? 0 : t.kommentare.size());
		for (Store.Task t : store.tasks) sb.append(t.id).append(t.status).append(t.wer).append('|');
		for (Store.Material m : store.materials) sb.append(m.id).append(m.have).append(m.need).append('|');
		for (Store.Place p : store.places) sb.append(p.id).append('|');
		sb.append(store.players);
		return sb.toString();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int projW = Math.min(160, panelW / 3);
		String head = "Bautrupp" + (store.config.gruppe.isEmpty() ? "" : "  ·  " + store.config.gruppe)
				+ (store.hasAdmin() && store.isAdmin() ? "  ·  Admin" : "");
		g.text(font, font.plainSubstrByWidth(head, panelW - projW - 8), left, 8, WHITE);
		// Grüner Strich unter dem geöffneten Reiter
		g.fill(activeTabX, 47, activeTabX + activeTabW, 49, GREEN);

		if (tab == Tab.AUFGABEN) {
			long all = store.tasks.stream().filter(t -> !t.isChild() && Store.inProject(t.projekt, store.config.projekt)).count();
			long done = store.tasks.stream().filter(t -> !t.isChild() && Store.inProject(t.projekt, store.config.projekt) && "fertig".equals(t.status)).count();
			String prog = done + " / " + all + " fertig";
			int free = panelW - 104 - 88 - 88;
			if (font.width(prog) + 8 < free) g.text(font, prog, left + panelW - font.width(prog), TOOLBAR_Y + 6, GREEN);
		}

		for (Row r : rows) {
			boolean hover = r.taskId != null && mouseX >= left && mouseX < left + r.textW && mouseY >= r.y && mouseY < r.y + ROW_H - 1;
			if (hover) g.fill(left - 2, r.y - 1, left + r.textW, r.y + ROW_H - 2, 0x30FFFFFF);
			int tw = r.textW - 4;
			g.text(font, font.plainSubstrByWidth(r.title, tw), left, r.y + 2, r.color);
			int sx = left;
			if (r.pct >= 0) {
				// Kleiner Balken vor der zweiten Zeile
				int bw = 36, by = r.y + 15;
				g.fill(sx, by, sx + bw, by + 4, 0xFF3A3A3A);
				g.fill(sx, by, sx + bw * r.pct / 100, by + 4, r.pct >= 100 ? GREEN : 0xFFC08A5E);
				String pct = r.pct + "%";
				g.text(font, pct, sx + bw + 3, r.y + 13, r.subColor);
				sx += bw + 3 + font.width(pct) + 6;
			}
			g.text(font, font.plainSubstrByWidth(r.sub, left + tw - sx), sx, r.y + 13, r.subColor);
			// Ganzen Text zeigen, wenn er abgeschnitten ist
			if (mouseX >= left && mouseX < left + tw && mouseY >= r.y && mouseY < r.y + ROW_H - 1
					&& (font.width(r.title) > tw || font.width(r.sub) > left + tw - sx))
				g.setTooltipForNextFrame(Component.literal(r.title + "\n" + r.sub), mouseX, mouseY);
		}
		for (Label l : labels) {
			if (l.y < TOOLBAR_Y - 2 || l.y > listBottom() - 8) continue;
			g.text(font, font.plainSubstrByWidth(l.text, panelW), left, l.y, l.color);
		}
		int hy = LIST_TOP + 6;
		for (String h : hints) {
			g.text(font, font.plainSubstrByWidth(h, panelW), left, hy, GREY);
			hy += 12;
		}

		String status;
		int color;
		if (!store.config.isComplete()) { status = "Nicht eingerichtet"; color = YELLOW; }
		else if (store.error != null) { status = store.error; color = RED; }
		else if (noticeText != null && System.currentTimeMillis() < noticeUntil) { status = noticeText; color = GREEN; }
		else if (!store.loadedOnce) { status = "Verbinde ..."; color = GREY; }
		else { status = "Verbunden"; color = GREEN; }
		String keyHint = tab == Tab.EINSTELLUNGEN ? (settingsHeight > listBottom() - TOOLBAR_Y ? "Mausrad: mehr" : "") : "Mausrad: blättern";
		if (tab == Tab.MATERIAL && store.hasStorage(store.config.projekt)) {
			long n = store.storages.stream().filter(st -> st.projekt.isEmpty() || store.config.projekt.isEmpty() || st.projekt.equals(store.config.projekt)).count();
			long last = store.storages.stream().mapToLong(st -> st.aktualisiert).max().orElse(0);
			keyHint = "Lager: " + n + (n == 1 ? " Kiste" : " Kisten") + (last > 0 ? ", gezählt " + Store.ago(last) : "");
		}
		g.text(font, font.plainSubstrByWidth(status, panelW - font.width(keyHint) - 8), left, height - 14, color);
		g.text(font, keyHint, left + panelW - font.width(keyHint), height - 14, BLUE);
	}

	@Override
	public void removed() {
		store.onChange = () -> {};
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
