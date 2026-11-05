package de.bautrupp;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zählmodus "Bestand": zählt, wie viele der gewünschten Blöcke gerade im Bereich einer
 * Aufgabe stehen, egal wer sie wann gesetzt hat.
 * Gezählt wird Chunk für Chunk. Was gerade nicht geladen ist (zu weit weg), nimmt die Mod aus
 * ihrem Gedächtnis, wenn sie den Chunk in den letzten 15 Minuten gesehen hat. So klappt es auch bei
 * Baustellen, die größer als die Sichtweite sind: einmal alles ablaufen, danach zählt es.
 * Abschnitte (16x16x16), in denen der Block gar nicht vorkommt, werden übersprungen;
 * die Arbeit wird über mehrere Ticks verteilt, damit das Spiel nicht ruckelt.
 * Gezählt wird nach Item, so wie man es zum Bauen braucht: Wandfackel = Fackel, Tür und Bett
 * einmal (nicht beide Hälften), doppelte Stufe = 2 Stufen, 3 Kerzen in einem Block = 3 Kerzen.
 * Aufgaben mit Bauplan (aus einer Schematic) prüfen nur die Stellen, an denen laut Plan dieses Item
 * hingehört. Ist die Drehung unbekannt, werden alle vier probiert und die beste genommen.
 */
public class BestandScanner {
	private static final long RESCAN_MS = 5000, CACHE_MS = 15 * 60_000;
	/** Arbeit pro Tick: Abschnitte (Bereich) bzw. geprüfte Positionen (Bauplan). */
	private static final int SECTIONS_PER_TICK = 24, PLAN_CHECKS_PER_TICK = 4096;
	/** Geladene Baupläne (Positionsnummern) je Aufgabe; laufende und fehlgeschlagene Ladevorgänge. */
	private static final Map<String, int[]> plans = new HashMap<>();
	private static final Map<String, Long> planWait = new HashMap<>();
	private static final Map<String, Long> lastScan = new HashMap<>();
	/** Gedächtnis: je Aufgabe (und Bereich) die zuletzt gezählten Zahlen pro Chunk und Drehung. */
	private static final Map<String, Map<Long, Seen>> memory = new HashMap<>();
	/** Aufgaben, bei denen Teile der Baustelle noch nie gesehen wurden. */
	private static final Set<String> incomplete = new HashSet<>();
	private static Job job;
	private static int idle = 0;
	private static String worldKey = "";

	private record Seen(int[] counts, long time) {}

	private static class Job {
		Store.Task task;
		Item item;
		String memoryKey;
		int rots = 1;
		// Bereich (Rechteck oder Umkreis)
		int minX, maxX, minY, maxY, minZ, maxZ;
		boolean circle;
		int cx, cz;
		long r2;
		/** Chunks der Baustelle in Reihenfolge; beim Bauplan je Chunk die Positionen (Drehung, x, y, z). */
		final List<Long> chunks = new ArrayList<>();
		final Map<Long, int[]> planPositions = new HashMap<>();
		final Map<Long, int[]> fresh = new HashMap<>();
		int next = 0;
	}

	public static void tick(Minecraft mc, Store store) {
		if (mc.level == null || mc.player == null || !store.loadedOnce) return;
		// Andere Welt oder Dimension: Gedächtnis gilt nicht mehr
		String wk = mc.level.dimension().toString() + "@" + System.identityHashCode(mc.level);
		if (!wk.equals(worldKey)) { worldKey = wk; memory.clear(); incomplete.clear(); job = null; }
		if (job == null) {
			if (++idle < 20) return;
			idle = 0;
			job = nextJob(mc, store);
			if (job == null) return;
		}
		Job j = job;
		int budget = j.planPositions.isEmpty() ? SECTIONS_PER_TICK : PLAN_CHECKS_PER_TICK;
		while (budget > 0 && j.next < j.chunks.size()) {
			long key = j.chunks.get(j.next++);
			LevelChunk chunk = mc.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
			if (chunk == null) continue; // nicht geladen: Gedächtnis nehmen
			budget -= j.planPositions.isEmpty() ? scanChunk(mc, j, chunk, key) : scanPlanChunk(mc, j, key);
		}
		if (j.next >= j.chunks.size()) finish(store, j);
	}

	/** Bereich: alle Abschnitte eines Chunks zählen; gibt die Arbeit (Abschnitte) zurück. */
	private static int scanChunk(Minecraft mc, Job j, LevelChunk chunk, long key) {
		int baseX = ChunkPos.getX(key) << 4, baseZ = ChunkPos.getZ(key) << 4, minY = mc.level.getMinY(), n = 0, work = 0;
		for (int sy = (j.minY - minY) >> 4; sy <= (j.maxY - minY) >> 4; sy++, work++)
			n += scanSection(j, chunk.getSection(sy), baseX, minY + (sy << 4), baseZ);
		j.fresh.put(key, new int[]{n});
		return Math.max(1, work);
	}

	private static int scanSection(Job j, LevelChunkSection sec, int baseX, int baseY, int baseZ) {
		if (sec.hasOnlyAir() || !sec.maybeHas(s -> s.getBlock().asItem() == j.item)) return 0;
		int x0 = Math.max(j.minX, baseX), x1 = Math.min(j.maxX, baseX + 15);
		int y0 = Math.max(j.minY, baseY), y1 = Math.min(j.maxY, baseY + 15);
		int z0 = Math.max(j.minZ, baseZ), z1 = Math.min(j.maxZ, baseZ + 15);
		int n = 0;
		for (int x = x0; x <= x1; x++)
			for (int z = z0; z <= z1; z++) {
				if (j.circle) {
					long dx = x - j.cx, dz = z - j.cz;
					if (dx * dx + dz * dz > j.r2) continue;
				}
				for (int y = y0; y <= y1; y++) {
					BlockState s = sec.getBlockState(x - baseX, y - baseY, z - baseZ);
					if (s.getBlock().asItem() == j.item) n += weight(s);
				}
			}
		return n;
	}

	/** Bauplan: die Stellen in diesem Chunk prüfen, je Drehung getrennt; gibt die Arbeit zurück. */
	private static int scanPlanChunk(Minecraft mc, Job j, long key) {
		int[] pos = j.planPositions.get(key), counts = new int[j.rots];
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int i = 0; i < pos.length; i += 4) {
			BlockState s = mc.level.getBlockState(p.set(pos[i + 1], pos[i + 2], pos[i + 3]));
			if (s.getBlock().asItem() == j.item) counts[pos[i]] += weight(s);
		}
		j.fresh.put(key, counts);
		return Math.max(1, pos.length / 4);
	}

	/** Ergebnis: frisch Gezähltes plus Gedächtnis; gespeichert wird nur, wenn jeder Chunk bekannt ist. */
	private static void finish(Store store, Job j) {
		job = null;
		idle = 20; // gleich mit dem nächsten Ziel weitermachen
		long now = System.currentTimeMillis();
		lastScan.put(j.task.id, now);
		Map<Long, Seen> mem = memory.computeIfAbsent(j.memoryKey, k -> new HashMap<>());
		j.fresh.forEach((k, v) -> mem.put(k, new Seen(v, now)));
		int[] total = new int[j.rots];
		for (long key : j.chunks) {
			Seen seen = mem.get(key);
			if (seen == null || now - seen.time > CACHE_MS) {
				incomplete.add(j.task.id);
				return;
			}
			for (int r = 0; r < j.rots; r++) total[r] += seen.counts[r];
		}
		incomplete.remove(j.task.id);
		int best = 0;
		for (int c : total) best = Math.max(best, c);
		Store.Task live = store.task(j.task.id);
		if (live != null) store.setBestand(live, best);
	}

	/** Bauplan der Aufgabe, oder null (wird dann im Hintergrund geladen). */
	private static int[] plan(Store store, Store.Task t) {
		int[] p = plans.get(t.id);
		if (p != null) return p;
		long now = System.currentTimeMillis();
		if (now < planWait.getOrDefault(t.id, 0L)) return null;
		planWait.put(t.id, now + 60000); // höchstens einmal pro Minute versuchen
		store.loadPlan(t.id, s -> {
			if (s == null) return;
			try {
				plans.put(t.id, PlanMath.decode(s));
				planWait.remove(t.id);
			} catch (NumberFormatException e) {
				BautruppClient.LOG.warn("Bauplan von {} unlesbar", t.id);
			}
		});
		return null;
	}

	private static Job planJob(Store.Task t, Item item, int[] idx) {
		Store.Plan pl = t.plan;
		if (pl.b <= 0 || pl.l <= 0 || idx.length == 0) return null;
		int[] rots = pl.rot >= 0 ? new int[]{pl.rot & 3} : new int[]{0, 1, 2, 3};
		Job j = new Job();
		j.rots = rots.length;
		Map<Long, List<Integer>> byChunk = new LinkedHashMap<>();
		for (int r = 0; r < rots.length; r++)
			for (int i : idx) {
				int[] w = PlanMath.toWorld(i, pl.b, pl.l, rots[r]);
				int x = pl.x + w[0], y = pl.y + w[1], z = pl.z + w[2];
				List<Integer> l = byChunk.computeIfAbsent(ChunkPos.pack(x >> 4, z >> 4), k -> new ArrayList<>());
				l.add(r); l.add(x); l.add(y); l.add(z);
			}
		byChunk.forEach((k, l) -> {
			j.chunks.add(k);
			j.planPositions.put(k, l.stream().mapToInt(Integer::intValue).toArray());
		});
		return j;
	}

	private static Job areaJob(Minecraft mc, Store.Task t) {
		Job j = new Job();
		j.minY = mc.level.getMinY();
		j.maxY = mc.level.getMaxY();
		if (t.bereich != null && t.bereich.valid()) {
			j.minX = Math.min(t.bereich.x1, t.bereich.x2); j.maxX = Math.max(t.bereich.x1, t.bereich.x2);
			j.minZ = Math.min(t.bereich.z1, t.bereich.z2); j.maxZ = Math.max(t.bereich.z1, t.bereich.z2);
			if (t.bereich.y1 != null && t.bereich.y2 != null) {
				j.minY = Math.max(j.minY, Math.min(t.bereich.y1, t.bereich.y2));
				j.maxY = Math.min(j.maxY, Math.max(t.bereich.y1, t.bereich.y2));
			}
		} else {
			j.circle = true;
			j.cx = t.x; j.cz = t.z;
			j.r2 = (long) t.radius * t.radius;
			j.minX = t.x - t.radius; j.maxX = t.x + t.radius;
			j.minZ = t.z - t.radius; j.maxZ = t.z + t.radius;
		}
		if (j.minY > j.maxY) return null;
		for (int cx = j.minX >> 4; cx <= j.maxX >> 4; cx++)
			for (int cz = j.minZ >> 4; cz <= j.maxZ >> 4; cz++) j.chunks.add(ChunkPos.pack(cx, cz));
		return j;
	}

	/** Nächstes Ziel mit Bestand-Zählung, von dessen Baustelle hier mindestens ein Chunk geladen ist. */
	private static Job nextJob(Minecraft mc, Store store) {
		String dim = BautruppClient.currentDim();
		long now = System.currentTimeMillis();
		for (Store.Task t : store.tasks) {
			if (!t.hasGoal() || !t.bestandMode() || "fertig".equals(t.status) || store.locked(t)) continue;
			if (t.dim != null && !t.dim.isEmpty() && !t.dim.equals(dim)) continue;
			if (now - lastScan.getOrDefault(t.id, 0L) < RESCAN_MS) continue;
			Identifier id = Identifier.tryParse(t.item);
			if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
			Item item = BuiltInRegistries.ITEM.getValue(id);
			if (item == Items.AIR) continue;
			Job j;
			String shape;
			if (t.plan != null) {
				int[] idx = plan(store, t);
				if (idx == null) continue;
				j = planJob(t, item, idx);
				shape = "plan " + t.plan.x + " " + t.plan.y + " " + t.plan.z + " " + t.plan.rot + " " + idx.length;
			} else {
				j = areaJob(mc, t);
				shape = t.bereich != null && t.bereich.valid() ? t.bereich.text() : "r " + t.radius + " " + t.x + " " + t.z;
			}
			if (j == null) continue;
			boolean any = false;
			for (long key : j.chunks) if (mc.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null) { any = true; break; }
			if (!any) continue; // ganz weit weg: nichts zu tun
			j.task = t;
			j.item = item;
			j.memoryKey = t.id + "|" + t.item + "|" + shape;
			return j;
		}
		return null;
	}

	/** Kurz für die Anzeige: zählt dieses Ziel gerade, und wenn nicht, warum? */
	public static String why(Store store, Store.Task t) {
		if (!t.hasGoal()) return "";
		if ("fertig".equals(t.status)) return "fertig";
		if (store.locked(t)) return "zählt noch nicht: gesperrt, " + store.lockText(t);
		String me = Store.myName(), verb = "abbauen".equals(t.goalKind()) ? "abbaust" : "setzt";
		if (t.bestandMode()) {
			if (t.dim != null && !t.dim.isEmpty() && !t.dim.equals(BautruppClient.currentDim()))
				return "zählt, wenn jemand mit der Mod in der Dimension " + Store.dimName(t.dim) + " hingeht";
			if (t.plan != null && !plans.containsKey(t.id)) return "Bauplan wird geladen ...";
			Long last = lastScan.get(t.id);
			long ago = last == null ? -1 : (System.currentTimeMillis() - last) / 1000;
			if (incomplete.contains(t.id)) return "Teile der Baustelle noch nie geladen: einmal ganz ablaufen";
			if (ago < 0 || ago > 60) return "zählt, wenn jemand mit der Mod in der Nähe ist";
			return "zählt " + (t.plan != null ? "nach Bauplan" : "was im Bereich steht") + ", zuletzt vor " + ago + " s";
		}
		if (!t.has(me)) {
			String storage = t.countsStorage() ? " (Lager zählt trotzdem)" : "";
			return t.people().isEmpty() ? "zählt nicht: niemand eingetragen, übernimm die Aufgabe" + storage
					: "zählt bei " + t.peopleText() + ", nicht bei dir" + storage;
		}
		if (t.builds() && !t.collects()) return t.hasArea() ? "zählt, was du im Bereich " + verb : "zählt, was du " + verb + " (ab dem Eintragen)";
		if (t.isCombo()) return "zählt dein Inventar und was du " + (t.hasArea() ? "im Bereich " : "") + "setzt";
		return "zählt dein Inventar" + (t.countsStorage() ? " und das Lager" : "");
	}

	/** Wie viele Items in diesem Blockzustand stecken. */
	static int weight(BlockState s) {
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return 0;
		if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return 0;
		if (s.hasProperty(BlockStateProperties.SLAB_TYPE) && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
		if (s.hasProperty(BlockStateProperties.CANDLES)) return s.getValue(BlockStateProperties.CANDLES);
		if (s.hasProperty(BlockStateProperties.PICKLES)) return s.getValue(BlockStateProperties.PICKLES);
		if (s.hasProperty(BlockStateProperties.EGGS)) return s.getValue(BlockStateProperties.EGGS);
		if (s.hasProperty(BlockStateProperties.LAYERS)) return s.getValue(BlockStateProperties.LAYERS);
		return 1;
	}

}
