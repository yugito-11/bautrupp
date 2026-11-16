package de.bautrupp;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Neue Aufgabe anlegen, Schritt für Schritt von oben nach unten: Was ist zu tun? Ziel (optional:
 * Art, Anzahl, Item)? Ort und für wen? Ersetzt die volle Eingabezeile im Hauptmenü.
 */
public class NewTaskScreen extends Screen {
	private static final String[][] ARTS = {{"", "Kein Ziel"}, {"sammeln", "Sammeln"}, {"abbauen", "Abbauen"}, {"platzieren", "Platzieren"}, {"kombi", "Samm.+Bau"}};
	private static final String[] ART_TIPS = {
			"Einfache Aufgabe ohne Zählen, z. B. \"Brücke bauen\"",
			"Fertig, sobald die Eingetragenen so viele dabeihaben (Lager-Kisten zählen mit)",
			"Zählt abgebaute Blöcke",
			"Zählt gesetzte Blöcke",
			"Sammeln und verbauen in einer Aufgabe"};
	/** Umkreis für Abbau-/Platzier-Ziele, die "hier" angelegt werden. */
	private static final int AREA_RADIUS = 32;
	private static String art = "";

	/** Für die Vorschau beim Entwickeln: Art und Eingaben vorbelegen. */
	static NewTaskScreen preset(Store store, BautruppScreen parent, String a, String amount, String item) {
		art = a;
		NewTaskScreen s = new NewTaskScreen(store, parent);
		s.amount = amount;
		s.item = item;
		return s;
	}

	/** Für die Vorschau: Titel setzen. */
	NewTaskScreen titled(String title) {
		name = title;
		return this;
	}

	private final Store store;
	private final BautruppScreen parent;
	private String name = "", amount = "", item = "";
	/** Letzte Fehlermeldung (für die Vorschau lesbar). */
	String lastError() { return error; }
	private boolean here = false, forMe = true;
	private String error;
	private EditBox nameBox, amountBox, itemBox;
	private int x, w, top;

	private record Label(int x, int y, String text, int color) {}
	private final List<Label> labels = new ArrayList<>();

	public NewTaskScreen(Store store, BautruppScreen parent) {
		super(Component.literal("Neue Aufgabe"));
		this.store = store;
		this.parent = parent;
	}

	@Override
	protected void init() {
		labels.clear();
		w = Math.min(width - 24, 380);
		x = (width - w) / 2;
		boolean goal = !art.isEmpty();
		top = Math.max(6, (height - (goal ? 208 : 150)) / 2);
		int y = top + 16;

		labels.add(new Label(x, y, goal ? "Titel (optional, sonst z. B. \"640 Oak Log sammeln\")" : "Was ist zu tun?", BautruppScreen.GREY));
		nameBox = box(x, y + 10, w, name, goal ? "z. B. Holz für das Lager" : "z. B. Brücke über den Fluss", 120, s -> name = s);
		y += 38;

		labels.add(new Label(x, y, "Ziel", BautruppScreen.GREY));
		int bw = (w - 4 * 3) / 5;
		for (int i = 0; i < ARTS.length; i++) {
			String key = ARTS[i][0];
			Button b = button(ARTS[i][1], x + i * (bw + 3), y + 10, bw, z -> { art = key; rebuildWidgets(); });
			b.active = !art.equals(key);
			b.setTooltip(Tooltip.create(Component.literal(ART_TIPS[i])));
		}
		y += 38;

		if (goal) {
			labels.add(new Label(x, y, "Anzahl und Item (englisch oder wie im Spiel; leer = Item in der Hand)", BautruppScreen.GREY));
			amountBox = box(x, y + 10, 56, amount, "Anzahl", 7, s -> amount = s);
			itemBox = box(x + 60, y + 10, w - 60 - 64, item, "z. B. Oak Log", 60, s -> {
				// Vorschläge als Knöpfe: neu aufbauen, Eingabe behält den Fokus
				item = s;
				rebuildWidgets();
				setFocused(itemBox);
			});
			// Nicht eindeutig: bis zu drei Vorschläge zum Anklicken
			if (!item.isBlank() && ItemNames.findItem(item) == null) {
				List<String> sug = ItemNames.suggest(item, 3);
				int sw = (w - 8) / 3;
				for (int i = 0; i < sug.size(); i++) {
					String pick = sug.get(i);
					button(pick, x + i * (sw + 4), y + 34, sw, b -> { item = pick; rebuildWidgets(); });
				}
			}
			Button hand = button("Hand", x + w - 60, y + 10, 60, b -> {
				ItemStack s = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
				if (s.isEmpty()) { error = "Du hast nichts in der Hand"; return; }
				item = ItemNames.label(s);
				itemBox.setValue(item);
				error = null;
			});
			hand.setTooltip(Tooltip.create(Component.literal("Das Item in deiner Hand übernehmen")));
			y += 58;
		}

		String hereText = !here ? "Ort: keiner" : goal && !"sammeln".equals(art) ? "Ort: hier (Umkreis " + AREA_RADIUS + ")" : "Ort: hier";
		Button pos = button(hereText, x, y, (w - 4) / 2, b -> { here = !here; rebuildWidgets(); });
		pos.setTooltip(Tooltip.create(Component.literal("Mit deiner aktuellen Position anlegen. Bei Abbauen/Platzieren zählen dann nur Blöcke im Umkreis von "
				+ AREA_RADIUS + " Blöcken. Liegt das Projekt in einem Bereich, gilt sonst dieser.")));
		Button who = button(forMe ? "Für: mich" : "Für: niemand (noch)", x + (w + 4) / 2, y, (w - 4) / 2, b -> { forMe = !forMe; rebuildWidgets(); });
		who.setTooltip(Tooltip.create(Component.literal("Mich gleich eintragen, oder offen lassen, damit sich jemand meldet. Hat das Projekt Spieler, bekommen sie die Aufgabe.")));
		y += 30;

		button("Anlegen", x, y, (w - 4) / 2, b -> create());
		button("Abbrechen", x + (w + 4) / 2, y, (w - 4) / 2, b -> onClose());
		setInitialFocus(goal && name.isEmpty() ? amountBox : nameBox);
	}

	private EditBox box(int bx, int by, int bw, String value, String hint, int max, java.util.function.Consumer<String> onChange) {
		EditBox b = new EditBox(font, bx, by, bw, 20, Component.literal(hint));
		b.setMaxLength(max);
		b.setValue(value);
		b.setHint(Component.literal(hint));
		b.setResponder(onChange);
		return addRenderableWidget(b);
	}

	private Button button(String label, int bx, int by, int bw, Button.OnPress onPress) {
		return addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(bx, by, bw, 20).build());
	}

	/** Was zum eingetippten Item gefunden wird: "Erkannt: Oak Log" oder Vorschläge. */
	private String itemHint() {
		if (item.isBlank()) return "";
		var found = ItemNames.findItem(item);
		if (found != null) return "Erkannt: " + ItemNames.label(found);
		List<String> sug = ItemNames.suggest(item, 3);
		return sug.isEmpty() ? "Kein Item gefunden" : "Meintest du: " + String.join(", ", sug) + "?";
	}

	void create() {
		String title = name.trim(), me = forMe ? Store.myName() : "";
		BlockPos p = here && minecraft.player != null ? minecraft.player.blockPosition() : null;
		if (art.isEmpty()) {
			if (title.isEmpty()) { error = "Schreib oben hin, was zu tun ist"; return; }
			if (p != null) store.addTask(title, me, p.getX(), p.getY(), p.getZ(), BautruppClient.currentDim());
			else store.addTask(title, me, null, null, null, "");
			parent.notice("Aufgabe angelegt: " + title);
			onClose();
			return;
		}
		int n;
		try { n = Integer.parseInt(amount.trim()); } catch (NumberFormatException e) { n = 0; }
		if (n <= 0) { error = "Bei Anzahl bitte eine Zahl größer als 0 eintragen"; return; }
		net.minecraft.world.item.Item stack;
		if (item.isBlank()) {
			ItemStack hand = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
			if (hand.isEmpty()) { error = "Schreib ein Item hin oder nimm es in die Hand"; return; }
			stack = hand.getItem();
		} else {
			stack = ItemNames.findItem(item);
			if (stack == null) { error = itemHint(); return; }
		}
		String problem = BautruppClient.goalProblem(art, stack);
		if (problem != null) { error = problem; return; }
		if (title.isEmpty()) title = n + " " + ItemNames.label(stack) + " " + Store.goalVerb(art);
		store.addGoalTask(title, me, art, GoalTracker.idOf(stack), ItemNames.english(stack), n, p, BautruppClient.currentDim(), AREA_RADIUS);
		parent.notice("Aufgabe angelegt: " + title);
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_RETURN && getFocused() instanceof EditBox) {
			create();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, "Neue Aufgabe" + (store.config.projekt.isEmpty() ? "" : " im Projekt " + store.projectName(store.config.projekt)), x, top, BautruppScreen.WHITE);
		for (Label l : labels) g.text(font, font.plainSubstrByWidth(l.text, w), l.x, l.y, l.color);
		if (itemBox != null) {
			String h = itemHint();
			// Bei Vorschlägen stehen dort die Knöpfe
			if (h.startsWith("Erkannt") || h.startsWith("Kein")) g.text(font, font.plainSubstrByWidth(h, w), x, itemBox.getY() + 26, h.startsWith("Erkannt") ? BautruppScreen.GREEN : BautruppScreen.YELLOW);
		}
		if (error != null) g.text(font, font.plainSubstrByWidth(error, w), x, height - 14, BautruppScreen.RED);
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
