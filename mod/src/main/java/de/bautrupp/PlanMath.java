package de.bautrupp;

/** Rechnungen für Baupläne aus Schematics (ohne Minecraft-Klassen, damit man sie leicht prüfen kann). */
public final class PlanMath {
	private PlanMath() {}

	/** "a,1z,3k" (Base36) -> Positionsnummern; Nummer = x + b * (z + l * y). */
	public static int[] decode(String s) {
		if (s == null || s.isBlank()) return new int[0];
		String[] parts = s.split(",");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim(), 36);
		return out;
	}

	/**
	 * Position in der Schematic -> Abstand zur Ecke in der Welt (dx, y, dz), gedreht um rot mal 90°
	 * im Uhrzeigersinn. Die Ecke ist immer die mit den kleinsten Koordinaten (wie auf der Webseite).
	 */
	public static int[] toWorld(int index, int b, int l, int rot) {
		int x = index % b, rest = index / b, z = rest % l, y = rest / l;
		return switch (rot & 3) {
			case 1 -> new int[]{l - 1 - z, y, x};
			case 2 -> new int[]{b - 1 - x, y, l - 1 - z};
			case 3 -> new int[]{z, y, b - 1 - x};
			default -> new int[]{x, y, z};
		};
	}
}
