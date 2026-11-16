package de.bautrupp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Item-Namen: Standard ist Englisch (wie auf der Webseite), egal welche Sprache das Spiel hat.
 * In den Einstellungen kann man auf die Spielsprache umstellen. Finden klappt immer mit beidem:
 * englischer Name, Name in der Spielsprache (z. B. "Diamantblock") oder ID ("diamond_block").
 */
public class ItemNames {
	private static Map<String, Item> byName;
	private static String builtFor = "";
	private static Map<String, String> english;

	private static String norm(String s) {
		return s.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
	}

	/** ID ohne "minecraft:" -> englischer Name (aus assets/bautrupp/items_en.json). */
	private static Map<String, String> english() {
		if (english == null) {
			english = new HashMap<>();
			try (InputStream in = ItemNames.class.getResourceAsStream("/assets/bautrupp/items_en.json")) {
				if (in != null) english = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
			} catch (Exception e) {
				BautruppClient.LOG.warn("Konnte die englischen Item-Namen nicht laden", e);
			}
		}
		return english;
	}

	private static boolean gameLanguage() {
		return "spiel".equals(BautruppClient.store.config.itemSprache);
	}

	/** Name in der Spielsprache, ohne ein ItemStack zu bauen (geht auch, bevor eine Welt geladen ist). */
	private static String gameName(Item i) {
		return Component.translatable(i.getDescriptionId()).getString();
	}

	/** Englischer Name eines Items (so wird er gespeichert). */
	public static String english(ItemStack s) {
		return english(s.getItem());
	}

	public static String english(Item i) {
		return english().getOrDefault(BuiltInRegistries.ITEM.getKey(i).getPath(), gameName(i));
	}

	/** Name zum Anzeigen, in der eingestellten Sprache. */
	public static String label(ItemStack s) {
		return label(s.getItem());
	}

	public static String label(Item i) {
		return gameLanguage() ? gameName(i) : english(i);
	}

	/** Name zum Anzeigen für eine gespeicherte ID ("minecraft:oak_planks"); sonst der gespeicherte Name. */
	public static String label(String fullId, String fallback) {
		Identifier id = fullId == null || fullId.isEmpty() ? null : Identifier.tryParse(fullId);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return fallback == null || fallback.isEmpty() ? (fullId == null ? "" : fullId) : fallback;
		return label(BuiltInRegistries.ITEM.getValue(id));
	}

	private static Map<String, Item> names() {
		String lang = net.minecraft.client.Minecraft.getInstance().options.languageCode;
		if (byName == null || !lang.equals(builtFor)) {
			byName = new HashMap<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item == Items.AIR) continue;
				var id = BuiltInRegistries.ITEM.getKey(item);
				String en = english().get(id.getPath());
				if (en != null) byName.putIfAbsent(norm(en), item);
				byName.putIfAbsent(norm(gameName(item)), item);
				byName.putIfAbsent(norm(id.getPath()), item);
				byName.putIfAbsent(norm(id.toString()), item);
			}
			builtFor = lang;
		}
		return byName;
	}

	/** Das Item zum Namen, oder null. Bei einem eindeutigen Teil-Treffer ("diamond bl") auch dieses. */
	public static Item findItem(String text) {
		if (text == null || text.isBlank()) return null;
		String n = norm(text);
		Item exact = names().get(n);
		if (exact != null) return exact;
		// Genau ein Item, dessen Name so anfängt ("oak lo" -> Oak Log)
		Item start = null;
		for (Map.Entry<String, Item> e : names().entrySet()) {
			if (!e.getKey().startsWith(n)) continue;
			if (start != null && start != e.getValue()) { start = null; break; }
			start = e.getValue();
		}
		if (start != null) return start;
		List<Item> partial = matches(n, 2);
		return partial.size() == 1 ? partial.get(0) : null;
	}

	/** Wie findItem, aber als ItemStack (nur in einer Welt aufrufen). */
	public static ItemStack find(String text) {
		Item i = findItem(text);
		return i == null ? null : new ItemStack(i);
	}

	/** Bis zu max Vorschläge, deren Name den Text enthält. */
	public static List<String> suggest(String text, int max) {
		List<String> out = new ArrayList<>();
		for (Item i : matches(norm(text), max)) out.add(label(i));
		return out;
	}

	/** Passt der Suchtext zu diesem Item (englisch, Spielsprache oder ID)? */
	public static boolean matchesSearch(String fullId, String shownName, String query) {
		String q = norm(query);
		if (q.isEmpty()) return true;
		if (norm(shownName).contains(q) || norm(fullId == null ? "" : fullId).contains(q)) return true;
		Identifier id = fullId == null || fullId.isEmpty() ? null : Identifier.tryParse(fullId);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return false;
		Item i = BuiltInRegistries.ITEM.getValue(id);
		return norm(english(i)).contains(q) || norm(gameName(i)).contains(q);
	}

	/** Treffer für den Text: erst Namen, die damit anfangen, dann Wortanfänge, dann irgendwo; kürzere zuerst. */
	private static List<Item> matches(String n, int max) {
		List<Item> out = new ArrayList<>();
		if (n.length() < 3) return out;
		List<Map.Entry<String, Item>> hits = new ArrayList<>();
		for (Map.Entry<String, Item> e : names().entrySet()) if (e.getKey().contains(n)) hits.add(e);
		hits.sort(java.util.Comparator.<Map.Entry<String, Item>>comparingInt(e -> e.getKey().startsWith(n) ? 0 : e.getKey().contains(" " + n) ? 1 : 2)
				.thenComparingInt(e -> e.getKey().length()).thenComparing(Map.Entry::getKey));
		for (Map.Entry<String, Item> e : hits) {
			if (out.contains(e.getValue())) continue;
			out.add(e.getValue());
			if (out.size() >= max) break;
		}
		return out;
	}
}
