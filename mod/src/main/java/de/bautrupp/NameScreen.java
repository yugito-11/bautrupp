package de.bautrupp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Kleines Fenster, um einen Namen einzugeben (z. B. für ein neues Projekt). */
public class NameScreen extends Screen {
	private final String hint;
	private final Screen parent;
	private final Consumer<String> onDone;
	private EditBox box;

	public NameScreen(String title, String hint, Screen parent, Consumer<String> onDone) {
		super(Component.literal(title));
		this.hint = hint;
		this.parent = parent;
		this.onDone = onDone;
	}

	@Override
	protected void init() {
		int w = Math.min(width - 40, 260), x = (width - w) / 2, y = height / 2 - 20;
		box = new EditBox(font, x, y, w, 20, title);
		box.setMaxLength(40);
		box.setHint(Component.literal(hint));
		addRenderableWidget(box);
		setInitialFocus(box);
		addRenderableWidget(Button.builder(Component.literal("Anlegen"), b -> done()).bounds(x, y + 26, w / 2 - 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Abbrechen"), b -> onClose()).bounds(x + w / 2 + 2, y + 26, w / 2 - 2, 20).build());
	}

	private void done() {
		String name = box.getValue().trim();
		if (name.isEmpty()) return;
		onDone.accept(name);
		onClose();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN && getFocused() == box) {
			done();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, height / 2 - 36, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
