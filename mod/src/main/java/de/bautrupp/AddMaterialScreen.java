package de.bautrupp;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Material von Hand auf die Liste setzen: Item (Name oder aus der Hand) und benötigte Menge. */
public class AddMaterialScreen extends Screen {
	private final Store store;
	private final BautruppScreen parent;
	private String item = "", amount = "", error;
	private EditBox itemBox, amountBox;
	private int x, w, top;

	public AddMaterialScreen(Store store, BautruppScreen parent) {
		super(Component.literal("Material eintragen"));
		this.store = store;
		this.parent = parent;
	}

	@Override
	protected void init() {
		w = Math.min(width - 24, 340);
		x = (width - w) / 2;
		top = Math.max(6, (height - 120) / 2);
		int y = top + 26;
		itemBox = new EditBox(font, x, y, w - 64, 20, Component.literal("Item"));
		itemBox.setMaxLength(60);
		itemBox.setValue(item);
		itemBox.setHint(Component.literal("Item, z. B. Block of Diamond"));
		itemBox.setResponder(s -> item = s);
		addRenderableWidget(itemBox);
		addRenderableWidget(Button.builder(Component.literal("Hand"), b -> {
			ItemStack s = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
			if (s.isEmpty()) { error = "Du hast nichts in der Hand"; return; }
			item = ItemNames.label(s);
			itemBox.setValue(item);
		}).bounds(x + w - 60, y, 60, 20).build());
		y += 36;
		amountBox = new EditBox(font, x, y, 100, 20, Component.literal("Anzahl"));
		amountBox.setMaxLength(8);
		amountBox.setValue(amount);
		amountBox.setHint(Component.literal("Anzahl"));
		amountBox.setResponder(s -> amount = s);
		addRenderableWidget(amountBox);
		y += 30;
		addRenderableWidget(Button.builder(Component.literal("Eintragen"), b -> add()).bounds(x, y, (w - 4) / 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Abbrechen"), b -> onClose()).bounds(x + (w + 4) / 2, y, (w - 4) / 2, 20).build());
		setInitialFocus(itemBox);
	}

	private String hint() {
		if (item.isBlank()) return "";
		var found = ItemNames.findItem(item);
		if (found != null) return "Erkannt: " + ItemNames.label(found) + (store.hasStorage(store.config.projekt) ? " – das Lager zählt mit" : "");
		List<String> sug = ItemNames.suggest(item, 3);
		return sug.isEmpty() ? "Kein Item erkannt – wird von Hand gezählt" : "Meintest du: " + String.join(", ", sug) + "?";
	}

	/** Für die Vorschau: Eingaben setzen. */
	AddMaterialScreen with(String i, String a) {
		item = i;
		amount = a;
		return this;
	}

	void add() {
		int need;
		try { need = Integer.parseInt(amount.trim()); } catch (NumberFormatException e) { need = 0; }
		if (item.isBlank()) { error = "Schreib ein Item hin"; return; }
		if (need <= 0) { error = "Bei Anzahl bitte eine Zahl größer als 0 eintragen"; return; }
		var found = ItemNames.findItem(item);
		String name = found == null ? item.trim() : ItemNames.english(found), id = found == null ? "" : GoalTracker.idOf(found);
		store.addMaterial(name, id, need);
		parent.notice("Eingetragen: " + need + " " + (found == null ? name : ItemNames.label(found)));
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_RETURN && getFocused() instanceof EditBox) {
			add();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, "Material eintragen" + (store.config.projekt.isEmpty() ? "" : " im Projekt " + store.projectName(store.config.projekt)), x, top, BautruppScreen.WHITE);
		g.text(font, "Item (englisch oder wie im Spiel)", x, top + 16, BautruppScreen.GREY);
		String h = hint();
		g.text(font, font.plainSubstrByWidth(h, w), x, itemBox.getY() + 23, h.startsWith("Erkannt") ? BautruppScreen.GREEN : BautruppScreen.YELLOW);
		g.text(font, "Benötigt (Stück; 1 Stack = 64, 1 Shulker = 1728)", x, amountBox.getY() - 10, BautruppScreen.GREY);
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
