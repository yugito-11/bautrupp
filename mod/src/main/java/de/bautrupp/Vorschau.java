package de.bautrupp;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Nur zum Entwickeln: öffnet die Bautrupp-Menüs der Reihe nach und macht Bildschirmfotos
 * (run/screenshots), danach beendet sich das Spiel. Aktiv nur in der Entwicklungsumgebung
 * und nur, wenn BAUTRUPP_VORSCHAU gesetzt ist, z. B. "aufgaben,material,aufgabe:Wände".
 */
public final class Vorschau {
	private static final List<String> steps = new ArrayList<>();
	private static int step = -1, wait = 0, ticks = 0;
	private static boolean active;

	private Vorschau() {}

	public static void init() {
		String spec = System.getenv("BAUTRUPP_VORSCHAU");
		if (spec == null || spec.isBlank() || !FabricLoader.getInstance().isDevelopmentEnvironment()) return;
		for (String s : spec.split(",")) if (!s.isBlank()) steps.add(s.trim());
		active = true;
	}

	public static void tick(Minecraft mc, Store store) {
		if (!active) return;
		if (step < 0) {
			// Warten, bis ein Menü da ist (Titelbild o. Ä.) und die Daten geladen sind
			ticks++;
			if (ticks % 200 == 0) BautruppClient.LOG.info("Vorschau wartet: Bildschirm {}, Daten {}", mc.gui.screen(), store.loadedOnce);
			if (mc.gui.screen() == null || !store.loadedOnce || ticks < 100) return;
			step = 0;
			wait = 0;
		}
		if (step >= steps.size()) {
			if (++wait > 20) mc.stop();
			return;
		}
		wait++;
		if (wait == 1) open(mc, store, steps.get(step));
		else if (wait == 40) Screenshot.grab(mc, false);
		else if (wait >= 60) { step++; wait = 0; }
	}

	private static void open(Minecraft mc, Store store, String s) {
		BautruppClient.LOG.info("Vorschau: {}", s);
		Screen next;
		BautruppScreen main = BautruppScreen.withTab(store, "aufgaben");
		if (s.startsWith("aufgabe:")) {
			// Anfang des Titels (nur ASCII, Umlaute kommen über die Umgebung nicht sicher an); optional "/reiter"
			String[] p = s.substring(8).split("/");
			Store.Task t = store.tasks.stream().filter(x -> !x.isChild() && x.titel.toLowerCase().startsWith(p[0].toLowerCase())).findFirst().orElse(null);
			next = new TaskScreen(store, t == null ? "" : t.id, main);
			if (p.length > 1) TaskScreen.presetPage(p[1]);
		} else if (s.equals("anlegen")) {
			// Wie ein Klick auf "Anlegen": eine Aufgabe ohne Ziel, eine mit Ziel, ein Material
			mc.gui.setScreen(main);
			NewTaskScreen a = NewTaskScreen.preset(store, main, "", "", "").titled("Test: Brücke bauen");
			mc.gui.setScreen(a);
			a.create();
			NewTaskScreen b = NewTaskScreen.preset(store, main, "sammeln", "320", "oak lo").titled("");
			mc.gui.setScreen(b);
			b.create();
			BautruppClient.LOG.info("Vorschau anlegen: Fehler ohne Ziel = {}, mit Ziel = {}", a.lastError(), b.lastError());
			AddMaterialScreen m = new AddMaterialScreen(store, main).with("glass pane", "128");
			mc.gui.setScreen(m);
			m.add();
			for (Store.Task t : store.tasks) if (t.titel.startsWith("Test") || t.titel.startsWith("320"))
				BautruppClient.LOG.info("Vorschau angelegt: {} | wer={} | Ziel={} {} {}", t.titel, t.wer, t.ziel, t.item, t.art);
			for (Store.Material x : store.materials) BautruppClient.LOG.info("Vorschau Material: {} {} {}", x.need, x.item, x.itemId);
			next = BautruppScreen.withTab(store, "aufgaben");
		} else if (s.equals("neu")) {
			next = NewTaskScreen.preset(store, main, "", "", "");
		} else if (s.equals("neu-ziel2")) {
			next = NewTaskScreen.preset(store, main, "platzieren", "80", "stairs");
		} else if (s.equals("neu-ziel")) {
			next = NewTaskScreen.preset(store, main, "sammeln", "640", "oak lo");
		} else if (s.equals("material-neu")) {
			next = new AddMaterialScreen(store, main);
		} else if (s.equals("projekte")) {
			next = new ProjectScreen(store, main);
		} else {
			next = BautruppScreen.withTab(store, s);
		}
		mc.gui.setScreen(next);
	}
}
