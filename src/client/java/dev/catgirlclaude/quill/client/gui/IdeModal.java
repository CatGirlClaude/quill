package dev.catgirlclaude.quill.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Common interface for modal dialog overlays rendered above {@link QuillIdeScreen}.
 */
public interface IdeModal {
	boolean hasTextFields();

	void render(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY);

	boolean mouseClicked(MouseButtonEvent event);

	boolean mouseDragged(MouseButtonEvent event);

	void mouseReleased(MouseButtonEvent event);

	boolean keyPressed(KeyEvent event);

	boolean charTyped(CharacterEvent event);
}
