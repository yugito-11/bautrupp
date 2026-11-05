package de.bautrupp;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.HashMap;
import java.util.Map;

/**
 * Lager-Kisten: Öffnet jemand mit der Mod eine Kiste, Fass oder Shulker-Kiste, kann er
 * sie oben links als Lager festlegen. Bei jedem Öffnen eines Lagers zählt die Mod den
 * Inhalt (auch Shulker-Kisten darin) und speichert ihn, damit die Materialliste
 * automatisch weiß, was schon da ist.
 */
public class StorageWatcher {
	/** So lange nach dem Rechtsklick gilt das geöffnete Menü als zu diesem Block gehörig. */
	private static final int CLICK_WINDOW_TICKS = 20;
	private static final int FIRST_COUNT_TICKS = 5, COUNT_EVERY_TICKS = 40;

	private static BlockPos lastClick;
	private static String lastClickDim = "";
	private static int lastClickTick = -1000;
	private static int clientTicks = 0;

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && player == Minecraft.getInstance().player) {
				lastClick = hit.getBlockPos();
				lastClickDim = BautruppClient.currentDim();
				lastClickTick = clientTicks;
			}
			return InteractionResult.PASS;
		});

		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
			AbstractContainerMenu menu = acs.getMenu();
			if (!(menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu)) return;
			Store store = BautruppClient.store;
			if (!store.config.isComplete() || !store.loadedOnce || lastClick == null) return;
			if (clientTicks - lastClickTick > CLICK_WINDOW_TICKS) return;
			attach(mc, acs, menu, store, lastClick, lastClickDim);
		});
	}

	public static void tick() {
		clientTicks++;
	}

	private static void attach(Minecraft mc, AbstractContainerScreen<?> screen, AbstractContainerMenu menu, Store store, BlockPos pos, String dim) {
		Store.Storage[] current = {store.storageAt(pos, dim)};
		Map<String, Integer>[] lastSent = new Map[]{null};
		int[] ticks = {0};

		Button button = Button.builder(Component.literal(label(current[0])), b -> {
			if (current[0] == null) {
				current[0] = store.addStorage(pos, partnerOf(mc, pos), dim, "");
				Map<String, Integer> c = count(menu);
				store.setStorageContent(current[0], c);
				lastSent[0] = c;
				BautruppClient.chat("Diese Kiste ist jetzt ein Lager. Jedes Mal, wenn jemand mit der Mod sie öffnet, wird der Inhalt gezählt.");
			} else if (store.canDelete(current[0].ersteller)) {
				store.deleteStorage(current[0]);
				current[0] = null;
			}
			b.setMessage(Component.literal(label(current[0])));
			b.setTooltip(tooltip(store, current[0]));
		}).bounds(4, 4, 190, 20).build();
		button.setTooltip(tooltip(store, current[0]));
		Screens.getWidgets(screen).add(button);

		// Inhalt kommt erst ein paar Ticks nach dem Öffnen vom Server; danach regelmäßig nachzählen.
		ScreenEvents.afterTick(screen).register(s -> {
			ticks[0]++;
			if (current[0] == null) return;
			if (ticks[0] == FIRST_COUNT_TICKS || (ticks[0] > FIRST_COUNT_TICKS && ticks[0] % COUNT_EVERY_TICKS == 0)) {
				Map<String, Integer> c = count(menu);
				if (!c.equals(lastSent[0])) {
					store.setStorageContent(current[0], c);
					lastSent[0] = c;
				}
			}
		});
		ScreenEvents.remove(screen).register(s -> {
			if (current[0] == null || ticks[0] < FIRST_COUNT_TICKS) return;
			Map<String, Integer> c = count(menu);
			if (!c.equals(lastSent[0])) store.setStorageContent(current[0], c);
		});
	}

	private static String label(Store.Storage s) {
		return s == null ? "Bautrupp: Als Lager festlegen" : "Bautrupp-Lager ✔ (entfernen)";
	}

	private static Tooltip tooltip(Store store, Store.Storage s) {
		if (s == null) return Tooltip.create(Component.literal("Die Mod zählt dann bei jedem Öffnen, was in dieser Kiste liegt, und trägt es in die Materialliste ein."));
		String info = s.aktualisiert > 0 ? "Zuletzt gezählt " + Store.ago(s.aktualisiert) + " von " + s.von + "." : "";
		if (!store.canDelete(s.ersteller)) info += " Entfernen kann nur " + s.ersteller + " oder der Admin.";
		return Tooltip.create(Component.literal("Diese Kiste ist ein Lager. " + info));
	}

	/** Bei Doppelkisten die zweite Hälfte, sonst null. */
	private static BlockPos partnerOf(Minecraft mc, BlockPos pos) {
		if (mc.level == null) return null;
		BlockState state = mc.level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)) return null;
		if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
		return pos.relative(ChestBlock.getConnectedDirection(state));
	}

	/** Zählt alle Items in der Kiste (nicht im eigenen Inventar), auch in Shulker-Kisten. */
	static Map<String, Integer> count(AbstractContainerMenu menu) {
		Map<String, Integer> out = new HashMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) continue;
			add(out, slot.getItem());
		}
		return out;
	}

	private static void add(Map<String, Integer> out, ItemStack s) {
		if (s.isEmpty()) return;
		out.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
		ItemContainerContents box = s.get(DataComponents.CONTAINER);
		if (box != null) box.nonEmptyItemCopyStream().forEach(x -> add(out, x));
	}
}
