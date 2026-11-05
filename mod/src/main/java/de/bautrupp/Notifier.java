package de.bautrupp;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * Meldungen über Änderungen der anderen (aus dem Verlauf). Sie erscheinen nur im
 * eigenen Chat-Fenster: Die Mod zeigt sie lokal an und schickt nichts an den Server,
 * deshalb sieht sie niemand ohne die Mod.
 */
public class Notifier {
	private static final int MAX_AT_ONCE = 5;
	private static final Set<String> seen = new HashSet<>();

	/** Eigene Einträge nicht als Meldung zeigen. */
	public static void markSeen(String id) {
		seen.add(id);
	}

	public static void onUpdate(Store store, List<Store.Task> oldTasks, List<Store.Task> newTasks,
								List<Store.LogEntry> oldLog, List<Store.LogEntry> newLog) {
		String mode = store.config.meldungen;
		if ("aus".equals(mode)) return;
		String me = Store.myName();
		Set<String> known = new HashSet<>();
		for (Store.LogEntry e : oldLog) known.add(e.id);

		List<Store.LogEntry> fresh = new ArrayList<>();
		for (Store.LogEntry e : newLog) {
			if (known.contains(e.id) || seen.contains(e.id)) continue;
			seen.add(e.id);
			if (me.equalsIgnoreCase(e.wer)) continue;
			Store.Task t = e.aufgabe == null || e.aufgabe.isEmpty() ? null : find(newTasks, e.aufgabe);
			boolean aboutMe = t != null && (t.has(me) || me.equalsIgnoreCase(t.ersteller));
			if ("meine".equals(mode) && !aboutMe) continue;
			fresh.add(e);
		}

		int shown = 0;
		for (Store.LogEntry e : fresh) {
			if (shown == MAX_AT_ONCE) {
				show(Component.literal("+ " + (fresh.size() - shown) + " weitere Änderungen (Verlauf auf der Webseite)").withStyle(ChatFormatting.GRAY));
				break;
			}
			Store.Task t = e.aufgabe == null || e.aufgabe.isEmpty() ? null : find(newTasks, e.aufgabe);
			boolean forMe = t != null && t.has(me);
			show(Component.literal(e.wer + " " + e.text).withStyle(forMe ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
			shown++;
		}
	}

	private static Store.Task find(List<Store.Task> list, String id) {
		for (Store.Task t : list) if (t.id.equals(id)) return t;
		return null;
	}

	private static void show(Component msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.player.sendSystemMessage(Component.literal("[Bautrupp] ").withStyle(ChatFormatting.GREEN).append(msg));
	}
}
