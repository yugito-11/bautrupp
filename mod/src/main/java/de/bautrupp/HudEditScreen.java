package de.bautrupp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Bearbeiten-Modus der Anzeige: Kasten mit der Maus verschieben (oder irgendwo hinklicken, dann
 * springt er dorthin), mit den Pfeiltasten schieben, über "Position" an feste Stellen setzen,
 * an der Ecke unten rechts breiter/schmaler ziehen, mit dem Mausrad (oder Knöpfen) größer/kleiner.
 */
public class HudEditScreen extends Screen {
	private static final int HANDLE = 8, OUTLINE = 0xFF7BD35A, HINT = 0xFFFFFFFF;
	private final Screen parent;
	private final Config c;
	private boolean dragging, resizing;
	private double grabX, grabY;
	private int boxX, boxY, boxW, boxH;
	private int preset = 0;
	private static final String[] PRESETS = {"Links Mitte", "Rechts Mitte", "Links oben", "Rechts oben", "Links unten", "Rechts unten"};

	public HudEditScreen(Screen parent) {
		super(Component.literal("Anzeige anpassen"));
		this.parent = parent;
		this.c = BautruppClient.store.config;
	}

	@Override
	protected void init() {
		int bw = 60, gap = 4, n = 6, total = n * bw + (n - 1) * gap, x = (width - total) / 2, y = height - 28;
		addRenderableWidget(Button.builder(Component.literal("Position: " + PRESETS[preset]), b -> {
			applyPreset(preset);
			preset = (preset + 1) % PRESETS.length;
			b.setMessage(Component.literal("Position: " + PRESETS[preset]));
		}).bounds((width - 160) / 2, y - 24, 160, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Zeilen -"), b -> c.hudZeilen = Math.max(1, c.hudZeilen - 1)).bounds(x, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Zeilen +"), b -> c.hudZeilen = Math.min(12, c.hudZeilen + 1)).bounds(x + (bw + gap), y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Kleiner"), b -> scale(-0.1f)).bounds(x + 2 * (bw + gap), y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Größer"), b -> scale(0.1f)).bounds(x + 3 * (bw + gap), y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Zurücksetzen"), b -> {
			c.hudX = -1; c.hudY = -1; c.hudBreite = 150; c.hudGroesse = 1.0f; c.hudZeilen = 5;
		}).bounds(x + 4 * (bw + gap), y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose()).bounds(x + 5 * (bw + gap), y, bw, 20).build());
	}

	/** Kasten an eine feste Stelle setzen (Anteil der Bildschirmgröße wie beim Ziehen). */
	private void applyPreset(int p) {
		boolean right = p % 2 == 1;
		int x = right ? width - boxW - 6 : 6;
		int y = switch (p / 2) { case 1 -> 6; case 2 -> height - boxH - 70; default -> height / 3 - boxH / 2; };
		moveTo(x, y);
	}

	private void moveTo(int nx, int ny) {
		nx = Math.max(0, Math.min(nx, width - boxW));
		ny = Math.max(0, Math.min(ny, height - boxH));
		c.hudX = nx / (float) width;
		c.hudY = ny / (float) height;
	}

	private void scale(float d) {
		c.hudGroesse = Math.max(0.5f, Math.min(2.0f, Math.round((c.hudGroesse + d) * 10) / 10f));
	}

	private List<TaskHud.Row> rows() {
		List<TaskHud.Row> rows = minecraft.player == null ? List.of() : TaskHud.myRows(minecraft, BautruppClient.store);
		if (!rows.isEmpty()) return rows;
		return List.of(
				new TaskHud.Row("Beispiel: Dach vom Rathaus aus Fichtentreppen bauen", true, "120/640", null),
				new TaskHud.Row("Beispiel: Weizenfarm anlegen", false, "35m", null));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// Kein Unschärfe-Hintergrund: man soll sehen, wo der Kasten im Spiel landet.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		List<TaskHud.Row> rows = rows();
		int h = TaskHud.height(font, rows, c);
		int[] pos = TaskHud.position(width, height, c, h);
		boxX = pos[0]; boxY = pos[1];
		boxW = Math.round(c.hudBreite * c.hudGroesse); boxH = Math.round(h * c.hudGroesse);
		TaskHud.draw(g, font, rows, boxX, boxY, c);
		g.outline(boxX - 1, boxY - 1, boxW + 2, boxH + 2, OUTLINE);
		g.fill(boxX + boxW - HANDLE, boxY + boxH - HANDLE, boxX + boxW, boxY + boxH, OUTLINE);
		g.centeredText(font, "Kasten ziehen oder irgendwo hinklicken = verschieben · Pfeiltasten = schieben (Shift = schneller)", width / 2, 8, HINT);
		g.centeredText(font, "Grüne Ecke ziehen = breiter/schmaler · Mausrad = größer/kleiner · Esc oder Fertig = speichern", width / 2, 20, HINT);
		g.centeredText(font, "Größe " + Math.round(c.hudGroesse * 100) + " % · Breite " + c.hudBreite + " · " + c.hudZeilen + " Aufgaben", width / 2, height - 62, 0xFFA0A0A0);
		if (minecraft.player != null && TaskHud.myRows(minecraft, BautruppClient.store).isEmpty())
			g.centeredText(font, "Beispiel – im Spiel erscheint der Kasten erst, wenn dir offene Aufgaben zugeteilt sind", width / 2, 32, 0xFFF2C94C);
	}

	private boolean inBox(double x, double y) {
		return x >= boxX && x <= boxX + boxW && y >= boxY && y <= boxY + boxH;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (e.button() != 0) return false;
		if (e.x() >= boxX + boxW - HANDLE - 2 && e.x() <= boxX + boxW + 2 && e.y() >= boxY + boxH - HANDLE - 2 && e.y() <= boxY + boxH + 2) {
			resizing = true;
			return true;
		}
		// Außerhalb des Kastens geklickt: Kasten springt dorthin (Mitte unter die Maus)
		if (!inBox(e.x(), e.y())) {
			moveTo((int) Math.round(e.x() - boxW / 2.0), (int) Math.round(e.y() - boxH / 2.0));
			boxX = Math.round(c.hudX * width);
			boxY = Math.round(c.hudY * height);
		}
		dragging = true;
		grabX = e.x() - boxX;
		grabY = e.y() - boxY;
		return true;
	}

	/** Falls das Spiel kein "Ziehen" meldet: Mausbewegung bei gedrückter Taste reicht auch. */
	@Override
	public void mouseMoved(double x, double y) {
		if (dragging) moveTo((int) Math.round(x - grabX), (int) Math.round(y - grabY));
		else if (resizing) c.hudBreite = Math.max(90, Math.min(400, Math.round((float) ((x - boxX) / c.hudGroesse))));
		super.mouseMoved(x, y);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int step = e.hasShiftDown() ? 10 : 1;
		int dx = e.isLeft() ? -step : e.isRight() ? step : 0, dy = e.isUp() ? -step : e.isDown() ? step : 0;
		if (dx != 0 || dy != 0) {
			moveTo(boxX + dx, boxY + dy);
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (resizing) {
			c.hudBreite = Math.max(90, Math.min(400, Math.round((float) ((e.x() - boxX) / c.hudGroesse))));
			return true;
		}
		if (dragging) {
			moveTo((int) Math.round(e.x() - grabX), (int) Math.round(e.y() - grabY));
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		dragging = false;
		resizing = false;
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scale(dy > 0 ? 0.1f : -0.1f);
		return true;
	}

	@Override
	public void onClose() {
		if ("aus".equals(c.anzeige)) c.anzeige = "links";
		c.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
