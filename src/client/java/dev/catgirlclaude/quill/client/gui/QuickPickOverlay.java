package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.editor.FuzzyMatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * VS Code-style top-center quick pick overlay backing both the Command Palette
 * ({@code Ctrl+Shift+P} / {@code F1}, prefixed with {@code >}) and Quick Open File Switcher ({@code Ctrl+P}).
 */
public final class QuickPickOverlay {
	public enum Mode {
		COMMANDS,
		FILES
	}

	public record PickItem(
			String id,
			String label,
			String detail,
			String shortcut,
			String searchText,
			Runnable action
	) {
	}

	private record ScoredItem(PickItem item, int score, Set<Integer> labelHighlightIndices) {
	}

	private static final int ROW_HEIGHT = 18;
	private static final int MAX_VISIBLE_ROWS = 10;

	private final Supplier<List<PickItem>> commandSupplier;
	private final Supplier<List<PickItem>> fileSupplier;
	private final Runnable onClose;

	private final StringBuilder input = new StringBuilder();
	private int cursorPos = 0;
	private int selectedIndex = 0;
	private int scrollOffset = 0;

	private List<ScoredItem> filtered = List.of();
	private IdeLayout.Rect panelRect = new IdeLayout.Rect(0, 0, 0, 0);
	private final List<IdeLayout.Rect> rowRects = new ArrayList<>();

	public QuickPickOverlay(
			Mode initialMode,
			Supplier<List<PickItem>> commandSupplier,
			Supplier<List<PickItem>> fileSupplier,
			Runnable onClose
	) {
		this.commandSupplier = Objects.requireNonNull(commandSupplier, "commandSupplier");
		this.fileSupplier = Objects.requireNonNull(fileSupplier, "fileSupplier");
		this.onClose = Objects.requireNonNull(onClose, "onClose");
		if (initialMode == Mode.COMMANDS) {
			input.append(">");
			cursorPos = 1;
		}
		rebuildFiltered();
	}

	public Mode activeMode() {
		return input.length() > 0 && input.charAt(0) == '>' ? Mode.COMMANDS : Mode.FILES;
	}

	public String queryText() {
		String raw = input.toString();
		return raw.startsWith(">") ? raw.substring(1).trim() : raw.trim();
	}

	public void render(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, 0, 0, screenWidth, screenHeight, 0x66000000);

		int panelW = Math.min(440, Math.max(240, screenWidth - 40));
		int visibleRows = Math.min(MAX_VISIBLE_ROWS, Math.max(1, filtered.size()));
		int headerH = 38;
		int listH = visibleRows * ROW_HEIGHT + 6;
		int panelH = Math.min(screenHeight - 28, headerH + listH);
		int px = (screenWidth - panelW) / 2;
		int py = Math.max(12, Math.min(34, (screenHeight - panelH) / 6));
		panelRect = new IdeLayout.Rect(px, py, panelW, panelH);

		QuillDraw.fill(graphics, px, py, panelW, panelH, QuillTheme.BG_POPUP);
		QuillDraw.outline(graphics, px, py, panelW, panelH, QuillTheme.ACCENT_FOCUS);

		// Mode badge + hint
		Mode mode = activeMode();
		String title = mode == Mode.COMMANDS
				? "Command Palette  (type to filter commands)"
				: "Quick Open File  (type filename, or '>' for commands)";
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, title, panelW - 16), px + 8, py + 4, QuillTheme.TEXT_MUTED);

		// Input box
		IdeLayout.Rect inputRect = new IdeLayout.Rect(px + 8, py + 16, panelW - 16, 17);
		QuillDraw.fill(graphics, inputRect.x(), inputRect.y(), inputRect.width(), inputRect.height(), QuillTheme.BG_INPUT);
		QuillDraw.outline(graphics, inputRect.x(), inputRect.y(), inputRect.width(), inputRect.height(), QuillTheme.ACCENT_FOCUS);

		String raw = input.toString();
		int textY = inputRect.y() + (inputRect.height() - font.lineHeight) / 2;
		QuillDraw.beginClip(graphics, new IdeLayout.Rect(inputRect.x() + 2, inputRect.y() + 1, inputRect.width() - 4, inputRect.height() - 2));
		if (raw.isEmpty()) {
			QuillDraw.text(graphics, font, "Search files by name...", inputRect.x() + 5, textY, QuillTheme.TEXT_MUTED);
		} else {
			QuillDraw.text(graphics, font, raw, inputRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
		}
		if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
			int cx = inputRect.x() + 5 + font.width(raw.substring(0, Math.min(cursorPos, raw.length())));
			QuillDraw.fill(graphics, cx, inputRect.y() + 3, 1, inputRect.height() - 6, QuillTheme.TEXT_BRIGHT);
		}
		QuillDraw.endClip(graphics);

		QuillDraw.hLine(graphics, px + 1, panelW - 2, py + headerH - 1, QuillTheme.BORDER_SUBTLE);

		// Results list
		IdeLayout.Rect listRect = new IdeLayout.Rect(px + 2, py + headerH + 2, panelW - 4, Math.max(0, panelRect.bottom() - (py + headerH + 4)));
		rowRects.clear();

		QuillDraw.beginClip(graphics, listRect);
		if (filtered.isEmpty()) {
			String emptyMsg = mode == Mode.COMMANDS ? "No matching commands." : "No matching files in datapacks/.";
			QuillDraw.text(graphics, font, emptyMsg, listRect.x() + 8, listRect.y() + 4, QuillTheme.TEXT_MUTED);
		} else {
			int maxVisible = Math.max(1, listRect.height() / ROW_HEIGHT);
			selectedIndex = Math.max(0, Math.min(filtered.size() - 1, selectedIndex));
			if (selectedIndex < scrollOffset) {
				scrollOffset = selectedIndex;
			} else if (selectedIndex >= scrollOffset + maxVisible) {
				scrollOffset = selectedIndex - maxVisible + 1;
			}
			scrollOffset = Math.max(0, Math.min(Math.max(0, filtered.size() - maxVisible), scrollOffset));

			int end = Math.min(filtered.size(), scrollOffset + maxVisible);
			for (int i = scrollOffset; i < end; i++) {
				ScoredItem entry = filtered.get(i);
				int ry = listRect.y() + (i - scrollOffset) * ROW_HEIGHT;
				IdeLayout.Rect rowRect = new IdeLayout.Rect(listRect.x() + 2, ry, listRect.width() - 4, ROW_HEIGHT);
				rowRects.add(rowRect);

				boolean selected = i == selectedIndex;
				boolean hovered = listRect.contains(mouseX, mouseY) && rowRect.contains(mouseX, mouseY);
				if (selected) {
					QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.BG_POPUP_SELECTED);
				} else if (hovered) {
					QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.BG_HOVER);
				}
				if (hovered) {
					QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
				}

				int ty = rowRect.y() + (ROW_HEIGHT - font.lineHeight) / 2;
				int rightLimit = rowRect.right() - 6;

				// Shortcut badge on right
				String shortcut = entry.item().shortcut();
				if (shortcut != null && !shortcut.isBlank()) {
					int sw = font.width(shortcut) + 6;
					int sx = rightLimit - sw;
					QuillDraw.fill(graphics, sx, rowRect.y() + 3, sw, ROW_HEIGHT - 6, QuillTheme.BG_BUTTON_SECONDARY);
					QuillDraw.text(graphics, font, shortcut, sx + 3, ty, QuillTheme.TEXT_MUTED);
					rightLimit = sx - 6;
				}

				// Label with matched-char accent highlights
				String label = entry.item().label();
				int maxLabelW = Math.max(40, rightLimit - (rowRect.x() + 6));
				String clippedLabel = QuillDraw.clipToWidth(font, label, maxLabelW);
				drawHighlightedText(graphics, font, clippedLabel, rowRect.x() + 6, ty,
						selected ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY,
						QuillTheme.SYNTAX_COORDINATE, entry.labelHighlightIndices());

				// Detail / parent path
				String detail = entry.item().detail();
				if (detail != null && !detail.isBlank()) {
					int dx = rowRect.x() + 6 + font.width(clippedLabel) + 8;
					int detailMaxW = rightLimit - dx;
					if (detailMaxW > 24) {
						QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, detail, detailMaxW),
								dx, ty, QuillTheme.TEXT_MUTED);
					}
				}
			}
		}
		QuillDraw.endClip(graphics);
	}

	private static void drawHighlightedText(
			GuiGraphicsExtractor graphics,
			Font font,
			String text,
			int x,
			int y,
			int baseColor,
			int highlightColor,
			Set<Integer> highlightIndices
	) {
		if (highlightIndices.isEmpty()) {
			QuillDraw.text(graphics, font, text, x, y, baseColor);
			return;
		}
		int i = 0;
		while (i < text.length()) {
			boolean hl = highlightIndices.contains(i);
			int start = i;
			i++;
			while (i < text.length() && highlightIndices.contains(i) == hl) {
				i++;
			}
			int segX = x + (start == 0 ? 0 : font.width(text.substring(0, start)));
			QuillDraw.text(graphics, font, text.substring(start, i), segX, y, hl ? highlightColor : baseColor);
		}
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		if (!panelRect.contains(event.x(), event.y())) {
			onClose.run();
			return true;
		}
		for (int i = 0; i < rowRects.size(); i++) {
			if (rowRects.get(i).contains(event.x(), event.y())) {
				selectedIndex = scrollOffset + i;
				executeSelected();
				return true;
			}
		}
		return true;
	}

	public boolean mouseScrolled(double scrollY) {
		if (filtered.isEmpty() || scrollY == 0) {
			return true;
		}
		int maxScroll = Math.max(0, filtered.size() - Math.min(MAX_VISIBLE_ROWS, filtered.size()));
		scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(scrollY) * 2));
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			onClose.run();
			return true;
		}
		if (event.isConfirmation()) {
			executeSelected();
			return true;
		}
		if (event.isUp()) {
			if (!filtered.isEmpty()) {
				selectedIndex = (selectedIndex - 1 + filtered.size()) % filtered.size();
			}
			return true;
		}
		if (event.isDown()) {
			if (!filtered.isEmpty()) {
				selectedIndex = (selectedIndex + 1) % filtered.size();
			}
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_PAGEUP || event.key() == InputConstants.KEY_PAGEUP) {
			selectedIndex = Math.max(0, selectedIndex - MAX_VISIBLE_ROWS);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_PAGEDOWN || event.key() == InputConstants.KEY_PAGEDOWN) {
			if (!filtered.isEmpty()) {
				selectedIndex = Math.min(filtered.size() - 1, selectedIndex + MAX_VISIBLE_ROWS);
			}
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_BACKSPACE || event.key() == InputConstants.KEY_BACKSPACE) {
			if (cursorPos > 0) {
				input.deleteCharAt(cursorPos - 1);
				cursorPos--;
				rebuildFiltered();
			}
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_DELETE || event.key() == InputConstants.KEY_DELETE) {
			if (cursorPos < input.length()) {
				input.deleteCharAt(cursorPos);
				rebuildFiltered();
			}
			return true;
		}
		if (event.isLeft()) {
			cursorPos = Math.max(0, cursorPos - 1);
			return true;
		}
		if (event.isRight()) {
			cursorPos = Math.min(input.length(), cursorPos + 1);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_HOME || event.key() == InputConstants.KEY_HOME) {
			cursorPos = 0;
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_END || event.key() == InputConstants.KEY_END) {
			cursorPos = input.length();
			return true;
		}
		if (event.isPaste()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) {
				String clip = mc.keyboardHandler.getClipboard();
				if (clip != null && !clip.isEmpty()) {
					String clean = clip.replace("\r", "").replace("\n", "");
					input.insert(cursorPos, clean);
					cursorPos += clean.length();
					rebuildFiltered();
				}
			}
			return true;
		}
		return true;
	}

	public boolean charTyped(CharacterEvent event) {
		if (!event.isAllowedChatCharacter()) {
			return false;
		}
		String s = event.codepointAsString();
		input.insert(cursorPos, s);
		cursorPos += s.length();
		rebuildFiltered();
		return true;
	}

	private void executeSelected() {
		if (filtered.isEmpty()) {
			return;
		}
		int idx = Math.max(0, Math.min(filtered.size() - 1, selectedIndex));
		PickItem chosen = filtered.get(idx).item();
		onClose.run();
		if (chosen.action() != null) {
			chosen.action().run();
		}
	}

	private void rebuildFiltered() {
		Mode mode = activeMode();
		String query = queryText();
		List<PickItem> source = mode == Mode.COMMANDS ? commandSupplier.get() : fileSupplier.get();

		List<ScoredItem> results = new ArrayList<>();
		for (PickItem item : source) {
			Optional<FuzzyMatcher.MatchResult> searchMatch = FuzzyMatcher.match(query, item.searchText());
			if (searchMatch.isEmpty()) {
				continue;
			}
			// Compute highlight indices directly against label when label matches query
			Set<Integer> labelHighlights = Set.of();
			if (!query.isEmpty()) {
				Optional<FuzzyMatcher.MatchResult> labelMatch = FuzzyMatcher.match(query, item.label());
				if (labelMatch.isPresent()) {
					labelHighlights = new HashSet<>(labelMatch.get().matchedIndices());
				}
			}
			results.add(new ScoredItem(item, searchMatch.get().score(), labelHighlights));
		}

		if (!query.isEmpty()) {
			results.sort(Comparator.comparingInt(ScoredItem::score).reversed()
					.thenComparing(s -> s.item().label(), String.CASE_INSENSITIVE_ORDER));
		}
		this.filtered = results;
		this.selectedIndex = 0;
		this.scrollOffset = 0;
	}
}
