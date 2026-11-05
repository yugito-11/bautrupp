package de.bautrupp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Schickt Verlaufseinträge per Webhook in einen Discord-Kanal. Wer die Änderung macht,
 * schickt die Nachricht (Mod oder Webseite); einen eigenen Server braucht es nicht.
 * Eingerichtet wird der Webhook auf der Webseite unter Einstellungen.
 */
public class Discord {
	/** Farben der Nachrichten je Art. */
	private static final Map<String, Integer> COLORS = Map.ofEntries(
			Map.entry("neu", 0x2F6E9E), Map.entry("erledigt", 0x4F8A2B), Map.entry("status", 0xC28B12),
			Map.entry("uebernommen", 0x7D4FB5), Map.entry("kommentar", 0x5D6B7A), Map.entry("schritt", 0x2A8A7E),
			Map.entry("geloescht", 0xB33A2E), Map.entry("projekt", 0x8A5A35), Map.entry("lager", 0xA8572B),
			Map.entry("material", 0x6DB33F), Map.entry("meilenstein", 0xE2B23D));
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	public static boolean validUrl(String url) {
		return url != null && url.matches("https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+");
	}

	/**
	 * Absendername: das Projekt, zu dem die Änderung gehört, sonst "Bautrupp".
	 * Discord erlaubt höchstens 80 Zeichen und kein @ # : ``` oder "discord"/"clyde" im Namen.
	 */
	static String senderName(String projekt) {
		String n = projekt == null ? "" : projekt.replaceAll("[@#:`]", "").replaceAll("(?i)discord|clyde", "")
				.replaceAll("\\s+", " ").replaceAll("^[\\s\\-_.,]+|[\\s\\-_.,]+$", "");
		if (n.length() > 80) n = n.substring(0, 80).trim();
		return n.isEmpty() ? "Bautrupp" : n;
	}

	public static void post(Store store, Store.LogEntry e, String type, String projekt) {
		if (!validUrl(store.discordWebhook)) return;
		if (Boolean.FALSE.equals(store.discordEvents.get(type))) return;

		JsonObject embed = new JsonObject();
		embed.addProperty("description", "**" + e.wer + "** " + e.text);
		embed.addProperty("color", COLORS.getOrDefault(type, 0x5D6B7A));
		embed.addProperty("timestamp", Instant.ofEpochMilli(e.zeit).toString());
		JsonObject author = new JsonObject();
		author.addProperty("name", e.wer);
		author.addProperty("icon_url", "https://mc-heads.net/avatar/" + e.wer + "/64");
		embed.add("author", author);
		String proj = store.projectName(projekt);
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Bautrupp · aus Minecraft");
		embed.add("footer", footer);

		JsonObject body = new JsonObject();
		body.addProperty("username", senderName(proj));
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		body.add("embeds", embeds);
		// Keine @everyone/@here-Erwähnungen aus Aufgabentexten auslösen
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		body.add("allowed_mentions", mentions);

		HttpRequest req = HttpRequest.newBuilder(URI.create(store.discordWebhook))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(ex -> {
			BautruppClient.LOG.warn("Bautrupp: Discord-Nachricht fehlgeschlagen", ex);
			return null;
		});
	}
}
