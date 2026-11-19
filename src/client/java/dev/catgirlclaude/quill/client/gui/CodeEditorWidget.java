package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.brigadier.CommandDispatcher;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.editor.BrigadierAssistService;
import dev.catgirlclaude.quill.client.editor.SingleLineTextInput;
import dev.catgirlclaude.quill.client.editor.SyntaxHighlighter;
import dev.catgirlclaude.quill.client.editor.TextDocumentBuffer;
import dev.catgirlclaude.quill.client.editor.WorkspaceNavigation;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Multi-line code editor widget with dynamic VS Code syntax highlighting, customizable font,
 * live Brigadier command autocomplete &amp; hover signatures, in-file {@code Ctrl+F} find bar,
 * right-click context menu, {@code Alt+Z} soft word wrap, {@code Shift+Wheel} horizontal scrolling,
 * bracket-pair matching, mouse click/double-click/triple-click/gutter/drag selection,
 * {@code Shift+Arrows}, {@code Ctrl+Shift+Arrows}, {@code Ctrl+Backspace/Delete},
 * {@code Tab}/{@code Shift+Tab} indent/unindent, clipboard, undo/redo, and 2D scrolling.
 */
public final class CodeEditorWidget {
	private static final int LINE_PADDING_Y = 2;
	private static final int CODE_LEFT_PADDING = 2;
	private static final int SCROLLBAR_SIZE = 5;

	static int codeLeftPadding() {
		return CODE_LEFT_PADDING;
	}

	private static final int MAX_VISIBLE_SUGGESTIONS = 8;
	private static final int SUGGESTION_ROW_HEIGHT = 13;
	private static final long MULTI_CLICK_WINDOW_MS = 400L;
	static final long HOVER_DELAY_MS = 500L;

	public record FindMatch(int line, int startCol, int endCol) {
	}

	public record CtrlClickRequest(OpenTabState tab, TextDocumentBuffer.Position position) {
	}

	public record FunctionPreview(
			String functionId,
			String relativePath,
			List<String> headerLines,
			boolean truncated
	) {
	}

	enum HoverKind {
		NONE,
		COMMAND_SIGNATURES,
		FUNCTION_PREVIEW
	}

	record HoverTooltipState(
			HoverKind kind,
			int anchorRow,
			List<String> signatures,
			FunctionPreview functionPreview
	) {
		static final HoverTooltipState NONE = new HoverTooltipState(HoverKind.NONE, -1, List.of(), null);

		boolean isVisible() {
			return kind != HoverKind.NONE;
		}
	}

	public static final class WordWrapLayout {
		public record VisualRow(int logicalLine, int startCol, int endCol, boolean firstSegment) {
		}

		private WordWrapLayout() {
		}

		public static List<VisualRow> wrapLines(
				List<String> lines,
				boolean wordWrap,
				int maxWidthPx,
				ToIntFunction<String> textWidth
		) {
			List<VisualRow> rows = new ArrayList<>();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (!wordWrap || maxWidthPx <= 16 || line.isEmpty() || textWidth.applyAsInt(line) <= maxWidthPx) {
					rows.add(new VisualRow(i, 0, line.length(), true));
					continue;
				}
				int pos = 0;
				while (pos < line.length()) {
					int lo = pos + 1;
					int hi = line.length();
					int best = pos + 1;
					while (lo <= hi) {
						int mid = (lo + hi) >>> 1;
						int w = textWidth.applyAsInt(line.substring(pos, mid));
						if (w <= maxWidthPx) {
							best = mid;
							lo = mid + 1;
						} else {
							hi = mid - 1;
						}
					}
					int cut = best;
					if (cut < line.length()) {
						int lastSpace = -1;
						for (int k = cut; k > pos; k--) {
							if (line.charAt(k - 1) <= ' ') {
								lastSpace = k;
								break;
							}
						}
						if (lastSpace > pos) {
							cut = lastSpace;
						}
					}
					rows.add(new VisualRow(i, pos, cut, pos == 0));
					pos = cut;
				}
			}
			return rows;
		}

		static int visualRowFor(List<VisualRow> rows, int logicalLine, int col) {
			if (rows.isEmpty()) {
				return 0;
			}
			int fallback = 0;
			for (int i = 0; i < rows.size(); i++) {
				VisualRow r = rows.get(i);
				if (r.logicalLine() == logicalLine) {
					fallback = i;
					boolean isLastForLine = (i + 1 >= rows.size()) || rows.get(i + 1).logicalLine() != logicalLine;
					if (col >= r.startCol() && (col < r.endCol() || (isLastForLine && col <= r.endCol()))) {
						return i;
					}
				} else if (r.logicalLine() > logicalLine) {
					break;
				}
			}
			return fallback;
		}
	}

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
	private List<WordWrapLayout.VisualRow> cachedVisualRows = List.of();
	private int cachedWrapWidth = 0;

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
	private Predicate<CtrlClickRequest> ctrlClickHandler = req -> false;
	private Consumer<ContextMenuOverlay> contextMenuOpener = menu -> {
	};
	private Runnable contextMenuDismisser = () -> {
	};
	private Supplier<String> clipboardGetter = CodeEditorWidget::readMinecraftClipboard;
	private Consumer<String> clipboardSetter = CodeEditorWidget::writeMinecraftClipboard;

	// In-file Find bar state (Ctrl+F)
	private boolean findBarOpen = false;
	private boolean findBarFocused = false;
	private final SingleLineTextInput findInput = new SingleLineTextInput("");
	private int activeFindMatchIdx = 0;
	private IdeLayout.Rect findBarRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect findInputRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect findPrevBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect findNextBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect findCloseBtnRect = new IdeLayout.Rect(0, 0, 0, 0);

	// Brigadier autocomplete & hover state
	private boolean autocompleteOpen = false;
	private int selectedSuggestionIndex = 0;
	private int suggestionScroll = 0;
	private long assistGeneration = 0L;
	private String lastCaretTabPath = "";
	private BrigadierAssistService.AssistResult currentAssist = BrigadierAssistService.AssistResult.EMPTY;

	private LongSupplier clock = System::currentTimeMillis;
	private BiFunction<String, Integer, List<String>> commandHoverResolver = CodeEditorWidget::queryLiveHoverSignatures;
	private Function<String, CompletableFuture<FunctionPreview>> functionPreviewResolver = fnId -> CompletableFuture.completedFuture(null);
	private String pendingHoverKey = "";
	private long pendingHoverStartMs = 0L;
	private boolean hoverQueryIssued = false;
	private long hoverGeneration = 0L;
	private HoverTooltipState activeHoverTooltip = HoverTooltipState.NONE;

	public void setOnBufferEdited(Consumer<OpenTabState> onBufferEdited) {
		this.onBufferEdited = onBufferEdited != null ? onBufferEdited : tab -> {
		};
	}

	public void setCtrlClickHandler(Predicate<CtrlClickRequest> ctrlClickHandler) {
		this.ctrlClickHandler = ctrlClickHandler != null ? ctrlClickHandler : req -> false;
	}

	public void setFunctionPreviewResolver(Function<String, CompletableFuture<FunctionPreview>> functionPreviewResolver) {
		this.functionPreviewResolver = functionPreviewResolver != null
				? functionPreviewResolver
				: fnId -> CompletableFuture.completedFuture(null);
	}

	void setClockForTest(LongSupplier clock) {
		this.clock = clock != null ? clock : System::currentTimeMillis;
	}

	void setCommandHoverResolverForTest(BiFunction<String, Integer, List<String>> commandHoverResolver) {
		this.commandHoverResolver = commandHoverResolver != null ? commandHoverResolver : CodeEditorWidget::queryLiveHoverSignatures;
	}

	void resetHoverState() {
		pendingHoverKey = "";
		pendingHoverStartMs = 0L;
		hoverQueryIssued = false;
		hoverGeneration++;
		activeHoverTooltip = HoverTooltipState.NONE;
	}

	public void setContextMenuHooks(Consumer<ContextMenuOverlay> contextMenuOpener, Runnable contextMenuDismisser) {
		this.contextMenuOpener = contextMenuOpener != null ? contextMenuOpener : menu -> {
		};
		this.contextMenuDismisser = contextMenuDismisser != null ? contextMenuDismisser : () -> {
		};
	}

	void setClipboardBridge(Supplier<String> clipboardGetter, Consumer<String> clipboardSetter) {
		this.clipboardGetter = clipboardGetter != null ? clipboardGetter : CodeEditorWidget::readMinecraftClipboard;
		this.clipboardSetter = clipboardSetter != null ? clipboardSetter : CodeEditorWidget::writeMinecraftClipboard;
	}

	void setViewportBoundsForTest(IdeLayout.Rect editorRect, IdeLayout.Rect gutterRect, IdeLayout.Rect codeViewport) {
		this.lastEditorRect = editorRect;
		this.lastGutterRect = gutterRect;
		this.lastCodeViewport = codeViewport;
	}

	public boolean isFindBarOpen() {
		return findBarOpen;
	}

	public boolean isFindBarFocused() {
		return findBarOpen && findBarFocused;
	}

	public int activeFindMatchIndex() {
		return activeFindMatchIdx;
	}

	public void openFindBar(OpenTabState tab) {
		this.findBarOpen = true;
		this.findBarFocused = true;
		dismissAutocomplete();
		if (tab != null && tab.buffer().hasSelection()) {
			TextDocumentBuffer.Position s = tab.buffer().selectionStart();
			TextDocumentBuffer.Position e = tab.buffer().selectionEnd();
			if (s.line() == e.line()) {
				String selected = tab.buffer().getSelectedText();
				if (!selected.isBlank()) {
					findInput.setText(selected);
					findInput.selectAll();
				}
			}
		} else if (!findInput.text().isEmpty()) {
			findInput.selectAll();
		}
		activeFindMatchIdx = 0;
	}

	public void closeFindBar() {
		this.findBarOpen = false;
		this.findBarFocused = false;
		this.findInput.mouseReleased();
		clearFindBarRects();
	}

	private void clearFindBarRects() {
		findBarRect = new IdeLayout.Rect(0, 0, 0, 0);
		findInputRect = new IdeLayout.Rect(0, 0, 0, 0);
		findPrevBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		findNextBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		findCloseBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	}

	public List<FindMatch> findMatches(OpenTabState tab) {
		if (tab == null) {
			return List.of();
		}
		String query = findInput.text();
		if (query == null || query.isEmpty()) {
			return List.of();
		}
		String needle = query.toLowerCase(Locale.ROOT);
		List<FindMatch> matches = new ArrayList<>();
		TextDocumentBuffer buffer = tab.buffer();
		int qLen = query.length();
		for (int i = 0; i < buffer.lineCount(); i++) {
			String lineLower = buffer.line(i).toLowerCase(Locale.ROOT);
			int from = 0;
			while (from <= lineLower.length() - qLen) {
				int idx = lineLower.indexOf(needle, from);
				if (idx < 0) {
					break;
				}
				matches.add(new FindMatch(i, idx, idx + qLen));
				from = idx + qLen;
			}
		}
		return matches;
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
		return 1.0f;
	}

	private static int scaledLineHeight(Font font, float scale) {
		int base = font != null ? font.lineHeight : 9;
		return Math.max(8, Math.round((base + LINE_PADDING_Y) * scale));
	}

	private void touchCaret() {
		lastCaretActivityMs = System.currentTimeMillis();
		resetHoverState();
	}

	private boolean isCaretVisible() {
		long elapsed = System.currentTimeMillis() - lastCaretActivityMs;
		if (elapsed < 450L) {
			return true;
		}
		return ((elapsed / 500L) % 2L) == 0L;
	}

	boolean shouldDrawCaret(boolean focused) {
		return focused && !isFindBarFocused() && isCaretVisible();
	}

	public void render(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect editorRect,
			OpenTabState tab,
			int mouseX,
			int mouseY
	) {
		render(graphics, font, editorRect, tab, mouseX, mouseY, true);
	}

	public void render(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect editorRect,
			OpenTabState tab,
			int mouseX,
			int mouseY,
			boolean focused
	) {
		lastEditorRect = editorRect;
		if (!tab.relativePath().equals(lastCaretTabPath)) {
			lastCaretTabPath = tab.relativePath();
			dismissAutocomplete();
			currentAssist = BrigadierAssistService.AssistResult.EMPTY;
			resetHoverState();
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

		boolean wordWrap = QuillClientConfig.current().wordWrap();
		int wrapMaxWidth = Math.max(40, codeW - CODE_LEFT_PADDING - 8);
		cachedWrapWidth = wrapMaxWidth;
		List<String> rawLines = new ArrayList<>(buffer.lineCount());
		for (int i = 0; i < buffer.lineCount(); i++) {
			rawLines.add(buffer.line(i));
		}
		List<WordWrapLayout.VisualRow> visualRows = WordWrapLayout.wrapLines(
				rawLines, wordWrap, wrapMaxWidth, s -> QuillDraw.textWidth(font, s, scale));
		cachedVisualRows = visualRows;

		if (tab.consumeScrollToCaret()) {
			ensureCaretVisible(font, tab);
		}

		int visibleLines = Math.max(1, codeH / lineHeight);
		int totalVisualRows = Math.max(1, visualRows.size());
		int maxScrollLine = Math.max(0, totalVisualRows - visibleLines);
		tab.setScrollLine(Math.min(maxScrollLine, tab.scrollLine()));

		int maxScrollX = 0;
		if (wordWrap) {
			tab.setScrollX(0);
		} else {
			int maxLineWidth = Math.max(
					computeMaxLineWidth(font, buffer, tab.scrollLine(), visibleLines, scale),
					QuillDraw.textWidth(font, buffer.line(buffer.cursor().line()), scale)
			);
			maxScrollX = Math.max(0, maxLineWidth - codeW + 24);
			tab.setScrollX(Math.min(maxScrollX, tab.scrollX()));
		}

		// Gutter background
		QuillDraw.fill(graphics, editorRect.x(), editorRect.y(), gutterWidth, editorRect.height(), QuillTheme.BG_GUTTER);
		QuillDraw.vLine(graphics, codeX - 1, editorRect.y(), editorRect.height(), QuillTheme.BORDER_SUBTLE);

		boolean hoveringPopup = isAutocompleteOpen() && lastPopupRect.contains(mouseX, mouseY);
		boolean hoveringFind = findBarOpen && findBarRect.contains(mouseX, mouseY);
		WorkspaceNavigation.NavigationTarget hoverNavTarget = null;
		int hoverNavLine = -1;
		if (lastCodeViewport.contains(mouseX, mouseY) && !hoveringPopup && !hoveringFind) {
			if (isControlDownSafe()) {
				TextDocumentBuffer.Position hoverPos = hitTest(font, tab, mouseX, mouseY);
				if (hoverPos.line() >= 0 && hoverPos.line() < buffer.lineCount()) {
					hoverNavTarget = WorkspaceNavigation.resolveTargetAtCursor(
							tab.relativePath(),
							buffer.line(hoverPos.line()),
							hoverPos.column(),
							Set.of()
					);
					if (hoverNavTarget != null) {
						hoverNavLine = hoverPos.line();
					}
				}
			}
			QuillDraw.requestCursor(graphics, hoverNavTarget != null ? CursorTypes.POINTING_HAND : CursorTypes.IBEAM);
		} else if (lastGutterRect.contains(mouseX, mouseY) && !hoveringPopup && !hoveringFind) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}

		int firstRow = Math.min(Math.max(0, tab.scrollLine()), Math.max(0, visualRows.size() - 1));
		int lastRow = Math.min(visualRows.size() - 1, firstRow + visibleLines);
		TextDocumentBuffer.Position cursor = buffer.cursor();
		boolean hasSel = buffer.hasSelection();
		TextDocumentBuffer.Position selStart = buffer.selectionStart();
		TextDocumentBuffer.Position selEnd = buffer.selectionEnd();
		TextDocumentBuffer.BracketMatch bracketMatch = buffer.findMatchingBracket();
		List<FindMatch> matches = findBarOpen ? findMatches(tab) : List.of();
		if (activeFindMatchIdx >= matches.size()) {
			activeFindMatchIdx = matches.isEmpty() ? 0 : matches.size() - 1;
		}
		int caretVisualRow = WordWrapLayout.visualRowFor(visualRows, cursor.line(), cursor.column());

		QuillDraw.beginClip(graphics, new IdeLayout.Rect(editorRect.x(), editorRect.y(), editorRect.width(), codeH));
		for (int v = firstRow; v <= lastRow; v++) {
			WordWrapLayout.VisualRow vr = visualRows.get(v);
			int rowY = editorRect.y() + 4 + (v - firstRow) * lineHeight;
			boolean isCurrentLine = vr.logicalLine() == cursor.line();

			if (isCurrentLine) {
				QuillDraw.fill(graphics, codeX, rowY - 1, codeW, lineHeight, QuillTheme.LINE_HIGHLIGHT);
			}

			if (vr.firstSegment()) {
				String lineNumStr = String.valueOf(vr.logicalLine() + 1);
				int numX = codeX - 6 - QuillDraw.textWidth(font, lineNumStr, scale);
				QuillDraw.textScaled(graphics, font, lineNumStr, numX, rowY,
						isCurrentLine ? QuillTheme.TEXT_PRIMARY : QuillTheme.TEXT_MUTED, scale);
			}
		}
		QuillDraw.endClip(graphics);

		SyntaxHighlighter.Language language = SyntaxHighlighter.languageForPath(tab.relativePath());
		int caretScreenX = codeX + CODE_LEFT_PADDING;
		int caretScreenY = editorRect.y() + 4;

		QuillDraw.beginClip(graphics, lastCodeViewport);
		for (int v = firstRow; v <= lastRow; v++) {
			WordWrapLayout.VisualRow vr = visualRows.get(v);
			int i = vr.logicalLine();
			int rowY = editorRect.y() + 4 + (v - firstRow) * lineHeight;
			String fullLine = buffer.line(i);
			int segStart = Math.min(vr.startCol(), fullLine.length());
			int segEnd = Math.min(vr.endCol(), fullLine.length());
			String segText = fullLine.substring(segStart, segEnd);
			int drawX = codeX + CODE_LEFT_PADDING - tab.scrollX();

			// Find matches highlight for this visual row
			if (!matches.isEmpty()) {
				for (int mIdx = 0; mIdx < matches.size(); mIdx++) {
					FindMatch m = matches.get(mIdx);
					if (m.line() != i) {
						continue;
					}
					int ovStart = Math.max(segStart, m.startCol());
					int ovEnd = Math.min(segEnd, m.endCol());
					if (ovEnd > ovStart) {
						int mx1 = drawX + QuillDraw.textWidth(font, segText.substring(0, ovStart - segStart), scale);
						int mx2 = drawX + QuillDraw.textWidth(font, segText.substring(0, ovEnd - segStart), scale);
						int fillColor = (mIdx == activeFindMatchIdx) ? 0x66EA5C00 : 0x33D19A66;
						QuillDraw.fill(graphics, mx1, rowY - 1, Math.max(2, mx2 - mx1), lineHeight, fillColor);
					}
				}
			}

			// Selection highlight for this visual row
			if (hasSel && i >= selStart.line() && i <= selEnd.line()) {
				int lineSelStart = (i == selStart.line()) ? selStart.column() : 0;
				int lineSelEnd = (i == selEnd.line()) ? selEnd.column() : fullLine.length();
				int ovStart = Math.max(segStart, lineSelStart);
				int ovEnd = Math.min(segEnd, lineSelEnd);
				boolean extendNewline = (i < selEnd.line()) && (segEnd == fullLine.length()) && (lineSelEnd >= fullLine.length());
				if (ovEnd > ovStart || extendNewline) {
					int x1 = drawX + QuillDraw.textWidth(font, segText.substring(0, Math.max(0, ovStart - segStart)), scale);
					int x2 = drawX + QuillDraw.textWidth(font, segText.substring(0, Math.max(0, ovEnd - segStart)), scale);
					if (extendNewline) {
						x2 += Math.max(5, Math.round(6 * scale));
					}
					if (x2 > x1) {
						QuillDraw.fill(graphics, x1, rowY - 1, x2 - x1, lineHeight, QuillTheme.SELECTION_BG);
					}
				}
			}

			// Matching bracket pair highlight
			if (bracketMatch != null) {
				drawBracketHighlightIfOnSegment(graphics, font, fullLine, segStart, segEnd, i, drawX, rowY, lineHeight, scale, bracketMatch.open());
				drawBracketHighlightIfOnSegment(graphics, font, fullLine, segStart, segEnd, i, drawX, rowY, lineHeight, scale, bracketMatch.close());
			}

			if (language == SyntaxHighlighter.Language.PLAIN || segText.isEmpty()) {
				QuillDraw.textScaled(graphics, font, segText, drawX, rowY, QuillTheme.SYNTAX_PLAIN, scale);
			} else {
				List<SyntaxHighlighter.TokenSpan> spans = SyntaxHighlighter.tokenizeLine(fullLine, language);
				for (SyntaxHighlighter.TokenSpan span : spans) {
					int ovStart = Math.max(segStart, span.start());
					int ovEnd = Math.min(segEnd, span.end());
					if (ovEnd <= ovStart) {
						continue;
					}
					String part = fullLine.substring(ovStart, ovEnd);
					int segX = drawX + (ovStart == segStart ? 0 : QuillDraw.textWidth(font, fullLine.substring(segStart, ovStart), scale));
					QuillDraw.textScaled(graphics, font, part, segX, rowY, span.color(), scale);
				}
			}

			if (hoverNavTarget != null && i == hoverNavLine) {
				int ovStart = Math.max(segStart, hoverNavTarget.startCol());
				int ovEnd = Math.min(segEnd, hoverNavTarget.endCol());
				if (ovEnd > ovStart) {
					int ux1 = drawX + QuillDraw.textWidth(font, fullLine.substring(segStart, ovStart), scale);
					int ux2 = drawX + QuillDraw.textWidth(font, fullLine.substring(segStart, ovEnd), scale);
					QuillDraw.hLine(graphics, ux1, Math.max(2, ux2 - ux1), rowY + Math.round(font.lineHeight * scale), QuillTheme.ACCENT_BLUE);
				}
			}

			if (v == caretVisualRow && i == cursor.line()) {
				int col = Math.max(segStart, Math.min(cursor.column(), segEnd));
				caretScreenX = drawX + QuillDraw.textWidth(font, segText.substring(0, col - segStart), scale);
				caretScreenY = rowY;
				if (shouldDrawCaret(focused)) {
					QuillDraw.fill(graphics, caretScreenX, rowY - 1, 1, lineHeight, QuillTheme.CURSOR_COLOR);
				}
			}
		}
		QuillDraw.endClip(graphics);

		// Vertical scrollbar
		if (maxScrollLine > 0 && codeH > 12) {
			int trackX = editorRect.right() - SCROLLBAR_SIZE;
			int thumbH = Math.max(14, (int) ((long) codeH * visibleLines / totalVisualRows));
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

		// In-file Find bar overlay (top-right corner of editor)
		if (findBarOpen) {
			renderFindBar(graphics, font, editorRect, matches, mouseX, mouseY);
		} else {
			clearFindBarRects();
		}

		// Brigadier autocomplete popup & hover signature tooltips (.mcfunction only)
		if (language == SyntaxHighlighter.Language.MCFUNCTION) {
			if (isAutocompleteOpen()) {
				resetHoverState();
				renderAutocompletePopup(graphics, font, editorRect, caretScreenX, caretScreenY, lineHeight, mouseX, mouseY);
			} else {
				lastPopupRect = new IdeLayout.Rect(0, 0, 0, 0);
				suggestionRowRects.clear();
				if (hoveringFind) {
					resetHoverState();
				} else {
					renderHoverOrUsageTooltip(graphics, font, editorRect, tab, lineHeight, mouseX, mouseY);
				}
			}
		} else {
			lastPopupRect = new IdeLayout.Rect(0, 0, 0, 0);
			suggestionRowRects.clear();
			resetHoverState();
		}
	}

	private void renderFindBar(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect editorRect,
			List<FindMatch> matches,
			int mouseX,
			int mouseY
	) {
		int barW = Math.min(230, Math.max(150, editorRect.width() - 24));
		int barH = 22;
		int bx = editorRect.right() - barW - 10;
		int by = editorRect.y() + 4;
		findBarRect = new IdeLayout.Rect(bx, by, barW, barH);

		QuillDraw.fill(graphics, bx - 1, by + 1, barW + 3, barH + 3, QuillTheme.Metrics.SHADOW_OUTER);
		QuillDraw.fill(graphics, bx, by, barW, barH, QuillTheme.BG_POPUP);
		QuillDraw.outline(graphics, bx, by, barW, barH, findBarFocused ? QuillTheme.ACCENT_FOCUS : QuillTheme.BORDER_SUBTLE);

		int btnSize = 14;
		findCloseBtnRect = new IdeLayout.Rect(bx + barW - btnSize - 4, by + 4, btnSize, btnSize);
		findNextBtnRect = new IdeLayout.Rect(findCloseBtnRect.x() - btnSize - 2, by + 4, btnSize, btnSize);
		findPrevBtnRect = new IdeLayout.Rect(findNextBtnRect.x() - btnSize - 2, by + 4, btnSize, btnSize);

		String countText = findInput.text().isEmpty()
				? ""
				: (matches.isEmpty() ? "0/0" : (activeFindMatchIdx + 1) + "/" + matches.size());
		int countW = countText.isEmpty() ? 0 : QuillDraw.textWidth(font, countText) + 6;
		int inputW = Math.max(48, findPrevBtnRect.x() - bx - 8 - countW);
		findInputRect = new IdeLayout.Rect(bx + 4, by + 3, inputW, 16);
		findInput.render(graphics, font, findInputRect, "Find", findBarFocused);

		int ty = by + (barH - font.lineHeight) / 2;
		if (!countText.isEmpty()) {
			int countColor = matches.isEmpty() ? QuillTheme.TEXT_WARNING : QuillTheme.TEXT_MUTED;
			QuillDraw.text(graphics, font, countText, findInputRect.right() + 4, ty, countColor);
		}

		drawFindButton(graphics, font, findPrevBtnRect, "^", !matches.isEmpty(), findPrevBtnRect.contains(mouseX, mouseY));
		drawFindButton(graphics, font, findNextBtnRect, "v", !matches.isEmpty(), findNextBtnRect.contains(mouseX, mouseY));

		boolean hoverClose = findCloseBtnRect.contains(mouseX, mouseY);
		if (hoverClose) {
			QuillDraw.fill(graphics, findCloseBtnRect.x(), findCloseBtnRect.y(), findCloseBtnRect.width(), findCloseBtnRect.height(), QuillTheme.BG_HOVER);
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
		QuillDraw.drawCloseX(graphics, findCloseBtnRect.x() + 3, findCloseBtnRect.y() + 3, 7,
				hoverClose ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);
	}

	private static void drawFindButton(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect rect,
			String glyph,
			boolean enabled,
			boolean hovered
	) {
		if (enabled && hovered) {
			QuillDraw.fill(graphics, rect.x(), rect.y(), rect.width(), rect.height(), QuillTheme.BG_HOVER);
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
		int gx = rect.x() + (rect.width() - QuillDraw.textWidth(font, glyph)) / 2;
		int gy = rect.y() + (rect.height() - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, glyph, gx, gy,
				enabled ? (hovered ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY) : QuillTheme.TEXT_MUTED);
	}

	private static void drawBracketHighlightIfOnSegment(
			GuiGraphicsExtractor graphics,
			Font font,
			String fullLine,
			int segStart,
			int segEnd,
			int lineIndex,
			int drawX,
			int rowY,
			int lineHeight,
			float scale,
			TextDocumentBuffer.Position pos
	) {
		if (pos == null || pos.line() != lineIndex || pos.column() < segStart || pos.column() >= segEnd) {
			return;
		}
		int bx = drawX + QuillDraw.textWidth(font, fullLine.substring(segStart, pos.column()), scale);
		int bw = Math.max(4, QuillDraw.textWidth(font, fullLine.substring(pos.column(), pos.column() + 1), scale));
		QuillDraw.fill(graphics, bx, rowY - 1, bw, lineHeight, QuillTheme.BRACKET_MATCH_BG);
		QuillDraw.outline(graphics, bx, rowY - 1, bw, lineHeight, QuillTheme.BORDER_SUBTLE);
	}

	private void jumpToNearestFindMatch(Font font, OpenTabState tab) {
		List<FindMatch> matches = findMatches(tab);
		if (matches.isEmpty()) {
			activeFindMatchIdx = 0;
			return;
		}
		TextDocumentBuffer buffer = tab.buffer();
		TextDocumentBuffer.Position anchor = buffer.hasSelection() ? buffer.selectionStart() : buffer.cursor();
		int chosen = 0;
		for (int i = 0; i < matches.size(); i++) {
			FindMatch m = matches.get(i);
			if (m.line() > anchor.line() || (m.line() == anchor.line() && m.startCol() >= anchor.column())) {
				chosen = i;
				break;
			}
		}
		activeFindMatchIdx = chosen;
		selectFindMatch(font, tab, matches.get(chosen));
	}

	private void jumpToFindMatch(Font font, OpenTabState tab, int delta) {
		List<FindMatch> matches = findMatches(tab);
		if (matches.isEmpty()) {
			activeFindMatchIdx = 0;
			return;
		}
		activeFindMatchIdx = Math.floorMod(activeFindMatchIdx + delta, matches.size());
		selectFindMatch(font, tab, matches.get(activeFindMatchIdx));
	}

	private void selectFindMatch(Font font, OpenTabState tab, FindMatch match) {
		tab.buffer().setCursor(match.line(), match.startCol(), false);
		tab.buffer().setCursor(match.line(), match.endCol(), true);
		touchCaret();
		ensureCaretVisible(font, tab);
	}

	public static boolean isPositionWithinSelection(TextDocumentBuffer buffer, TextDocumentBuffer.Position pos) {
		if (buffer == null || pos == null || !buffer.hasSelection()) {
			return false;
		}
		TextDocumentBuffer.Position start = buffer.selectionStart();
		TextDocumentBuffer.Position end = buffer.selectionEnd();
		return pos.compareTo(start) >= 0 && pos.compareTo(end) <= 0;
	}

	public List<ContextMenuOverlay.MenuEntry> buildContextMenuEntries(Font font, OpenTabState tab) {
		if (tab == null) {
			return List.of();
		}
		TextDocumentBuffer buffer = tab.buffer();
		boolean hasSelection = buffer.hasSelection();
		String clip = getClipboardSafe();
		boolean hasClipboard = clip != null && !clip.isEmpty();

		List<ContextMenuOverlay.MenuEntry> items = new ArrayList<>();
		items.add(new ContextMenuOverlay.ActionItem("Cut", "Ctrl+X", hasSelection, () -> {
			String selected = buffer.getSelectedText();
			if (!selected.isEmpty()) {
				setClipboardSafe(selected);
				buffer.deleteSelection();
				dismissAutocomplete();
				touchCaret();
				ensureCaretVisible(font, tab);
				onBufferEdited.accept(tab);
			}
		}));
		items.add(new ContextMenuOverlay.ActionItem("Copy", "Ctrl+C", hasSelection, () -> {
			String selected = buffer.getSelectedText();
			if (!selected.isEmpty()) {
				setClipboardSafe(selected);
			}
		}));
		items.add(new ContextMenuOverlay.ActionItem("Paste", "Ctrl+V", hasClipboard, () -> {
			String pasted = getClipboardSafe();
			if (pasted != null && !pasted.isEmpty()) {
				buffer.insertText(pasted);
				dismissAutocomplete();
				touchCaret();
				ensureCaretVisible(font, tab);
				onBufferEdited.accept(tab);
			}
		}));
		items.add(new ContextMenuOverlay.Separator());
		items.add(new ContextMenuOverlay.ActionItem("Select All", "Ctrl+A", true, () -> {
			buffer.selectAll();
			dismissAutocomplete();
			touchCaret();
		}));
		items.add(new ContextMenuOverlay.ActionItem("Find in File", "Ctrl+F", true, () -> openFindBar(tab)));
		items.add(new ContextMenuOverlay.Separator());
		items.add(new ContextMenuOverlay.ActionItem("Go to Top", "Ctrl+Home", true, () -> {
			buffer.moveHome(false, true);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
		}));
		items.add(new ContextMenuOverlay.ActionItem("Go to Bottom", "Ctrl+End", true, () -> {
			buffer.moveEnd(false, true);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
		}));
		items.add(new ContextMenuOverlay.ActionItem("Go to Start of Line", "Home", true, () -> {
			buffer.moveHome(false, false);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
		}));
		items.add(new ContextMenuOverlay.ActionItem("Go to End of Line", "End", true, () -> {
			buffer.moveEnd(false, false);
			dismissAutocomplete();
			touchCaret();
			ensureCaretVisible(font, tab);
		}));
		return List.copyOf(items);
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

	HoverTooltipState evaluateHoverAt(Font font, OpenTabState tab, int mouseX, int mouseY) {
		if (tab == null || SyntaxHighlighter.languageForPath(tab.relativePath()) != SyntaxHighlighter.Language.MCFUNCTION) {
			resetHoverState();
			return HoverTooltipState.NONE;
		}
		if (isAutocompleteOpen() || (findBarOpen && findBarRect.contains(mouseX, mouseY)) || !lastCodeViewport.contains(mouseX, mouseY)) {
			resetHoverState();
			return HoverTooltipState.NONE;
		}

		float scale = textScale();
		int lineHeight = scaledLineHeight(font, scale);
		int rawRelY = mouseY - (lastCodeViewport.y() + 4);
		if (rawRelY < 0) {
			resetHoverState();
			return HoverTooltipState.NONE;
		}
		int rawRowIdx = tab.scrollLine() + (rawRelY / lineHeight);
		TextDocumentBuffer buffer = tab.buffer();

		final int hoverLine;
		final int hoverCol;
		final int anchorRow;
		if (QuillClientConfig.current().wordWrap() && !cachedVisualRows.isEmpty()) {
			if (rawRowIdx < 0 || rawRowIdx >= cachedVisualRows.size()) {
				resetHoverState();
				return HoverTooltipState.NONE;
			}
			WordWrapLayout.VisualRow vr = cachedVisualRows.get(rawRowIdx);
			hoverLine = vr.logicalLine();
			anchorRow = rawRowIdx;
			String fullLine = buffer.line(hoverLine);
			int segStart = Math.min(vr.startCol(), fullLine.length());
			int segEnd = Math.min(vr.endCol(), fullLine.length());
			String segText = fullLine.substring(segStart, segEnd);
			int lineStartX = lastCodeViewport.x() + CODE_LEFT_PADDING;
			int lineWidthPx = font != null ? QuillDraw.textWidth(font, segText, scale) : segText.length() * 6;
			if (mouseX < lineStartX || mouseX > lineStartX + lineWidthPx + 2) {
				resetHoverState();
				return HoverTooltipState.NONE;
			}
			int relX = mouseX - lineStartX;
			int localCol = font != null
					? QuillDraw.charIndexAtPixel(font, segText, relX, scale)
					: Math.min(segText.length(), relX / 6);
			hoverCol = segStart + localCol;
		} else {
			if (rawRowIdx < 0 || rawRowIdx >= buffer.lineCount()) {
				resetHoverState();
				return HoverTooltipState.NONE;
			}
			hoverLine = rawRowIdx;
			anchorRow = rawRowIdx;
			String fullLine = buffer.line(hoverLine);
			int lineStartX = lastCodeViewport.x() + CODE_LEFT_PADDING - tab.scrollX();
			int lineWidthPx = font != null ? QuillDraw.textWidth(font, fullLine, scale) : fullLine.length() * 6;
			if (mouseX < lastCodeViewport.x() + CODE_LEFT_PADDING || mouseX > lineStartX + lineWidthPx + 2) {
				resetHoverState();
				return HoverTooltipState.NONE;
			}
			int relX = Math.max(0, mouseX - lineStartX);
			hoverCol = font != null
					? QuillDraw.charIndexAtPixel(font, fullLine, relX, scale)
					: Math.min(fullLine.length(), relX / 6);
		}

		String lineText = buffer.line(hoverLine);
		if (lineText.isBlank()) {
			resetHoverState();
			return HoverTooltipState.NONE;
		}

		WorkspaceNavigation.NavigationTarget fnCall =
				WorkspaceNavigation.resolveFunctionCallTargetAtCursor(tab.relativePath(), lineText, hoverCol);
		WorkspaceNavigation.CommandHoverTarget cmdVerb =
				WorkspaceNavigation.findCommandVerbAtCursor(tab.relativePath(), lineText, hoverCol);
		if (fnCall == null && cmdVerb == null) {
			resetHoverState();
			return HoverTooltipState.NONE;
		}

		String targetKey = fnCall != null
				? tab.relativePath() + "|FN|" + hoverLine + "|" + fnCall.identifier() + "|" + lineText
				: tab.relativePath() + "|CMD|" + hoverLine + "|" + cmdVerb.startCol() + "|" + cmdVerb.commandName() + "|" + lineText;

		long now = clock.getAsLong();
		if (!targetKey.equals(pendingHoverKey)) {
			pendingHoverKey = targetKey;
			pendingHoverStartMs = now;
			hoverQueryIssued = false;
			hoverGeneration++;
			activeHoverTooltip = HoverTooltipState.NONE;
			return HoverTooltipState.NONE;
		}

		if (now - pendingHoverStartMs < HOVER_DELAY_MS) {
			return HoverTooltipState.NONE;
		}

		if (!hoverQueryIssued) {
			hoverQueryIssued = true;
			final long gen = hoverGeneration;
			if (fnCall != null) {
				String fnId = fnCall.identifier();
				boolean fallbackToCommand = cmdVerb != null && "function".equals(cmdVerb.commandName());
				CompletableFuture<FunctionPreview> future = functionPreviewResolver.apply(fnId);
				if (future != null) {
					future.whenComplete((preview, error) -> {
						Runnable apply = () -> {
							if (gen != hoverGeneration) {
								return;
							}
							if (error == null && preview != null) {
								activeHoverTooltip = new HoverTooltipState(HoverKind.FUNCTION_PREVIEW, anchorRow, List.of(), preview);
							} else if (fallbackToCommand) {
								List<String> sigs = commandHoverResolver.apply(lineText, hoverCol);
								if (sigs != null && !sigs.isEmpty()) {
									activeHoverTooltip = new HoverTooltipState(HoverKind.COMMAND_SIGNATURES, anchorRow, List.copyOf(sigs), null);
								}
							}
						};
						Minecraft mc = Minecraft.getInstance();
						if (mc != null && !mc.isSameThread()) {
							mc.execute(apply);
						} else {
							apply.run();
						}
					});
				}
			} else {
				List<String> sigs = commandHoverResolver.apply(lineText, hoverCol);
				if (sigs != null && !sigs.isEmpty()) {
					activeHoverTooltip = new HoverTooltipState(HoverKind.COMMAND_SIGNATURES, anchorRow, List.copyOf(sigs), null);
				}
			}
		}

		return activeHoverTooltip;
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
		HoverTooltipState state = evaluateHoverAt(font, tab, mouseX, mouseY);
		if (!state.isVisible()) {
			return;
		}
		if (state.kind() == HoverKind.FUNCTION_PREVIEW && state.functionPreview() != null) {
			renderFunctionPreviewTooltip(graphics, font, editorRect, tab, lineHeight, mouseX, state.anchorRow(), state.functionPreview());
			return;
		}
		List<String> signatures = state.signatures();
		if (signatures.isEmpty()) {
			return;
		}
		int maxLines = Math.min(2, signatures.size());
		int maxW = 0;
		for (int i = 0; i < maxLines; i++) {
			maxW = Math.max(maxW, QuillDraw.textWidth(font, signatures.get(i)));
		}
		int boxW = Math.min(Math.max(80, editorRect.width() - 20), maxW + 12);
		int boxH = maxLines * 11 + 6;
		int rowY = editorRect.y() + 4 + (state.anchorRow() - tab.scrollLine()) * lineHeight;
		int bx = Math.max(editorRect.x() + 6, Math.min(mouseX + 8, editorRect.right() - boxW - 6));
		int by = rowY - boxH - 3;
		if (by < editorRect.y() + 2) {
			by = Math.min(editorRect.bottom() - boxH - 4, rowY + lineHeight + 3);
		}
		QuillDraw.fill(graphics, bx - 1, by + 1, boxW + 3, boxH + 3, QuillTheme.Metrics.SHADOW_OUTER);
		QuillDraw.fill(graphics, bx, by, boxW, boxH, QuillTheme.BG_POPUP);
		QuillDraw.outline(graphics, bx, by, boxW, boxH, QuillTheme.BORDER_SUBTLE);
		for (int i = 0; i < maxLines; i++) {
			String text = QuillDraw.clipToWidth(font, signatures.get(i), boxW - 8);
			QuillDraw.text(graphics, font, text, bx + 4, by + 3 + i * 11, QuillTheme.SYNTAX_PROPERTY);
		}
	}

	private static void renderFunctionPreviewTooltip(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect editorRect,
			OpenTabState tab,
			int lineHeight,
			int mouseX,
			int anchorRow,
			FunctionPreview preview
	) {
		String title = preview.functionId() != null ? preview.functionId() : "";
		String path = preview.relativePath() != null ? preview.relativePath() : "";
		int maxW = Math.max(QuillDraw.textWidth(font, title), QuillDraw.textWidth(font, path));
		for (String hLine : preview.headerLines()) {
			maxW = Math.max(maxW, QuillDraw.textWidth(font, hLine));
		}
		int boxW = Math.min(Math.max(120, editorRect.width() - 20), Math.min(340, Math.max(140, maxW + 14)));
		int headerH = path.isBlank() ? 15 : 26;
		int bodyLineCount = preview.headerLines().isEmpty() ? 1 : preview.headerLines().size();
		if (preview.truncated()) {
			bodyLineCount++;
		}
		int bodyH = bodyLineCount * 11 + 6;
		int boxH = headerH + 1 + bodyH;

		int rowY = editorRect.y() + 4 + (anchorRow - tab.scrollLine()) * lineHeight;
		int bx = Math.max(editorRect.x() + 6, Math.min(mouseX + 8, editorRect.right() - boxW - 6));
		int by = rowY - boxH - 3;
		if (by < editorRect.y() + 2) {
			by = Math.min(editorRect.bottom() - boxH - 4, rowY + lineHeight + 3);
		}

		QuillDraw.fill(graphics, bx - 1, by + 1, boxW + 3, boxH + 3, QuillTheme.Metrics.SHADOW_OUTER);
		QuillDraw.fill(graphics, bx, by, boxW, boxH, QuillTheme.BG_POPUP);
		QuillDraw.fill(graphics, bx + 1, by + 1, boxW - 2, headerH - 1, QuillTheme.BG_TAB_INACTIVE);
		QuillDraw.outline(graphics, bx, by, boxW, boxH, QuillTheme.BORDER_SUBTLE);

		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, title, boxW - 10), bx + 5, by + 3, QuillTheme.SYNTAX_COMMAND);
		if (!path.isBlank()) {
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, path, boxW - 10), bx + 5, by + 14, QuillTheme.TEXT_MUTED);
		}
		QuillDraw.hLine(graphics, bx + 1, boxW - 2, by + headerH, QuillTheme.BORDER_SUBTLE);

		int bodyY = by + headerH + 4;
		QuillDraw.beginClip(graphics, new IdeLayout.Rect(bx + 4, by + headerH + 1, boxW - 8, bodyH));
		if (preview.headerLines().isEmpty()) {
			QuillDraw.text(graphics, font, "(empty file)", bx + 5, bodyY, QuillTheme.TEXT_MUTED);
		} else {
			for (int i = 0; i < preview.headerLines().size(); i++) {
				String codeLine = preview.headerLines().get(i);
				int ly = bodyY + i * 11;
				List<SyntaxHighlighter.TokenSpan> spans = SyntaxHighlighter.tokenizeLine(codeLine, SyntaxHighlighter.Language.MCFUNCTION);
				for (SyntaxHighlighter.TokenSpan span : spans) {
					if (span.end() <= span.start()) {
						continue;
					}
					String part = codeLine.substring(span.start(), span.end());
					int lx = bx + 5 + (span.start() == 0 ? 0 : QuillDraw.textWidth(font, codeLine.substring(0, span.start())));
					QuillDraw.text(graphics, font, part, lx, ly, span.color());
				}
			}
			if (preview.truncated()) {
				int ly = bodyY + preview.headerLines().size() * 11;
				QuillDraw.text(graphics, font, "...", bx + 5, ly, QuillTheme.TEXT_MUTED);
			}
		}
		QuillDraw.endClip(graphics);
	}

	public boolean mouseClicked(MouseButtonEvent event, Font font, OpenTabState tab) {
		resetHoverState();
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			if (findBarOpen && findBarRect.contains(event.x(), event.y())) {
				return true;
			}
			if (lastCodeViewport.contains(event.x(), event.y()) || lastGutterRect.contains(event.x(), event.y())) {
				dismissAutocomplete();
				findBarFocused = false;
				if (lastCodeViewport.contains(event.x(), event.y())) {
					TextDocumentBuffer.Position pos = hitTest(font, tab, event.x(), event.y());
					if (!isPositionWithinSelection(tab.buffer(), pos)) {
						tab.buffer().setCursor(pos.line(), pos.column(), false);
					}
				} else {
					int lineIndex = hitTestLine(font, tab, event.y());
					if (!isPositionWithinSelection(tab.buffer(), new TextDocumentBuffer.Position(lineIndex, 0))) {
						tab.buffer().setCursor(lineIndex, 0, false);
					}
				}
				touchCaret();
				List<ContextMenuOverlay.MenuEntry> entries = buildContextMenuEntries(font, tab);
				if (!entries.isEmpty()) {
					contextMenuOpener.accept(new ContextMenuOverlay(
							(int) Math.round(event.x()),
							(int) Math.round(event.y()),
							entries,
							contextMenuDismisser
					));
				}
				return true;
			}
			return false;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (findBarOpen && findBarRect.contains(event.x(), event.y())) {
			dismissAutocomplete();
			findBarFocused = true;
			if (findCloseBtnRect.contains(event.x(), event.y())) {
				closeFindBar();
				touchCaret();
				return true;
			}
			if (findPrevBtnRect.contains(event.x(), event.y())) {
				jumpToFindMatch(font, tab, -1);
				return true;
			}
			if (findNextBtnRect.contains(event.x(), event.y())) {
				jumpToFindMatch(font, tab, 1);
				return true;
			}
			if (findInputRect.contains(event.x(), event.y())) {
				findInput.mouseClicked(event.x(), font, event.hasShiftDown());
			}
			return true;
		}
		findBarFocused = false;

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
		if (event.hasControlDownWithQuirk() && !event.hasAltDown() && !event.hasShiftDown()
				&& ctrlClickHandler.test(new CtrlClickRequest(tab, pos))) {
			dragMode = DragMode.NONE;
			touchCaret();
			return true;
		}
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
		if (findBarOpen && findInput.isDragging() && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			return findInput.mouseDragged(event.x(), font);
		}
		if (dragMode == DragMode.NONE || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		// Auto-scroll gently when dragging outside viewport edges
		if (event.y() < lastCodeViewport.y()) {
			tab.setScrollLine(Math.max(0, tab.scrollLine() - 1));
		} else if (event.y() > lastCodeViewport.bottom()) {
			int lineHeight = scaledLineHeight(font, textScale());
			int visibleLines = Math.max(1, lastCodeViewport.height() / lineHeight);
			int totalRows = !cachedVisualRows.isEmpty() ? cachedVisualRows.size() : tab.buffer().lineCount();
			int maxScroll = Math.max(0, totalRows - visibleLines);
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
		findInput.mouseReleased();
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			dragMode = DragMode.NONE;
		}
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY, Font font, OpenTabState tab) {
		return mouseScrolled(mouseX, mouseY, scrollX, scrollY, font, tab, isShiftDownSafe());
	}

	public boolean mouseScrolled(
			double mouseX,
			double mouseY,
			double scrollX,
			double scrollY,
			Font font,
			OpenTabState tab,
			boolean shiftDown
	) {
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
		resetHoverState();
		boolean wordWrap = QuillClientConfig.current().wordWrap();
		if (shiftDown && scrollY != 0) {
			if (!wordWrap) {
				int step = (int) Math.round(-scrollY * 28.0);
				if (step == 0) {
					step = scrollY > 0 ? -28 : 28;
				}
				tab.setScrollX(Math.max(0, tab.scrollX() + step));
			}
			return true;
		}
		int lineHeight = scaledLineHeight(font, textScale());
		int visibleLines = Math.max(1, lastCodeViewport.height() / lineHeight);
		int totalRows = !cachedVisualRows.isEmpty() ? cachedVisualRows.size() : tab.buffer().lineCount();
		int maxScrollLine = Math.max(0, totalRows - visibleLines);
		if (scrollY != 0) {
			tab.setScrollLine(Math.min(maxScrollLine, tab.scrollLine() - (int) Math.signum(scrollY) * 3));
		}
		if (scrollX != 0 && !wordWrap) {
			tab.setScrollX(tab.scrollX() - (int) (scrollX * 24));
		}
		return true;
	}

	public boolean keyPressed(KeyEvent event, Font font, OpenTabState tab) {
		TextDocumentBuffer buffer = tab.buffer();
		boolean shift = event.hasShiftDown();
		boolean ctrl = event.hasControlDownWithQuirk();
		boolean alt = event.hasAltDown();

		// Ctrl+F opens/focuses in-file Find bar
		if (ctrl && !alt && !shift && matchesLetter(event, InputConstants.KEY_F, 'f')) {
			openFindBar(tab);
			if (!findInput.text().isEmpty()) {
				jumpToNearestFindMatch(font, tab);
			}
			return true;
		}

		// Alt+Z toggles soft word wrap
		if (alt && !ctrl && !shift && matchesLetter(event, InputConstants.KEY_Z, 'z')) {
			QuillClientConfig cfg = QuillClientConfig.current();
			boolean wrapped = cfg.toggleWordWrap();
			if (wrapped) {
				tab.setScrollX(0);
			}
			cachedVisualRows = List.of();
			QuillClientConfig.saveCurrent();
			ensureCaretVisible(font, tab);
			return true;
		}

		// In-file Find bar input handling takes precedence when focused
		if (isFindBarFocused()) {
			if (event.isEscape()) {
				closeFindBar();
				touchCaret();
				return true;
			}
			if (event.isConfirmation()) {
				jumpToFindMatch(font, tab, shift ? -1 : 1);
				return true;
			}
			SingleLineTextInput.KeyActionResult res = findInput.keyPressed(event);
			if (res.handled()) {
				if (res.textChanged()) {
					jumpToNearestFindMatch(font, tab);
				}
				return true;
			}
		}

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
		QuillClientConfig cfg = QuillClientConfig.current();
		boolean redoShortcut = cfg.shortcut(QuillClientConfig.ShortcutAction.REDO).matches(event);
		boolean undoShortcut = cfg.shortcut(QuillClientConfig.ShortcutAction.UNDO).matches(event);
		if (redoShortcut || undoShortcut) {
			boolean changed = redoShortcut ? buffer.redo() : buffer.undo();
			if (changed) {
				dismissAutocomplete();
				touchCaret();
				ensureCaretVisible(font, tab);
				onBufferEdited.accept(tab);
			}
			return true;
		}
		if (ctrl && !shift && !alt && event.shortcutKey() == InputConstants.KEYCODE_Y) {
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
		if (isFindBarFocused()) {
			boolean changed = findInput.charTyped(event);
			if (changed) {
				jumpToNearestFindMatch(font, tab);
			}
			return changed;
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

	private List<WordWrapLayout.VisualRow> visualRowsFor(Font font, OpenTabState tab, float scale) {
		if (!cachedVisualRows.isEmpty()) {
			return cachedVisualRows;
		}
		TextDocumentBuffer buffer = tab.buffer();
		int wrapMaxWidth = Math.max(40, lastCodeViewport.width() - CODE_LEFT_PADDING - 8);
		List<String> rawLines = new ArrayList<>(buffer.lineCount());
		for (int i = 0; i < buffer.lineCount(); i++) {
			rawLines.add(buffer.line(i));
		}
		ToIntFunction<String> measurer = font != null
				? s -> QuillDraw.textWidth(font, s, scale)
				: s -> s.length() * 6;
		return WordWrapLayout.wrapLines(rawLines, QuillClientConfig.current().wordWrap(), wrapMaxWidth, measurer);
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

		if (QuillClientConfig.current().wordWrap()) {
			List<WordWrapLayout.VisualRow> rows = visualRowsFor(font, tab, scale);
			int caretRow = WordWrapLayout.visualRowFor(rows, cursor.line(), cursor.column());
			if (caretRow < tab.scrollLine()) {
				tab.setScrollLine(caretRow);
			} else if (caretRow >= tab.scrollLine() + visibleLines) {
				tab.setScrollLine(caretRow - visibleLines + 1);
			}
			tab.setScrollX(0);
			return;
		}

		if (cursor.line() < tab.scrollLine()) {
			tab.setScrollLine(cursor.line());
		} else if (cursor.line() >= tab.scrollLine() + visibleLines) {
			tab.setScrollLine(cursor.line() - visibleLines + 1);
		}

		String lineText = buffer.line(cursor.line());
		String prefix = lineText.substring(0, Math.min(cursor.column(), lineText.length()));
		int caretPixelX = font != null ? QuillDraw.textWidth(font, prefix, scale) : prefix.length() * 6;
		int viewW = Math.max(20, lastCodeViewport.width() - CODE_LEFT_PADDING - 12);
		if (caretPixelX < tab.scrollX()) {
			tab.setScrollX(Math.max(0, caretPixelX - 16));
		} else if (caretPixelX > tab.scrollX() + viewW) {
			tab.setScrollX(caretPixelX - viewW + 16);
		}
	}

	private int hitTestLine(Font font, OpenTabState tab, double mouseY) {
		int lineHeight = scaledLineHeight(font, textScale());
		int rowIdx = tab.scrollLine() + Math.max(0, ((int) mouseY - (lastCodeViewport.y() + 4)) / lineHeight);
		if (QuillClientConfig.current().wordWrap() && !cachedVisualRows.isEmpty()) {
			int v = Math.max(0, Math.min(cachedVisualRows.size() - 1, rowIdx));
			return cachedVisualRows.get(v).logicalLine();
		}
		return Math.max(0, Math.min(tab.buffer().lineCount() - 1, rowIdx));
	}

	private TextDocumentBuffer.Position hitTest(Font font, OpenTabState tab, double mouseX, double mouseY) {
		float scale = textScale();
		int lineHeight = scaledLineHeight(font, scale);
		int rowIdx = tab.scrollLine() + Math.max(0, ((int) mouseY - (lastCodeViewport.y() + 4)) / lineHeight);
		if (QuillClientConfig.current().wordWrap() && !cachedVisualRows.isEmpty()) {
			int v = Math.max(0, Math.min(cachedVisualRows.size() - 1, rowIdx));
			WordWrapLayout.VisualRow vr = cachedVisualRows.get(v);
			String fullLine = tab.buffer().line(vr.logicalLine());
			int segStart = Math.min(vr.startCol(), fullLine.length());
			int segEnd = Math.min(vr.endCol(), fullLine.length());
			String segText = fullLine.substring(segStart, segEnd);
			int relX = Math.max(0, (int) mouseX - (lastCodeViewport.x() + CODE_LEFT_PADDING));
			int localCol = font != null ? QuillDraw.charIndexAtPixel(font, segText, relX, scale) : Math.min(segText.length(), relX / 6);
			return new TextDocumentBuffer.Position(vr.logicalLine(), segStart + localCol);
		}
		int lineIndex = Math.max(0, Math.min(tab.buffer().lineCount() - 1, rowIdx));
		String lineText = tab.buffer().line(lineIndex);
		int relX = Math.max(0, (int) mouseX - (lastCodeViewport.x() + CODE_LEFT_PADDING - tab.scrollX()));
		int col = font != null ? QuillDraw.charIndexAtPixel(font, lineText, relX, scale) : Math.min(lineText.length(), relX / 6);
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

	private static boolean matchesLetter(KeyEvent event, int keyCode, char lower) {
		return event.key() == keyCode
				|| event.shortcutKey() == keyCode
				|| Character.toLowerCase((char) event.shortcutKey()) == lower;
	}

	private String getClipboardSafe() {
		String val = clipboardGetter.get();
		return val != null ? val : "";
	}

	private void setClipboardSafe(String value) {
		clipboardSetter.accept(value != null ? value : "");
	}

	private static String readMinecraftClipboard() {
		Minecraft mc = Minecraft.getInstance();
		return (mc != null && mc.keyboardHandler != null) ? mc.keyboardHandler.getClipboard() : "";
	}

	private static void writeMinecraftClipboard(String value) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.keyboardHandler != null) {
			mc.keyboardHandler.setClipboard(value);
		}
	}

	private static boolean isShiftDownSafe() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.hasShiftDown();
	}

	private static boolean isControlDownSafe() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.hasControlDown();
	}
}
