package de.bautrupp;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

/**
 * Zählt Ziel-Aufgaben, die dir gehören:
 * - sammeln: wie viele von dem Item du dabei hast (auch in Shulker-Kisten)
 * - abbauen / platzieren: über die Spiel-Statistik, ab dem Moment, ab dem die Aufgabe dir gehört
 */
public class GoalTracker {
	private static final int CHECK_EVERY_TICKS = 10;
	private static final int STATS_EVERY_TICKS = 40;
	private static final long MIN_SEND_GAP_MS = 1000;
	private static int ticks = 0, statTicks = 0;
	private static final Map<String, Long> lastSent = new HashMap<>();

	public static void tick(Minecraft mc, Store store) {
		if (mc.player == null || !store.loadedOnce) return;
		String me = Store.myName();
		boolean needsStats = false;
		for (Store.Task t : store.tasks)
			if (active(t, me) && t.builds() && !t.hasArea()) needsStats = true;

		// Die Statistik schickt der Server nur auf Anfrage (wie beim Öffnen des Statistik-Menüs).
		if (needsStats && ++statTicks >= STATS_EVERY_TICKS && mc.getConnection() != null) {
			statTicks = 0;
			mc.getConnection().send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
		}

		if (++ticks < CHECK_EVERY_TICKS) return;
		ticks = 0;
		StatsCounter stats = mc.player.getStats();
		// Spielzeit ist immer größer als 0, sobald die Statistik vom Server da ist.
		boolean statsLoaded = stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) > 0;
		String world = worldId(mc);
		long now = System.currentTimeMillis();

		for (Store.Task t : store.tasks) {
			if (!active(t, me)) continue;
			// Jeder zählt seinen eigenen Anteil; zusammen ergibt das den Fortschritt der Aufgabe.
			if (t.collects()) {
				int mine = t.share(me);
				int count = count(mc.player.getInventory(), t.item);
				if (count != mine) send(store, t, me, mine, count, false, now);
			}
			// Bestand im Bereich zählt BestandScanner, nicht die eigenen Platzierungen.
			if (t.builds() && !t.bestandMode()) {
				int mine = t.buildShare(me);
				int count;
				if (t.hasArea()) {
					// Mit Bereich zählt AreaCounter jeden Block selbst.
					Integer add = AreaCounter.pending.get(t.id);
					if (add == null) continue;
					count = mine + add;
				} else {
					if (!statsLoaded) continue;
					int value = statValue(stats, t);
					if (value < 0) continue;
					Store.Counter c = t.counter(me);
					if (c == null || c.start == null || !world.equals(c.welt) || value < c.start) {
						store.setBaseline(t, me, value, world);
						continue;
					}
					count = (c.basis == null ? 0 : c.basis) + value - c.start;
				}
				if (count != mine) send(store, t, me, mine, count, true, now);
			}
		}
	}

	private static void send(Store store, Store.Task t, String me, int mine, int count, boolean build, long now) {
		boolean decides = build || !t.isCombo();
		boolean reached = decides && store.goalTotal(t) - mine + count >= t.ziel;
		// Beim Abbauen ändert sich die Zahl ständig; nicht öfter als einmal pro Sekunde senden.
		String key = t.id + (build ? "#bau" : "");
		if (!reached && now - lastSent.getOrDefault(key, 0L) < MIN_SEND_GAP_MS) return;
		lastSent.put(key, now);
		if (build && t.hasArea()) AreaCounter.pending.remove(t.id);
		store.setProgress(t, me, count, build);
		if (reached) BautruppClient.chat("Geschafft! " + t.titel + " ist erledigt.");
	}

	private static boolean active(Store.Task t, String me) {
		// Gesperrte Aufgaben (Reihenfolge) zählen erst, wenn sie freigeschaltet sind.
		return t.hasGoal() && !"fertig".equals(t.status) && t.has(me) && !BautruppClient.store.locked(t);
	}

	/** -1, wenn das Item in diesem Spiel nicht existiert. */
	private static int statValue(StatsCounter stats, Store.Task t) {
		Identifier id = Identifier.tryParse(t.item);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return -1;
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (t.places()) return stats.getValue(Stats.ITEM_USED.get(item));
		Block block = Block.byItem(item);
		if (block == Blocks.AIR) return -1;
		return stats.getValue(Stats.BLOCK_MINED.get(block));
	}

	/** Statistiken gelten pro Welt bzw. Server; so merkt die Mod, wenn du woanders spielst. */
	private static String worldId(Minecraft mc) {
		if (mc.getCurrentServer() != null) return "server:" + mc.getCurrentServer().ip;
		if (mc.getSingleplayerServer() != null) return "welt:" + mc.getSingleplayerServer().getWorldData().getLevelName();
		return "";
	}

	public static int count(Inventory inv, String itemId) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (matches(s, itemId)) n += s.getCount();
			ItemContainerContents box = s.get(DataComponents.CONTAINER);
			if (box != null) {
				n += box.nonEmptyItemCopyStream().filter(x -> matches(x, itemId)).mapToInt(ItemStack::getCount).sum();
			}
		}
		return n;
	}

	private static boolean matches(ItemStack s, String itemId) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(itemId);
	}

	/** "Stone Bricks" -> "minecraft:stone_bricks", wenn es so ein Item gibt, sonst "". */
	public static String idFromName(String name) {
		String guess = name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "_");
		if (!guess.contains(":")) guess = "minecraft:" + guess;
		Identifier id = Identifier.tryParse(guess);
		return id != null && BuiltInRegistries.ITEM.containsKey(id) ? id.toString() : "";
	}

	public static String idOf(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}
}
