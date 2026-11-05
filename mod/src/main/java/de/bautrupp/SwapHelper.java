package de.bautrupp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Läuft als eigenes kleines Programm, nachdem Minecraft beendet wurde: wartet, bis die alte
 * Mod-Datei frei ist (Windows sperrt sie, solange das Spiel läuft), löscht sie und benennt die
 * neue Datei ("….jar.neu") in "….jar" um. Absichtlich ohne Minecraft- oder Fabric-Klassen,
 * damit es mit jedem Java läuft.
 *
 * Aufruf: java -cp <ordner> de.bautrupp.SwapHelper <alte.jar> <neue.jar.neu> <ziel.jar>
 */
public final class SwapHelper {
	private SwapHelper() {}

	public static void main(String[] args) throws Exception {
		if (args.length != 3) return;
		Path oldJar = Path.of(args[0]), newFile = Path.of(args[1]), target = Path.of(args[2]);
		// Bis zu 2 Minuten warten, bis Minecraft wirklich zu ist
		for (int i = 0; i < 240; i++) {
			if (!Files.exists(newFile)) return;
			try {
				Files.deleteIfExists(oldJar);
				// Erst wenn die alte weg ist, wird die neue zur .jar – so liegen nie zwei Bautrupp-Mods im Ordner
				Files.move(newFile, target, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (Exception locked) {
				Thread.sleep(500);
			}
		}
	}
}
