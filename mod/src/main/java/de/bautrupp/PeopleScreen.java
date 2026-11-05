package de.bautrupp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Admin: Leute für eine Aufgabe ein- und austragen (mehrere möglich). */
public class PeopleScreen extends Screen {
	private static final int ROW_H = 22, TOP = 40;
	private final Store store;
	private final String taskId;
	private final Screen parent;
	private int scroll = 0;

	public PeopleScreen(Store store, String taskId, Screen parent) {
		super(Component.literal("Wer macht mit?"));
		this.store = store;
		this.taskId = taskId;
		this.parent = parent;
	}

	@Override
	protected void init() {
		store.onChange = this::rebuildWidgets;
		Store.Task t = store.task(taskId);
		int w = Math.min(width - 40, 220), x = (width - w) / 2;
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose()).bounds(x, height - 28, w, 20).build());
		if (t == null) return;
		List<String> players = new ArrayList<>(store.players);
		int rows = Math.max(1, (height - TOP - 36) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, players.size() - rows));
		int y = TOP;
		for (int i = scroll; i < players.size() && i < scroll + rows; i++, y += ROW_H) {
			String name = players.get(i);
			boolean in = t.has(name);
			addRenderableWidget(Button.builder(Component.literal((in ? "✔ " : "    ") + name), b -> {
				List<String> p = t.people();
				if (in) p.removeIf(n -> n.equalsIgnoreCase(name));
				else p.add(name);
				store.setTaskPeople(t, p);
				rebuildWidgets();
			}).bounds(x, y, w, 20).build());
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scroll = Math.max(0, scroll - (int) Math.signum(dy));
		rebuildWidgets();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		Store.Task t = store.task(taskId);
		g.centeredText(font, t == null ? "Diese Aufgabe gibt es nicht mehr." : "Wer macht bei \"" + font.plainSubstrByWidth(t.titel, 200) + "\" mit?", width / 2, 14, 0xFFFFFFFF);
		g.centeredText(font, "Anklicken zum Ein- oder Austragen. Ziele zählen alle zusammen.", width / 2, 26, 0xFFA0A0A0);
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
