package de.bautrupp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Eine Aufgabe: oben Status, Leute und Löschen, darunter vier Seiten –
 * Übersicht (Ziele und Stand), Schritte, Kommentare und Einstellungen (Bereich, Zählen, Reihenfolge, weiteres Ziel).
 */
public class TaskScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GREEN = 0xFF7BD35A, YELLOW = 0xFFF2C94C, RED = 0xFFFF6B5E;
	private static final int ROW_H = 22, PAGE_Y = 58, CONTENT_Y = 84;
	private static final String[] PAGES = {"Übersicht", "Schritte", "Kommentare", "Einstellungen"};
	private static int page = 0;
	/** Erste Ecke eines Bereichs, bis die zweite gesetzt ist. */
	private static net.minecraft.core.BlockPos corner1;
	private static String corner1Task = "";
	private static String subArt = "platzieren";

	/** Für die Vorschau beim Entwickeln: Seite vorwählen ("schritte", "einstellungen", ...). */
	static void presetPage(String name) {
		String[] keys = {"ueb", "sch", "kom", "ein"};
		for (int i = 0; i < keys.length; i++) if (name.toLowerCase().startsWith(keys[i])) page = i;
	}

	private final Store store;
	private final String taskId;
	private final Screen parent;
	private int left, panelW, scroll = 0, activePageX, activePageW;
	private String stepText = "", commentText = "", subName = "", subAmount = "";
	private EditBox stepBox, commentBox;
	private String lastSignature = "";
	private boolean confirmDelete = false;

	private record Text(int x, int y, FormattedCharSequence text, int color) {}
	private final List<Text> texts = new ArrayList<>();

	public TaskScreen(Store store, String taskId, Screen parent) {
		super(Component.literal("Aufgabe"));
		this.store = store;
		this.taskId = taskId;
		this.parent = parent;
	}

	private void text(int x, int y, String s, int color) {
		texts.add(new Text(x, y, Component.literal(font.plainSubstrByWidth(s, Math.max(0, left + panelW - x))).getVisualOrderText(), color));
	}

	private Button button(String label, int x, int y, int w, Button.OnPress onPress) {
		return addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, Math.max(8, w - 8))), onPress).bounds(x, y, w, 20).build());
	}

	private static Button tip(Button b, String s) {
		b.setTooltip(Tooltip.create(Component.literal(s)));
		return b;
	}

	private int bottom() {
		return height - 18;
	}

	@Override
	protected void init() {
		store.onChange = this::dataChanged;
		lastSignature = signature();
		texts.clear();
		stepBox = commentBox = null;
		panelW = Math.min(width - 16, 520);
		left = (width - panelW) / 2;

		button("Zurück", left + panelW - 60, 2, 60, b -> onClose());
		Store.Task t = store.task(taskId);
		if (t == null) {
			text(left, 40, "Diese Aufgabe gibt es nicht mehr.", GREY);
			return;
		}
		text(left, 8, t.titel, WHITE);

		// Status | Leute | Löschen
		int si = BautruppScreen.statusIndex(t.status), sw = Math.min(64, (panelW - 8) / 5);
		boolean locked = store.locked(t);
		for (int i = 0; i < 3; i++) {
			String to = Store.STATUS[i];
			Button b = button(Store.STATUS_NAME[i], left + i * sw, 24, sw, x -> store.setTaskStatus(t, to));
			b.active = i != si && !(locked && i > si);
			if (locked && i > si) tip(b, "Gesperrt: " + store.lockText(t));
		}
		int delW = 56, px = left + 3 * sw + 4, pw = panelW - 3 * sw - 4 - delW - 4;
		List<String> people = t.people();
		String me = Store.myName();
		String who = people.isEmpty() ? "Niemand" : people.size() == 1 ? people.get(0) : people.get(0) + " +" + (people.size() - 1);
		String peopleLabel = store.isAdmin() ? "Leute: " + who : t.has(me) ? "Austragen (" + who + ")" : "Mitmachen (" + who + ")";
		tip(button(peopleLabel, px, 24, pw, b -> {
			if (store.isAdmin()) minecraft.gui.setScreen(new PeopleScreen(store, t.id, this));
			else store.toggleMe(t);
		}), (people.isEmpty() ? "Noch niemand eingetragen." : "Dabei: " + t.peopleText() + ".")
				+ (store.isAdmin() ? " Klicken, um Leute einzuteilen." : t.has(me) ? " Klicken, um dich auszutragen." : " Klicken, um mitzumachen."));
		Button del = button(confirmDelete ? "Sicher?" : "Löschen", left + panelW - delW, 24, delW, b -> {
			if (!confirmDelete) { confirmDelete = true; rebuildWidgets(); return; }
			store.deleteTask(t);
			onClose();
		});
		del.active = store.canDelete(t.ersteller);
		tip(del, del.active ? "Aufgabe mit allen Zielen löschen (zweimal klicken)" : "Löschen kann nur, wer sie angelegt hat, oder der Admin");

		String info = (t.projekt.isEmpty() ? "" : "Projekt " + store.projectName(t.projekt) + "  ·  ")
				+ (t.x != null ? t.x + " " + (t.y == null ? "~" : t.y) + " " + t.z + (t.dim.isEmpty() ? "" : " " + Store.dimName(t.dim)) + "  ·  " : "")
				+ (t.ersteller.isEmpty() ? "" : "angelegt von " + t.ersteller);
		text(left, 48 - 2, info, GREY);

		// Seiten
		int tw = (panelW - 3 * 4) / 4;
		String[] labels = {"Übersicht", "Schritte (" + t.stepsDone() + "/" + t.steps().size() + ")", "Kommentare (" + t.comments().size() + ")", "Einstellungen"};
		for (int i = 0; i < 4; i++) {
			int p = i;
			Button b = button(labels[i], left + i * (tw + 4), PAGE_Y, tw, x -> { page = p; scroll = 0; rebuildWidgets(); });
			b.active = page != i;
			if (page == i) { activePageX = left + i * (tw + 4); activePageW = tw; }
		}

		contentStart = texts.size();
		switch (page) {
			case 1 -> initSteps(t);
			case 2 -> initComments(t);
			case 3 -> initSettings(t);
			default -> initOverview(t, locked);
		}
		// Was beim Scrollen über die Kopfzeile oder unter den Rand rutscht, ausblenden
		moreBelow = false;
		for (var w : children())
			if (w instanceof net.minecraft.client.gui.components.AbstractWidget aw && aw.getY() != 2 && aw.getY() != 24 && aw.getY() != PAGE_Y
					&& (aw.getY() < CONTENT_Y || aw.getY() + aw.getHeight() > bottom() + 2)) {
				aw.visible = false;
				if (aw.getY() > bottom()) moreBelow = true;
			}
		for (int i = contentStart; i < texts.size(); i++) if (texts.get(i).y > bottom() + 6) moreBelow = true;
	}

	/** Ab hier gehören die Texte zur Seite (davor: Kopfzeile). */
	private int contentStart = 0;
	private boolean moreBelow = false;

	// ---------- Übersicht ----------

	private void initOverview(Store.Task t, boolean locked) {
		int y = CONTENT_Y;
		if (locked) { text(left, y, "Gesperrt: " + store.lockText(t), RED); y += 12; }
		List<Store.Task> kids = store.children(t);
		if (!t.hasGoal() && kids.isEmpty()) {
			text(left, y, "Diese Aufgabe hat kein Ziel, das gezählt wird.", GREY);
			text(left, y + 12, "Ein Ziel (z. B. 80 Treppen platzieren) fügst du unter \"Einstellungen\" hinzu.", GREY);
			return;
		}
		if (t.hasGoal()) {
			String parts = store.goalParts(t);
			String line = t.isCombo() ? t.name() + ": " + parts
					: store.goalTotal(t) + " / " + t.ziel + " " + t.name() + " " + Store.goalDone(t.art) + (parts.isEmpty() ? "" : "  (" + parts + ")");
			text(left, y, line, YELLOW);
			y += 11;
			String mat = store.materialHint(t);
			if (!mat.isEmpty()) { text(left + 8, y, mat, GREY); y += 11; }
			text(left + 8, y, "Zählung: " + BestandScanner.why(store, t), GREY);
			y += 15;
		}
		if (kids.isEmpty()) return;
		text(left, y, "Ziele: " + kids.stream().filter(c -> "fertig".equals(c.status)).count() + "/" + kids.size() + " fertig, gesamt " + store.percent(t) + " %", GREEN);
		y += 13;
		int rows = Math.max(1, (bottom() - y) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, kids.size() - rows));
		for (int i = scroll; i < kids.size() && i < scroll + rows; i++, y += ROW_H) {
			Store.Task c = kids.get(i);
			boolean fin = "fertig".equals(c.status);
			text(left, y + 1, (fin ? "✔ " : "• ") + store.goalTotal(c) + " / " + c.ziel + " " + c.name() + " " + Store.goalDone(c.art), fin ? GREEN : WHITE);
			String mat = store.materialHint(c);
			text(left + 10, y + 11, (mat.isEmpty() ? "" : mat + "  ·  ") + BestandScanner.why(store, c), GREY);
			if (store.canEditTask(t)) tip(button("x", left + panelW - 20, y, 20, b -> store.deleteTask(c)), "Ziel entfernen");
		}
		if (kids.size() > rows) text(left, bottom() + 4, "Mausrad: weitere Ziele", GREY);
	}

	// ---------- Schritte ----------

	private void initSteps(Store.Task t) {
		stepBox = new EditBox(font, left, CONTENT_Y, panelW - 24, 20, Component.literal("Neuer Schritt"));
		stepBox.setMaxLength(80);
		stepBox.setValue(stepText);
		stepBox.setHint(Component.literal("Neuer Schritt, z. B. Fundament legen"));
		stepBox.setResponder(s -> stepText = s);
		addRenderableWidget(stepBox);
		button("+", left + panelW - 20, CONTENT_Y, 20, b -> {
			if (stepText.isBlank()) return;
			store.addStep(t, stepText.trim());
			stepText = "";
			rebuildWidgets();
			setFocused(stepBox);
		});
		List<Store.Step> steps = t.steps();
		int y = CONTENT_Y + 26, rows = Math.max(1, (bottom() - y) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, steps.size() - rows));
		for (int i = scroll; i < steps.size() && i < scroll + rows; i++, y += ROW_H) {
			Store.Step s = steps.get(i);
			tip(button(s.erledigt ? "✔" : " ", left, y, 20, b -> store.toggleStep(t, s)), s.erledigt ? "Wieder offen" : "Abhaken");
			text(left + 26, y + 6, s.text, s.erledigt ? GREY : WHITE);
			Button x = button("x", left + panelW - 20, y, 20, b -> store.deleteStep(t, s));
			x.active = store.canDelete(t.ersteller);
		}
		if (steps.isEmpty()) text(left, CONTENT_Y + 30, "Noch keine Schritte. Zerleg die Aufgabe in kleine Teile zum Abhaken.", GREY);
	}

	// ---------- Kommentare ----------

	private void initComments(Store.Task t) {
		commentBox = new EditBox(font, left, CONTENT_Y, panelW - 64, 20, Component.literal("Kommentar"));
		commentBox.setMaxLength(200);
		commentBox.setValue(commentText);
		commentBox.setHint(Component.literal("Schreib etwas ..."));
		commentBox.setResponder(s -> commentText = s);
		addRenderableWidget(commentBox);
		button("Senden", left + panelW - 60, CONTENT_Y, 60, b -> {
			if (commentText.isBlank()) return;
			store.addComment(t, commentText.trim());
			commentText = "";
			rebuildWidgets();
			setFocused(commentBox);
		});
		// Neueste zuerst, Text umbrochen
		List<Store.Comment> newest = new ArrayList<>(t.comments());
		java.util.Collections.reverse(newest);
		scroll = Math.max(0, Math.min(scroll, newest.size() - 1));
		int y = CONTENT_Y + 28;
		for (int i = scroll; i < newest.size(); i++) {
			Store.Comment c = newest.get(i);
			List<FormattedCharSequence> lines = font.split(Component.literal(c.text), panelW - 24);
			int h = 11 + lines.size() * 10 + 6;
			if (y + h > bottom()) break;
			text(left, y, c.wer + "  ·  " + Store.ago(c.zeit), YELLOW);
			int ly = y + 11;
			for (FormattedCharSequence line : lines) {
				texts.add(new Text(left, ly, line, WHITE));
				ly += 10;
			}
			if (store.canDelete(c.wer)) {
				Button x = Button.builder(Component.literal("x"), b -> store.deleteComment(t, c)).bounds(left + panelW - 14, y - 2, 14, 14).build();
				x.setTooltip(Tooltip.create(Component.literal("Kommentar löschen")));
				addRenderableWidget(x);
			}
			y += h;
		}
		if (newest.isEmpty()) text(left, CONTENT_Y + 30, "Noch keine Kommentare.", GREY);
	}

	// ---------- Einstellungen ----------

	private void initSettings(Store.Task t) {
		boolean edit = store.canEditTask(t);
		int y = CONTENT_Y - scroll * 24;
		List<Store.Task> kids = store.children(t);
		boolean builds = t.hasGoal() && t.builds(), collects = t.hasGoal() && t.collects();
		for (Store.Task c : kids) { builds |= c.builds(); collects |= c.collects(); }

		// Reihenfolge
		List<Store.Task> options = new ArrayList<>();
		for (Store.Task o : store.tasks)
			if (!o.id.equals(t.id) && !o.isChild() && java.util.Objects.equals(o.projekt, t.projekt) && !t.id.equals(o.nachAufgabe)) options.add(o);
		Store.Task before = store.task(t.nachAufgabe);
		text(left, y, "Reihenfolge", GREEN);
		Button after = tip(button("Erst nach: " + (before == null ? "–" : before.titel), left, y + 10, panelW, b -> {
			int i = before == null ? -1 : options.indexOf(before);
			store.setAfter(t, i + 1 < options.size() ? options.get(i + 1).id : "");
		}), "Die Aufgabe bleibt gesperrt, bis die gewählte fertig ist. Klicken wechselt durch die Aufgaben im Projekt, \"–\" = keine Reihenfolge.");
		after.active = edit && !options.isEmpty();
		y += 36;

		// Weiteres Ziel
		text(left, y, "Weiteres Ziel hinzufügen", GREEN);
		y += 10;
		int artW = 72, addW = 24, amountW = 48, nameW = panelW - artW - addW - amountW - 12;
		EditBox name = new EditBox(font, left, y, nameW, 20, Component.literal("Ziel"));
		name.setMaxLength(60);
		name.setValue(subName);
		name.setHint(Component.literal("Item, z. B. Oak Stairs"));
		name.setResponder(v -> subName = v);
		addRenderableWidget(name);
		EditBox amount = new EditBox(font, left + nameW + 4, y, amountW, 20, Component.literal("Anzahl"));
		amount.setMaxLength(7);
		amount.setValue(subAmount);
		amount.setHint(Component.literal("Anz."));
		amount.setResponder(v -> subAmount = v);
		addRenderableWidget(amount);
		String artLabel = switch (subArt) { case "sammeln" -> "Sammeln"; case "abbauen" -> "Abbauen"; case "kombi" -> "Samm.+Bau"; default -> "Platzieren"; };
		button(artLabel, left + nameW + amountW + 8, y, artW, b -> {
			subArt = switch (subArt) { case "platzieren" -> "kombi"; case "kombi" -> "sammeln"; case "sammeln" -> "abbauen"; default -> "platzieren"; };
			rebuildWidgets();
		});
		Button add = tip(button("+", left + panelW - addW, y, addW, b -> {
			var item = ItemNames.findItem(subName);
			int n;
			try { n = Integer.parseInt(subAmount.trim()); } catch (NumberFormatException e) { n = 0; }
			if (item == null || n <= 0) {
				store.error = item == null ? "Kein Item \"" + subName + "\" gefunden. Schreib den Namen auf Englisch oder wie im Spiel." : "Bitte eine Anzahl eintragen";
				rebuildWidgets();
				return;
			}
			String problem = BautruppClient.goalProblem(subArt, item);
			if (problem != null) { store.error = problem; rebuildWidgets(); return; }
			store.addSubGoal(t, subArt, GoalTracker.idOf(item), ItemNames.english(item), n);
			subName = "";
			subAmount = "";
			store.error = null;
			rebuildWidgets();
		}), "Weiteres Ziel zu dieser Aufgabe hinzufügen. Die Aufgabe ist fertig, wenn alle Ziele fertig sind.");
		add.active = edit;
		y += 30;

		if (!builds && !collects) return;
		if (!builds) {
			text(left, y, "Sammeln", GREEN);
			Button b = tip(button("Lager-Kisten zählen mit: " + (Boolean.FALSE.equals(t.lagerZaehlt) ? "Aus" : "An"), left, y + 10, panelW,
					x -> store.setStorageCounts(t, Boolean.FALSE.equals(t.lagerZaehlt))),
					"An: Was in den Lager-Kisten des Projekts liegt, zählt mit. Schon verbaute Blöcke zählen immer mit.");
			b.active = edit;
			return;
		}

		// Bereich
		var mc = minecraft;
		boolean waiting = corner1 != null && t.id.equals(corner1Task);
		text(left, y, "Bereich: " + (waiting ? "Ecke 1 gesetzt – jetzt zur zweiten Ecke gehen" : t.hasArea() ? t.areaText() : "zählt überall"), waiting ? YELLOW : GREEN);
		y += 10;
		int q = (panelW - 12) / 4;
		Button c1 = tip(button("Ecke 1 hier", left, y, q, b -> { corner1 = mc.player.blockPosition(); corner1Task = t.id; rebuildWidgets(); }),
				"An eine Ecke des Bereichs stellen und klicken, dann an die gegenüberliegende Ecke und \"Ecke 2 hier\".");
		Button c2 = button("Ecke 2 hier", left + q + 4, y, q, b -> {
			var p2 = mc.player.blockPosition();
			Store.Area a = new Store.Area();
			a.x1 = corner1.getX(); a.z1 = corner1.getZ(); a.x2 = p2.getX(); a.z2 = p2.getZ();
			// Unterschiedliche Höhen: Bereich gilt nur zwischen diesen Höhen, sonst für jede Höhe
			if (corner1.getY() != p2.getY()) { a.y1 = corner1.getY(); a.y2 = p2.getY(); }
			t.dim = BautruppClient.currentDim();
			store.setArea(t, a);
			corner1 = null;
			rebuildWidgets();
		});
		Button r = tip(button("Umkreis 32", left + 2 * (q + 4), y, q, b -> store.setRadius(t, mc.player.blockPosition(), BautruppClient.currentDim(), 32)),
				"Zählt nur im Umkreis von 32 Blöcken um deine Position");
		Button none = tip(button("Überall", left + 3 * (q + 4), y, q, b -> store.clearArea(t)), "Bereich entfernen: Blöcke zählen dann überall");
		c1.active = edit && mc.player != null;
		c2.active = edit && waiting && mc.player != null;
		r.active = edit && mc.player != null;
		none.active = edit && t.hasArea();
		y += 28;

		// Zählen
		boolean bestand = t.hasArea() && !"platzierungen".equals(t.modus);
		text(left, y, "Zählen", GREEN);
		y += 10;
		int h = (panelW - 4) / 2;
		Button mode = tip(button(!t.hasArea() ? "Zählt: was gesetzt wird" : bestand ? "Zählt: was im Bereich steht" : "Zählt: was gesetzt wird", left, y, h,
				b -> store.setMode(t, bestand ? "platzierungen" : "bestand")),
				!t.hasArea() ? "\"Was im Bereich steht\" geht nur mit Bereich." :
						"Was im Bereich steht: egal wer es gesetzt hat (jemand mit der Mod muss in der Nähe sein). Was gesetzt wird: nur, was Leute mit der Mod setzen oder abbauen.");
		mode.active = edit && t.hasArea();
		if (collects) {
			boolean on = !Boolean.FALSE.equals(t.lagerZaehlt);
			tip(button("Lager zählt mit: " + (on ? "An" : "Aus"), left + h + 4, y, h, b -> store.setStorageCounts(t, !on)),
					"Zählt beim Sammeln mit, was in den Lager-Kisten des Projekts liegt").active = edit;
		} else if (bestand && "abbauen".equals(t.goalKind())) {
			tip(button("Anfang neu zählen", left + h + 4, y, h, b -> store.resetBestandStart(t)),
					"Bei der nächsten Zählung gilt, was dann im Bereich steht, als Anfang").active = edit;
		}
		if (t.hasGoal()) text(left, y + 24, "Jetzt: " + BestandScanner.why(store, t), GREY);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		int before = scroll;
		scroll = Math.max(0, scroll - (int) Math.signum(dy));
		if (page == 3) scroll = Math.min(scroll, 4);
		if (scroll != before) rebuildWidgets();
		return true;
	}

	private void dataChanged() {
		String sig = signature();
		if (sig.equals(lastSignature)) return;
		boolean stepFocus = stepBox != null && getFocused() == stepBox;
		boolean commentFocus = commentBox != null && getFocused() == commentBox;
		rebuildWidgets();
		if (stepFocus && stepBox != null) setFocused(stepBox);
		if (commentFocus && commentBox != null) setFocused(commentBox);
	}

	private String signature() {
		Store.Task t = store.task(taskId);
		if (t == null) return "weg";
		StringBuilder sb = new StringBuilder(t.status).append(t.nachAufgabe).append(store.locked(t));
		for (Store.Task c : store.children(t)) sb.append(c.id).append(c.status).append(store.goalTotal(c));
		sb.append(t.wer).append(store.goalTotal(t)).append(t.areaText()).append(t.lagerZaehlt).append(t.modus).append(t.bestand).append(t.bestandZeit).append(store.isAdmin());
		for (Store.Step s : t.steps()) sb.append(s.id).append(s.erledigt);
		for (Store.Comment c : t.comments()) sb.append(c.id);
		return sb.toString();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.fill(activePageX, PAGE_Y + 21, activePageX + activePageW, PAGE_Y + 23, GREEN);
		for (int i = 0; i < texts.size(); i++) {
			Text t = texts.get(i);
			if (i >= contentStart && (t.y < CONTENT_Y - 2 || t.y > bottom() + 6)) continue;
			g.text(font, t.text, t.x, t.y, t.color);
		}
		Store.Task task = store.task(taskId);
		if (task != null && font.width(task.titel) > panelW - 70 && mouseY >= 6 && mouseY < 20 && mouseX >= left && mouseX < left + panelW - 70)
			g.setTooltipForNextFrame(Component.literal(task.titel), mouseX, mouseY);
		if (store.error != null) g.text(font, font.plainSubstrByWidth(store.error, panelW), left, height - 12, RED);
		else if (moreBelow || scroll > 0) {
			String h = moreBelow ? "Mausrad: mehr ▼" : "Mausrad: zurück ▲";
			g.text(font, h, left + panelW - font.width(h), height - 12, 0xFF6FB0E0);
		}
	}

	@Override
	public void removed() {
		store.onChange = () -> {};
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
