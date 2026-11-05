package de.bautrupp;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class BautruppClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("bautrupp");
	public static Store store;
	private static KeyMapping openKey;
	private static boolean openNextTick = false;
	private static boolean openHudEditor = false;

	@Override
	public void onInitializeClient() {
		store = new Store(Config.load());

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bautrupp", "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.bautrupp.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick()) openNextTick = true;
			if (openHudEditor && mc.gui.screen() == null) {
				openHudEditor = false;
				mc.gui.setScreen(new HudEditScreen(null));
			}
			if (openNextTick && mc.gui.screen() == null) {
				openNextTick = false;
				mc.gui.setScreen(new BautruppScreen(store));
			}
			store.tick(mc.gui.screen() instanceof BautruppScreen);
			AreaCounter.tick(mc);
			StorageWatcher.tick();
			GoalTracker.tick(mc, store);
			BestandScanner.tick(mc, store);
		});

		AreaCounter.register();
		StorageWatcher.register();
		HudElementRegistry.attachElementAfter(VanillaHudElements.MOB_EFFECTS, Identifier.fromNamespaceAndPath("bautrupp", "aufgaben"), TaskHud::extract);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
			register(dispatcher, ctx, "bautrupp");
			register(dispatcher, ctx, "bt");
		});
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> d, CommandBuildContext ctx, String name) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = literal(name)
				.executes(c -> { openNextTick = true; return 1; })
				.then(literal("aufgabe").then(argument("text", StringArgumentType.greedyString()).executes(c -> {
					store.addTask(StringArgumentType.getString(c, "text"), "", null, null, null, "");
					c.getSource().sendFeedback(prefix().append("Aufgabe angelegt."));
					return 1;
				})))
				.then(goalCommand("sammeln", ctx))
				.then(goalCommand("abbauen", ctx))
				.then(goalCommand("platzieren", ctx))
				.then(goalCommand("kombi", ctx))
				.then(literal("ort").then(argument("name", StringArgumentType.greedyString()).executes(c -> {
					BlockPos p = c.getSource().getPlayer().blockPosition();
					String dim = currentDim();
					store.addPlace(StringArgumentType.getString(c, "name"), dim, p.getX(), p.getY(), p.getZ());
					c.getSource().sendFeedback(prefix().append("Ort gespeichert: " + p.getX() + " " + p.getY() + " " + p.getZ()));
					return 1;
				})))
				.then(literal("pruefen").executes(c -> {
					// Für jedes offene Ziel im gewählten Projekt: Stand und ob/warum es gerade zählt
					int n = 0, hidden = 0;
					for (Store.Task t : store.tasks) {
						if (!t.hasGoal() || "fertig".equals(t.status) || !Store.inProject(t.projekt, store.config.projekt)) continue;
						if (n >= 12) { hidden++; continue; }
						c.getSource().sendFeedback(Component.literal(" • " + t.titel + ": " + store.goalTotal(t) + "/" + t.ziel + " – " + BestandScanner.why(store, t)));
						n++;
					}
					if (n == 0) c.getSource().sendFeedback(prefix().append("Keine offenen Ziele im gewählten Projekt."));
					if (hidden > 0) c.getSource().sendFeedback(Component.literal(" ... und " + hidden + " weitere (Projekt wählen: /bt projekt <Name>)"));
					return 1;
				}))
				.then(literal("meine").executes(c -> {
					String me = Store.myName();
					int n = 0;
					for (Store.Task t : store.tasks) {
						if (t.has(me) && !"fertig".equals(t.status)) {
							c.getSource().sendFeedback(Component.literal(" • " + t.titel + ("arbeit".equals(t.status) ? " (in Arbeit)" : "")));
							n++;
						}
					}
					if (n == 0) c.getSource().sendFeedback(prefix().append("Du hast gerade keine offenen Aufgaben."));
					return 1;
				}))
				.then(literal("projekt")
						.then(literal("alle").executes(c -> {
							store.config.projekt = "";
							store.config.save();
							c.getSource().sendFeedback(prefix().append("Du siehst jetzt alle Projekte."));
							return 1;
						}))
						.then(literal("ecke1").executes(c -> projectCorner(c.getSource(), 1)))
						.then(literal("ecke2").executes(c -> projectCorner(c.getSource(), 2)))
						.then(literal("bereich").then(literal("aus").executes(c -> {
							Store.Project p = store.project(store.config.projekt);
							if (p == null) { c.getSource().sendError(Component.literal("Erst ein Projekt wählen: /bt projekt <Name>")); return 0; }
							store.setProjectArea(p, null, "");
							c.getSource().sendFeedback(prefix().append("Bereich vom Projekt \"" + p.name + "\" entfernt."));
							return 1;
						})))
						.then(literal("neu").then(argument("name", StringArgumentType.greedyString()).executes(c -> {
							Store.Project p = store.addProject(StringArgumentType.getString(c, "name"));
							store.config.projekt = p.id;
							store.config.save();
							c.getSource().sendFeedback(prefix().append("Projekt \"" + p.name + "\" angelegt und ausgewählt. Neue Aufgaben landen jetzt dort."));
							return 1;
						})))
						.then(argument("name", StringArgumentType.greedyString())
								.suggests((c, b) -> { for (Store.Project p : store.projects) b.suggest(p.name); return b.buildFuture(); })
								.executes(c -> {
									String wanted = StringArgumentType.getString(c, "name");
									for (Store.Project p : store.projects) {
										if (p.name.equalsIgnoreCase(wanted)) {
											store.config.projekt = p.id;
											store.config.save();
											c.getSource().sendFeedback(prefix().append("Projekt \"" + p.name + "\" ausgewählt."));
											return 1;
										}
									}
									c.getSource().sendError(Component.literal("Kein Projekt mit diesem Namen. Neu anlegen: /bt projekt neu " + wanted));
									return 0;
								})))
				.then(literal("suche").then(argument("item", StringArgumentType.greedyString()).executes(c -> search(c.getSource(), StringArgumentType.getString(c, "item")))))
				.then(literal("meldungen").then(argument("art", StringArgumentType.word())
						.suggests((c, b) -> { b.suggest("alle"); b.suggest("meine"); b.suggest("aus"); return b.buildFuture(); })
						.executes(c -> {
							String m = StringArgumentType.getString(c, "art").toLowerCase();
							if (!m.equals("alle") && !m.equals("meine") && !m.equals("aus")) {
								c.getSource().sendError(Component.literal("Bitte alle, meine oder aus angeben."));
								return 0;
							}
							store.config.meldungen = m;
							store.config.save();
							c.getSource().sendFeedback(prefix().append("Meldungen: " + m));
							return 1;
						})))
				.then(literal("material").then(argument("anzahl", IntegerArgumentType.integer(1, 10000000))
						.executes(c -> {
							ItemStack hand = c.getSource().getPlayer().getMainHandItem();
							if (hand.isEmpty()) {
								c.getSource().sendError(Component.literal("Nimm das Item in die Hand oder schreib es dahinter, z. B. /bt material 1728 stone_bricks"));
								return 0;
							}
							return addMaterial(c.getSource(), IntegerArgumentType.getInteger(c, "anzahl"), new ItemStack(hand.getItem()));
						})
						.then(argument("item", ItemArgument.item(ctx)).executes(c ->
								addMaterial(c.getSource(), IntegerArgumentType.getInteger(c, "anzahl"), new ItemStack(ItemArgument.getItem(c, "item").item()))))))
				.then(literal("anzeige").then(literal("bearbeiten").executes(c -> { openHudEditor = true; return 1; })).then(argument("seite", StringArgumentType.word())
						.suggests((c, b) -> { b.suggest("links"); b.suggest("rechts"); b.suggest("aus"); return b.buildFuture(); })
						.executes(c -> {
							String s = StringArgumentType.getString(c, "seite").toLowerCase();
							if (!s.equals("links") && !s.equals("rechts") && !s.equals("aus")) {
								c.getSource().sendError(Component.literal("Bitte links, rechts oder aus angeben."));
								return 0;
							}
							store.config.anzeige = s;
							if (!s.equals("aus")) { store.config.hudX = -1; store.config.hudY = -1; }
							store.config.save();
							c.getSource().sendFeedback(prefix().append("Aufgaben-Anzeige: " + s));
							return 1;
						})))
				.then(literal("admin")
						.then(literal("abmelden").executes(c -> {
							store.logoutAdmin();
							c.getSource().sendFeedback(prefix().append("Du bist nicht mehr als Admin angemeldet."));
							return 1;
						}))
						.then(literal("neu").then(argument("passwort", StringArgumentType.greedyString()).executes(c -> {
							if (store.setAdminPassword(StringArgumentType.getString(c, "passwort")))
								c.getSource().sendFeedback(prefix().append("Admin-Passwort festgelegt. Du bist Admin."));
							else c.getSource().sendError(Component.literal(store.error));
							return 1;
						})))
						.then(argument("passwort", StringArgumentType.greedyString()).executes(c -> {
							String pw = StringArgumentType.getString(c, "passwort");
							boolean ok = store.hasAdmin() ? store.loginAdmin(pw) : store.setAdminPassword(pw);
							if (ok) c.getSource().sendFeedback(prefix().append("Du bist jetzt Admin."));
							else c.getSource().sendError(Component.literal(store.error));
							return 1;
						})))
				.then(literal("datenbank").then(argument("url", StringArgumentType.greedyString()).executes(c -> {
					store.config.datenbank = Config.normalizeUrl(StringArgumentType.getString(c, "url"));
					store.config.save();
					store.refresh();
					c.getSource().sendFeedback(prefix().append("Datenbank gespeichert."));
					return 1;
				})))
				.then(literal("gruppe").then(argument("code", StringArgumentType.word()).executes(c -> {
					store.config.gruppe = Config.normalizeGroup(StringArgumentType.getString(c, "code"));
					store.config.save();
					store.loadedOnce = false;
					store.refresh();
					c.getSource().sendFeedback(prefix().append("Gruppe: " + store.config.gruppe));
					return 1;
				})));
		d.register(root);
	}

	/**
	 * /bt sammeln|abbauen|platzieren <anzahl> [item] [hier [radius]];
	 * ohne Item zählt das Item in der Hand, mit "hier" nur Blöcke im Umkreis deiner Position.
	 */
	private static LiteralArgumentBuilder<FabricClientCommandSource> goalCommand(String art, CommandBuildContext ctx) {
		return literal(art).then(argument("anzahl", IntegerArgumentType.integer(1, 1000000))
				.executes(c -> goalFromHand(c, art, 0))
				.then(hier(c -> goalFromHand(c, art, 32), c -> goalFromHand(c, art, IntegerArgumentType.getInteger(c, "radius"))))
				.then(argument("item", ItemArgument.item(ctx))
						.executes(c -> goalFromArg(c, art, 0))
						.then(hier(c -> goalFromArg(c, art, 32), c -> goalFromArg(c, art, IntegerArgumentType.getInteger(c, "radius"))))));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> hier(
			com.mojang.brigadier.Command<FabricClientCommandSource> plain, com.mojang.brigadier.Command<FabricClientCommandSource> withRadius) {
		return literal("hier").executes(plain).then(argument("radius", IntegerArgumentType.integer(1, 512)).executes(withRadius));
	}

	private static int goalFromHand(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> c, String art, int radius) {
		ItemStack hand = c.getSource().getPlayer().getMainHandItem();
		if (hand.isEmpty()) {
			c.getSource().sendError(Component.literal("Nimm den Block oder das Item in die Hand, oder schreib es dahinter, z. B. /bt " + art + " 640 dirt"));
			return 0;
		}
		return addGoal(c.getSource(), art, IntegerArgumentType.getInteger(c, "anzahl"), new ItemStack(hand.getItem()), radius);
	}

	private static int goalFromArg(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> c, String art, int radius) {
		return addGoal(c.getSource(), art, IntegerArgumentType.getInteger(c, "anzahl"), new ItemStack(ItemArgument.getItem(c, "item").item()), radius);
	}

	private static BlockPos firstCorner;

	/** /bt projekt ecke1 / ecke2: Bereich des gewählten Projekts mit zwei Ecken festlegen (alle Höhen). */
	private static int projectCorner(FabricClientCommandSource src, int which) {
		Store.Project p = store.project(store.config.projekt);
		if (p == null) {
			src.sendError(Component.literal("Erst ein Projekt wählen: /bt projekt <Name>"));
			return 0;
		}
		BlockPos here = src.getPlayer().blockPosition();
		if (which == 1) {
			firstCorner = here;
			src.sendFeedback(prefix().append("Ecke 1 gemerkt (" + here.getX() + " " + here.getZ() + "). Jetzt zur gegenüberliegenden Ecke gehen und /bt projekt ecke2 eingeben."));
			return 1;
		}
		if (firstCorner == null) {
			src.sendError(Component.literal("Zuerst /bt projekt ecke1 an der ersten Ecke eingeben."));
			return 0;
		}
		Store.Area a = new Store.Area();
		a.x1 = Math.min(firstCorner.getX(), here.getX()); a.x2 = Math.max(firstCorner.getX(), here.getX());
		a.z1 = Math.min(firstCorner.getZ(), here.getZ()); a.z2 = Math.max(firstCorner.getZ(), here.getZ());
		store.setProjectArea(p, a, currentDim());
		firstCorner = null;
		src.sendFeedback(prefix().append("Bereich vom Projekt \"" + p.name + "\": " + a.text() + ". Bauen/Abbauen im Projekt zählt jetzt dort."));
		return 1;
	}

	public static String goalProblem(String art, ItemStack item) {
		if ("abbauen".equals(art) && Block.byItem(item.getItem()) == Blocks.AIR) return "Das ist kein Block, den man abbauen kann.";
		if (("platzieren".equals(art) || "kombi".equals(art)) && !(item.getItem() instanceof BlockItem)) return "Das ist kein Block, den man platzieren kann.";
		return null;
	}

	private static int addGoal(FabricClientCommandSource src, String art, int amount, ItemStack item, int radius) {
		String problem = goalProblem(art, item);
		if (problem != null) {
			src.sendError(Component.literal(problem));
			return 0;
		}
		String name = ItemNames.label(item);
		boolean area = radius > 0 && !"sammeln".equals(art);
		BlockPos center = radius > 0 ? src.getPlayer().blockPosition() : null;
		store.addGoalTask(amount + " " + name + " " + Store.goalVerb(art), Store.myName(), art, GoalTracker.idOf(item), ItemNames.english(item), amount,
				center, currentDim(), radius);
		String where = area ? " im Umkreis von " + radius + " Blöcken um deine Position" : "";
		String how = switch (art) {
			case "abbauen" -> "Gezählt wird ab jetzt jeder abgebaute Block" + where + ".";
			case "platzieren" -> "Gezählt wird ab jetzt jeder platzierte Block" + where + ".";
			case "kombi" -> "Gezählt wird, was ihr sammelt und was verbaut ist" + where + ". Fertig, sobald alles verbaut ist.";
			default -> "Sie wird fertig, sobald du so viele dabei hast.";
		};
		src.sendFeedback(prefix().append("Aufgabe angelegt: " + amount + " " + name + " " + Store.goalVerb(art) + ". " + how));
		return 1;
	}

	private static int addMaterial(FabricClientCommandSource src, int amount, ItemStack item) {
		String name = ItemNames.label(item);
		store.addMaterial(ItemNames.english(item), GoalTracker.idOf(item), amount);
		src.sendFeedback(prefix().append("Material eingetragen: " + amount + " " + name
				+ (store.hasStorage(store.config.projekt) ? ". Das Lager zählt automatisch mit." : ".")));
		return 1;
	}

	/** /bt suche <Name>: in welchen Lager-Kisten liegt das Item, wie viel, wie weit weg. */
	private static int search(FabricClientCommandSource src, String name) {
		ItemStack item = ItemNames.find(name);
		if (item == null) {
			List<String> s = ItemNames.suggest(name, 3);
			src.sendError(Component.literal("Kein Item \"" + name + "\" gefunden." + (s.isEmpty() ? "" : " Meintest du: " + String.join(", ", s) + "?")));
			return 0;
		}
		String id = Store.key(GoalTracker.idOf(item)), label = ItemNames.label(item);
		BlockPos here = src.getPlayer().blockPosition();
		List<Store.Storage> found = new ArrayList<>();
		for (Store.Storage s : store.storages) if (s.inhalt != null && s.inhalt.getOrDefault(id, 0) > 0) found.add(s);
		if (found.isEmpty()) {
			src.sendFeedback(prefix().append("Kein " + label + " in den Lager-Kisten. Gezählt wird, wenn jemand mit der Mod eine Kiste öffnet."));
			return 1;
		}
		found.sort((a, b) -> b.inhalt.get(id) - a.inhalt.get(id));
		int total = found.stream().mapToInt(s -> s.inhalt.get(id)).sum();
		src.sendFeedback(prefix().append(total + " " + label + " in " + found.size() + (found.size() == 1 ? " Kiste:" : " Kisten:")));
		for (Store.Storage s : found) {
			String where = s.x + " " + s.y + " " + s.z + (s.dim.equals(currentDim()) ? ", " + Math.round(Math.sqrt(here.distSqr(new BlockPos(s.x, s.y, s.z)))) + "m entfernt" : ", " + Store.dimName(s.dim));
			String proj = store.projectName(s.projekt);
			src.sendFeedback(Component.literal(" • " + s.inhalt.get(id) + " bei " + where + (proj.isEmpty() ? "" : " (Projekt " + proj + ")")
					+ (s.aktualisiert > 0 ? ", gezählt " + Store.ago(s.aktualisiert) : "")));
		}
		return 1;
	}

		public static String currentDim() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return "";
		if (mc.level.dimension() == Level.NETHER) return "nether";
		if (mc.level.dimension() == Level.END) return "end";
		return "overworld";
	}

	private static net.minecraft.network.chat.MutableComponent prefix() {
		return Component.literal("[Bautrupp] ").withStyle(ChatFormatting.GREEN);
	}

	public static void chat(String msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.sendSystemMessage(prefix().append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
	}
}
