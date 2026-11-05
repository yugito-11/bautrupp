package de.bautrupp;

import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Zählt Abbau- und Platzier-Aufgaben mit Bereich selbst mit: nur Blöcke im Umkreis
 * um die Baustelle (gleiche Dimension) zählen. Die Zahlen werden gesammelt und von
 * {@link GoalTracker} in die Datenbank geschrieben.
 */
public class AreaCounter {
	/** Wie lange nach dem Rechtsklick geprüft wird, ob der Block wirklich steht (der Server kann ablehnen). */
	private static final int CONFIRM_AFTER_TICKS = 6;

	private record PendingPlace(BlockPos pos, Block block, String itemId, int tick) {}

	private static final List<PendingPlace> pendingPlaces = new ArrayList<>();
	/** Gezählte, aber noch nicht gespeicherte Blöcke je Aufgabe. */
	static final Map<String, Integer> pending = new HashMap<>();
	private static int tickCounter = 0;

	public static void register() {
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			String id = BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString();
			count("abbauen", id, pos);
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || player != Minecraft.getInstance().player) return InteractionResult.PASS;
			ItemStack stack = player.getItemInHand(hand);
			if (stack.getItem() instanceof BlockItem bi && hasActive("platzieren")) {
				BlockState clicked = level.getBlockState(hit.getBlockPos());
				BlockPos target = clicked.canBeReplaced() ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
				pendingPlaces.add(new PendingPlace(target, bi.getBlock(), GoalTracker.idOf(stack), tickCounter));
			}
			return InteractionResult.PASS;
		});
	}

	public static void tick(Minecraft mc) {
		tickCounter++;
		if (pendingPlaces.isEmpty()) return;
		Iterator<PendingPlace> it = pendingPlaces.iterator();
		while (it.hasNext()) {
			PendingPlace p = it.next();
			if (tickCounter - p.tick < CONFIRM_AFTER_TICKS) continue;
			it.remove();
			if (mc.level != null && mc.level.getBlockState(p.pos).getBlock() == p.block) count("platzieren", p.itemId, p.pos);
		}
	}

	private static boolean hasActive(String art) {
		String me = Store.myName();
		for (Store.Task t : BautruppClient.store.tasks)
			if (isActive(t, art, me)) return true;
		return false;
	}

	private static boolean isActive(Store.Task t, String art, String me) {
		boolean kind = "platzieren".equals(art) ? t.places() : art.equals(t.goalKind());
		return t.hasGoal() && t.hasArea() && kind && !"fertig".equals(t.status) && t.has(me) && !t.bestandMode() && !BautruppClient.store.locked(t);
	}

	private static void count(String art, String itemId, BlockPos pos) {
		String me = Store.myName();
		String dim = BautruppClient.currentDim();
		for (Store.Task t : BautruppClient.store.tasks) {
			if (!isActive(t, art, me) || !itemId.equals(t.item)) continue;
			if (!t.inArea(pos, dim)) continue;
			pending.merge(t.id, 1, Integer::sum);
		}
	}
}
