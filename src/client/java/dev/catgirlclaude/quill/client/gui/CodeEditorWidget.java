package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.brigadier.CommandDispatcher;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.editor.BrigadierAssistService;
import dev.catgirlclaude.quill.client.editor.SyntaxHighlighter;
import dev.catgirlclaude.quill.client.editor.TextDocumentBuffer;
import dev.catgirlclaude.quill.client.session.OpenTabState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Multi-line code editor widget with dynamic VS Code syntax highlighting, customizable font
 * and text size scaling, live Brigadier command autocomplete &amp; hover signatures,
 * bracket-pair matching, mouse click/double-click/triple-click/gutter/drag selection,
 * {@code Shift+Arrows}, {@code Ctrl+Shift+Arrows}, {@code Ctrl+Backspace/Delete},
 * {@code Tab}/{@code Shift+Tab} indent/unindent, clipboard, undo/redo, and 2D scrolling.
 */
public final class CodeEditorWidget {
	private static final int LINE_PADDING_Y = 2;
	private static final int CODE_LEFT_PADDING = 6;
	private static final int SCROLLBAR_SIZE = 5;
	private static final int MAX_VISIBLE_SUGGESTIONS = 8;
	private static final int SUGGESTION_ROW_HEIGHT = 13;
	private static final long MULTI_CLICK_WINDOW_MS = 400L;

	private enum DragMode {
		NONE,
		CHAR,
		WORD,
		LINE
	}

	private IdeLayout.Rect lastEditorRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect lastGutterRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect lastCodeViewport = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect lastPopupRect = new IdeLayout.Rect(0, 0, 0, 0);
	private final List<IdeLayout.Rect> suggestionRowRects = new ArrayList<>();

	private DragMode dragMode = DragMode.NONE;
	private long lastClickMs = 0L;
	private TextDocumentBuffer.Position lastClickPos = null;
	private int clickCount = 0;
	private int wordAnchorLine = 0;
	private int wordAnchorStartCol = 0;
	private int wordAnchorEndCol = 0;
	private int lineAnchor = 0;
	private long lastCaretActivityMs = System.currentTimeMillis();

	private Consumer<OpenTabState> onBufferEdited = tab -> {
	};

	// Brigadier autocomplete & hover state
	private boolean autocompleteOpen = false;
	private int selectedSuggestionIndex = 0;
	private int suggestionScroll = 0;
	private long assistGeneration = 0L;
	private String lastCaretTabPath = "";
	private BrigadierAssistService.AssistResult currentAssist = BrigadierAssistService.AssistResult.EMPTY;

	private String lastHoverKey = "";
	private List<String> cachedHoverSignatures = List.of();
	private int lastHoverLine = -1;

	public void setOnBufferEdited(Consumer<OpenTabState> onBufferEdited) {
		this.onBufferEdited = onBufferEdited != null ? onBufferEdited : tab -> {
		};
	}

	public boolean isAutocompleteOpen() {
		return autocompleteOpen && !currentAssist.suggestions().isEmpty();
	}

	public void dismissAutocomplete() {
		autocompleteOpen = false;
		selectedSuggestionIndex = 0;
		suggestionScroll = 0;
		lastPopupRect = new IdeLayout.Rect(0, 0, 0, 0);
		suggestionRowRects.clear();
	}

	private static float textScale() {
		return QuillClientConfig.current().editorTextScale();
	}

	private static int scaledLineHeight(Font font, float scale) {
		int base = font != null ? font.lineHeight : 9;
		return Math.max(8, Math.round((base + LINE_PADDING_Y) * scale));
	}

	private void touchCaret() {
		lastCaretActivityMs = System.currentTimeMillis();
	}

	private boolean isCaretVisible() {
		long elapsed = System.currentTimeMillis() - lastCaretActivityMs;
		if (elapsed < 450L) {
			return true;
		}
		return ((elapsed / 500L) % 2L) == 0L;
	}

	public void render(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect editorRect,
			OpenTabState tab,
			int mouseX,
			int mouseY
	) {
		lastEditorRect = editorRect;
		if (!tab.relativePath().equals(lastCaretTabPath)) {
			lastCaretTabPath = tab.relativePath();
			dismissAutocomplete();
			currentAssist = BrigadierAssistService.AssistResult.EMPTY;
			lastHoverKey = "";
			cachedHoverSignatures = List.of();
		}

		float scale = textScale();
		TextDocumentBuffer buffer = tab.buffer();
		int lineHeight = scaledLineHeight(font, scale);
		int gutterWidth = Math.max(30, QuillDraw.textWidth(font, String.valueOf(Math.max(1, buffer.lineCount())), scale) + 14);
		int codeX = editorRect.x() + gutterWidth;
		int codeW = Math.max(1, editorRect.width() - gutterWidth - SCROLLBAR_SIZE);
		int codeH = Math.max(1, editorRect.height() - SCROLLBAR_SIZE);
		lastGutterRect = new IdeLayout.Rect(editorRect.x(), editorRect.y(), gutterWidth, codeH);
		lastCodeViewport = new IdeLayout.Rect(codeX, editorRect.y(), codeW, codeH);

		if (tab.consumeScrollToCaret()) {
			ensureCaretVisible(font, tab);
		}

		int visibleLines = Math.max(1, codeH / lineHeight);
		int maxScrollLine = Math.max(0, buffer.lineCount() - visibleLines);
		tab.setScrollLine(Math.min(maxScrollLine, tab.scrollLine()));

		int maxLineWidth = Math.max(
				computeMaxLineWidth(font, buffer, tab.scrollLine(), visibleLines, scale),
				QuillDraw.textWidth(font, buffer.line(buffer.cursor().line()), scale)
		);
		int maxScrollX = Math.max(0, maxLineWidth - codeW + 24);
		tab.setScrollX(Math.min(maxScrollX, tab.scrollX()));

		// Gutter background
		QuillDraw.fill(graphics, editorRect.x(), editorRect.y(), gutterWidth, editorRect.height(), QuillTheme.BG_GUTTER);
		QuillDraw.vLine(graphics, codeX - 1, editorRect.y(), editorRect.height(), QuillTheme.BORDER_SUBTLE);

		boolean hoveringPopup = isAutocompleteOpen() && lastPopupRect.contains(mouseX, mouseY);
		if (lastCodeViewport.contains(mouseX, mouseY) && !hoveringPopup) {
			QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
		} else if (lastGutterRect.contains(mouseX, mouseY) && !hoveringPopup) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}

		int firstLine = tab.scrollLine();
		int lastLine = Math.min(buffer.lineCount() - 1, firstLine + visibleLines);
		TextDocumentBuffer.Position cursor = buffer.cursor();
		boolean hasSel = buffer.hasSelection();
		TextDocumentBuffer.Position selStart = buffer.selectionStart();
		TextDocumentBuffer.Position selEnd = buffer.selectionEnd();
		TextDocumentBuffer.BracketMatch bracketMatch = buffer.findMatchingBracket();

		QuillDraw.beginClip(graphics, new IdeLayout.Rect(editorRect.x(), editorRect.y(), editorRect.width(), codeH));
		for (int i = firstLine; i <= lastLine; i++) {
			int rowY = editorRect.y() + 4 + (i - firstLine) * lineHeight;
			boolean isCurrentLine = i == cursor.line();

			if (isCurrentLine) {
				QuillDraw.fill(graphics, codeX, rowY - 1, codeW, lineHeight, QuillTheme.LINE_HIGHLIGHT);
			}

			String lineNumStr = String.valueOf(i + 1);
			int numX = codeX - 6 - QuillDraw.textWidth(font, lineNumStr, scale);
			QuillDraw.textScaled(graphics, font, lineNumStr, numX, rowY,
					isCurrentLine ? QuillTheme.TEXT_PRIMARY : QuillTheme.TEXT_MUTED, scale);
		}
		QuillDraw.endClip(graphics);

		SyntaxHighlighter.Language language = SyntaxHighlighter.languageForPath(tab.relativePath());
		int caretScreenX = codeX + CODE_LEFT_PADDING;
		int caretScreenY = editorRect.y() + 4;

		QuillDraw.beginClip(graphics, lastCodeViewport);
		for (int i = firstLine; i <= lastLine; i++) {
			int rowY = editorRect.y() + 4 + (i - firstLine) * lineHeight;
			String lineText = buffer.line(i);
			int drawX = codeX + CODE_LEFT_PADDING - tab.scrollX();

			// Selection highlight for this line
			if (hasSel && i >= selStart.line() && i <= selEnd.line()) {
				int startCol = (i == selStart.line()) ? selStart.column() : 0;
				int endCol = (i == selEnd.line()) ? selEnd.column() : lineText.length();
				int x1 = drawX + QuillDraw.textWidth(font, lineText.substring(0, Math.min(startCol, lineText.length())), scale);
				int x2 = drawX + QuillDraw.textWidth(font, lineText.substring(0, Math.min(endCol, lineText.length())), scale);
				if (i < selEnd.line() && endCol == lineText.length()) {
					x2 += Math.max(5, Math.round(6 * scale));
				}
				if (x2 > x1) {
					QuillDraw.fill(graphics, x1, rowY - 1, x2 - x1, lineHeight, QuillTheme.SELECTION_BG);
				}
			}

			// Matching bracket pair highlight
			if (bracketMatch != null) {
				drawBracketHighlightIfOnLine(graphics, font, lineText, i, drawX, rowY, lineHeight, scale, bracketMatch.open());
				drawBracketHighlightIfOnLine(graphics, font, lineText, i, drawX, rowY, lineHeight, scale, bracketMatch.close());
			}

			if (language == SyntaxHighlighter.Language.PLAIN || lineText.isEmpty()) {
				QuillDraw.textScaled(graphics, font, lineText, drawX, rowY, QuillTheme.SYNTAX_PLAIN, scale);
			} else {
				List<SyntaxHighlighter.TokenSpan> spans = SyntaxHighlighter.tokenizeLine(lineText, language);
				for (SyntaxHighlighter.TokenSpan span : spans) {
					String segment = lineText.substring(span.start(), span.end());
					int segX = drawX + (span.start() == 0 ? 0 : QuillDraw.textWidth(font, lineText.substring(0, span.start()), scale));
					QuillDraw.textScaled(graphics, font, segment, segX, rowY, span.color(), scale);
				}
			}

			if (i == cursor.line()) {
				int col = Math.min(cursor.column(), lineText.length());
				caretScreenX = drawX + QuillDraw.textWidth(font, lineText.substring(0, col), scale);
				caretScreenY = rowY;
				if (isCaretVisible()) {
					QuillDraw.fill(graphics, caretScreenX, rowY - 1, 1, lineHeight, QuillTheme.CURSOR_COLOR);
				}
			}
		}
		QuillDraw.endClip(graphics);

		// Vertical scrollbar
		if (maxScrollLine > 0 && codeH > 12) {
			int trackX = editorRect.right() - SCROLLBAR_SIZE;
			int thumbH = Math.max(14, (int) ((long) codeH * visibleLines / buffer.lineCount()));
			int thumbY = editorRect.y() + (int) ((long) tab.scrollLine() * (codeH - thumbH) / maxScrollLine);
			QuillDraw.fill(graphics, trackX, editorRect.y(), SCROLLBAR_SIZE, codeH, QuillTheme.BG_SIDEBAR);
			QuillDraw.fill(graphics, trackX + 1, thumbY, SCROLLBAR_SIZE - 2, thumbH, QuillTheme.TEXT_MUTED);
		}

		// Horizontal scrollbar
		if (maxScrollX > 0 && codeW > 12) {
			int trackY = editorRect.bottom() - SCROLLBAR_SIZE;
			int totalW = codeW + maxScrollX;
			int thumbW = Math.max(16, (int) ((long) codeW * codeW / totalW));
			int thumbX = codeX + (int) ((long) tab.scrollX() * (codeW - thumbW) / maxScrollX);
			QuillDraw.fill(graphics, codeX, trackY, codeW, SCROLLBAR_SIZE, QuillTheme.BG_SIDEBAR);
			QuillDraw.fill(graphics, thumbX, trackY + 1, thumbW, SCROLLBAR_SIZE - 2, QuillTheme.TEXT_MUTED);
		}

		// Brigadier autocomplete popup & hover signature tooltips (.mcfunction only)
		if (language == SyntaxHighlighter.Language.MCFUNCTION) {
			if (isAutocompleteOpen()) {
				renderAutocompletePopup(graphics, font, editorRect, caretScreenX, caretScreenY, lineHeight, mouseX, mouseY);
			} else {
				lastPopupRect = new IdeLayout.Rect(0, 0, 0, 0);
				suggestionRowRects.clear();
				renderHoverOrUsageTooltip(graphics, font, editorRect, tab, lineHeight, mouseX, mouseY);
			}
		} else {
			lastPopupRect = new IdeLayout.Rect(0, 0, 0, 0);
			suggestionRowRects.clear();
		}
	}

	private static void drawBracketHighlightIfOnLine(
			GuiGraphicsExtractor graphics,
			Font font,
			String lineText,
			int lineIndex,
			int drawX,
			int rowY,
			int lineHeight,
			float scale,
			TextDocumentBuffer.Position pos
	) {
		if (pos == null || pos.line() != lineIndex || pos.column() < 0 || pos.column() >= lineText.length()) {
			return;
		}
		int bx = drawX + QuillDraw.textWidth(font, lineText.substring(0, pos.column()), scale);
		int bw = Math.max(4, QuillDraw.textWidth(font, lineText.substring(pos.column(), pos.column() + 1), scale));
		QuillDraw.fill(graphics, bx, rowY - 1, bw, lineHeight, QuillTheme.BRACKET_MATCH_BG);
		QuillDraw.outline(graphics, bx, rowY - 1, bw, lineHeight, QuillTheme.BORDER_SUBTLE);
	}

	private void renderAutocompletePopup(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect editorRect,
			int caretX,
			int caretY,
			int lineHeight,
			int mouseX,
			int mouseY
	) {
		List<BrigadierAssistService.SuggestionEntry> list = currentAssist.suggestions();
		if (list.isEmpty()) {
			lastPopupRect = new IdeLayout.Rect(0, 0, 0, 0);
			suggestionRowRects.clear();
			return;
		}

		selectedSuggestionIndex = Math.max(0, Math.min(list.size() - 1, selectedSuggestionIndex));
		int visibleCount = Math.min(MAX_VISIBLE_SUGGESTIONS, list.size());
		if (selectedSuggestionIndex < suggestionScroll) {
			suggestionScroll = selectedSuggestionIndex;
		} else if (selectedSuggestionIndex >= suggestionScroll + visibleCount) {
			suggestionScroll = selectedSuggestionIndex - visibleCount + 1;
		}
		suggestionScroll = Math.max(0, Math.min(Math.max(0, list.size() - visibleCount), suggestionScroll));

		List<String> usages = currentAssist.usageSignatures();
		boolean showUsageHeader = !usages.isEmpty();
		int usageHeaderH = showUsageHeader ? 14 : 0;

		int contentW = 140;
		for (int i = suggestionScroll; i < Math.min(list.size(), suggestionScroll + visibleCount); i++) {
			BrigadierAssistService.SuggestionEntry e = list.get(i);
			int rowW = QuillDraw.textWidth(font, e.text()) + 16;
			if (e.tooltip() != null && !e.tooltip().isBlank()) {
				rowW += Math.min(120, QuillDraw.textWidth(font, " - " + e.tooltip()));
			}
			contentW = Math.max(contentW, rowW);
		}
		if (showUsageHeader) {
			contentW = Math.max(contentW, Math.min(280, QuillDraw.textWidth(font, usages.getFirst()) + 14));
		}
		int popupW = Math.min(Math.max(120, editorRect.width() - 16), Math.min(300, contentW));
		int popupH = usageHeaderH + visibleCount * SUGGESTION_ROW_HEIGHT + 4;

		int px = Math.max(editorRect.x() + 4, Math.min(caretX, editorRect.right() - popupW - 4));
		int py = caretY + lineHeight + 2;
		if (py + popupH > editorRect.bottom() - 4) {
			py = Math.max(editorRect.y() + 4, caretY - popupH - 2);
		}

		lastPopupRect = new IdeLayout.Rect(px, py, popupW, popupH);
		QuillDraw.fill(graphics, px, py, popupW, popupH, QuillTheme.BG_POPUP);
		QuillDraw.outline(graphics, px, py, popupW, popupH, QuillTheme.ACCENT_FOCUS);

		int y = py + 2;
		if (showUsageHeader) {
			QuillDraw.fill(graphics, px + 1, py + 1, popupW - 2, usageHeaderH - 1, QuillTheme.BG_TAB_INACTIVE);
			QuillDraw.hLine(graphics, px + 1, popupW - 2, py + usageHeaderH, QuillTheme.BORDER_SUBTLE);
			String sig = QuillDraw.clipToWidth(font, usages.getFirst(), popupW - 10);
			QuillDraw.text(graphics, font, sig, px + 5, py + 3, QuillTheme.SYNTAX_PROPERTY);
			y = py + usageHeaderH + 2;
		}

		suggestionRowRects.clear();
		for (int i = 0; i < visibleCount; i++) {
			int idx = suggestionScroll + i;
			BrigadierAssistService.SuggestionEntry entry = list.get(idx);
			IdeLayout.Rect rowRect = new IdeLayout.Rect(px + 2, y, popupW - 4, SUGGESTION_ROW_HEIGHT);
			suggestionRowRects.add(rowRect);

			boolean isSelected = idx == selectedSuggestionIndex;
			boolean isHovered = rowRect.contains(mouseX, mouseY);
			if (isSelected) {
				QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.BG_POPUP_SELECTED);
			} else if (isHovered) {
				QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.BG_HOVER);
			}
			if (isHovered) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}

			int ty = rowRect.y() + (SUGGESTION_ROW_HEIGHT - font.lineHeight) / 2;
			String label = entry.text();
			String tip = (entry.tooltip() != null && !entry.tooltip().isBlank()) ? "  " + entry.tooltip() : "";
			int labelW = QuillDraw.textWidth(font, label);
			int maxLabelW = rowRect.width() - 8;
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, label, maxLabelW),
					rowRect.x() + 4, ty, isSelected ? QuillTheme.TEXT_BRIGHT : QuillTheme.SYNTAX_COMMAND);
			if (!tip.isEmpty() && labelW + 14 < maxLabelW) {
				QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, tip, maxLabelW - labelW),
						rowRect.x() + 4 + labelW, ty, QuillTheme.TEXT_MUTED);
			}
			y += SUGGESTION_ROW_HEIGHT;
		}
	}

	private void renderHoverOrUsageTooltip(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect editorRect,
			OpenTabState tab,
			int lineHeight,
			int mouseX,
			int mouseY
	) {
		if (!lastCodeViewport.contains(mouseX, mouseY)) {
			return;
		}
		TextDocumentBuffer buffer = tab.buffer();
		int hoverLine = tab.scrollLine() + Math.max(0, (mouseY - (lastCodeViewport.y() + 4)) / lineHeight);
		if (hoverLine < 0 || hoverLine >= buffer.lineCount()) {
			return;
		}
		String lineText = buffer.line(hoverLine);
		if (lineText.isBlank()) {
			return;
		}
		TextDocumentBuffer.Position hoverPos = hitTest(font, tab, mouseX, mouseY);
		String hoverKey = tab.relativePath() + "|" + hoverLine + "|" + hoverPos.column() + "|" + lineText;
		if (!hoverKey.equals(lastHoverKey)) {
			lastHoverKey = hoverKey;
			lastHoverLine = hoverLine;
			cachedHoverSignatures = queryLiveHoverSignatures(lineText, hoverPos.column());
		}

		if (cachedHoverSignatures.isEmpty() || lastHoverLine != hoverLine) {
			return;
		}
		int maxLines = Math.min(2, cachedHoverSignatures.size());
		int maxW = 0;
		for (int i = 0; i < maxLines; i++) {
			maxW = Math.max(maxW, QuillDraw.textWidth(font, cachedHoverSignatures.get(i)));
		}
		int boxW = Math.min(Math.max(80, editorRect.width() - 20), maxW + 12);
		int boxH = maxLines * 11 + 6;
		int rowY = editorRect.y() + 4 + (hoverLine - tab.scrollLine()) * lineHeight;
		int bx = Math.max(editorRect.x() + 6, Math.min(mouseX + 8, editorRect.right() - boxW - 6));
		int by = rowY - boxH - 3;
		if (by < editorRect.y() + 2) {
			by = Math.min(editorRect.bottom() - boxH - 4, rowY + lineHeight + 3);
		}
		QuillDraw.fill(graphics, bx, by, boxW, boxH, QuillTheme.BG_POPUP);
		QuillDraw.outline(graphics, bx, by, boxW, boxH, QuillTheme.BORDER_SUBTLE);
		for (int i = 0; i < maxLines; i++) {
			String text = QuillDraw.clipToWidth(font, cachedHoverSignatures.get(i), boxW - 8);
			QuillDraw.text(graphics, font, text, bx + 4, by + 3 + i * 11, QuillTheme.SYNTAX_PROPERTY);
		}
	}

	public boolean mouseClicked(MouseButtonEvent event, Font font, OpenTabState tab) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (isAutocompleteOpen() && lastPopupRect.contains(event.x(), event.y())) {
			for (int i = 0; i < suggestionRowRects.size(); i++) {
				if (suggestionRowRects.get(i).contains(event.x(), event.y())) {
					selectedSuggestionIndex = suggestionScroll + i;
					acceptSelectedSuggestion(font, tab);
					return true;
				}
			}
			return true;
		}
		dismissAutocomplete();

		// Gutter click selects entire line
		if (lastGutterRect.contains(event.x(), event.y())) {
			int lineIndex = hitTestLine(font, tab, event.y());
			if (event.hasShiftDown()) {
				tab.buffer().extendLineSelection(tab.buffer().cursor().line(), lineIndex);
			} else {
				tab.buffer().selectLine(lineIndex);
			}
			lineAnchor = lineIndex;
			dragMode = DragMode.LINE;
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}

		if (!lastCodeViewport.contains(event.x(), event.y())) {
			return false;
		}
		TextDocumentBuffer.Position pos = hitTest(font, tab, event.x(), event.y());
		long now = System.currentTimeMillis();
		boolean shift = event.hasShiftDown();
		if (!shift && lastClickPos != null && now - lastClickMs <= MULTI_CLICK_WINDOW_MS
				&& lastClickPos.line() == pos.line() && Math.abs(lastClickPos.column() - pos.column()) <= 1) {
			clickCount = (clickCount % 3) + 1;
		} else {
			clickCount = 1;
		}
		lastClickMs = now;
		lastClickPos = pos;

		if (shift) {
			tab.buffer().setCursor(pos.line(), pos.column(), true);
			dragMode = DragMode.CHAR;
		} else if (clickCount == 2) {
			int[] bounds = tab.buffer().wordBoundsAt(pos.line(), pos.column());
			wordAnchorLine = pos.line();
			wordAnchorStartCol = bounds[0];
			wordAnchorEndCol = bounds[1];
			tab.buffer().selectWordAt(pos.line(), pos.column());
			dragMode = DragMode.WORD;
		} else if (clickCount == 3) {
			lineAnchor = pos.line();
			tab.buffer().selectLine(pos.line());
			dragMode = DragMode.LINE;
		} else {
			tab.buffer().setCursor(pos.line(), pos.column(), false);
			dragMode = DragMode.CHAR;
		}
		touchCaret();
		ensureCaretVisible(font, tab);
		return true;
	}

	public boolean mouseDragged(MouseButtonEvent event, Font font, OpenTabState tab) {
		if (dragMode == DragMode.NONE || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		// Auto-scroll gently when dragging outside viewport edges
		if (event.y() < lastCodeViewport.y()) {
			tab.setScrollLine(Math.max(0, tab.scrollLine() - 1));
		} else if (event.y() > lastCodeViewport.bottom()) {
			int lineHeight = scaledLineHeight(font, textScale());
			int visibleLines = Math.max(1, lastCodeViewport.height() / lineHeight);
			int maxScroll = Math.max(0, tab.buffer().lineCount() - visibleLines);
			tab.setScrollLine(Math.min(maxScroll, tab.scrollLine() + 1));
		}

		if (dragMode == DragMode.LINE) {
			int lineIndex = hitTestLine(font, tab, event.y());
			tab.buffer().extendLineSelection(lineAnchor, lineIndex);
		} else if (dragMode == DragMode.WORD) {
			TextDocumentBuffer.Position pos = hitTest(font, tab, event.x(), event.y());
			tab.buffer().extendWordSelection(wordAnchorLine, wordAnchorStartCol, wordAnchorEndCol, pos.line(), pos.column());
		} else {
			TextDocumentBuffer.Position pos = hitTest(font, tab, event.x(), event.y());
			tab.buffer().setCursor(pos.line(), pos.column(), true);
		}
		touchCaret();
		ensureCaretVisible(font, tab);
		return true;
	}

	public void mouseReleased(MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			dragMode = DragMode.NONE;
		}
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY, Font font, OpenTabState tab) {
		if (isAutocompleteOpen() && lastPopupRect.contains(mouseX, mouseY)) {
			int total = currentAssist.suggestions().size();
			int maxScroll = Math.max(0, total - Math.min(MAX_VISIBLE_SUGGESTIONS, total));
			if (scrollY != 0) {
				suggestionScroll = Math.max(0, Math.min(maxScroll, suggestionScroll - (int) Math.signum(scrollY) * 2));
			}
			return true;
		}
		if (!lastEditorRect.contains(mouseX, mouseY)) {
			return false;
		}
		int lineHeight = scaledLineHeight(font, textScale());
		int visibleLines = Math.max(1, lastCodeViewport.height() / lineHeight);
		int maxScrollLine = Math.max(0, tab.buffer().lineCount() - visibleLines);
		if (scrollY != 0) {
			tab.setScrollLine(Math.min(maxScrollLine, tab.scrollLine() - (int) Math.signum(scrollY) * 3));
		}
		if (scrollX != 0) {
			tab.setScrollX(tab.scrollX() - (int) (scrollX * 24));
		}
		return true;
	}

	public boolean keyPressed(KeyEvent event, Font font, OpenTabState tab) {
		TextDocumentBuffer buffer = tab.buffer();
		boolean shift = event.hasShiftDown();
		boolean ctrl = event.hasControlDownWithQuirk();

		// Configurable autocomplete shortcut (default Ctrl+Space)
		if (QuillClientConfig.current().shortcut(QuillClientConfig.ShortcutAction.TRIGGER_AUTOCOMPLETE).matches(event)) {
			triggerAutocomplete(tab, true);
			return true;
		}

		// Autocomplete popup navigation takes precedence when open
		if (isAutocompleteOpen()) {
			if (event.isEscape()) {
				dismissAutocomplete();
				return true;
			}
			if (event.isUp()) {
				int size = currentAssist.suggestions().size();
				selectedSuggestionIndex = (selectedSuggestionIndex - 1 + size) % size;
				return true;
			}
			if (event.isDown()) {
				int size = currentAssist.suggestions().size();
				selectedSuggestionIndex = (selectedSuggestionIndex + 1) % size;
				return true;
			}
			if (event.isCycleFocus() || event.isConfirmation()) {
				acceptSelectedSuggestion(font, tab);
				return true;
			}
		}

		if (event.isSelectAll()) {
			buffer.selectAll();
			dismissAutocomplete();
			touchCaret();
			return true;
		}
		if (event.isCopy()) {
			String selected = buffer.getSelectedText();
			if (!selected.isEmpty()) {
				setClipboardSafe(selected);
			}
			return true;
		}
		if (event.isCut()) {
			String selected = buffer.getSelectedText();
			if (!selected.isEmpty()) {
				setClipboardSafe(selected);
				buffer.deleteSelection();
				dismissAutocomplete();
				touchCaret();
				ensureCaretVisible(font, tab);
				onBufferEdited.accept(tab);
			}
			return true;
		}
		if (event.isPaste()) {
			String clip = getClipboardSafe();
			if (clip != null && !clip.isEmpty()) {
				buffer.insertText(clip);
				dismissAutocomplete();
				touchCaret();
				ensureCaretVisible(font, tab);
				onBufferEdited.accept(tab);
			}
			return true;
		}
		if (ctrl && !event.hasAltDown() && event.shortcutKey() == InputConstants.KEYCODE_Z) {
			boolean changed = shift ? buffer.redo() : buffer.undo();
			if (changed) {
				dismissAutocomplete();
				touchCaret();
				ensureCaretVisible(font, tab);
				onBufferEdited.accept(tab);
			}
			return true;
		}
		if (ctrl && !shift && !event.hasAltDown() && event.shortcutKey() == InputConstants.KEYCODE_Y) {
			if (buffer.redo()) {
				dismissAutocomplete();
				touchCaret();
				ensureCaretVisible(font, tab);
				onBufferEdited.accept(tab);
			}
			return true;
		}

		if (event.isConfirmation()) {
			buffer.insertNewline();
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			onBufferEdited.accept(tab);
			return true;
		}
		if (event.isCycleFocus()) {
			if (shift) {
				buffer.unindentSelection();
			} else {
				buffer.insertTab();
			}
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			onBufferEdited.accept(tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_BACKSPACE || event.key() == InputConstants.KEY_BACKSPACE) {
			if (ctrl) {
				buffer.deleteWordBackward();
			} else {
				buffer.backspace();
			}
			touchCaret();
			ensureCaretVisible(font, tab);
			if (autocompleteOpen) {
				triggerAutocomplete(tab, false);
			}
			onBufferEdited.accept(tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_DELETE || event.key() == InputConstants.KEY_DELETE) {
			if (ctrl) {
				buffer.deleteWordForward();
			} else {
				buffer.deleteForward();
			}
			touchCaret();
			ensureCaretVisible(font, tab);
			if (autocompleteOpen) {
				triggerAutocomplete(tab, false);
			}
			onBufferEdited.accept(tab);
			return true;
		}

		if (event.isLeft()) {
			buffer.moveLeft(shift, ctrl);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.isRight()) {
			buffer.moveRight(shift, ctrl);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.isUp()) {
			buffer.moveVertical(-1, shift);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.isDown()) {
			buffer.moveVertical(1, shift);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_HOME || event.key() == InputConstants.KEY_HOME) {
			buffer.moveHome(shift, ctrl);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_END || event.key() == InputConstants.KEY_END) {
			buffer.moveEnd(shift, ctrl);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}
		int pageLines = Math.max(1, lastCodeViewport.height() / scaledLineHeight(font, textScale()));
		if (event.shortcutKey() == InputConstants.KEYCODE_PAGEUP || event.key() == InputConstants.KEY_PAGEUP) {
			buffer.moveVertical(-pageLines, shift);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_PAGEDOWN || event.key() == InputConstants.KEY_PAGEDOWN) {
			buffer.moveVertical(pageLines, shift);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
			return true;
		}

		return false;
	}

	public boolean charTyped(CharacterEvent event, Font font, OpenTabState tab) {
		if (!event.isAllowedChatCharacter()) {
			return false;
		}
		String typed = event.codepointAsString();
		tab.buffer().insertText(typed);
		touchCaret();
		ensureCaretVisible(font, tab);
		onBufferEdited.accept(tab);

		if (SyntaxHighlighter.languageForPath(tab.relativePath()) == SyntaxHighlighter.Language.MCFUNCTION) {
			String currentLine = tab.buffer().line(tab.buffer().cursor().line());
			if (!currentLine.isBlank() && !currentLine.stripLeading().startsWith("#")) {
				triggerAutocomplete(tab, false);
			} else {
				dismissAutocomplete();
			}
		}
		return true;
	}

	private void acceptSelectedSuggestion(Font font, OpenTabState tab) {
		List<BrigadierAssistService.SuggestionEntry> list = currentAssist.suggestions();
		if (list.isEmpty()) {
			dismissAutocomplete();
			return;
		}
		int idx = Math.max(0, Math.min(list.size() - 1, selectedSuggestionIndex));
		BrigadierAssistService.SuggestionEntry entry = list.get(idx);
		TextDocumentBuffer buffer = tab.buffer();
		buffer.replaceRange(buffer.cursor().line(), entry.replaceStartCol(), entry.replaceEndCol(), entry.text());
		dismissAutocomplete();
		touchCaret();
		ensureCaretVisible(font, tab);
		onBufferEdited.accept(tab);
	}

	private void triggerAutocomplete(OpenTabState tab, boolean explicit) {
		if (SyntaxHighlighter.languageForPath(tab.relativePath()) != SyntaxHighlighter.Language.MCFUNCTION) {
			dismissAutocomplete();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			return;
		}
		CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
		ClientSuggestionProvider provider = connection.getSuggestionsProvider();
		if (dispatcher == null || provider == null) {
			return;
		}

		TextDocumentBuffer.Position cursor = tab.buffer().cursor();
		String lineText = tab.buffer().line(cursor.line());
		long gen = ++assistGeneration;

		BrigadierAssistService.query(dispatcher, provider, lineText, cursor.column()).whenComplete((result, error) -> {
			Runnable apply = () -> {
				if (gen != assistGeneration) {
					return;
				}
				if (error != null || result == null || result.suggestions().isEmpty()) {
					currentAssist = result == null ? BrigadierAssistService.AssistResult.EMPTY : result;
					if (!explicit || currentAssist.suggestions().isEmpty()) {
						dismissAutocomplete();
					}
					return;
				}
				currentAssist = result;
				selectedSuggestionIndex = 0;
				suggestionScroll = 0;
				autocompleteOpen = true;
			};
			if (mc.isSameThread()) {
				apply.run();
			} else {
				mc.execute(apply);
			}
		});
	}

	private static List<String> queryLiveHoverSignatures(String lineText, int hoverColumn) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return List.of();
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection == null || connection.getCommands() == null || connection.getSuggestionsProvider() == null) {
			return List.of();
		}
		return BrigadierAssistService.queryHoverSignatures(
				connection.getCommands(),
				connection.getSuggestionsProvider(),
				lineText,
				hoverColumn
		);
	}

	public void ensureCaretVisible(Font font, OpenTabState tab) {
		if (lastCodeViewport.width() <= 0 || lastCodeViewport.height() <= 0) {
			return;
		}
		float scale = textScale();
		TextDocumentBuffer buffer = tab.buffer();
		TextDocumentBuffer.Position cursor = buffer.cursor();
		int lineHeight = scaledLineHeight(font, scale);
		int visibleLines = Math.max(1, (lastCodeViewport.height() - 6) / lineHeight);

		if (cursor.line() < tab.scrollLine()) {
			tab.setScrollLine(cursor.line());
		} else if (cursor.line() >= tab.scrollLine() + visibleLines) {
			tab.setScrollLine(cursor.line() - visibleLines + 1);
		}

		String lineText = buffer.line(cursor.line());
		int caretPixelX = QuillDraw.textWidth(font, lineText.substring(0, Math.min(cursor.column(), lineText.length())), scale);
		int viewW = Math.max(20, lastCodeViewport.width() - CODE_LEFT_PADDING - 12);
		if (caretPixelX < tab.scrollX()) {
			tab.setScrollX(Math.max(0, caretPixelX - 16));
		} else if (caretPixelX > tab.scrollX() + viewW) {
			tab.setScrollX(caretPixelX - viewW + 16);
		}
	}

	private int hitTestLine(Font font, OpenTabState tab, double mouseY) {
		int lineHeight = scaledLineHeight(font, textScale());
		int lineIndex = tab.scrollLine() + Math.max(0, ((int) mouseY - (lastCodeViewport.y() + 4)) / lineHeight);
		return Math.max(0, Math.min(tab.buffer().lineCount() - 1, lineIndex));
	}

	private TextDocumentBuffer.Position hitTest(Font font, OpenTabState tab, double mouseX, double mouseY) {
		float scale = textScale();
		int lineIndex = hitTestLine(font, tab, mouseY);
		String lineText = tab.buffer().line(lineIndex);
		int relX = Math.max(0, (int) mouseX - (lastCodeViewport.x() + CODE_LEFT_PADDING - tab.scrollX()));
		int col = QuillDraw.charIndexAtPixel(font, lineText, relX, scale);
		return new TextDocumentBuffer.Position(lineIndex, col);
	}

	private static int computeMaxLineWidth(Font font, TextDocumentBuffer buffer, int firstLine, int visibleLines, float scale) {
		int max = 0;
		int end = Math.min(buffer.lineCount(), firstLine + visibleLines + 1);
		for (int i = firstLine; i < end; i++) {
			max = Math.max(max, QuillDraw.textWidth(font, buffer.line(i), scale));
		}
		return max;
	}

	private static String getClipboardSafe() {
		Minecraft mc = Minecraft.getInstance();
		return (mc != null && mc.keyboardHandler != null) ? mc.keyboardHandler.getClipboard() : "";
	}

	private static void setClipboardSafe(String value) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.keyboardHandler != null) {
			mc.keyboardHandler.setClipboard(value);
		}
	}
}
