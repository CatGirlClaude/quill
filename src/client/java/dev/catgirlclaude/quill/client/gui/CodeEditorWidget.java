package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.editor.TextDocumentBuffer;
import dev.catgirlclaude.quill.client.session.OpenTabState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Multi-line code editor widget using proportional {@link Font} metrics for caret positioning,
 * selection highlighting, mouse click/drag selection, clipboard, undo/redo, and 2D scrolling.
 */
public final class CodeEditorWidget {
	private static final int LINE_PADDING_Y = 2;
	private static final int CODE_LEFT_PADDING = 6;
	private static final int SCROLLBAR_SIZE = 5;

	private IdeLayout.Rect lastEditorRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect lastCodeViewport = new IdeLayout.Rect(0, 0, 0, 0);
	private boolean draggingSelection = false;

	public void render(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect editorRect,
			OpenTabState tab,
			int mouseX,
			int mouseY
	) {
		lastEditorRect = editorRect;
		TextDocumentBuffer buffer = tab.buffer();

		int lineHeight = font.lineHeight + LINE_PADDING_Y;
		int gutterWidth = Math.max(30, font.width(String.valueOf(Math.max(1, buffer.lineCount()))) + 14);
		int codeX = editorRect.x() + gutterWidth;
		int codeW = Math.max(1, editorRect.width() - gutterWidth - SCROLLBAR_SIZE);
		int codeH = Math.max(1, editorRect.height() - SCROLLBAR_SIZE);
		lastCodeViewport = new IdeLayout.Rect(codeX, editorRect.y(), codeW, codeH);

		if (tab.consumeScrollToCaret()) {
			ensureCaretVisible(font, tab);
		}

		int visibleLines = Math.max(1, codeH / lineHeight);
		int maxScrollLine = Math.max(0, buffer.lineCount() - visibleLines);
		tab.setScrollLine(Math.min(maxScrollLine, tab.scrollLine()));

		int maxLineWidth = Math.max(
				computeMaxLineWidth(font, buffer, tab.scrollLine(), visibleLines),
				font.width(buffer.line(buffer.cursor().line()))
		);
		int maxScrollX = Math.max(0, maxLineWidth - codeW + 24);
		tab.setScrollX(Math.min(maxScrollX, tab.scrollX()));

		// Gutter background
		QuillDraw.fill(graphics, editorRect.x(), editorRect.y(), gutterWidth, editorRect.height(), QuillTheme.BG_GUTTER);
		QuillDraw.vLine(graphics, codeX - 1, editorRect.y(), editorRect.height(), QuillTheme.BORDER_SUBTLE);

		if (lastCodeViewport.contains(mouseX, mouseY)) {
			QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
		}

		// Render gutter and code lines
		int firstLine = tab.scrollLine();
		int lastLine = Math.min(buffer.lineCount() - 1, firstLine + visibleLines);
		TextDocumentBuffer.Position cursor = buffer.cursor();
		boolean hasSel = buffer.hasSelection();
		TextDocumentBuffer.Position selStart = buffer.selectionStart();
		TextDocumentBuffer.Position selEnd = buffer.selectionEnd();

		QuillDraw.beginClip(graphics, new IdeLayout.Rect(editorRect.x(), editorRect.y(), editorRect.width(), codeH));
		for (int i = firstLine; i <= lastLine; i++) {
			int rowY = editorRect.y() + 4 + (i - firstLine) * lineHeight;
			boolean isCurrentLine = i == cursor.line();

			if (isCurrentLine) {
				QuillDraw.fill(graphics, codeX, rowY - 1, codeW, lineHeight, QuillTheme.LINE_HIGHLIGHT);
			}

			String lineNumStr = String.valueOf(i + 1);
			int numX = codeX - 6 - font.width(lineNumStr);
			QuillDraw.text(graphics, font, lineNumStr, numX, rowY,
					isCurrentLine ? QuillTheme.TEXT_PRIMARY : QuillTheme.TEXT_MUTED);
		}
		QuillDraw.endClip(graphics);

		QuillDraw.beginClip(graphics, lastCodeViewport);
		for (int i = firstLine; i <= lastLine; i++) {
			int rowY = editorRect.y() + 4 + (i - firstLine) * lineHeight;
			String lineText = buffer.line(i);
			int drawX = codeX + CODE_LEFT_PADDING - tab.scrollX();

			// Selection highlight for this line
			if (hasSel && i >= selStart.line() && i <= selEnd.line()) {
				int startCol = (i == selStart.line()) ? selStart.column() : 0;
				int endCol = (i == selEnd.line()) ? selEnd.column() : lineText.length();
				int x1 = drawX + font.width(lineText.substring(0, Math.min(startCol, lineText.length())));
				int x2 = drawX + font.width(lineText.substring(0, Math.min(endCol, lineText.length())));
				if (i < selEnd.line() && endCol == lineText.length()) {
					x2 += 6;
				}
				if (x2 > x1) {
					QuillDraw.fill(graphics, x1, rowY - 1, x2 - x1, lineHeight, QuillTheme.SELECTION_BG);
				}
			}

			QuillDraw.text(graphics, font, lineText, drawX, rowY, QuillTheme.TEXT_PRIMARY);

			// Blinking caret
			if (i == cursor.line() && (System.currentTimeMillis() / 500L) % 2L == 0L) {
				int col = Math.min(cursor.column(), lineText.length());
				int caretX = drawX + font.width(lineText.substring(0, col));
				QuillDraw.fill(graphics, caretX, rowY - 1, 1, lineHeight, QuillTheme.CURSOR_COLOR);
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
	}

	public boolean mouseClicked(MouseButtonEvent event, Font font, OpenTabState tab) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (!lastCodeViewport.contains(event.x(), event.y())) {
			return false;
		}
		TextDocumentBuffer.Position pos = hitTest(font, tab, event.x(), event.y());
		tab.buffer().setCursor(pos.line(), pos.column(), event.hasShiftDown());
		draggingSelection = true;
		ensureCaretVisible(font, tab);
		return true;
	}

	public boolean mouseDragged(MouseButtonEvent event, Font font, OpenTabState tab) {
		if (!draggingSelection || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		TextDocumentBuffer.Position pos = hitTest(font, tab, event.x(), event.y());
		tab.buffer().setCursor(pos.line(), pos.column(), true);
		ensureCaretVisible(font, tab);
		return true;
	}

	public void mouseReleased(MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			draggingSelection = false;
		}
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY, Font font, OpenTabState tab) {
		if (!lastEditorRect.contains(mouseX, mouseY)) {
			return false;
		}
		int lineHeight = font.lineHeight + LINE_PADDING_Y;
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

		if (event.isSelectAll()) {
			buffer.selectAll();
			return true;
		}
		if (event.isCopy()) {
			String selected = buffer.getSelectedText();
			if (!selected.isEmpty()) {
				Minecraft.getInstance().keyboardHandler.setClipboard(selected);
			}
			return true;
		}
		if (event.isCut()) {
			String selected = buffer.getSelectedText();
			if (!selected.isEmpty()) {
				Minecraft.getInstance().keyboardHandler.setClipboard(selected);
				buffer.deleteSelection();
				ensureCaretVisible(font, tab);
			}
			return true;
		}
		if (event.isPaste()) {
			String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
			if (clip != null && !clip.isEmpty()) {
				buffer.insertText(clip);
				ensureCaretVisible(font, tab);
			}
			return true;
		}
		if (ctrl && !event.hasAltDown() && event.shortcutKey() == InputConstants.KEYCODE_Z) {
			boolean changed = shift ? buffer.redo() : buffer.undo();
			if (changed) {
				ensureCaretVisible(font, tab);
			}
			return true;
		}
		if (ctrl && !shift && !event.hasAltDown() && event.shortcutKey() == InputConstants.KEYCODE_Y) {
			if (buffer.redo()) {
				ensureCaretVisible(font, tab);
			}
			return true;
		}

		if (event.isConfirmation()) {
			buffer.insertNewline();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.isCycleFocus()) {
			buffer.insertTab();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_BACKSPACE || event.key() == InputConstants.KEY_BACKSPACE) {
			buffer.backspace();
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_DELETE || event.key() == InputConstants.KEY_DELETE) {
			buffer.deleteForward();
			ensureCaretVisible(font, tab);
			return true;
		}

		if (event.isLeft()) {
			buffer.moveLeft(shift, ctrl);
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.isRight()) {
			buffer.moveRight(shift, ctrl);
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.isUp()) {
			buffer.moveVertical(-1, shift);
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.isDown()) {
			buffer.moveVertical(1, shift);
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_HOME || event.key() == InputConstants.KEY_HOME) {
			buffer.moveHome(shift, ctrl);
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_END || event.key() == InputConstants.KEY_END) {
			buffer.moveEnd(shift, ctrl);
			ensureCaretVisible(font, tab);
			return true;
		}
		int pageLines = Math.max(1, lastCodeViewport.height() / Math.max(1, font.lineHeight + LINE_PADDING_Y));
		if (event.shortcutKey() == InputConstants.KEYCODE_PAGEUP || event.key() == InputConstants.KEY_PAGEUP) {
			buffer.moveVertical(-pageLines, shift);
			ensureCaretVisible(font, tab);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_PAGEDOWN || event.key() == InputConstants.KEY_PAGEDOWN) {
			buffer.moveVertical(pageLines, shift);
			ensureCaretVisible(font, tab);
			return true;
		}

		return false;
	}

	public boolean charTyped(CharacterEvent event, Font font, OpenTabState tab) {
		if (!event.isAllowedChatCharacter()) {
			return false;
		}
		tab.buffer().insertText(event.codepointAsString());
		ensureCaretVisible(font, tab);
		return true;
	}

	public void ensureCaretVisible(Font font, OpenTabState tab) {
		if (lastCodeViewport.width() <= 0 || lastCodeViewport.height() <= 0) {
			return;
		}
		TextDocumentBuffer buffer = tab.buffer();
		TextDocumentBuffer.Position cursor = buffer.cursor();
		int lineHeight = font.lineHeight + LINE_PADDING_Y;
		int visibleLines = Math.max(1, (lastCodeViewport.height() - 6) / lineHeight);

		if (cursor.line() < tab.scrollLine()) {
			tab.setScrollLine(cursor.line());
		} else if (cursor.line() >= tab.scrollLine() + visibleLines) {
			tab.setScrollLine(cursor.line() - visibleLines + 1);
		}

		String lineText = buffer.line(cursor.line());
		int caretPixelX = font.width(lineText.substring(0, Math.min(cursor.column(), lineText.length())));
		int viewW = Math.max(20, lastCodeViewport.width() - CODE_LEFT_PADDING - 12);
		if (caretPixelX < tab.scrollX()) {
			tab.setScrollX(Math.max(0, caretPixelX - 16));
		} else if (caretPixelX > tab.scrollX() + viewW) {
			tab.setScrollX(caretPixelX - viewW + 16);
		}
	}

	private TextDocumentBuffer.Position hitTest(Font font, OpenTabState tab, double mouseX, double mouseY) {
		TextDocumentBuffer buffer = tab.buffer();
		int lineHeight = font.lineHeight + LINE_PADDING_Y;
		int lineIndex = tab.scrollLine() + Math.max(0, ((int) mouseY - (lastCodeViewport.y() + 4)) / lineHeight);
		lineIndex = Math.max(0, Math.min(buffer.lineCount() - 1, lineIndex));

		String lineText = buffer.line(lineIndex);
		int relX = Math.max(0, (int) mouseX - (lastCodeViewport.x() + CODE_LEFT_PADDING - tab.scrollX()));
		String sub = font.plainSubstrByWidth(lineText, relX);
		int col = sub.length();
		if (col < lineText.length()) {
			int wBefore = font.width(sub);
			int wAfter = font.width(lineText.substring(0, col + 1));
			if (relX - wBefore > wAfter - relX) {
				col++;
			}
		}
		return new TextDocumentBuffer.Position(lineIndex, col);
	}

	private static int computeMaxLineWidth(Font font, TextDocumentBuffer buffer, int firstLine, int visibleLines) {
		int max = 0;
		int end = Math.min(buffer.lineCount(), firstLine + visibleLines + 1);
		for (int i = firstLine; i < end; i++) {
			max = Math.max(max, font.width(buffer.line(i)));
		}
		return max;
	}
}
