package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.editor.SingleLineTextInput;
import dev.catgirlclaude.quill.client.editor.WorkspaceFileCache;
import dev.catgirlclaude.quill.client.editor.WorkspaceSearchEngine;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * VS Code-style Search &amp; Replace sidebar panel ({@code Ctrl+Shift+F}) scanning across all
 * datapack text files via {@link WorkspaceSearchEngine}, backed by {@link SingleLineTextInput}
 * for full text selection in query and replace boxes.
 */
public final class SearchSidebarWidget {
	private static final int ROW_HEIGHT = 15;

	public enum FocusedField {
		NONE,
		QUERY,
		REPLACE
	}

	private sealed interface ResultRow permits FileHeaderRow, MatchLineRow {
	}

	private record FileHeaderRow(WorkspaceSearchEngine.FileSearchResult file, IdeLayout.Rect rowRect, IdeLayout.Rect replaceFileBtn) implements ResultRow {
	}

	private record MatchLineRow(WorkspaceSearchEngine.LineMatch match, IdeLayout.Rect rowRect) implements ResultRow {
	}

	private final ClientEditorSession session;
	private final WorkspaceFileCache fileCache;
	private final Consumer<ModalPromptOverlay> modalOpener;
	private final Runnable modalCloser;

	private final SingleLineTextInput queryInput = new SingleLineTextInput();
	private final SingleLineTextInput replaceInput = new SingleLineTextInput();
	private boolean caseSensitive = false;
	private boolean regexMode = false;
	private FocusedField focusedField = FocusedField.NONE;

	private WorkspaceSearchEngine.SearchOutcome lastOutcome = new WorkspaceSearchEngine.SearchOutcome(List.of(), 0, null);
	private boolean searching = false;
	private long searchGeneration = 0L;
	private final Set<String> collapsedFiles = new HashSet<>();
	private final List<ResultRow> visibleRows = new ArrayList<>();
	private int scrollPixels = 0;

	private IdeLayout.Rect refreshBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect queryBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect caseBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect regexBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect replaceBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect replaceAllBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect resultsRect = new IdeLayout.Rect(0, 0, 0, 0);

	public SearchSidebarWidget(
			ClientEditorSession session,
			WorkspaceFileCache fileCache,
			Consumer<ModalPromptOverlay> modalOpener,
			Runnable modalCloser
	) {
		this.session = session;
		this.fileCache = fileCache;
		this.modalOpener = modalOpener;
		this.modalCloser = modalCloser;
	}

	public boolean hasFocusedTextInput() {
		return focusedField != FocusedField.NONE;
	}

	public FocusedField focusedField() {
		return focusedField;
	}

	public String queryText() {
		return queryInput.text();
	}

	public String replaceText() {
		return replaceInput.text();
	}

	public WorkspaceSearchEngine.SearchOutcome lastOutcome() {
		return lastOutcome;
	}

	public void unfocus() {
		this.focusedField = FocusedField.NONE;
		queryInput.mouseReleased();
		replaceInput.mouseReleased();
	}

	public void focusSearchInput(String initialSelection) {
		this.focusedField = FocusedField.QUERY;
		if (initialSelection != null && !initialSelection.isEmpty() && !initialSelection.contains("\n")) {
			queryInput.setText(initialSelection);
			queryInput.selectAll();
			runSearch();
		} else if (!queryInput.isEmpty()) {
			queryInput.selectAll();
			runSearch();
		}
	}

	public void refreshAndSearch() {
		fileCache.invalidate();
		runSearch();
	}

	public void runSearch() {
		String q = queryInput.text();
		long gen = ++searchGeneration;
		if (q.isEmpty()) {
			searching = false;
			lastOutcome = new WorkspaceSearchEngine.SearchOutcome(List.of(), 0, null);
			scrollPixels = 0;
			return;
		}
		searching = true;
		WorkspaceSearchEngine.SearchOptions opts = currentOptions();
		WorkspaceSearchEngine.searchWorkspace(session, opts, fileCache).whenComplete((outcome, error) -> session.runOnUi(() -> {
			if (gen != searchGeneration) {
				return;
			}
			searching = false;
			if (error != null) {
				lastOutcome = new WorkspaceSearchEngine.SearchOutcome(List.of(), 0, "Search failed: " + error.getMessage());
			} else {
				lastOutcome = outcome;
			}
			scrollPixels = 0;
		}));
	}

	private WorkspaceSearchEngine.SearchOptions currentOptions() {
		return new WorkspaceSearchEngine.SearchOptions(queryInput.text(), caseSensitive, regexMode);
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect area, int mouseX, int mouseY) {
		// Header
		QuillDraw.text(graphics, font, "SEARCH", area.x() + 6, area.y() + 5, QuillTheme.TEXT_MUTED);
		String refLabel = "Refresh";
		int refW = QuillDraw.textWidth(font, refLabel) + 8;
		refreshBtnRect = new IdeLayout.Rect(area.right() - refW - 5, area.y() + 2, refW, 14);
		boolean hoverRef = refreshBtnRect.contains(mouseX, mouseY);
		if (hoverRef) {
			QuillDraw.fill(graphics, refreshBtnRect.x(), refreshBtnRect.y(), refreshBtnRect.width(), refreshBtnRect.height(), QuillTheme.BG_HOVER);
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
		QuillDraw.text(graphics, font, refLabel, refreshBtnRect.x() + 4, area.y() + 5,
				hoverRef ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);
		QuillDraw.hLine(graphics, area.x(), area.width() - 1, area.y() + 18, QuillTheme.BORDER_SUBTLE);

		int pad = 6;
		int row1Y = area.y() + 23;
		int toggleW = 18;
		regexBtnRect = new IdeLayout.Rect(area.right() - pad - toggleW - 1, row1Y, toggleW, 16);
		caseBtnRect = new IdeLayout.Rect(regexBtnRect.x() - 2 - toggleW, row1Y, toggleW, 16);
		int queryW = Math.max(40, caseBtnRect.x() - 3 - (area.x() + pad));
		queryBoxRect = new IdeLayout.Rect(area.x() + pad, row1Y, queryW, 16);

		queryInput.render(graphics, font, queryBoxRect, "Search...", focusedField == FocusedField.QUERY);
		drawToggleBtn(graphics, font, caseBtnRect, "Aa", caseSensitive, caseBtnRect.contains(mouseX, mouseY));
		drawToggleBtn(graphics, font, regexBtnRect, ".*", regexMode, regexBtnRect.contains(mouseX, mouseY));

		int row2Y = row1Y + 20;
		String allLabel = "All";
		int allW = Math.max(24, QuillDraw.textWidth(font, allLabel) + 8);
		replaceAllBtnRect = new IdeLayout.Rect(area.right() - pad - allW - 1, row2Y, allW, 16);
		int replaceW = Math.max(40, replaceAllBtnRect.x() - 3 - (area.x() + pad));
		replaceBoxRect = new IdeLayout.Rect(area.x() + pad, row2Y, replaceW, 16);

		replaceInput.render(graphics, font, replaceBoxRect, "Replace...", focusedField == FocusedField.REPLACE);
		boolean canReplace = !searching && !lastOutcome.hasError() && lastOutcome.totalMatches() > 0;
		boolean hoverAll = canReplace && replaceAllBtnRect.contains(mouseX, mouseY);
		QuillDraw.fill(graphics, replaceAllBtnRect.x(), replaceAllBtnRect.y(), replaceAllBtnRect.width(), replaceAllBtnRect.height(),
				canReplace ? (hoverAll ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON) : QuillTheme.BG_BUTTON_SECONDARY);
		QuillDraw.text(graphics, font, allLabel,
				replaceAllBtnRect.x() + (replaceAllBtnRect.width() - QuillDraw.textWidth(font, allLabel)) / 2,
				replaceAllBtnRect.y() + (replaceAllBtnRect.height() - font.lineHeight) / 2,
				canReplace ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

		if (queryBoxRect.contains(mouseX, mouseY) || replaceBoxRect.contains(mouseX, mouseY)) {
			QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
		} else if (caseBtnRect.contains(mouseX, mouseY) || regexBtnRect.contains(mouseX, mouseY) || hoverAll) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}

		// Status summary
		int summaryY = row2Y + 20;
		String summaryText;
		int summaryColor = QuillTheme.TEXT_MUTED;
		if (searching) {
			summaryText = "Searching...";
		} else if (lastOutcome.hasError()) {
			summaryText = lastOutcome.errorMessage();
			summaryColor = QuillTheme.TEXT_ERROR;
		} else if (queryInput.isEmpty()) {
			summaryText = "Type to search workspace";
		} else if (lastOutcome.totalMatches() == 0) {
			summaryText = "No results found.";
		} else {
			summaryText = lastOutcome.totalMatches() + " result(s) in " + lastOutcome.files().size() + " file(s)";
		}
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, summaryText, area.width() - 12),
				area.x() + pad, summaryY, summaryColor);

		int listTop = summaryY + 13;
		QuillDraw.hLine(graphics, area.x(), area.width() - 1, listTop - 1, QuillTheme.BORDER_SUBTLE);
		resultsRect = new IdeLayout.Rect(area.x(), listTop, area.width() - 1, Math.max(0, area.bottom() - listTop));

		renderResults(graphics, font, mouseX, mouseY);
	}

	private void drawToggleBtn(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect rect, String label, boolean active, boolean hovered) {
		int bg = active ? QuillTheme.ACCENT_BLUE : (hovered ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);
		QuillDraw.fill(graphics, rect.x(), rect.y(), rect.width(), rect.height(), bg);
		int tx = rect.x() + (rect.width() - QuillDraw.textWidth(font, label)) / 2;
		int ty = rect.y() + (rect.height() - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, label, tx, ty, active ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);
	}

	private void renderResults(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		visibleRows.clear();
		if (resultsRect.height() <= 0 || lastOutcome.files().isEmpty()) {
			return;
		}

		int totalRows = 0;
		for (WorkspaceSearchEngine.FileSearchResult file : lastOutcome.files()) {
			totalRows++;
			if (!collapsedFiles.contains(file.relativePath())) {
				totalRows += file.matches().size();
			}
		}
		int maxScroll = Math.max(0, totalRows * ROW_HEIGHT - resultsRect.height() + 4);
		scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels));

		QuillDraw.beginClip(graphics, resultsRect);
		int y = resultsRect.y() + 2 - scrollPixels;

		for (WorkspaceSearchEngine.FileSearchResult file : lastOutcome.files()) {
			boolean collapsed = collapsedFiles.contains(file.relativePath());
			IdeLayout.Rect headerRect = new IdeLayout.Rect(resultsRect.x(), y, resultsRect.width(), ROW_HEIGHT);
			IdeLayout.Rect replaceBtn = new IdeLayout.Rect(headerRect.right() - 18, y + 1, 15, ROW_HEIGHT - 2);
			if (y + ROW_HEIGHT >= resultsRect.y() && y <= resultsRect.bottom()) {
				visibleRows.add(new FileHeaderRow(file, headerRect, replaceBtn));
				boolean hoverRow = resultsRect.contains(mouseX, mouseY) && headerRect.contains(mouseX, mouseY);
				boolean hoverRep = hoverRow && replaceBtn.contains(mouseX, mouseY);
				if (hoverRow) {
					QuillDraw.fill(graphics, headerRect.x(), headerRect.y(), headerRect.width(), headerRect.height(), QuillTheme.BG_HOVER);
					QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
				}

				int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
				String chevron = collapsed ? ">" : "v";
				String rel = file.relativePath();
				int slash = rel.lastIndexOf('/');
				String base = slash >= 0 ? rel.substring(slash + 1) : rel;
				String label = chevron + " " + base + " (" + file.matches().size() + ")";
				int maxW = Math.max(20, (hoverRow ? replaceBtn.x() : headerRect.right()) - (headerRect.x() + 6));
				int fileColor = rel.endsWith(".mcfunction") ? QuillTheme.TEXT_FUNCTION
						: (rel.endsWith(".json") || rel.endsWith(".mcmeta") ? QuillTheme.TEXT_JSON : QuillTheme.TEXT_PRIMARY);
				QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, label, maxW), headerRect.x() + 4, textY, fileColor);

				if (hoverRow) {
					QuillDraw.fill(graphics, replaceBtn.x(), replaceBtn.y(), replaceBtn.width(), replaceBtn.height(),
							hoverRep ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON_SECONDARY);
					QuillDraw.text(graphics, font, "R",
							replaceBtn.x() + (replaceBtn.width() - QuillDraw.textWidth(font, "R")) / 2, textY, QuillTheme.TEXT_BRIGHT);
				}
			}
			y += ROW_HEIGHT;

			if (!collapsed) {
				for (WorkspaceSearchEngine.LineMatch match : file.matches()) {
					if (y + ROW_HEIGHT >= resultsRect.y() && y <= resultsRect.bottom()) {
						IdeLayout.Rect matchRect = new IdeLayout.Rect(resultsRect.x(), y, resultsRect.width(), ROW_HEIGHT);
						visibleRows.add(new MatchLineRow(match, matchRect));
						boolean hoverMatch = resultsRect.contains(mouseX, mouseY) && matchRect.contains(mouseX, mouseY);
						if (hoverMatch) {
							QuillDraw.fill(graphics, matchRect.x(), matchRect.y(), matchRect.width(), matchRect.height(), QuillTheme.BG_HOVER);
							QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
						}
						renderMatchLine(graphics, font, match, matchRect);
					}
					y += ROW_HEIGHT;
				}
			}
		}
		QuillDraw.endClip(graphics);
	}

	private static void renderMatchLine(
			GuiGraphicsExtractor graphics,
			Font font,
			WorkspaceSearchEngine.LineMatch match,
			IdeLayout.Rect rect
	) {
		int textY = rect.y() + (ROW_HEIGHT - font.lineHeight) / 2;
		String linePrefix = match.oneBasedLine() + ": ";
		int prefixX = rect.x() + 10;
		QuillDraw.text(graphics, font, linePrefix, prefixX, textY, QuillTheme.TEXT_MUTED);
		int snippetX = prefixX + QuillDraw.textWidth(font, linePrefix);
		int availW = Math.max(10, rect.right() - snippetX - 4);

		String rawLine = match.lineText();
		int trimLead = 0;
		while (trimLead < rawLine.length() && Character.isWhitespace(rawLine.charAt(trimLead))) {
			trimLead++;
		}
		int matchStart = Math.max(0, match.startCol() - trimLead);
		int matchEnd = Math.max(matchStart, match.endCol() - trimLead);
		String trimmed = rawLine.substring(trimLead);

		int windowStart = 0;
		if (matchStart > 14) {
			windowStart = Math.max(0, matchStart - 10);
		}
		String prefixEllipsis = windowStart > 0 ? "..." : "";
		String view = prefixEllipsis + trimmed.substring(Math.min(windowStart, trimmed.length()));
		int adjStart = prefixEllipsis.length() + Math.max(0, matchStart - windowStart);
		int adjEnd = prefixEllipsis.length() + Math.max(0, matchEnd - windowStart);

		String clipped = QuillDraw.clipToWidth(font, view, availW);
		if (adjStart < clipped.length()) {
			int hlEnd = Math.min(clipped.length(), adjEnd);
			if (hlEnd > adjStart) {
				int hx = snippetX + QuillDraw.textWidth(font, clipped.substring(0, adjStart));
				int hw = Math.max(2, QuillDraw.textWidth(font, clipped.substring(adjStart, hlEnd)));
				QuillDraw.fill(graphics, hx, rect.y() + 2, hw, ROW_HEIGHT - 4, QuillTheme.MATCH_HIGHLIGHT);
			}
		}
		QuillDraw.text(graphics, font, clipped, snippetX, textY, QuillTheme.TEXT_PRIMARY);
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		if (refreshBtnRect.contains(mx, my)) {
			refreshAndSearch();
			return true;
		}
		if (queryBoxRect.contains(mx, my)) {
			focusedField = FocusedField.QUERY;
			queryInput.mouseClicked(mx, safeFont(), event.hasShiftDown());
			return true;
		}
		if (replaceBoxRect.contains(mx, my)) {
			focusedField = FocusedField.REPLACE;
			replaceInput.mouseClicked(mx, safeFont(), event.hasShiftDown());
			return true;
		}
		if (caseBtnRect.contains(mx, my)) {
			caseSensitive = !caseSensitive;
			runSearch();
			return true;
		}
		if (regexBtnRect.contains(mx, my)) {
			regexMode = !regexMode;
			runSearch();
			return true;
		}
		if (replaceAllBtnRect.contains(mx, my) && !searching && !lastOutcome.hasError() && lastOutcome.totalMatches() > 0) {
			promptReplaceAll();
			return true;
		}
		if (resultsRect.contains(mx, my)) {
			for (ResultRow row : visibleRows) {
				if (row instanceof FileHeaderRow fhr && fhr.rowRect().contains(mx, my)) {
					if (fhr.replaceFileBtn().contains(mx, my)) {
						promptReplaceInFile(fhr.file());
					} else {
						String path = fhr.file().relativePath();
						if (!collapsedFiles.add(path)) {
							collapsedFiles.remove(path);
						}
					}
					return true;
				}
				if (row instanceof MatchLineRow mlr && mlr.rowRect().contains(mx, my)) {
					unfocus();
					WorkspaceSearchEngine.LineMatch m = mlr.match();
					session.openFileAndSelect(m.relativePath(), m.oneBasedLine(), m.startCol(), m.endCol());
					return true;
				}
			}
		}
		return false;
	}

	public boolean mouseDragged(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (focusedField == FocusedField.QUERY && queryInput.isDragging()) {
			return queryInput.mouseDragged(event.x(), safeFont());
		}
		if (focusedField == FocusedField.REPLACE && replaceInput.isDragging()) {
			return replaceInput.mouseDragged(event.x(), safeFont());
		}
		return false;
	}

	public void mouseReleased(MouseButtonEvent event) {
		queryInput.mouseReleased();
		replaceInput.mouseReleased();
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!resultsRect.contains(mouseX, mouseY) || scrollY == 0) {
			return false;
		}
		scrollPixels = Math.max(0, scrollPixels - (int) (scrollY * ROW_HEIGHT * 2));
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		if (focusedField == FocusedField.NONE) {
			return false;
		}
		if (event.isEscape()) {
			unfocus();
			return true;
		}
		if (event.isCycleFocus()) {
			focusedField = focusedField == FocusedField.QUERY ? FocusedField.REPLACE : FocusedField.QUERY;
			return true;
		}
		if (event.isConfirmation()) {
			if (focusedField == FocusedField.QUERY) {
				runSearch();
			} else if (lastOutcome.totalMatches() > 0 && !lastOutcome.hasError()) {
				promptReplaceAll();
			}
			return true;
		}

		SingleLineTextInput active = activeInput();
		SingleLineTextInput.KeyActionResult res = active.keyPressed(event);
		if (res.textChanged() && focusedField == FocusedField.QUERY) {
			runSearch();
		}
		return res.handled();
	}

	public boolean charTyped(CharacterEvent event) {
		if (focusedField == FocusedField.NONE) {
			return false;
		}
		boolean changed = activeInput().charTyped(event);
		if (changed && focusedField == FocusedField.QUERY) {
			runSearch();
		}
		return changed;
	}

	private void promptReplaceAll() {
		int total = lastOutcome.totalMatches();
		int fileCount = lastOutcome.files().size();
		List<String> paths = lastOutcome.files().stream()
				.map(WorkspaceSearchEngine.FileSearchResult::relativePath)
				.toList();
		WorkspaceSearchEngine.SearchOptions opts = currentOptions();
		String rep = replaceInput.text();

		modalOpener.accept(ModalPromptOverlay.confirm(
				"Replace Across Files",
				"Replace " + total + " occurrence(s) across " + fileCount + " file(s) with '" + rep + "'?",
				"Replace All",
				true,
				() -> {
					modalCloser.run();
					WorkspaceSearchEngine.replaceInFiles(session, paths, opts, rep, fileCache)
							.whenComplete((sum, err) -> session.runOnUi(this::runSearch));
				},
				modalCloser
		));
	}

	private void promptReplaceInFile(WorkspaceSearchEngine.FileSearchResult file) {
		WorkspaceSearchEngine.SearchOptions opts = currentOptions();
		String rep = replaceInput.text();
		modalOpener.accept(ModalPromptOverlay.confirm(
				"Replace In File",
				"Replace " + file.matches().size() + " occurrence(s) in '" + file.relativePath() + "'?",
				"Replace",
				false,
				() -> {
					modalCloser.run();
					WorkspaceSearchEngine.replaceInFiles(session, List.of(file.relativePath()), opts, rep, fileCache)
							.whenComplete((sum, err) -> session.runOnUi(this::runSearch));
				},
				modalCloser
		));
	}

	private SingleLineTextInput activeInput() {
		return focusedField == FocusedField.REPLACE ? replaceInput : queryInput;
	}

	private static Font safeFont() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null ? mc.font : null;
	}
}
