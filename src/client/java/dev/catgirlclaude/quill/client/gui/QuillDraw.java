package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Thin wrapper around Minecraft 26.3's {@link GuiGraphicsExtractor} to localize
 * all direct GUI rendering calls in one place.
 */
public final class QuillDraw {
	private QuillDraw() {
	}

	public static void fill(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		if (width <= 0 || height <= 0) {
			return;
		}
		graphics.fill(x, y, x + width, y + height, color);
	}

	public static void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		if (width <= 0 || height <= 0) {
			return;
		}
		graphics.outline(x, y, width, height, color);
	}

	public static void hLine(GuiGraphicsExtractor graphics, int x, int width, int y, int color) {
		if (width <= 0) {
			return;
		}
		graphics.horizontalLine(x, x + width - 1, y, color);
	}

	public static void vLine(GuiGraphicsExtractor graphics, int x, int y, int height, int color) {
		if (height <= 0) {
			return;
		}
		graphics.verticalLine(x, y, y + height - 1, color);
	}

	public static void text(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		graphics.text(font, text, x, y, color, false);
	}

	public static void textShadow(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		graphics.text(font, text, x, y, color, true);
	}

	public static String clipToWidth(Font font, String text, int maxWidth) {
		if (maxWidth <= 0 || text == null || text.isEmpty()) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int ellipsisW = font.width(ellipsis);
		if (maxWidth <= ellipsisW) {
			return font.plainSubstrByWidth(text, maxWidth);
		}
		return font.plainSubstrByWidth(text, maxWidth - ellipsisW) + ellipsis;
	}

	public static void beginClip(GuiGraphicsExtractor graphics, IdeLayout.Rect rect) {
		graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
	}

	public static void endClip(GuiGraphicsExtractor graphics) {
		graphics.disableScissor();
	}

	public static void requestCursor(GuiGraphicsExtractor graphics, CursorType cursor) {
		graphics.requestCursor(cursor);
	}
}
