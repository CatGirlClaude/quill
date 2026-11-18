package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.editor.FuzzyMatcher;
import dev.catgirlclaude.quill.client.editor.SingleLineTextInput;
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
 * VS Code-style top-anchored Command Center dropdown backing both the Command Palette
 * ({@code Ctrl+Shift+P} / {@code F1}, prefixed with {@code >}) and Quick Open File Switcher ({@code Ctrl+P}),
 * with full text selection in the search input via {@link SingleLineTextInput}.
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
	private static final int HEADER_HEIGHT = 38;
	private static final int MAX_VISIBLE_ROWS = 10;

	private final Supplier<List<PickItem>> commandSupplier;
	private final Supplier<List<PickItem>> fileSupplier;
	private final Runnable onClose;

	private final SingleLineTextInput input = new SingleLineTextInput();
	private int selectedIndex = 0;
	private int scrollOffset = 0;
	private float revealProgress;
	private long lastFrameNs = 0L;

	private List<ScoredItem> filtered = List.of();
	private IdeLayout.Rect panelRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect inputRect = new IdeLayout.Rect(0, 0, 0, 0);
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
		this.revealProgress = QuillClientConfig.current().animationsEnabled() ? 0.0f : 1.0f;
		if (initialMode == Mode.COMMANDS) {
			input.setText(">");
		}
		rebuildFiltered();
	}

	public Mode activeMode() {
		String raw = input.text();
		return !raw.isEmpty() && raw.charAt(0) == '>' ? Mode.COMMANDS : Mode.FILES;
	}

	public String queryText() {
		String raw = input.text();
		return raw.startsWith(">") ? raw.substring(1).trim() : raw.trim();
	}

	static IdeLayout.Rect computeDropdownBounds(
			IdeLayout.Rect windowRect,
			IdeLayout.Rect titleBarRect,
			IdeLayout.Rect anchorRect,
			int rowCount,
			int screenHeight,
			float revealProgress
	) {
		int winX = windowRect != null ? windowRect.x() : 0;
		int winW = windowRect != null && windowRect.width() > 0 ? windowRect.width() : 640;
		int maxAvailW = Math.max(180, winW - 24);
		int preferredW = (anchorRect != null && anchorRect.width() > 0) ? Math.max(anchorRect.width() + 80, 380) : 440;
		int panelW = Math.min(480, Math.min(maxAvailW, preferredW));

		int centerX = (anchorRect != null && anchorRect.width() > 0)
				? anchorRect.x() + anchorRect.width() / 2
				: winX + winW / 2;
		int minX = winX + 6;
		int maxX = Math.max(minX, winX + winW - panelW - 6);
		int px = Math.max(minX, Math.min(maxX, centerX - panelW / 2));

		int py = (titleBarRect != null && titleBarRect.height() > 0)
				? titleBarRect.bottom() - 1
				: Math.max(8, (windowRect != null ? windowRect.y() : 12) + 23);

		int visibleRows = Math.min(MAX_VISIBLE_ROWS, Math.max(1, rowCount));
		int listH = visibleRows * ROW_HEIGHT + 6;
		int maxBottomSpace = Math.max(HEADER_HEIGHT + ROW_HEIGHT, screenHeight - py - 10);
		int targetH = Math.min(maxBottomSpace, HEADER_HEIGHT + listH);

		float p = Math.max(0.0f, Math.min(1.0f, revealProgress));
		int currentH = p >= 0.999f
				? targetH
				: Math.max(HEADER_HEIGHT, Math.round(HEADER_HEIGHT + (targetH - HEADER_HEIGHT) * p));
		return new IdeLayout.Rect(px, py, panelW, currentH);
	}

	public void render(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
		IdeLayout.Rect fallbackWin = new IdeLayout.Rect(0, 0, screenWidth, screenHeight);
		render(graphics, font, fallbackWin, new IdeLayout.Rect(0, 0, screenWidth, 24),
				new IdeLayout.Rect(0, 0, 0, 0), screenWidth, screenHeight, mouseX, mouseY);
	}

	public void render(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect windowRect,
			IdeLayout.Rect titleBarRect,
			IdeLayout.Rect commandCenterRect,
			int screenWidth,
			int screenHeight,
			int mouseX,
			int mouseY
	) {
		long now = System.nanoTime();
		float dt = lastFrameNs == 0L ? 0.016f : Math.min(0.1f, Math.max(0.0f, (now - lastFrameNs) / 1_000_000_000.0f));
		lastFrameNs = now;
		revealProgress = QuillAnimator.stepTowards(
				revealProgress, 1.0f, dt, 18.0f, QuillClientConfig.current().animationsEnabled());

		panelRect = computeDropdownBounds(windowRect, titleBarRect, commandCenterRect, filtered.size(), screenHeight, revealProgress);
		int px = panelRect.x();
		int py = panelRect.y();
		int panelW = panelRect.width();
		int panelH = panelRect.height();

		// Subtle drop shadow anchored under the top bar (no full-screen dimming box)
		QuillDraw.fill(graphics, px - 2, py + 1, panelW + 5, panelH + 4, QuillTheme.Metrics.SHADOW_OUTER);
		QuillDraw.fill(graphics, px + 1, py + 2, panelW + 1, panelH + 2, QuillTheme.Metrics.SHADOW_INNER);
		QuillDraw.fill(graphics, px, py, panelW, panelH, QuillTheme.BG_POPUP);
		QuillDraw.outline(graphics, px, py, panelW, panelH, QuillTheme.ACCENT_FOCUS);

		// Mode badge + hint
		Mode mode = activeMode();
		String title = mode == Mode.COMMANDS
				? "Command Palette"
				: "Quick Open File  (type '>' for commands)";
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, title, panelW - 16), px + 8, py + 4, QuillTheme.TEXT_MUTED);

		// Input box with full selection
		inputRect = new IdeLayout.Rect(px + 8, py + 16, panelW - 16, 17);
		input.render(graphics, font, inputRect, mode == Mode.COMMANDS ? "Type command name..." : "Search files by name...", true);
		if (inputRect.contains(mouseX, mouseY)) {
			QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
		}

		QuillDraw.hLine(graphics, px + 1, panelW - 2, py + HEADER_HEIGHT - 1, QuillTheme.BORDER_SUBTLE);

		// Results list
		int listH = Math.max(0, panelRect.bottom() - (py + HEADER_HEIGHT + 4));
		IdeLayout.Rect listRect = new IdeLayout.Rect(px + 2, py + HEADER_HEIGHT + 2, panelW - 4, listH);
		rowRects.clear();
		if (listH <= 2) {
			return;
		}

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
					int sw = QuillDraw.textWidth(font, shortcut) + 6;
					int sx = rightLimit - sw;
					QuillDraw.fill(graphics, sx, rowRect.y() + 3, sw, ROW_HEIGHT - 6, QuillTheme.BG_BUTTON_SECONDARY);
					QuillDraw.text(graphics, font, shortcut, sx + 3, ty, QuillTheme.TEXT_MUTED);
					rightLimit = sx - 6;
				}

				int labelX = rowRect.x() + 6;
				if (mode == Mode.FILES) {
					int iconW = QuillDraw.drawFileTypeIcon(graphics, font, entry.item().label(), labelX, rowRect.y(), ROW_HEIGHT);
					labelX += iconW + 5;
				}

				// Label with matched-char accent highlights
				String label = entry.item().label();
				int maxLabelW = Math.max(40, rightLimit - labelX);
				String clippedLabel = QuillDraw.clipToWidth(font, label, maxLabelW);
				drawHighlightedText(graphics, font, clippedLabel, labelX, ty,
						selected ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY,
						QuillTheme.SYNTAX_COORDINATE, entry.labelHighlightIndices());

				// Detail / parent path
				String detail = entry.item().detail();
				if (detail != null && !detail.isBlank()) {
					int dx = labelX + QuillDraw.textWidth(font, clippedLabel) + 8;
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
			int segX = x + (start == 0 ? 0 : QuillDraw.textWidth(font, text.substring(0, start)));
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
		if (inputRect.contains(event.x(), event.y())) {
			input.mouseClicked(event.x(), safeFont(), event.hasShiftDown());
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

	public boolean mouseDragged(MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && input.isDragging()) {
			input.mouseDragged(event.x(), safeFont());
		}
		return true;
	}

	public void mouseReleased(MouseButtonEvent event) {
		input.mouseReleased();
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

		SingleLineTextInput.KeyActionResult res = input.keyPressed(event);
		if (res.textChanged()) {
			rebuildFiltered();
		}
		return true;
	}

	public boolean charTyped(CharacterEvent event) {
		if (!input.charTyped(event)) {
			return false;
		}
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

	private static Font safeFont() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null ? mc.font : null;
	}
}
