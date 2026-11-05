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

/** Details einer Aufgabe: Schritte zum Abhaken (links) und Kommentare (rechts). */
public class TaskScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GREEN = 0xFF7BD35A, YELLOW = 0xFFF2C94C, RED = 0xFFFF6B5E;
	private static final int STEP_H = 22;
	/** Bei Zielen kommt eine Zeile mit Knöpfen dazu; alles darunter rutscht nach unten. */
	private int off = 0, listTop = 80;
	/** Erste Ecke eines Bereichs, bis die zweite gesetzt ist. */
	private static net.minecraft.core.BlockPos corner1;
	private static String corner1Task = "";

	private final Store store;
	private final String taskId;
	private final Screen parent;
	private int left, panelW, colW;
	private int stepScroll = 0, commentScroll = 0;
	private String stepText = "", commentText = "";
	private EditBox stepBox, commentBox;
	private String lastSignature = "";

	private record Text(int x, int y, FormattedCharSequence text, int color) {}
	private final List<Text> texts = new ArrayList<>();

	public TaskScreen(Store store, String taskId, Screen parent) {
		super(Component.literal("Aufgabe"));
		this.store = store;
		this.taskId = taskId;
		this.parent = parent;
	}

	private void text(int x, int y, String s, int color) {
		texts.add(new Text(x, y, Component.literal(s).getVisualOrderText(), color));
	}

	private Button button(String label, int x, int y, int w, Button.OnPress onPress) {
		return addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(x, y, w, 20).build());
	}

	@Override
	protected void init() {
		store.onChange = this::dataChanged;
		lastSignature = signature();
		texts.clear();
		panelW = Math.min(width - 20, 460);
		left = (width - panelW) / 2;
		colW = (panelW - 12) / 2;

		button("Zurück", left + panelW - 60, 4, 60, b -> onClose());
		Store.Task t = store.task(taskId);
		if (t == null) {
			text(left, 40, "Diese Aufgabe gibt es nicht mehr.", GREY);
			return;
		}

		text(left, 9, font.plainSubstrByWidth(t.titel, panelW - 70), WHITE);
		String info = Store.STATUS_NAME[Math.max(0, List.of(Store.STATUS).indexOf(t.status))]
				+ "  ·  " + (t.people().isEmpty() ? "niemand eingetragen" : t.peopleText())
				+ (t.projekt.isEmpty() ? "" : "  ·  Projekt " + store.projectName(t.projekt))
				+ (t.ersteller.isEmpty() ? "" : "  ·  angelegt von " + t.ersteller);
		text(left, 24, font.plainSubstrByWidth(info, panelW), GREY);
		List<Store.Task> kids = store.children(t);
		boolean builds = t.hasGoal() && t.builds(), collects = t.hasGoal() && t.collects();
		for (Store.Task c : kids) { builds |= c.builds(); collects |= c.collects(); }
		int y = 36;
		if (store.locked(t)) {
			text(left, y, "Gesperrt: " + store.lockText(t), RED);
			y += 12;
		}
		if (t.hasGoal()) {
			String parts = store.goalParts(t);
			String line = t.isCombo() ? t.name() + ": " + parts
					: store.goalTotal(t) + " / " + t.ziel + " " + t.name() + " " + Store.goalDone(t.art) + (parts.isEmpty() ? "" : "  (" + parts + ")");
			String mat = store.materialHint(t);
			if (!mat.isEmpty()) line += "  ·  " + mat;
			text(left, y, font.plainSubstrByWidth(line, panelW), YELLOW);
			y += 12;
			text(left, y, font.plainSubstrByWidth("Zählung: " + BestandScanner.why(store, t), panelW), GREY);
			y += 12;
		}
		y = initChildren(t, kids, y);
		if (builds || collects) y = initGoalRow(t, y, builds, collects);
		y = initOrderAndAdd(t, y);
		off = y - 46;
		listTop = 80 + off;

		initSteps(t);
		initComments(t);
	}

	/** Teilziele: eine Zeile je Ziel mit Fortschritt, löschbar. */
	private int initChildren(Store.Task t, List<Store.Task> kids, int y) {
		if (kids.isEmpty()) return y;
		text(left, y, "Ziele: " + kids.stream().filter(c -> "fertig".equals(c.status)).count() + "/" + kids.size() + " fertig, gesamt " + store.percent(t) + " %", GREEN);
		y += 12;
		int shown = 0;
		for (Store.Task c : kids) {
			if (shown == 4) { text(left, y, "+ " + (kids.size() - 4) + " weitere (auf der Webseite)", GREY); y += 12; break; }
			String line = ("fertig".equals(c.status) ? "✔ " : "• ") + store.goalTotal(c) + " / " + c.ziel + " " + c.name() + " " + Store.goalDone(c.art);
			String mat = store.materialHint(c);
			if (!mat.isEmpty()) line += "  ·  " + mat;
			text(left + 4, y + 2, font.plainSubstrByWidth(line, panelW - 30), "fertig".equals(c.status) ? GREEN : WHITE);
			if (store.canEditTask(t)) {
				Button x = Button.builder(Component.literal("x"), b -> store.deleteTask(c)).bounds(left + panelW - 16, y, 14, 12).build();
				x.setTooltip(Tooltip.create(Component.literal("Ziel entfernen")));
				addRenderableWidget(x);
			}
			y += 12;
			shown++;
		}
		return y + 2;
	}

	private static String subArt = "platzieren";
	private String subName = "", subAmount = "";

	/** Reihenfolge ("erst nach") und weiteres Ziel hinzufügen. */
	private int initOrderAndAdd(Store.Task t, int y) {
		boolean edit = store.canEditTask(t);
		List<Store.Task> options = new ArrayList<>();
		for (Store.Task o : store.tasks)
			if (!o.id.equals(t.id) && !o.isChild() && java.util.Objects.equals(o.projekt, t.projekt) && !t.id.equals(o.nachAufgabe)) options.add(o);
		Store.Task before = store.task(t.nachAufgabe);
		Button after = button(font.plainSubstrByWidth("Erst nach: " + (before == null ? "–" : before.titel), 160), left, y, 170, b -> {
			int i = before == null ? -1 : options.indexOf(before);
			store.setAfter(t, i + 1 < options.size() ? options.get(i + 1).id : "");
		});
		after.active = edit && !options.isEmpty();
		after.setTooltip(Tooltip.create(Component.literal("Reihenfolge (optional): Die Aufgabe ist gesperrt, bis die gewählte fertig ist. Klicken wechselt durch die Aufgaben im Projekt, \"–\" = keine Reihenfolge.")));

		// Weiteres Ziel: Name, Anzahl, Art, +
		int x = left + 176;
		EditBox name = new EditBox(font, x, y + 1, Math.max(60, panelW - 176 - 44 - 70 - 24 - 12), 18, Component.literal("Ziel"));
		name.setMaxLength(60);
		name.setValue(subName);
		name.setHint(Component.literal("Weiteres Ziel, z. B. Treppen"));
		name.setResponder(v -> subName = v);
		addRenderableWidget(name);
		x += name.getWidth() + 4;
		EditBox amount = new EditBox(font, x, y + 1, 40, 18, Component.literal("Anzahl"));
		amount.setMaxLength(7);
		amount.setValue(subAmount);
		amount.setHint(Component.literal("Anz."));
		amount.setResponder(v -> subAmount = v);
		addRenderableWidget(amount);
		x += 44;
		String artLabel = switch (subArt) { case "sammeln" -> "Sammeln"; case "abbauen" -> "Abbauen"; case "kombi" -> "Samm.+Bau"; default -> "Platzieren"; };
		button(artLabel, x, y, 70, b -> {
			subArt = switch (subArt) { case "platzieren" -> "kombi"; case "kombi" -> "sammeln"; case "sammeln" -> "abbauen"; default -> "platzieren"; };
			rebuildWidgets();
		});
		x += 74;
		Button add = button("+", x, y, 20, b -> {
			ItemStack item = ItemNames.find(subName);
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
			rebuildWidgets();
		});
		add.active = edit;
		add.setTooltip(Tooltip.create(Component.literal("Weiteres Ziel zu dieser Aufgabe hinzufügen (z. B. 80 Treppen platzieren). Die Aufgabe ist fertig, wenn alle Ziele fertig sind.")));
		return y + 24;
	}

	/** Sammeln: Lager zählt mit an/aus. Abbauen/Platzieren: Bereich mit zwei Ecken oder Umkreis. */
	private int initGoalRow(Store.Task t, int y, boolean builds, boolean collects) {
		boolean edit = store.canEditTask(t);
		if (!builds) {
			Button b = button("Lager zählt mit: " + (t.countsStorage() || !t.hasGoal() && !Boolean.FALSE.equals(t.lagerZaehlt) ? "An" : "Aus"), left, y, 150,
					x -> store.setStorageCounts(t, Boolean.FALSE.equals(t.lagerZaehlt)));
			b.active = edit;
			b.setTooltip(Tooltip.create(Component.literal("An: Was in den Lager-Kisten des Projekts liegt, zählt zum Fortschritt. "
					+ "Bereits verbaute Blöcke (Platzieren-Aufgaben im selben Projekt) zählen immer mit.")));
			return y + 24;
		}
		var mc = minecraft;
		boolean waiting = corner1 != null && t.id.equals(corner1Task);
		Button c1 = button("Ecke 1 hier", left, y, 80, b -> {
			corner1 = mc.player.blockPosition();
			corner1Task = t.id;
			rebuildWidgets();
		});
		c1.setTooltip(Tooltip.create(Component.literal("Stell dich an eine Ecke des Bereichs und klick hier. Danach an die gegenüberliegende Ecke und \"Ecke 2 hier\".")));
		Button c2 = button("Ecke 2 hier", left + 84, y, 80, b -> {
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
		c2.active = edit && waiting;
		c1.active = edit;
		Button r = button("Umkreis 32 hier", left + 168, y, 100, b -> store.setRadius(t, mc.player.blockPosition(), BautruppClient.currentDim(), 32));
		r.active = edit;
		r.setTooltip(Tooltip.create(Component.literal("Zählt nur Blöcke im Umkreis von 32 Blöcken um deine Position. Anderen Umkreis: auf der Webseite.")));
		Button none = button("Überall", left + 272, y, 60, b -> store.clearArea(t));
		none.active = edit && t.hasArea();
		none.setTooltip(Tooltip.create(Component.literal("Bereich entfernen: Blöcke zählen dann überall.")));
		String info = waiting ? "Ecke 1: " + corner1.getX() + " " + corner1.getY() + " " + corner1.getZ() + " – jetzt zur zweiten Ecke gehen"
				: t.hasArea() ? t.areaText() : "Zählt überall";
		text(left + 338, y + 6, font.plainSubstrByWidth(info, Math.max(0, panelW - 338)), waiting ? YELLOW : GREY);

		// Zweite Zeile: Zählmodus
		int y2 = y + 24;
		boolean bestand = t.hasArea() && !"platzierungen".equals(t.modus);
		Button mode = button("Zählt: " + (!t.hasArea() ? "Platzierungen" : bestand ? "Bestand" : "Platzierungen"), left, y2, 150,
				b -> store.setMode(t, bestand ? "platzierungen" : "bestand"));
		mode.active = edit && t.hasArea();
		mode.setTooltip(Tooltip.create(Component.literal(!t.hasArea()
				? "Bestand zählen geht nur mit Bereich. Leg erst einen Bereich fest."
				: "Bestand: zählt, was im Bereich steht, egal wer es gesetzt hat (nur wenn jemand mit der Mod in der Nähe ist). "
				+ "Platzierungen: zählt, was Leute mit der Mod setzen oder abbauen.")));
		String status;
		if (!t.hasGoal()) status = t.hasArea() ? (bestand ? "Ziele zählen den Bestand im Bereich" : "Ziele zählen Platzierungen") : "Ziele zählen überall";
		else if (!bestand) status = "abbauen".equals(t.goalKind()) ? "Zählt abgebaute Blöcke" : "Zählt gesetzte Blöcke";
		else if (t.bestand == null) status = "Noch nicht gezählt – geh mit der Mod in die Nähe des Bereichs";
		else status = ("abbauen".equals(t.goalKind()) && t.bestandStart != null ? "Am Anfang " + t.bestandStart + ", jetzt " + t.bestand + " im Bereich"
				: t.bestand + " im Bereich") + ", gezählt " + Store.ago(t.bestandZeit) + " von " + t.bestandVon;
		int sx = left + 154;
		if (collects) {
			boolean on = !Boolean.FALSE.equals(t.lagerZaehlt);
			Button lager = button("Lager: " + (on ? "An" : "Aus"), sx, y2, 70, b -> store.setStorageCounts(t, !on));
			lager.active = edit;
			lager.setTooltip(Tooltip.create(Component.literal("Zählt beim Sammeln mit, was in den Lager-Kisten des Projekts liegt.")));
			sx += 74;
		}
		if (bestand && "abbauen".equals(t.goalKind())) {
			Button reset = button("Neu starten", sx, y2, 80, b -> store.resetBestandStart(t));
			reset.active = edit;
			reset.setTooltip(Tooltip.create(Component.literal("Vergisst den Anfangsbestand. Bei der nächsten Zählung zählt das, was dann im Bereich steht, als Anfang.")));
			sx += 84;
		}
		text(sx, y2 + 6, font.plainSubstrByWidth(status, Math.max(0, left + panelW - sx)), GREY);
		return y2 + 24;
	}

	private void initSteps(Store.Task t) {
		List<Store.Step> steps = t.steps();
		text(left, 50 + off, "Schritte (" + t.stepsDone() + "/" + steps.size() + ")", GREEN);
		stepBox = new EditBox(font, left, 58 + off, colW - 24, 18, Component.literal("Neuer Schritt"));
		stepBox.setMaxLength(80);
		stepBox.setValue(stepText);
		stepBox.setHint(Component.literal("Neuer Schritt, z. B. Fundament"));
		stepBox.setResponder(s -> stepText = s);
		addRenderableWidget(stepBox);
		button("+", left + colW - 20, 58 + off, 20, b -> {
			if (stepText.isBlank()) return;
			store.addStep(t, stepText.trim());
			stepText = "";
			rebuildWidgets();
			setFocused(stepBox);
		});

		int rows = Math.max(1, (height - listTop - 20) / STEP_H);
		stepScroll = Math.max(0, Math.min(stepScroll, steps.size() - rows));
		int y = listTop;
		for (int i = stepScroll; i < steps.size() && i < stepScroll + rows; i++, y += STEP_H) {
			Store.Step s = steps.get(i);
			button(s.erledigt ? "✔" : " ", left, y, 20, b -> store.toggleStep(t, s))
					.setTooltip(Tooltip.create(Component.literal(s.erledigt ? "Wieder offen" : "Abhaken")));
			text(left + 24, y + 6, font.plainSubstrByWidth(s.text, colW - 50), s.erledigt ? GREY : WHITE);
			Button x = button("x", left + colW - 20, y, 20, b -> store.deleteStep(t, s));
			x.active = store.canDelete(t.ersteller);
		}
		if (steps.isEmpty()) text(left, listTop + 4, "Noch keine Schritte.", GREY);
	}

	private void initComments(Store.Task t) {
		int cx = left + colW + 12;
		List<Store.Comment> comments = t.comments();
		text(cx, 50 + off, "Kommentare (" + comments.size() + ")", GREEN);
		commentBox = new EditBox(font, cx, 58 + off, colW - 56, 18, Component.literal("Kommentar"));
		commentBox.setMaxLength(200);
		commentBox.setValue(commentText);
		commentBox.setHint(Component.literal("Schreib etwas ..."));
		commentBox.setResponder(s -> commentText = s);
		addRenderableWidget(commentBox);
		button("Senden", cx + colW - 52, 58 + off, 52, b -> {
			if (commentText.isBlank()) return;
			store.addComment(t, commentText.trim());
			commentText = "";
			rebuildWidgets();
			setFocused(commentBox);
		});

		// Neueste zuerst, Text umbrochen
		List<Store.Comment> newest = new ArrayList<>(comments);
		java.util.Collections.reverse(newest);
		commentScroll = Math.max(0, Math.min(commentScroll, newest.size() - 1));
		int y = listTop;
		for (int i = commentScroll; i < newest.size(); i++) {
			Store.Comment c = newest.get(i);
			List<FormattedCharSequence> lines = font.split(Component.literal(c.text), colW - 24);
			int h = 11 + lines.size() * 10 + 6;
			if (y + h > height - 20) break;
			text(cx, y, c.wer + "  ·  " + Store.ago(c.zeit), YELLOW);
			int ly = y + 11;
			for (FormattedCharSequence line : lines) {
				texts.add(new Text(cx, ly, line, WHITE));
				ly += 10;
			}
			if (store.canDelete(c.wer)) {
				Button x = Button.builder(Component.literal("x"), b -> store.deleteComment(t, c)).bounds(cx + colW - 14, y - 2, 14, 14).build();
				x.setTooltip(Tooltip.create(Component.literal("Kommentar löschen")));
				addRenderableWidget(x);
			}
			y += h;
		}
		if (comments.isEmpty()) text(cx, listTop + 4, "Noch keine Kommentare.", GREY);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		int step = (int) Math.signum(dy);
		if (x < left + colW + 6) stepScroll = Math.max(0, stepScroll - step);
		else commentScroll = Math.max(0, commentScroll - step);
		rebuildWidgets();
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
		sb.append(t.status).append(t.wer).append(store.goalTotal(t)).append(t.areaText()).append(t.lagerZaehlt).append(t.modus).append(t.bestand).append(t.bestandZeit).append(store.isAdmin());
		for (Store.Step s : t.steps()) sb.append(s.id).append(s.erledigt);
		for (Store.Comment c : t.comments()) sb.append(c.id);
		return sb.toString();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		for (Text t : texts) g.text(font, t.text, t.x, t.y, t.color);
		Store.Task task = store.task(taskId);
		if (task != null && font.width(task.titel) > panelW - 70 && mouseY >= 6 && mouseY < 20 && mouseX >= left && mouseX < left + panelW - 70)
			g.setTooltipForNextFrame(Component.literal(task.titel), mouseX, mouseY);
		if (store.error != null) g.text(font, store.error, left, height - 14, RED);
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
