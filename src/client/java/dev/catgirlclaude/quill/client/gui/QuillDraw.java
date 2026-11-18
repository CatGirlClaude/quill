package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorType;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;

/**
 * Thin wrapper around Minecraft 26.3's {@link GuiGraphicsExtractor} that localizes
 * direct GUI rendering calls and applies the configured client font family and text scale.
 */
public final class QuillDraw {
	private static final EnumMap<QuillClientConfig.FontChoice, Style> FONT_STYLES =
			new EnumMap<>(QuillClientConfig.FontChoice.class);

	private QuillDraw() {
	}

	public static Style styleFor(QuillClientConfig.FontChoice choice) {
		if (choice == null || choice == QuillClientConfig.FontChoice.MINECRAFT_DEFAULT) {
			return Style.EMPTY;
		}
		return FONT_STYLES.computeIfAbsent(choice, c ->
				Style.EMPTY.withFont(new FontDescription.Resource(Identifier.parse(c.resourceId()))));
	}

	public static Style activeFontStyle() {
		return styleFor(QuillClientConfig.current().fontChoice());
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
		textWithStyle(graphics, font, text, activeFontStyle(), x, y, color, false, 1.0f);
	}

	public static void textShadow(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		textWithStyle(graphics, font, text, activeFontStyle(), x, y, color, !QuillTheme.isLightTheme(), 1.0f);
	}

	public static void textScaled(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, float scale) {
		textWithStyle(graphics, font, text, activeFontStyle(), x, y, color, false, scale);
	}

	public static void textWithFont(
			GuiGraphicsExtractor graphics,
			Font font,
			String text,
			QuillClientConfig.FontChoice fontChoice,
			int x,
			int y,
			int color,
			float scale
	) {
		textWithStyle(graphics, font, text, styleFor(fontChoice), x, y, color, false, scale);
	}

	private static void textWithStyle(
			GuiGraphicsExtractor graphics,
			Font font,
			String text,
			Style style,
			int x,
			int y,
			int color,
			boolean shadow,
			float scale
	) {
		if (text == null || text.isEmpty()) {
			return;
		}
		boolean scaled = Math.abs(scale - 1.0f) > 0.001f;
		if (scaled) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale, scale);
			if (style == Style.EMPTY) {
				graphics.text(font, text, 0, 0, color, shadow);
			} else {
				graphics.text(font, Component.literal(text).withStyle(style), 0, 0, color, shadow);
			}
			graphics.pose().popMatrix();
		} else if (style == Style.EMPTY) {
			graphics.text(font, text, x, y, color, shadow);
		} else {
			graphics.text(font, Component.literal(text).withStyle(style), x, y, color, shadow);
		}
	}

	public static int textWidth(Font font, String text) {
		return textWidth(font, text, 1.0f);
	}

	public static int textWidth(Font font, String text, float scale) {
		if (font == null || text == null || text.isEmpty()) {
			return 0;
		}
		Style style = activeFontStyle();
		int baseWidth = (style == Style.EMPTY)
				? font.width(text)
				: font.width(FormattedText.of(text, style));
		if (Math.abs(scale - 1.0f) <= 0.001f) {
			return baseWidth;
		}
		return Math.round(baseWidth * scale);
	}

	public static String plainSubstrByWidth(Font font, String text, int maxWidth) {
		return plainSubstrByWidth(font, text, maxWidth, 1.0f);
	}

	public static String plainSubstrByWidth(Font font, String text, int maxWidth, float scale) {
		if (font == null || text == null || text.isEmpty() || maxWidth <= 0) {
			return "";
		}
		int unscaledMax = Math.abs(scale - 1.0f) <= 0.001f
				? maxWidth
				: Math.max(0, Math.round(maxWidth / scale));
		Style style = activeFontStyle();
		if (style == Style.EMPTY) {
			return font.plainSubstrByWidth(text, unscaledMax);
		}
		return font.getSplitter().plainHeadByWidth(text, unscaledMax, style);
	}

	/**
	 * Converts a pixel X offset relative to the start of {@code text} into the nearest character index,
	 * accounting for the active font family and text scale.
	 */
	public static int charIndexAtPixel(Font font, String text, int relativePixelX, float scale) {
		if (text == null || text.isEmpty() || relativePixelX <= 0) {
			return 0;
		}
		if (font == null) {
			return text.length();
		}
		String sub = plainSubstrByWidth(font, text, relativePixelX, scale);
		int col = sub.length();
		if (col < text.length()) {
			int wBefore = textWidth(font, sub, scale);
			int wAfter = textWidth(font, text.substring(0, col + 1), scale);
			if (relativePixelX - wBefore > wAfter - relativePixelX) {
				col++;
			}
		}
		return col;
	}

	public static String clipToWidth(Font font, String text, int maxWidth) {
		return clipToWidth(font, text, maxWidth, 1.0f);
	}

	public static String clipToWidth(Font font, String text, int maxWidth, float scale) {
		if (maxWidth <= 0 || text == null || text.isEmpty()) {
			return "";
		}
		if (textWidth(font, text, scale) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int ellipsisW = textWidth(font, ellipsis, scale);
		if (maxWidth <= ellipsisW) {
			return plainSubstrByWidth(font, text, maxWidth, scale);
		}
		return plainSubstrByWidth(font, text, maxWidth - ellipsisW, scale) + ellipsis;
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
