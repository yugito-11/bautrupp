package de.bautrupp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
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

	/** Englischer Name eines Items (so wird er gespeichert). */
	public static String english(ItemStack s) {
		String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return english().getOrDefault(path, s.getHoverName().getString());
	}

	/** Name zum Anzeigen, in der eingestellten Sprache. */
	public static String label(ItemStack s) {
		return gameLanguage() ? s.getHoverName().getString() : english(s);
	}

	/** Name zum Anzeigen für eine gespeicherte ID ("minecraft:oak_planks"); sonst der gespeicherte Name. */
	public static String label(String fullId, String fallback) {
		Identifier id = fullId == null || fullId.isEmpty() ? null : Identifier.tryParse(fullId);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return fallback == null || fallback.isEmpty() ? (fullId == null ? "" : fullId) : fallback;
		return label(new ItemStack(BuiltInRegistries.ITEM.getValue(id)));
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
				byName.putIfAbsent(norm(new ItemStack(item).getHoverName().getString()), item);
				byName.putIfAbsent(norm(id.getPath()), item);
				byName.putIfAbsent(norm(id.toString()), item);
			}
			builtFor = lang;
		}
		return byName;
	}

	/** Das Item zum Namen, oder null. Bei einem eindeutigen Teil-Treffer ("diamond bl") auch dieses. */
	public static ItemStack find(String text) {
		if (text == null || text.isBlank()) return null;
		String n = norm(text);
		Item exact = names().get(n);
		if (exact != null) return new ItemStack(exact);
		List<Item> partial = matches(n, 2);
		return partial.size() == 1 ? new ItemStack(partial.get(0)) : null;
	}

	/** Bis zu max Vorschläge, deren Name den Text enthält. */
	public static List<String> suggest(String text, int max) {
		List<String> out = new ArrayList<>();
		for (Item i : matches(norm(text), max)) out.add(label(new ItemStack(i)));
		return out;
	}

	/** Passt der Suchtext zu diesem Item (englisch, Spielsprache oder ID)? */
	public static boolean matchesSearch(String fullId, String shownName, String query) {
		String q = norm(query);
		if (q.isEmpty()) return true;
		if (norm(shownName).contains(q) || norm(fullId == null ? "" : fullId).contains(q)) return true;
		Identifier id = fullId == null || fullId.isEmpty() ? null : Identifier.tryParse(fullId);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return false;
		ItemStack s = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
		return norm(english(s)).contains(q) || norm(s.getHoverName().getString()).contains(q);
	}

	private static List<Item> matches(String n, int max) {
		List<Item> out = new ArrayList<>();
		if (n.length() < 3) return out;
		for (Map.Entry<String, Item> e : names().entrySet()) {
			if (e.getKey().contains(n) && !out.contains(e.getValue())) {
				out.add(e.getValue());
				if (out.size() >= max) break;
			}
		}
		return out;
	}
}
