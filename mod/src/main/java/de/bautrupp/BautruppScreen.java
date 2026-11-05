package de.bautrupp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Das Menü, das sich mit J öffnet: Aufgaben, Material, Orte und Einstellungen. */
public class BautruppScreen extends Screen {
	private enum Tab { AUFGABEN, MATERIAL, ORTE, EINSTELLUNGEN }

	private static Tab tab = Tab.AUFGABEN;
	private static boolean onlyMine = false;
	private static String goalArt = "sammeln";

	private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GREEN = 0xFF7BD35A, YELLOW = 0xFFF2C94C, RED = 0xFFFF6B5E, BLUE = 0xFF6FB0E0;
	private static final int ROW_H = 24, LIST_TOP = 78;
	/** Umkreis für Abbau-/Platzier-Ziele, die mit "+ hier" angelegt werden. */
	private static final int AREA_RADIUS = 32;

	private final Store store;
	private int scroll = 0;
	private int left, panelW;
	private String lastSignature = "";
	private int activeTabX, activeTabW;
	private String noticeText;
	private long noticeUntil;

	// Eingaben überleben das Neuaufbauen der Knöpfe
	private String inText = "", inAmount = "";
	private EditBox mainBox, amountBox, searchBox;
	/** Oberkante der Liste (im Material-Reiter tiefer wegen Suche). */
	private int listTop = LIST_TOP;
	private String dbText, groupText;

	private record Line(int y, String text, int color, String sub, int subColor) {}
	private final List<Line> lines = new ArrayList<>();
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
		panelW = Math.min(width - 20, 460);
		left = (width - panelW) / 2;
		lines.clear();
		hints.clear();
		listTop = LIST_TOP;

		// Projekt-Auswahl oben rechts: Alle -> Projekt 1 -> Projekt 2 -> ...
		button("+", left + panelW - 174, 2, 20, b -> minecraft.gui.setScreen(new NameScreen("Neues Projekt", "z. B. Rathaus", this, name -> {
			Store.Project p = store.addProject(name);
			store.config.projekt = p.id;
			store.config.save();
		}))).setTooltip(Tooltip.create(Component.literal("Neues Projekt anlegen")));
		Button proj = button(font.plainSubstrByWidth("Projekt: " + projectLabel(), 140), left + panelW - 150, 2, 150, b -> {
			List<String> ids = new ArrayList<>();
			ids.add("");
			for (Store.Project pr : store.projects) ids.add(pr.id);
			int i = ids.indexOf(store.config.projekt);
			store.config.projekt = ids.get((i + 1) % ids.size());
			store.config.save();
			scroll = 0;
			rebuildWidgets();
		});
		Store.Project cur = store.project(store.config.projekt);
		proj.setTooltip(Tooltip.create(Component.literal(store.projects.isEmpty()
				? "Noch keine Projekte. Mit + links daneben legst du eins an."
				: "Klicken, um das Projekt zu wechseln. Neue Aufgaben und Material landen im gewählten Projekt."
				+ (cur != null && cur.wer != null && !cur.wer.isEmpty() ? "\nSpieler: " + cur.wer.replace(",", ", ") : "")
				+ (cur != null && cur.bereich != null && cur.bereich.valid() ? "\nBereich: " + cur.bereich.text() : "")
				+ (cur != null ? "\nBereich festlegen: /bt projekt ecke1 und ecke2" : ""))));

		// Reiter
		Tab[] tabs = Tab.values();
		String[] names = {"Aufgaben", "Material", "Orte", "Einstellungen"};
		int tw = (panelW - 3 * 4) / 4;
		for (int i = 0; i < tabs.length; i++) {
			Tab t = tabs[i];
			Button b = addRenderableWidget(Button.builder(Component.literal(names[i]), x -> switchTab(t))
					.bounds(left + i * (tw + 4), 24, tw, 20).build());
			b.active = tab != t;
			if (tab == t) { activeTabX = left + i * (tw + 4); activeTabW = tw; }
		}

		if (!store.config.isComplete() && tab != Tab.EINSTELLUNGEN) {
			// Ohne Datenbank gibt es nichts zu zeigen; erklären statt umleiten.
			hints.add("Noch nicht verbunden.");
			hints.add("Damit ihr eine gemeinsame Liste habt, trag im Reiter Einstellungen");
			hints.add("die Datenbank-Adresse und euren Gruppencode ein.");
			hints.add("Wie man die Datenbank erstellt, steht in der ANLEITUNG.md.");
			button("Zu den Einstellungen", left, LIST_TOP + 56, 140, b -> switchTab(Tab.EINSTELLUNGEN));
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
	private void notice(String text) {
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
		inAmount = "";
		rebuildWidgets();
	}

	private int visibleRows() {
		return Math.max(1, (height - listTop - 24) / ROW_H);
	}

	private EditBox box(int x, int w, String value, String hint, int max, java.util.function.Consumer<String> onChange) {
		EditBox b = new EditBox(font, x, 50, w, 20, Component.literal(hint));
		b.setMaxLength(max);
		b.setValue(value);
		b.setHint(Component.literal(hint));
		b.setResponder(onChange);
		return addRenderableWidget(b);
	}

	private Button button(String label, int x, int y, int w, Button.OnPress onPress) {
		return addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(x, y, w, 20).build());
	}

	// ---------- Aufgaben ----------

	private void initTasks() {
		int right = left + panelW;
		mainBox = box(left, panelW - 264, inText, "Neue Aufgabe, z. B. Brücke über den Fluss", 120, s -> inText = s);
		String artLabel = switch (goalArt) { case "abbauen" -> "Abbauen"; case "platzieren" -> "Platzieren"; case "kombi" -> "Samm.+Bau"; default -> "Sammeln"; };
		button(artLabel, right - 260, 50, 64, b -> {
			goalArt = switch (goalArt) { case "sammeln" -> "abbauen"; case "abbauen" -> "platzieren"; case "platzieren" -> "kombi"; default -> "sammeln"; };
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Art des Ziels, wenn du bei Anz. eine Zahl einträgst: Sammeln (so viele dabeihaben), Abbauen, Platzieren oder Sammeln + Bauen (beides in einer Aufgabe)")));
		amountBox = box(right - 192, 42, inAmount, "Anz.", 7, s -> inAmount = s);
		amountBox.setTooltip(Tooltip.create(Component.literal("Optional: Anzahl eingeben und links den Namen des Items schreiben (z. B. Block of Diamond oder Diamantblock). Ist das Feld leer, zählt das Item in deiner Hand. Die Aufgabe wird dann von selbst fertig.")));
		Button add = button("+", right - 146, 50, 20, b -> addTask(false));
		add.setTooltip(Tooltip.create(Component.literal("Aufgabe anlegen")));
		Button here = button("+ hier", right - 122, 50, 50, b -> addTask(true));
		here.setTooltip(Tooltip.create(Component.literal("Aufgabe mit deiner aktuellen Position anlegen. Bei Abbauen/Platzieren zählen dann nur Blöcke im Umkreis von " + AREA_RADIUS + " Blöcken.")));
		button(onlyMine ? "Meine" : "Alle", right - 68, 50, 68, b -> { onlyMine = !onlyMine; rebuildWidgets(); })
				.setTooltip(Tooltip.create(Component.literal("Zwischen allen und nur deinen Aufgaben wechseln")));

		String me = Store.myName();
		List<Store.Task> list = new ArrayList<>();
		// Offene zuerst, dann in Arbeit, dann fertig
		for (String st : new String[]{"arbeit", "offen", "fertig"})
			for (Store.Task t : store.tasks)
				// Teilziele erscheinen bei ihrer Aufgabe, nicht als eigene Zeile
				if (st.equals(t.status) && !t.isChild() && (!onlyMine || t.has(me)) && Store.inProject(t.projekt, store.config.projekt)) list.add(t);
		clampScroll(list.size());

		int y = LIST_TOP;
		for (int i = scroll; i < list.size() && i < scroll + visibleRows(); i++, y += ROW_H) {
			Store.Task t = list.get(i);
			int si = statusIndex(t.status);
			int color = si == 2 ? GREY : si == 1 ? YELLOW : WHITE;
			String sub = Store.STATUS_NAME[si];
			boolean locked = store.locked(t);
			if (locked) sub = "Gesperrt: " + store.lockText(t);
			List<Store.Task> kids = store.children(t);
			if (!kids.isEmpty()) sub += "  ·  " + (kids.size() + (t.hasGoal() ? 1 : 0)) + " Ziele, " + store.percent(t) + " %";
			else if (t.hasGoal() && t.isCombo()) sub += "  ·  " + t.name() + ": gesammelt " + store.collectTotal(t) + ", verbaut " + t.progress() + " / " + t.ziel;
			else if (t.hasGoal()) sub += "  ·  " + store.goalTotal(t) + " / " + t.ziel + " " + t.name() + " " + Store.goalDone(t.art);
			if (t.hasGoal() && t.hasArea() && !"sammeln".equals(t.goalKind())) sub += t.plan != null ? " (nach Bauplan)" : t.bestandMode() ? " (Bestand im Bereich)" : " (" + t.areaText() + ")";
			if (t.schritte != null && !t.schritte.isEmpty()) sub += "  ·  Schritte " + t.stepsDone() + "/" + t.schritte.size();
			if (t.kommentare != null && !t.kommentare.isEmpty()) sub += "  ·  " + t.kommentare.size() + " Komm.";
			if (store.config.projekt.isEmpty() && !t.projekt.isEmpty()) sub += "  ·  " + store.projectName(t.projekt);
			if (t.x != null) sub += "  ·  " + t.x + " " + (t.y == null ? "~" : t.y) + " " + t.z + (t.dim.isEmpty() ? "" : " " + Store.dimName(t.dim));
			lines.add(new Line(y, t.titel, color, sub, si == 1 ? YELLOW : si == 2 ? GREEN : GREY));

			button("...", right - 208, y, 20, b -> minecraft.gui.setScreen(new TaskScreen(store, t.id, this)))
					.setTooltip(Tooltip.create(Component.literal("Details: Schritte und Kommentare")));
			String next = Store.STATUS[(si + 1) % 3];
			String nextLabel = si == 0 ? "Start" : si == 1 ? "Fertig" : "Neu";
			Button stat = button(nextLabel, right - 184, y, 46, b -> store.setTaskStatus(t, next));
			stat.setTooltip(Tooltip.create(Component.literal(locked ? "Gesperrt: " + store.lockText(t) : "Status ändern auf: " + Store.STATUS_NAME[(si + 1) % 3])));
			stat.active = !locked || si == 2;
			List<String> people = t.people();
			String who = people.isEmpty() ? "Niemand" : people.size() == 1 ? people.get(0) : people.get(0) + " +" + (people.size() - 1);
			Button owner = button(font.plainSubstrByWidth(who, 100), right - 134, y, 110, b -> {
				// Admin: Leute auswählen; alle anderen: sich selbst dazunehmen oder austragen
				if (store.isAdmin()) minecraft.gui.setScreen(new PeopleScreen(store, t.id, this));
				else store.toggleMe(t);
			});
			String dabei = people.isEmpty() ? "Noch niemand eingetragen." : "Dabei: " + t.peopleText() + ".";
			if (store.isAdmin()) owner.setTooltip(Tooltip.create(Component.literal(dabei + " Klicken, um Leute einzuteilen.")));
			else owner.setTooltip(Tooltip.create(Component.literal(dabei + (t.has(me) ? " Klicken, um dich auszutragen." : " Klicken, um mitzumachen."))));
			deleteButton(right, y, t.ersteller, () -> store.deleteTask(t));
		}
		if (list.isEmpty()) hints.add(onlyMine ? "Du hast keine Aufgaben. Stell oben auf \"Alle\"." : "Noch keine Aufgaben. Schreib oben eine rein und klick auf +.");
	}

	private void deleteButton(int right, int y, String ersteller, Runnable onDelete) {
		Button x = button("x", right - 20, y, 20, b -> onDelete.run());
		if (store.canDelete(ersteller)) {
			x.setTooltip(Tooltip.create(Component.literal("Löschen")));
		} else {
			x.active = false;
			x.setTooltip(Tooltip.create(Component.literal("Löschen kann nur, wer es angelegt hat, oder der Admin")));
		}
	}

	private void addTask(boolean withPos) {
		String t = inText.trim();
		if (!inAmount.isBlank()) {
			addGoalTask(t, withPos);
			return;
		}
		if (t.isEmpty()) return;
		if (withPos && minecraft.player != null) {
			BlockPos p = minecraft.player.blockPosition();
			store.addTask(t, "", p.getX(), p.getY(), p.getZ(), BautruppClient.currentDim());
		} else {
			store.addTask(t, "", null, null, null, "");
		}
		inText = "";
		rebuildWidgets();
		setFocused(mainBox);
	}

	private void addGoalTask(String title, boolean withPos) {
		int amount;
		try {
			amount = Integer.parseInt(inAmount.trim());
		} catch (NumberFormatException e) {
			amount = 0;
		}
		if (amount <= 0) {
			store.error = "Bei Anzahl bitte eine Zahl größer als 0 eintragen";
			rebuildWidgets();
			return;
		}
		// Steht etwas im Feld, muss es ein Item-Name sein (z. B. "Diamantblock").
		// Nur wenn das Feld leer ist, wird das Item in der Hand genommen.
		ItemStack hand;
		if (!title.isEmpty()) {
			hand = ItemNames.find(title);
			if (hand == null) {
				List<String> s = ItemNames.suggest(title, 3);
				store.error = s.isEmpty() ? "Kein Item \"" + title + "\" gefunden. Schreib den Namen auf Englisch oder wie im Spiel."
						: "Kein Item \"" + title + "\". Meintest du: " + String.join(", ", s) + "?";
				rebuildWidgets();
				return;
			}
			title = "";
		} else {
			hand = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
			if (hand.isEmpty()) {
				store.error = "Schreib den Namen des Items ins Feld (z. B. Block of Diamond) oder nimm es in die Hand";
				rebuildWidgets();
				return;
			}
		}
		String problem = BautruppClient.goalProblem(goalArt, hand);
		if (problem != null) {
			store.error = problem;
			rebuildWidgets();
			return;
		}
		String name = ItemNames.label(hand);
		if (title.isEmpty()) title = amount + " " + name + " " + Store.goalVerb(goalArt);
		BlockPos center = withPos ? minecraft.player.blockPosition() : null;
		store.addGoalTask(title, Store.myName(), goalArt, GoalTracker.idOf(hand), ItemNames.english(hand), amount, center, BautruppClient.currentDim(), AREA_RADIUS);
		notice("Aufgabe angelegt: " + title);
		inText = "";
		inAmount = "";
		store.error = null;
		rebuildWidgets();
		setFocused(mainBox);
	}

	private static int statusIndex(String s) {
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
		mainBox = box(left, panelW - 196, inText, "Item, z. B. Block of Diamond", 60, s -> inText = s);
		amountBox = box(right - 192, 80, inAmount, "Anzahl", 8, s -> inAmount = s);
		amountBox.setTooltip(Tooltip.create(Component.literal("Benötigte Menge in Stück (1 Stack = 64, 1 Shulker = 1728)")));
		button("+", right - 108, 50, 50, b -> addMaterial(false))
				.setTooltip(Tooltip.create(Component.literal("Mit dem Namen links eintragen")));
		button("+ Hand", right - 54, 50, 54, b -> addMaterial(true))
				.setTooltip(Tooltip.create(Component.literal("Das Item in deiner Hand eintragen. Dann zählt die Lager-Kiste automatisch mit.")));

		// Suche und Sortierung
		EditBox search = new EditBox(font, left, LIST_TOP - 2, panelW - 134, 18, Component.literal("Suchen"));
		search.setMaxLength(40);
		search.setValue(matSearch);
		search.setHint(Component.literal("Material suchen ..."));
		search.setResponder(q -> { matSearch = q; scroll = 0; rebuildWidgets(); setFocused(searchBox); });
		searchBox = addRenderableWidget(search);
		addRenderableWidget(Button.builder(Component.literal("Sortieren: " + MAT_SORT[matSort]), b -> {
			matSort = (matSort + 1) % MAT_SORT.length;
			scroll = 0;
			rebuildWidgets();
		}).bounds(right - 130, LIST_TOP - 2, 130, 18).build());
		listTop = LIST_TOP + 22;

		List<MatRow> rows = new ArrayList<>();
		for (Store.Material m : store.materials) {
			if (!Store.inProject(m.projekt, store.config.projekt) || (m.zusatz && !m.itemId.isEmpty())) continue;
			String id = store.materialItemId(m);
			int have = store.materialHave(m);
			rows.add(new MatRow(m, null, id, id.isEmpty() ? m.item : ItemNames.label(id, m.item), m.need, have, store.materialFromStorage(m) ? have : 0));
		}
		for (Store.AutoMaterial a : store.autoMaterials(store.config.projekt))
			rows.add(new MatRow(null, a, a.item, ItemNames.label(a.item, a.itemName), a.need, a.have(), a.lager));
		rows.removeIf(r -> !ItemNames.matchesSearch(r.id, r.name, matSearch));
		java.util.Comparator<MatRow> byName = java.util.Comparator.comparing(r -> r.name.toLowerCase(java.util.Locale.ROOT));
		java.util.Comparator<MatRow> order = switch (matSort) {
			case 1 -> java.util.Comparator.comparingInt(MatRow::missing).reversed();
			case 2 -> java.util.Comparator.comparingInt(MatRow::pct);
			case 3 -> java.util.Comparator.comparingInt(MatRow::need).reversed();
			case 4 -> java.util.Comparator.comparingInt(MatRow::lager).reversed();
			default -> byName;
		};
		rows.sort(order.thenComparing(byName));

		clampScroll(rows.size());
		int y = listTop;
		for (int i = scroll; i < rows.size() && i < scroll + visibleRows(); i++, y += ROW_H) {
			MatRow r = rows.get(i);
			boolean done = r.have >= r.need;
			String sub = r.have + " / " + r.need + "  (" + r.pct() + " %)";
			if (r.a != null) {
				sub += "  Lager " + r.lager + (r.a.verbaut > 0 ? ", verbaut " + r.a.verbaut : "");
				if (!r.a.tasks.isEmpty()) sub += "  ·  für " + r.a.tasks.get(0).titel + (r.a.tasks.size() > 1 ? " +" + (r.a.tasks.size() - 1) : "");
				if (r.a.extra > 0) sub += "  ·  " + r.a.extra + " ohne Aufgabe";
			} else if (store.materialFromStorage(r.m)) sub += " im Lager";
			if (!done) sub += "  ·  fehlt " + Store.stacks(r.missing());
			lines.add(new Line(y, r.name + (r.a != null ? "  (aus Aufgaben)" : ""), done ? GREEN : WHITE, sub, done ? GREEN : GREY));
			if (r.m != null) {
				Store.Material m = r.m;
				if (!store.materialFromStorage(m)) {
					button("-64", right - 140, y, 32, b -> store.setHave(m, m.have - 64));
					button("+1", right - 104, y, 26, b -> store.setHave(m, m.have + 1));
					button("+64", right - 74, y, 32, b -> store.setHave(m, m.have + 64));
				}
				deleteButton(right, y, m.ersteller, () -> store.deleteMaterial(m));
			} else if (!r.a.tasks.isEmpty()) {
				Store.Task t = r.a.tasks.get(0);
				button("...", right - 20, y, 20, b -> minecraft.gui.setScreen(new TaskScreen(store, t.id, this)))
						.setTooltip(Tooltip.create(Component.literal("Zur Aufgabe: " + t.titel)));
			}
		}
		if (rows.isEmpty()) hints.add(matSearch.isBlank() ? "Noch nichts auf der Liste. Trag oben ein Item und die Anzahl ein." : "Nichts gefunden.");
	}

	private void addMaterial(boolean fromHand) {
		String item = inText.trim();
		int need;
		try {
			need = Integer.parseInt(inAmount.trim());
		} catch (NumberFormatException e) {
			need = 0;
		}
		if (need <= 0) {
			store.error = "Bei Anzahl bitte eine Zahl größer als 0 eintragen";
			rebuildWidgets();
			return;
		}
		String itemId;
		if (fromHand) {
			ItemStack hand = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
			if (hand.isEmpty()) {
				store.error = "Nimm das Item in die Hand";
				rebuildWidgets();
				return;
			}
			itemId = GoalTracker.idOf(hand);
			if (item.isEmpty()) item = ItemNames.english(hand);
		} else {
			if (item.isEmpty()) return;
			ItemStack found = ItemNames.find(item);
			itemId = found == null ? "" : GoalTracker.idOf(found);
			if (found != null) {
				item = ItemNames.english(found);
				notice("Erkannt: " + ItemNames.label(found) + (store.hasStorage(store.config.projekt) ? ". Das Lager zählt automatisch mit." : ""));
			} else {
				List<String> s = ItemNames.suggest(item, 3);
				notice("Kein Item erkannt" + (s.isEmpty() ? "" : " (meintest du " + String.join(", ", s) + "?)") + ". Wird von Hand gezählt.");
			}
		}
		store.addMaterial(item, itemId, need);
		inText = "";
		inAmount = "";
		rebuildWidgets();
	}

	// ---------- Orte ----------

	private void initPlaces() {
		int right = left + panelW;
		mainBox = box(left, panelW - 124, inText, "Name, z. B. Basis oder Dorf", 60, s -> inText = s);
		button("Hier speichern", right - 120, 50, 120, b -> {
			if (inText.isBlank() || minecraft.player == null) return;
			BlockPos p = minecraft.player.blockPosition();
			store.addPlace(inText.trim(), BautruppClient.currentDim(), p.getX(), p.getY(), p.getZ());
			inText = "";
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Speichert deine aktuelle Position")));

		clampScroll(store.places.size());
		int y = LIST_TOP;
		for (int i = scroll; i < store.places.size() && i < scroll + visibleRows(); i++, y += ROW_H) {
			Store.Place p = store.places.get(i);
			String sub = Store.dimName(p.dim) + "  ·  " + p.x + " " + p.y + " " + p.z;
			if ("overworld".equals(p.dim)) sub += "  ·  Nether: " + Math.floorDiv(p.x, 8) + " " + Math.floorDiv(p.z, 8);
			if ("nether".equals(p.dim)) sub += "  ·  Oberwelt: " + p.x * 8 + " " + p.z * 8;
			int dimColor = "nether".equals(p.dim) ? RED : "end".equals(p.dim) ? YELLOW : GREEN;
			lines.add(new Line(y, p.name, WHITE, sub, dimColor));
			String coords = p.x + " " + p.y + " " + p.z;
			button("Kopieren", right - 84, y, 60, b -> {
				minecraft.keyboardHandler.setClipboard(coords);
				BautruppClient.chat("Koordinaten kopiert: " + coords);
			}).setTooltip(Tooltip.create(Component.literal("Koordinaten in die Zwischenablage kopieren")));
			deleteButton(right, y, p.ersteller, () -> store.deletePlace(p));
		}
		if (store.places.isEmpty()) hints.add("Noch keine Orte. Stell dich hin, gib einen Namen ein und klick auf Hier speichern.");
	}

	// ---------- Einstellungen ----------

	private void initSettings() {
		EditBox db = new EditBox(font, left, LIST_TOP + 12, panelW, 20, Component.literal("Datenbank"));
		db.setMaxLength(300);
		db.setValue(dbText);
		db.setHint(Component.literal("https://...firebasedatabase.app"));
		db.setResponder(s -> dbText = s);
		addRenderableWidget(db);

		EditBox gr = new EditBox(font, left, LIST_TOP + 56, panelW / 2, 20, Component.literal("Gruppencode"));
		gr.setMaxLength(40);
		gr.setValue(groupText);
		gr.setHint(Component.literal("z. B. drachenburg-4821"));
		gr.setResponder(s -> groupText = s);
		addRenderableWidget(gr);

		button("Speichern", left, LIST_TOP + 88, 100, b -> {
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
		});
		boolean hudOn = !"aus".equals(store.config.anzeige);
		button("Anzeige: " + (hudOn ? "An" : "Aus"), left + 108, LIST_TOP + 88, 78, b -> {
			store.config.anzeige = hudOn ? "aus" : "links";
			store.config.save();
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Zeigt deine offenen Aufgaben im Spiel am Bildschirmrand")));
		button("Verschieben", left + 190, LIST_TOP + 88, 80, b -> minecraft.gui.setScreen(new HudEditScreen(this)))
				.setTooltip(Tooltip.create(Component.literal("Anzeige mit der Maus verschieben, breiter machen und vergrößern")));
		String mode = store.config.meldungen;
		String mLabel = "Meldungen: " + ("meine".equals(mode) ? "Nur meine" : "aus".equals(mode) ? "Aus" : "Alle");
		boolean arrow = store.config.wegweiser;
		button("Wegweiser: " + (arrow ? "An" : "Aus"), left + 378, LIST_TOP + 88, 82, b -> {
			store.config.wegweiser = !arrow;
			store.config.save();
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Pfeil und Entfernung zur Baustelle in der Anzeige am Bildschirmrand")));
		button(mLabel, left + 274, LIST_TOP + 88, 100, b -> {
			store.config.meldungen = "alle".equals(mode) ? "meine" : "meine".equals(mode) ? "aus" : "alle";
			store.config.save();
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Meldungen über Änderungen der anderen im Chat. Sie sieht nur, wer die Mod hat; an den Server geht nichts.")));
		boolean english = !"spiel".equals(store.config.itemSprache);
		button("Item-Namen: " + (english ? "Englisch" : "Spielsprache"), left + panelW / 2 + 8, LIST_TOP + 56, panelW / 2 - 8, b -> {
			store.config.itemSprache = english ? "spiel" : "englisch";
			store.config.save();
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Wie Items in der Mod heißen. Englisch passt zur Webseite. Eintippen kannst du immer beides.")));
		lines.add(new Line(LIST_TOP, "Datenbank-Adresse (aus Firebase)", GREY, null, 0));
		lines.add(new Line(LIST_TOP + 44, "Gruppencode (alle in der Gruppe nehmen denselben)", GREY, null, 0));
		initAdmin(LIST_TOP + 118);
	}

	private void initAdmin(int y) {
		if (!store.config.isComplete() || !store.loadedOnce) return;
		boolean admin = store.hasAdmin() && store.isAdmin();
		String label = !store.hasAdmin() ? "Admin-Passwort festlegen (danach dürfen nur Admins andere einteilen)"
				: admin ? "Du bist Admin. Neues Passwort festlegen:" : "Admin-Passwort (zum Einteilen anderer Spieler)";
		lines.add(new Line(y, label, admin ? GREEN : GREY, null, 0));

		EditBox pw = new EditBox(font, left, y + 12, 160, 20, Component.literal("Admin-Passwort"));
		pw.setMaxLength(64);
		pw.setHint(Component.literal("Passwort"));
		// Passwort verdeckt anzeigen, z. B. für Streams
		pw.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
		addRenderableWidget(pw);

		if (!store.hasAdmin()) {
			button("Festlegen", left + 166, y + 12, 80, b -> { if (store.setAdminPassword(pw.getValue())) rebuildWidgets(); });
		} else if (admin) {
			button("Ändern", left + 166, y + 12, 60, b -> { if (store.setAdminPassword(pw.getValue())) rebuildWidgets(); });
			button("Abmelden", left + 232, y + 12, 70, b -> { store.logoutAdmin(); rebuildWidgets(); });
		} else {
			button("Anmelden", left + 166, y + 12, 80, b -> { if (store.loginAdmin(pw.getValue())) rebuildWidgets(); });
		}
	}

	// ---------- Allgemein ----------

	private void clampScroll(int size) {
		scroll = Math.max(0, Math.min(scroll, size - visibleRows()));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		int before = scroll;
		scroll = Math.max(0, scroll - (int) Math.signum(dy));
		if (scroll != before) rebuildWidgets();
		return true;
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
				.append(store.projects.size()).append(store.storages.size());
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
		String head = "Bautrupp" + (store.config.gruppe.isEmpty() ? "" : "  ·  Gruppe " + store.config.gruppe)
				+ (store.hasAdmin() && store.isAdmin() ? "  ·  Admin" : "");
		g.text(font, head, left, 9, WHITE);
		// Grüner Strich unter dem geöffneten Reiter
		g.fill(activeTabX, 45, activeTabX + activeTabW, 47, GREEN);

		if (tab == Tab.AUFGABEN) {
			long all = store.tasks.stream().filter(t -> Store.inProject(t.projekt, store.config.projekt)).count();
			long done = store.tasks.stream().filter(t -> Store.inProject(t.projekt, store.config.projekt) && "fertig".equals(t.status)).count();
			String prog = done + " / " + all + " erledigt";
			g.text(font, prog, left + panelW - 180 - font.width(prog), 9, GREEN);
		}

		int textW = switch (tab) {
			case AUFGABEN -> panelW - 214;
			case MATERIAL -> panelW - 148;
			case ORTE -> panelW - 92;
			default -> panelW;
		};
		for (Line l : lines) {
			// Ganzen Namen zeigen, wenn er abgeschnitten ist und die Maus darauf steht
			if (l.sub != null && font.width(l.text) > textW && mouseX >= left && mouseX <= left + textW && mouseY >= l.y && mouseY < l.y + ROW_H - 2)
				g.setTooltipForNextFrame(Component.literal(l.text), mouseX, mouseY);
			if (l.sub == null) {
				g.text(font, l.text, left, l.y, l.color);
			} else {
				g.text(font, font.plainSubstrByWidth(l.text, textW), left, l.y + 1, l.color);
				g.text(font, font.plainSubstrByWidth(l.sub, textW), left, l.y + 12, l.subColor);
			}
		}
		int hy = listTop + 6;
		for (String h : hints) {
			g.text(font, h, left, hy, GREY);
			hy += 12;
		}
		if (tab == Tab.EINSTELLUNGEN) {
			int y = LIST_TOP + 150;
			for (String s : new String[]{
					"Alle tragen dieselbe Adresse und denselben Gruppencode ein.",
					"Befehle: /bt aufgabe, /bt sammeln, /bt abbauen, /bt platzieren,",
					"/bt ort, /bt meine, /bt anzeige bearbeiten, /bt admin (siehe Anleitung)"}) {
				if (y > height - 26) break;
				g.text(font, s, left, y, GREY);
				y += 11;
			}
		}

		String status;
		int color;
		if (!store.config.isComplete()) { status = "Nicht eingerichtet"; color = YELLOW; }
		else if (store.error != null) { status = store.error; color = RED; }
		else if (noticeText != null && System.currentTimeMillis() < noticeUntil) { status = noticeText; color = GREEN; }
		else if (!store.loadedOnce) { status = "Verbinde ..."; color = GREY; }
		else { status = "Verbunden · aktualisiert sich automatisch"; color = GREEN; }
		g.text(font, status, left, height - 14, color);
		String keyHint = "Mausrad: blättern";
		if (tab == Tab.MATERIAL && store.hasStorage(store.config.projekt)) {
			long n = store.storages.stream().filter(st -> st.projekt.isEmpty() || store.config.projekt.isEmpty() || st.projekt.equals(store.config.projekt)).count();
			long last = store.storages.stream().mapToLong(st -> st.aktualisiert).max().orElse(0);
			keyHint = "Lager: " + n + (n == 1 ? " Kiste" : " Kisten") + (last > 0 ? ", gezählt " + Store.ago(last) : "");
		}
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
