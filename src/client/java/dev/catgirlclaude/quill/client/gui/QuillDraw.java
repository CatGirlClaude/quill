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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.ToIntFunction;

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

	public static String clipToWidth(
			Font font,
			String text,
			QuillClientConfig.FontChoice fontChoice,
			int maxWidth,
			float scale
	) {
		if (font == null || maxWidth <= 0 || text == null || text.isEmpty()) {
			return "";
		}
		Style style = styleFor(fontChoice);
		int baseWidth = style == Style.EMPTY ? font.width(text) : font.width(FormattedText.of(text, style));
		if (Math.round(baseWidth * scale) <= maxWidth) {
			return text;
		}
		int unscaledMax = Math.max(0, Math.round(maxWidth / Math.max(0.01f, scale)));
		int ellipsisW = style == Style.EMPTY ? font.width("...") : font.width(FormattedText.of("...", style));
		if (unscaledMax <= ellipsisW) {
			return style == Style.EMPTY
					? font.plainSubstrByWidth(text, unscaledMax)
					: font.getSplitter().plainHeadByWidth(text, unscaledMax, style);
		}
		String head = style == Style.EMPTY
				? font.plainSubstrByWidth(text, unscaledMax - ellipsisW)
				: font.getSplitter().plainHeadByWidth(text, unscaledMax - ellipsisW, style);
		return head + "...";
	}

	public static List<String> wrapLines(Font font, String text, int maxWidth) {
		return wrapLines(text, maxWidth, s -> textWidth(font, s));
	}

	public static List<String> wrapLines(String text, int maxWidth, ToIntFunction<String> widthMeasurer) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		if (maxWidth <= 0 || widthMeasurer == null) {
			return List.of(text);
		}
		List<String> lines = new ArrayList<>();
		for (String paragraph : text.split("\n", -1)) {
			if (paragraph.isEmpty()) {
				lines.add("");
				continue;
			}
			StringBuilder current = new StringBuilder();
			int i = 0;
			int len = paragraph.length();
			while (i < len) {
				while (i < len && paragraph.charAt(i) == ' ') {
					i++;
				}
				if (i >= len) {
					break;
				}
				int wordStart = i;
				while (i < len && paragraph.charAt(i) != ' ') {
					i++;
				}
				String word = paragraph.substring(wordStart, i);
				if (current.isEmpty()) {
					if (widthMeasurer.applyAsInt(word) <= maxWidth) {
						current.append(word);
					} else {
						appendSplitLongWord(word, maxWidth, widthMeasurer, lines, current);
					}
				} else {
					String candidate = current + " " + word;
					if (widthMeasurer.applyAsInt(candidate) <= maxWidth) {
						current.append(' ').append(word);
					} else {
						lines.add(current.toString());
						current.setLength(0);
						if (widthMeasurer.applyAsInt(word) <= maxWidth) {
							current.append(word);
						} else {
							appendSplitLongWord(word, maxWidth, widthMeasurer, lines, current);
						}
					}
				}
			}
			if (!current.isEmpty()) {
				lines.add(current.toString());
			}
		}
		return lines.isEmpty() ? List.of(text) : List.copyOf(lines);
	}

	private static void appendSplitLongWord(
			String word,
			int maxWidth,
			ToIntFunction<String> widthMeasurer,
			List<String> lines,
			StringBuilder current
	) {
		for (int i = 0; i < word.length(); ) {
			int next = i + 1;
			while (next < word.length() && widthMeasurer.applyAsInt(word.substring(i, next + 1)) <= maxWidth) {
				next++;
			}
			String chunk = word.substring(i, next);
			i = next;
			if (i < word.length()) {
				lines.add(chunk);
			} else {
				current.append(chunk);
			}
		}
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

	/**
	 * Minimal pixel-rectangle drawing target allowing unit tests to verify vector icon geometry
	 * without a live Minecraft graphics context.
	 */
	@FunctionalInterface
	public interface PixelRectTarget {
		void fill(int x, int y, int width, int height, int color);
	}

	public static int jsonBracesGapPx() {
		return 2;
	}

	/**
	 * Renders a VS Code Codicons-style 45-degree geometric chevron without ASCII fallback glyphs.
	 */
	public static void drawChevron(GuiGraphicsExtractor graphics, int x, int y, int rowHeight, boolean expanded, int color) {
		drawChevron((rx, ry, rw, rh, c) -> fill(graphics, rx, ry, rw, rh, c), x, y, rowHeight, expanded, color);
	}

	public static void drawChevron(PixelRectTarget target, int x, int y, int rowHeight, boolean expanded, int color) {
		int cx = x + 2;
		int cy = y + rowHeight / 2;
		if (expanded) {
			// Crisp down-pointing chevron (7px wide, 4px tall)
			target.fill(cx, cy - 1, 2, 1, color);
			target.fill(cx + 1, cy, 2, 1, color);
			target.fill(cx + 2, cy + 1, 2, 1, color);
			target.fill(cx + 3, cy + 2, 1, 1, color);
			target.fill(cx + 3, cy + 1, 2, 1, color);
			target.fill(cx + 4, cy, 2, 1, color);
			target.fill(cx + 5, cy - 1, 2, 1, color);
		} else {
			// Crisp right-pointing chevron (4px wide, 7px tall)
			target.fill(cx + 1, cy - 3, 1, 2, color);
			target.fill(cx + 2, cy - 2, 1, 2, color);
			target.fill(cx + 3, cy - 1, 1, 2, color);
			target.fill(cx + 4, cy, 1, 1, color);
			target.fill(cx + 3, cy, 1, 2, color);
			target.fill(cx + 2, cy + 1, 1, 2, color);
			target.fill(cx + 1, cy + 2, 1, 2, color);
		}
	}

	public static int drawFolderIcon(GuiGraphicsExtractor graphics, int x, int y, int rowHeight, boolean expanded, int color) {
		int top = y + Math.max(2, (rowHeight - 9) / 2);
		// Folder tab + body
		fill(graphics, x + 1, top, 4, 2, color);
		fill(graphics, x, top + 2, 10, 7, color);
		if (expanded) {
			// Subtle open-flap cutout on expanded folders
			fill(graphics, x + 1, top + 4, 8, 4, QuillTheme.BG_SIDEBAR);
			fill(graphics, x + 2, top + 5, 7, 3, color);
		} else {
			fill(graphics, x + 1, top + 3, 8, 1, QuillTheme.BG_SIDEBAR);
		}
		return 11;
	}

	/**
	 * Custom geometric terminal/command vector emblem for {@code .mcfunction} files (replaces literal {@code "fn"} text).
	 */
	public static int drawFunctionIcon(GuiGraphicsExtractor graphics, int x, int y, int rowHeight, int color) {
		int top = y + Math.max(2, (rowHeight - 9) / 2);
		outline(graphics, x, top, 12, 9, QuillTheme.withAlpha(color, 0xCC));
		// Command prompt '>' + '_' inside the emblem
		fill(graphics, x + 2, top + 2, 2, 1, color);
		fill(graphics, x + 3, top + 3, 2, 1, color);
		fill(graphics, x + 4, top + 4, 1, 1, color);
		fill(graphics, x + 3, top + 5, 2, 1, color);
		fill(graphics, x + 2, top + 6, 2, 1, color);
		fill(graphics, x + 6, top + 6, 4, 1, color);
		return 13;
	}

	/**
	 * Draws the approved Version B {@code {}} JSON icon: {@code '{'} and {@code '}'} rendered
	 * separately with a fixed {@value 2}px gap across all configured font families.
	 */
	public static int drawJsonBracesBadge(GuiGraphicsExtractor graphics, Font font, int x, int y, int color) {
		int leftW = textWidth(font, "{");
		int rightW = textWidth(font, "}");
		int gap = jsonBracesGapPx();
		text(graphics, font, "{", x, y, color);
		text(graphics, font, "}", x + leftW + gap, y, color);
		return leftW + gap + rightW;
	}

	public static int fileTypeIconWidth(Font font, String fileName) {
		QuillFileTypes.FileTypeInfo info = QuillFileTypes.classify(fileName);
		return switch (info.category()) {
			case FUNCTION -> 13;
			case JSON -> textWidth(font, "{") + jsonBracesGapPx() + textWidth(font, "}");
			case MARKDOWN, TEXT, IMAGE, AUDIO, NBT, ARCHIVE, BINARY -> 12;
		};
	}

	public static int drawFileTypeIcon(GuiGraphicsExtractor graphics, Font font, String fileName, int x, int y, int rowHeight) {
		QuillFileTypes.FileTypeInfo info = QuillFileTypes.classify(fileName);
		int color = info.badgeColor();
		int top = y + Math.max(2, (rowHeight - 9) / 2);
		int textY = font != null ? y + (rowHeight - font.lineHeight) / 2 : y + 2;

		return switch (info.category()) {
			case FUNCTION -> drawFunctionIcon(graphics, x, y, rowHeight, color);
			case JSON -> drawJsonBracesBadge(graphics, font, x, textY, color);
			case MARKDOWN -> {
				outline(graphics, x + 1, top, 10, 9, color);
				fill(graphics, x + 3, top + 2, 1, 5, color);
				fill(graphics, x + 4, top + 3, 1, 2, color);
				fill(graphics, x + 5, top + 2, 1, 5, color);
				fill(graphics, x + 8, top + 2, 1, 4, color);
				fill(graphics, x + 7, top + 5, 3, 1, color);
				fill(graphics, x + 8, top + 6, 1, 1, color);
				yield 12;
			}
			case TEXT -> {
				outline(graphics, x + 1, top, 9, 9, color);
				hLine(graphics, x + 3, 5, top + 3, color);
				hLine(graphics, x + 3, 5, top + 5, color);
				hLine(graphics, x + 3, 3, top + 7, color);
				yield 12;
			}
			case IMAGE -> {
				outline(graphics, x + 1, top, 10, 9, color);
				fill(graphics, x + 3, top + 2, 2, 2, color);
				fill(graphics, x + 3, top + 6, 3, 1, color);
				fill(graphics, x + 4, top + 5, 2, 1, color);
				fill(graphics, x + 6, top + 4, 3, 3, color);
				yield 12;
			}
			case AUDIO -> {
				// Speaker + sound wave
				fill(graphics, x + 1, top + 3, 2, 3, color);
				fill(graphics, x + 3, top + 2, 2, 5, color);
				fill(graphics, x + 5, top + 1, 1, 7, color);
				fill(graphics, x + 8, top + 3, 1, 3, color);
				fill(graphics, x + 10, top + 2, 1, 5, color);
				yield 12;
			}
			case NBT -> {
				// Isometric-inspired 3D cube outline
				outline(graphics, x + 1, top + 1, 9, 8, color);
				hLine(graphics, x + 3, 5, top + 3, color);
				vLine(graphics, x + 5, top + 3, 5, color);
				yield 12;
			}
			case ARCHIVE -> {
				outline(graphics, x + 1, top, 9, 9, color);
				hLine(graphics, x + 1, 9, top + 3, color);
				fill(graphics, x + 4, top + 1, 3, 2, color);
				fill(graphics, x + 4, top + 5, 3, 2, color);
				yield 12;
			}
			case BINARY -> {
				outline(graphics, x + 1, top, 10, 9, color);
				fill(graphics, x + 3, top + 2, 2, 2, color);
				fill(graphics, x + 7, top + 2, 2, 2, color);
				fill(graphics, x + 3, top + 5, 2, 2, color);
				fill(graphics, x + 7, top + 5, 2, 2, color);
				yield 12;
			}
		};
	}

	/**
	 * Draws a crisp diagonal X glyph at {@code (x, y)} of {@code size x size} pixels.
	 */
	public static void drawCloseX(GuiGraphicsExtractor graphics, int x, int y, int size, int color) {
		int s = Math.max(4, size);
		for (int i = 0; i < s; i++) {
			fill(graphics, x + i, y + i, 1, 1, color);
			fill(graphics, x + (s - 1 - i), y + i, 1, 1, color);
		}
	}

	/**
	 * Draws a crisp geometric checkmark inside an 8x8 icon area.
	 */
	public static void drawCheckmark(GuiGraphicsExtractor graphics, int x, int y, int color) {
		fill(graphics, x, y + 3, 1, 2, color);
		fill(graphics, x + 1, y + 4, 1, 2, color);
		fill(graphics, x + 2, y + 5, 1, 2, color);
		fill(graphics, x + 3, y + 4, 1, 2, color);
		fill(graphics, x + 4, y + 3, 1, 2, color);
		fill(graphics, x + 5, y + 2, 1, 2, color);
		fill(graphics, x + 6, y + 1, 1, 2, color);
	}

	/**
	 * Draws a smooth circular magnifying glass loupe centered in a 14x14 box at {@code (x, y)}.
	 */
	public static void drawSearchLoupe(GuiGraphicsExtractor graphics, int x, int y, int color) {
		// 9px circular lens ring (smooth octagonal pixel circle, top-left (x, y))
		hLine(graphics, x + 2, 5, y, color);
		hLine(graphics, x + 1, 2, y + 1, color);
		hLine(graphics, x + 6, 2, y + 1, color);
		vLine(graphics, x, y + 2, 5, color);
		vLine(graphics, x + 8, y + 2, 5, color);
		hLine(graphics, x + 1, 2, y + 7, color);
		hLine(graphics, x + 6, 2, y + 7, color);
		hLine(graphics, x + 2, 5, y + 8, color);
		// Subtle specular lens arc
		fill(graphics, x + 2, y + 2, 2, 1, QuillTheme.withAlpha(color, 0x66));
		fill(graphics, x + 2, y + 3, 1, 1, QuillTheme.withAlpha(color, 0x66));
		// Diagonal handle
		fill(graphics, x + 7, y + 7, 2, 2, color);
		fill(graphics, x + 8, y + 8, 2, 2, color);
		fill(graphics, x + 10, y + 10, 2, 2, color);
		fill(graphics, x + 11, y + 11, 2, 2, color);
	}

	/**
	 * Draws a crisp centered settings gear icon around {@code (centerX, centerY)}.
	 */
	public static void drawGearIcon(GuiGraphicsExtractor graphics, int centerX, int centerY, int color, int hollowBg) {
		// 4 cardinal teeth
		fill(graphics, centerX - 1, centerY - 6, 2, 2, color);
		fill(graphics, centerX - 1, centerY + 4, 2, 2, color);
		fill(graphics, centerX - 6, centerY - 1, 2, 2, color);
		fill(graphics, centerX + 4, centerY - 1, 2, 2, color);
		// 4 diagonal teeth
		fill(graphics, centerX - 5, centerY - 5, 2, 2, color);
		fill(graphics, centerX + 3, centerY - 5, 2, 2, color);
		fill(graphics, centerX - 5, centerY + 3, 2, 2, color);
		fill(graphics, centerX + 3, centerY + 3, 2, 2, color);
		// Rounded body ring + circular hollow center
		fill(graphics, centerX - 3, centerY - 4, 6, 8, color);
		fill(graphics, centerX - 4, centerY - 3, 8, 6, color);
		fill(graphics, centerX - 2, centerY - 2, 4, 4, hollowBg);
	}
}
