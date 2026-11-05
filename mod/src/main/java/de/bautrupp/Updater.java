package de.bautrupp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Selbst-Update über GitHub-Releases: Beim Start wird nachgesehen, ob es eine neuere Version gibt.
 * Die neue Datei wird als "bautrupp-x.y.z.jar.neu" in den mods-Ordner geladen (Fabric ignoriert sie so),
 * und erst nach dem Beenden von Minecraft tauscht {@link SwapHelper} sie gegen die alte aus.
 */
public class Updater {
	private static final String LATEST = "https://api.github.com/repos/yugito-11/bautrupp/releases/latest";
	private static final String SUFFIX = ".neu";
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** Fertig geladene neue Version, die beim Beenden eingesetzt wird. */
	private static Path ready;
	private static String readyVersion;
	private static boolean hookAdded;
	/** Meldung für den Chat, sobald man im Spiel ist. */
	public static volatile String pendingMessage;

	public static String currentVersion() {
		return FabricLoader.getInstance().getModContainer("bautrupp")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
	}

	/** Die eigene .jar im mods-Ordner, oder leer (z. B. in der Entwicklungsumgebung). */
	private static Optional<Path> ownJar() {
		Optional<ModContainer> c = FabricLoader.getInstance().getModContainer("bautrupp");
		if (c.isEmpty()) return Optional.empty();
		List<Path> paths = c.get().getOrigin().getPaths();
		if (paths.size() != 1) return Optional.empty();
		Path p = paths.get(0);
		return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") ? Optional.of(p) : Optional.empty();
	}

	/** Beim Spielstart: Reste aufräumen und (wenn eingeschaltet) nach Updates sehen. */
	public static void onStart(Config config) {
		Optional<Path> jar = ownJar();
		if (jar.isEmpty()) return;
		// Liegt schon eine neuere fertige Datei da (Tausch beim letzten Mal nicht geklappt)? Dann beim Beenden einsetzen.
		try (var files = Files.list(jar.get().getParent())) {
			for (Path f : files.toList()) {
				String n = f.getFileName().toString();
				if (!n.startsWith("bautrupp-") || !n.endsWith(".jar" + SUFFIX)) continue;
				String v = n.substring("bautrupp-".length(), n.length() - (".jar" + SUFFIX).length());
				if (newer(v, currentVersion()) && isBautrupp(f)) schedule(f, v);
				else Files.deleteIfExists(f);
			}
		} catch (Exception e) {
			BautruppClient.LOG.warn("Bautrupp-Update: Aufräumen fehlgeschlagen", e);
		}
		if (config.autoUpdate && ready == null) check(msg -> {});
	}

	/** Nach einer neueren Version sehen und sie herunterladen; feedback bekommt eine kurze Meldung. */
	public static void check(Consumer<String> feedback) {
		Optional<Path> jar = ownJar();
		if (jar.isEmpty()) {
			feedback.accept("Updates gehen nur mit der heruntergeladenen Mod-Datei.");
			return;
		}
		if (ready != null) {
			feedback.accept("Bautrupp " + readyVersion + " ist schon heruntergeladen und wird beim nächsten Neustart benutzt.");
			return;
		}
		HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST)).timeout(Duration.ofSeconds(15))
				.header("Accept", "application/vnd.github+json").header("User-Agent", "Bautrupp-Mod").GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenCompose(res -> {
			if (res.statusCode() != 200) throw new IllegalStateException("GitHub antwortet mit " + res.statusCode());
			JsonObject rel = JsonParser.parseString(res.body()).getAsJsonObject();
			String version = rel.get("tag_name").getAsString().replaceFirst("^v", "");
			if (!newer(version, currentVersion())) {
				feedback.accept("Du hast die neueste Version (" + currentVersion() + ").");
				return CompletableFuture.completedFuture(null);
			}
			String url = null;
			for (JsonElement a : rel.getAsJsonArray("assets")) {
				String name = a.getAsJsonObject().get("name").getAsString();
				if (name.startsWith("bautrupp-") && name.endsWith(".jar")) url = a.getAsJsonObject().get("browser_download_url").getAsString();
			}
			if (url == null) throw new IllegalStateException("Im Release " + version + " ist keine Mod-Datei");
			Path dir = jar.get().getParent(), part = dir.resolve("bautrupp-" + version + ".jar.laden"), done = dir.resolve("bautrupp-" + version + ".jar" + SUFFIX);
			HttpRequest dl = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).header("User-Agent", "Bautrupp-Mod").GET().build();
			return HTTP.sendAsync(dl, HttpResponse.BodyHandlers.ofFile(part)).thenAccept(r -> {
				try {
					if (r.statusCode() != 200 || !isBautrupp(part)) {
						Files.deleteIfExists(part);
						throw new IllegalStateException("Download unvollständig");
					}
					Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
					schedule(done, version);
					String msg = "Bautrupp " + version + " wurde heruntergeladen. Nach dem nächsten Neustart von Minecraft ist sie aktiv.";
					pendingMessage = msg;
					feedback.accept(msg);
				} catch (RuntimeException e) {
					throw e;
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
		}).exceptionally(e -> {
			BautruppClient.LOG.warn("Bautrupp-Update fehlgeschlagen", e);
			feedback.accept("Update-Prüfung fehlgeschlagen: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
			return null;
		});
	}

	/** Ist die Datei eine Bautrupp-Mod (vollständiges Zip mit fabric.mod.json und unserer ID)? */
	private static boolean isBautrupp(Path f) {
		try (ZipFile zip = new ZipFile(f.toFile())) {
			ZipEntry e = zip.getEntry("fabric.mod.json");
			if (e == null) return false;
			try (InputStream in = zip.getInputStream(e)) {
				return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString().equals("bautrupp");
			}
		} catch (Exception e) {
			return false;
		}
	}

	/** Beim Beenden von Minecraft den Tausch starten (eigener Prozess, weil die alte Datei bis zum Ende gesperrt ist). */
	private static synchronized void schedule(Path newFile, String version) {
		ready = newFile;
		readyVersion = version;
		if (hookAdded) return;
		hookAdded = true;
		Runtime.getRuntime().addShutdownHook(new Thread(Updater::startSwap, "bautrupp-update"));
	}

	private static void startSwap() {
		Optional<Path> jar = ownJar();
		if (jar.isEmpty() || ready == null || !Files.exists(ready)) return;
		try {
			// SwapHelper aus der eigenen Mod in einen Temp-Ordner kopieren und mit dem Java von Minecraft starten
			Path tmp = Files.createTempDirectory("bautrupp-update");
			Path cls = tmp.resolve("de/bautrupp/SwapHelper.class");
			Files.createDirectories(cls.getParent());
			try (InputStream in = Updater.class.getResourceAsStream("/de/bautrupp/SwapHelper.class")) {
				if (in == null) return;
				Files.copy(in, cls, StandardCopyOption.REPLACE_EXISTING);
			}
			String java = ProcessHandle.current().info().command()
					.orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
			Path target = jar.get().getParent().resolve("bautrupp-" + readyVersion + ".jar");
			new ProcessBuilder(java, "-cp", tmp.toString(), "de.bautrupp.SwapHelper", jar.get().toString(), ready.toString(), target.toString())
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
		} catch (Exception e) {
			BautruppClient.LOG.warn("Bautrupp-Update: Austausch konnte nicht gestartet werden", e);
		}
	}

	/** Ist a neuer als b? ("2.15.0" > "2.14.3") */
	static boolean newer(String a, String b) {
		String[] x = a.split("[.-]"), y = b.split("[.-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
			if (p != q) return p > q;
		}
		return false;
	}

	private static int num(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
