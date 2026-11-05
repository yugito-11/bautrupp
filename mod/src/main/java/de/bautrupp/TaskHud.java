package de.bautrupp;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Kleine Liste der eigenen offenen Aufgaben am Bildschirmrand. Position, Breite, Größe und
 * Zeilenzahl lassen sich im Bearbeiten-Modus (HudEditScreen) einstellen; lange Namen werden umgebrochen.
 */
public class TaskHud {
	public static final int PAD = 4, MARGIN = 6, MAX_TITLE_LINES = 3;
	private static final int BG = 0x90000000, TITLE = 0xFF7BD35A, WHITE = 0xFFFFFFFF, YELLOW = 0xFFF2C94C, GREY = 0xFFA0A0A0;

	/** Eine Zeile der Liste (für die Vorschau im Bearbeiten-Modus auch mit Beispieltexten). */
	public record Row(String title, boolean working, String right, String below) {}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Store store = BautruppClient.store;
		if ("aus".equals(store.config.anzeige) || mc.player == null || !store.config.isComplete() || !store.loadedOnce) return;
		if (mc.gui.screen() instanceof BautruppScreen || mc.gui.screen() instanceof HudEditScreen) return;
		// Die F3-Ansicht belegt beide Ränder oben; dann lieber nichts zeigen.
		if (mc.debugEntries.isOverlayVisible()) return;
		List<Row> rows = myRows(mc, store);
		if (rows.isEmpty()) return;
		int[] pos = position(g.guiWidth(), g.guiHeight(), store.config, height(mc.font, rows, store.config));
		draw(g, mc.font, rows, pos[0], pos[1], store.config);
	}

	public static List<Row> myRows(Minecraft mc, Store store) {
		String me = Store.myName();
		BlockPos here = mc.player.blockPosition();
		String dim = BautruppClient.currentDim();
		List<Row> rows = new ArrayList<>();
		for (String st : new String[]{"arbeit", "offen"})
			for (Store.Task t : store.tasks) {
				// Teilziele stecken in ihrer Aufgabe; gesperrte (Reihenfolge) kommen erst später
				if (!st.equals(t.status) || !t.has(me) || t.isChild() || store.locked(t)) continue;
				String right = "", below = null;
				String way = store.config.wegweiser ? direction(mc, t, here, dim) : "";
				List<Store.Task> kids = store.children(t);
				if (!kids.isEmpty()) {
					right = store.percent(t) + "%";
					below = (kids.size() + (t.hasGoal() ? 1 : 0)) + " Ziele";
				} else if (t.hasGoal()) {
					right = store.goalTotal(t) + "/" + t.ziel;
					if (t.isCombo()) below = "gesammelt " + store.collectTotal(t) + ", verbaut " + t.progress();
				} else {
					right = way;
					way = "";
				}
				if (!way.isEmpty()) below = below == null ? way : below + "  " + way;
				rows.add(new Row(t.titel, "arbeit".equals(st), right, below));
			}
		return rows;
	}

	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	/** Pfeil (aus Blickrichtung) und Entfernung zur Baustelle, z. B. "↗ 120m"; leer ohne Ort oder in anderer Dimension. */
	private static String direction(Minecraft mc, Store.Task t, BlockPos here, String dim) {
		if (t.dim != null && !t.dim.isEmpty() && !t.dim.equals(dim)) return "";
		double tx, tz;
		if (t.bereich != null && t.bereich.valid()) {
			tx = (t.bereich.x1 + t.bereich.x2) / 2.0;
			tz = (t.bereich.z1 + t.bereich.z2) / 2.0;
		} else if (t.x != null && t.z != null) {
			tx = t.x;
			tz = t.z;
		} else return "";
		double dx = tx + 0.5 - mc.player.getX(), dz = tz + 0.5 - mc.player.getZ();
		long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
		if (dist < 3) return "hier";
		// Minecraft: Blickwinkel 0 = Süden (+Z), im Uhrzeigersinn steigend
		double target = Math.toDegrees(Math.atan2(-dx, dz));
		double rel = ((target - mc.player.getYRot()) % 360 + 360) % 360;
		return ARROWS[(int) Math.round(rel / 45) % 8] + " " + dist + "m";
	}

	/** Bildschirmposition (links oben) in GUI-Pixeln; ohne eigene Position wie früher links/rechts. */
	public static int[] position(int guiW, int guiH, Config c, int h) {
		int w = Math.round(c.hudBreite * c.hudGroesse), hh = Math.round(h * c.hudGroesse);
		int x, y;
		if (c.hudX < 0 || c.hudY < 0) {
			x = "rechts".equals(c.anzeige) ? guiW - w - MARGIN : MARGIN;
			y = guiH / 3 - hh / 2;
		} else {
			x = Math.round(c.hudX * guiW);
			y = Math.round(c.hudY * guiH);
		}
		x = Math.max(0, Math.min(x, guiW - w));
		y = Math.max(0, Math.min(y, guiH - hh));
		return new int[]{x, y};
	}

	private static List<FormattedCharSequence> wrap(Font font, String text, int width) {
		List<FormattedCharSequence> lines = font.split(Component.literal(text), Math.max(20, width));
		if (lines.size() > MAX_TITLE_LINES) {
			lines = new ArrayList<>(lines.subList(0, MAX_TITLE_LINES));
			lines.set(MAX_TITLE_LINES - 1, Component.literal(font.plainSubstrByWidth(text, Math.max(20, width) * MAX_TITLE_LINES - 12) + "...").getVisualOrderText());
		}
		return lines;
	}

	private static int titleWidth(Font font, Config c, Row r) {
		return c.hudBreite - PAD * 2 - font.width("> ") - (r.right.isEmpty() ? 0 : font.width(" " + r.right));
	}

	/** Höhe ohne Größen-Faktor. */
	public static int height(Font font, List<Row> rows, Config c) {
		int lineH = font.lineHeight + 2;
		int n = 1;
		int shown = Math.min(c.hudZeilen, rows.size());
		for (int i = 0; i < shown; i++) {
			Row r = rows.get(i);
			n += wrap(font, r.title, titleWidth(font, c, r)).size() + (r.below == null ? 0 : 1);
		}
		if (rows.size() > shown) n++;
		return PAD * 2 + n * lineH;
	}

	public static void draw(GuiGraphicsExtractor g, Font font, List<Row> rows, int x, int y, Config c) {
		int h = height(font, rows, c), lineH = font.lineHeight + 2, w = c.hudBreite;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(c.hudGroesse, c.hudGroesse);
		g.fill(0, 0, w, h, BG);
		int ty = PAD;
		g.text(font, "Meine Aufgaben (" + rows.size() + ")", PAD, ty, TITLE);
		ty += lineH;
		int shown = Math.min(c.hudZeilen, rows.size());
		int indent = font.width("> ");
		for (int i = 0; i < shown; i++) {
			Row r = rows.get(i);
			int color = r.working ? YELLOW : WHITE;
			g.text(font, r.working ? ">" : "-", PAD, ty, color);
			List<FormattedCharSequence> lines = wrap(font, r.title, titleWidth(font, c, r));
			for (int l = 0; l < lines.size(); l++) {
				g.text(font, lines.get(l), PAD + indent, ty, color);
				if (l == 0 && !r.right.isEmpty()) g.text(font, r.right, w - PAD - font.width(r.right), ty, GREY);
				ty += lineH;
			}
			if (r.below != null) {
				g.text(font, font.plainSubstrByWidth(r.below, w - PAD * 2 - indent), PAD + indent, ty, GREY);
				ty += lineH;
			}
		}
		if (rows.size() > shown) g.text(font, "+ " + (rows.size() - shown) + " weitere (J)", PAD, ty, GREY);
		pose.popMatrix();
	}
}
