package de.bautrupp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Projekt wählen (eine Liste statt Durchklicken) oder ein neues anlegen. */
public class ProjectScreen extends Screen {
	private final Store store;
	private final Screen parent;
	private int scroll = 0, x, w, top, rowsShown;

	public ProjectScreen(Store store, Screen parent) {
		super(Component.literal("Projekt wählen"));
		this.store = store;
		this.parent = parent;
	}

	@Override
	protected void init() {
		w = Math.min(width - 24, 340);
		x = (width - w) / 2;
		top = 8;
		List<Store.Project> list = new ArrayList<>();
		list.add(null); // "Alle Projekte"
		list.addAll(store.projects);
		rowsShown = Math.max(1, (height - top - 20 - 30) / 22);
		scroll = Math.max(0, Math.min(scroll, list.size() - rowsShown));
		int y = top + 16;
		for (int i = scroll; i < list.size() && i < scroll + rowsShown; i++, y += 22) {
			Store.Project p = list.get(i);
			String id = p == null ? "" : p.id;
			long all = store.tasks.stream().filter(t -> !t.isChild() && (p == null || id.equals(t.projekt))).count();
			long done = store.tasks.stream().filter(t -> !t.isChild() && (p == null || id.equals(t.projekt)) && "fertig".equals(t.status)).count();
			boolean current = store.config.projekt.equals(id);
			String label = (current ? "✔ " : "") + (p == null ? "Alle Projekte" : p.name) + "  (" + done + "/" + all + ")";
			Button b = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, w - 12)), z -> {
				store.config.projekt = id;
				store.config.save();
				onClose();
			}).bounds(x, y, w, 20).build());
			if (p != null) {
				List<String> info = new ArrayList<>();
				if (p.wer != null && !p.wer.isEmpty()) info.add("Spieler: " + p.wer.replace(",", ", "));
				if (p.bereich != null && p.bereich.valid()) info.add("Bereich: " + p.bereich.text());
				info.add("Bereich festlegen: /bt projekt ecke1 und ecke2");
				b.setTooltip(Tooltip.create(Component.literal(String.join("\n", info))));
			}
		}
		int by = Math.min(y + 6, height - 26);
		addRenderableWidget(Button.builder(Component.literal("+ Neues Projekt"), b -> minecraft.gui.setScreen(new NameScreen("Neues Projekt", "z. B. Rathaus", this, name -> {
			Store.Project p = store.addProject(name);
			store.config.projekt = p.id;
			store.config.save();
		}))).bounds(x, by, (w - 4) / 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Zurück"), b -> onClose()).bounds(x + (w + 4) / 2, by, (w - 4) / 2, 20).build());
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		int before = scroll;
		scroll = Math.max(0, scroll - (int) Math.signum(dy));
		if (scroll != before) rebuildWidgets();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, "Projekt wählen", x, top, BautruppScreen.WHITE);
		if (store.projects.size() + 1 > rowsShown) g.text(font, "Mausrad: mehr", x + w - font.width("Mausrad: mehr"), top, BautruppScreen.BLUE);
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
