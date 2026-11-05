package de.bautrupp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Speichert Datenbank-Adresse und Gruppencode in config/bautrupp.json. */
public class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bautrupp.json");

	public String datenbank = "";
	public String gruppe = "";
	/** Aufgaben-Anzeige am Bildschirmrand: "links", "rechts" oder "aus". */
	public String anzeige = "links";
	/** Prüfwert des Admin-Passworts, wenn man sich als Admin angemeldet hat (nicht das Passwort selbst). */
	public String adminHash = "";
	/** Gewähltes Projekt (ID); leer = alle Projekte. Neue Einträge landen in diesem Projekt. */
	public String projekt = "";
	/** Meldungen im eigenen Chat: "alle", "meine" (nur zu eigenen Aufgaben) oder "aus". */
	public String meldungen = "alle";
	/** Anzeige am Bildschirmrand: Position als Anteil der Bildschirmbreite/-höhe (-1 = links/rechts wie früher), Breite, Größe, Zeilen. */
	public float hudX = -1, hudY = -1;
	public int hudBreite = 150, hudZeilen = 5;
	public float hudGroesse = 1.0f;
	/** Pfeil und Entfernung zur Baustelle in der Anzeige. */
	public boolean wegweiser = true;
	/** Item-Namen: "englisch" (Standard, wie auf der Webseite) oder "spiel" (Sprache des Spiels). */
	public String itemSprache = "englisch";

	public static Config load() {
		try {
			if (Files.exists(FILE)) {
				Config c = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Config.class);
				if (c != null) {
					if (c.anzeige == null) c.anzeige = "links";
					if (c.adminHash == null) c.adminHash = "";
					if (c.projekt == null) c.projekt = "";
					if (c.meldungen == null) c.meldungen = "alle";
					if (c.itemSprache == null) c.itemSprache = "englisch";
					if (c.hudBreite < 90) c.hudBreite = 150;
					if (c.hudZeilen < 1) c.hudZeilen = 5;
					if (c.hudGroesse < 0.5f) c.hudGroesse = 1.0f;
					return c;
				}
			}
		} catch (Exception e) {
			BautruppClient.LOG.warn("Konnte bautrupp.json nicht lesen", e);
		}
		return new Config();
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (Exception e) {
			BautruppClient.LOG.warn("Konnte bautrupp.json nicht speichern", e);
		}
	}

	public boolean isComplete() {
		return !datenbank.isBlank() && !gruppe.isBlank();
	}

	/** Macht aus dem, was man aus Firebase kopiert, eine saubere Basis-URL. */
	public static String normalizeUrl(String url) {
		String u = url.trim();
		if (u.isEmpty()) return u;
		if (!u.startsWith("http")) u = "https://" + u;
		while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
		return u;
	}

	/** Firebase erlaubt in Schlüsseln kein . # $ [ ] / */
	public static String normalizeGroup(String code) {
		return code.trim().replaceAll("[^A-Za-z0-9_-]", "");
	}
}
