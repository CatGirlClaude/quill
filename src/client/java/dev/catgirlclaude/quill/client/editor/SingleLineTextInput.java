package dev.catgirlclaude.quill.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import dev.catgirlclaude.quill.client.gui.IdeLayout;
import dev.catgirlclaude.quill.client.gui.QuillDraw;
import dev.catgirlclaude.quill.client.gui.QuillTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Reusable single-line text input with full text selection (mouse click, drag,
 * double-click word, triple-click all, {@code Shift+Arrows}, {@code Ctrl+Shift+Arrows},
 * {@code Shift+Home/End}, {@code Ctrl+A/C/X/V}, {@code Ctrl+Backspace/Delete}),
 * horizontal scroll, and custom-font-aware rendering.
 */
public final class SingleLineTextInput {
	private static final long MULTI_CLICK_WINDOW_MS = 400L;

	public record KeyActionResult(boolean handled, boolean textChanged) {
		public static final KeyActionResult NOT_HANDLED = new KeyActionResult(false, false);
		public static final KeyActionResult HANDLED_UNCHANGED = new KeyActionResult(true, false);
		public static final KeyActionResult HANDLED_CHANGED = new KeyActionResult(true, true);
	}

	private enum DragMode {
		NONE,
		CHAR,
		WORD,
		ALL
	}

	private final StringBuilder text = new StringBuilder();
	private int cursor = 0;
	private int selectionAnchor = -1;
	private int scrollX = 0;
	private long lastInteractionMs = System.currentTimeMillis();

	private long lastClickMs = 0L;
	private int lastClickIndex = -1;
	private int clickCount = 0;
	private DragMode dragMode = DragMode.NONE;
	private int wordAnchorStart = 0;
	private int wordAnchorEnd = 0;
	private IdeLayout.Rect lastBoxRect = new IdeLayout.Rect(0, 0, 0, 0);

	public SingleLineTextInput() {
	}

	public SingleLineTextInput(String initialText) {
		setText(initialText);
	}

	public String text() {
		return text.toString();
	}

	public int length() {
		return text.length();
	}

	public boolean isEmpty() {
		return text.isEmpty();
	}

	public void setText(String value) {
		text.setLength(0);
		if (value != null && !value.isEmpty()) {
			text.append(value.replace("\r", "").replace("\n", ""));
		}
		cursor = text.length();
		selectionAnchor = -1;
		scrollX = 0;
		touch();
	}

	public int cursor() {
		return cursor;
	}

	public boolean hasSelection() {
		return selectionAnchor >= 0 && selectionAnchor != cursor;
	}

	public int selectionStart() {
		return hasSelection() ? Math.min(selectionAnchor, cursor) : cursor;
	}

	public int selectionEnd() {
		return hasSelection() ? Math.max(selectionAnchor, cursor) : cursor;
	}

	public String selectedText() {
		if (!hasSelection()) {
			return "";
		}
		return text.substring(selectionStart(), selectionEnd());
	}

	public void setCursor(int pos, boolean extendSelection) {
		int clamped = clamp(pos);
		if (extendSelection) {
			if (selectionAnchor < 0) {
				selectionAnchor = cursor;
			}
		} else {
			selectionAnchor = -1;
		}
		cursor = clamped;
		if (selectionAnchor == cursor) {
			selectionAnchor = -1;
		}
		touch();
	}

	public void selectAll() {
		if (text.isEmpty()) {
			cursor = 0;
			selectionAnchor = -1;
		} else {
			selectionAnchor = 0;
			cursor = text.length();
		}
		touch();
	}

	public void selectRange(int start, int end) {
		int s = clamp(start);
		int e = clamp(end);
		if (s == e) {
			selectionAnchor = -1;
			cursor = s;
		} else {
			selectionAnchor = s;
			cursor = e;
		}
		touch();
	}

	public void clearSelection() {
		selectionAnchor = -1;
	}

	public boolean insertText(String raw) {
		if (raw == null || raw.isEmpty()) {
			return false;
		}
		String clean = raw.replace("\r", "").replace("\n", "");
		if (clean.isEmpty()) {
			return false;
		}
		if (hasSelection()) {
			deleteSelectionInternal();
		}
		text.insert(cursor, clean);
		cursor += clean.length();
		selectionAnchor = -1;
		touch();
		return true;
	}

	public boolean backspace(boolean word) {
		if (hasSelection()) {
			deleteSelectionInternal();
			touch();
			return true;
		}
		if (cursor == 0) {
			return false;
		}
		int target = word ? findPrevWordBoundary(cursor) : cursor - 1;
		text.delete(target, cursor);
		cursor = target;
		touch();
		return true;
	}

	public boolean deleteForward(boolean word) {
		if (hasSelection()) {
			deleteSelectionInternal();
			touch();
			return true;
		}
		if (cursor >= text.length()) {
			return false;
		}
		int target = word ? findNextWordBoundary(cursor) : cursor + 1;
		text.delete(cursor, target);
		touch();
		return true;
	}

	public void moveLeft(boolean extendSelection, boolean wordJump) {
		if (!extendSelection && hasSelection()) {
			setCursor(selectionStart(), false);
			return;
		}
		int target = wordJump ? findPrevWordBoundary(cursor) : Math.max(0, cursor - 1);
		setCursor(target, extendSelection);
	}

	public void moveRight(boolean extendSelection, boolean wordJump) {
		if (!extendSelection && hasSelection()) {
			setCursor(selectionEnd(), false);
			return;
		}
		int target = wordJump ? findNextWordBoundary(cursor) : Math.min(text.length(), cursor + 1);
		setCursor(target, extendSelection);
	}

	public void moveHome(boolean extendSelection) {
		setCursor(0, extendSelection);
	}

	public void moveEnd(boolean extendSelection) {
		setCursor(text.length(), extendSelection);
	}

	public void selectWordAt(int index) {
		int[] bounds = wordBoundsAt(index);
		selectRange(bounds[0], bounds[1]);
	}

	public KeyActionResult keyPressed(KeyEvent event) {
		boolean shift = event.hasShiftDown();
		boolean ctrl = event.hasControlDownWithQuirk();

		if (event.isSelectAll()) {
			selectAll();
			return KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.isCopy()) {
			if (hasSelection()) {
				setClipboardSafe(selectedText());
			}
			return KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.isCut()) {
			if (hasSelection()) {
				setClipboardSafe(selectedText());
				deleteSelectionInternal();
				touch();
				return KeyActionResult.HANDLED_CHANGED;
			}
			return KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.isPaste()) {
			String clip = getClipboardSafe();
			if (clip != null && !clip.isEmpty()) {
				boolean changed = insertText(clip);
				return changed ? KeyActionResult.HANDLED_CHANGED : KeyActionResult.HANDLED_UNCHANGED;
			}
			return KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_BACKSPACE || event.key() == InputConstants.KEY_BACKSPACE) {
			return backspace(ctrl) ? KeyActionResult.HANDLED_CHANGED : KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_DELETE || event.key() == InputConstants.KEY_DELETE) {
			return deleteForward(ctrl) ? KeyActionResult.HANDLED_CHANGED : KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.isLeft()) {
			moveLeft(shift, ctrl);
			return KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.isRight()) {
			moveRight(shift, ctrl);
			return KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_HOME || event.key() == InputConstants.KEY_HOME) {
			moveHome(shift);
			return KeyActionResult.HANDLED_UNCHANGED;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_END || event.key() == InputConstants.KEY_END) {
			moveEnd(shift);
			return KeyActionResult.HANDLED_UNCHANGED;
		}
		return KeyActionResult.NOT_HANDLED;
	}

	public boolean charTyped(CharacterEvent event) {
		if (!event.isAllowedChatCharacter()) {
			return false;
		}
		return insertText(event.codepointAsString());
	}

	public void mouseClicked(double mouseX, Font font, boolean shiftDown) {
		int idx = hitTestIndex(mouseX, font);
		long now = System.currentTimeMillis();
		if (!shiftDown && now - lastClickMs <= MULTI_CLICK_WINDOW_MS && Math.abs(idx - lastClickIndex) <= 1) {
			clickCount = (clickCount % 3) + 1;
		} else {
			clickCount = 1;
		}
		lastClickMs = now;
		lastClickIndex = idx;

		if (shiftDown) {
			setCursor(idx, true);
			dragMode = DragMode.CHAR;
		} else if (clickCount == 2) {
			int[] b = wordBoundsAt(idx);
			wordAnchorStart = b[0];
			wordAnchorEnd = b[1];
			selectRange(b[0], b[1]);
			dragMode = DragMode.WORD;
		} else if (clickCount == 3) {
			selectAll();
			dragMode = DragMode.ALL;
		} else {
			setCursor(idx, false);
			dragMode = DragMode.CHAR;
		}
	}

	public boolean mouseDragged(double mouseX, Font font) {
		if (dragMode == DragMode.NONE) {
			return false;
		}
		if (dragMode == DragMode.ALL) {
			selectAll();
			return true;
		}
		int idx = hitTestIndex(mouseX, font);
		if (dragMode == DragMode.WORD) {
			int[] b = wordBoundsAt(idx);
			if (b[0] < wordAnchorStart) {
				selectionAnchor = wordAnchorEnd;
				cursor = b[0];
			} else {
				selectionAnchor = wordAnchorStart;
				cursor = b[1];
			}
			touch();
		} else {
			setCursor(idx, true);
		}
		return true;
	}

	public void mouseReleased() {
		dragMode = DragMode.NONE;
	}

	public boolean isDragging() {
		return dragMode != DragMode.NONE;
	}

	public IdeLayout.Rect lastBoxRect() {
		return lastBoxRect;
	}

	public void render(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect rect,
			String placeholder,
			boolean focused
	) {
		this.lastBoxRect = rect;
		QuillDraw.fill(graphics, rect.x(), rect.y(), rect.width(), rect.height(), QuillTheme.BG_INPUT);
		QuillDraw.outline(graphics, rect.x(), rect.y(), rect.width(), rect.height(),
				focused ? QuillTheme.ACCENT_FOCUS : QuillTheme.BORDER_SUBTLE);

		String str = text.toString();
		int padX = 4;
		int innerW = Math.max(4, rect.width() - padX * 2);
		int clampedCursor = clamp(cursor);
		int cursorPx = QuillDraw.textWidth(font, str.substring(0, clampedCursor));
		if (cursorPx < scrollX) {
			scrollX = Math.max(0, cursorPx - 8);
		} else if (cursorPx > scrollX + innerW - 3) {
			scrollX = cursorPx - (innerW - 3);
		}
		int totalW = QuillDraw.textWidth(font, str);
		scrollX = Math.max(0, Math.min(Math.max(0, totalW - innerW + 6), scrollX));

		int textY = rect.y() + (rect.height() - font.lineHeight) / 2;
		int drawX = rect.x() + padX - scrollX;

		QuillDraw.beginClip(graphics, new IdeLayout.Rect(rect.x() + 2, rect.y() + 1, Math.max(0, rect.width() - 4), Math.max(0, rect.height() - 2)));
		if (hasSelection() && focused) {
			int selS = selectionStart();
			int selE = selectionEnd();
			int x1 = drawX + QuillDraw.textWidth(font, str.substring(0, selS));
			int x2 = drawX + QuillDraw.textWidth(font, str.substring(0, selE));
			if (x2 > x1) {
				QuillDraw.fill(graphics, x1, rect.y() + 2, x2 - x1, Math.max(1, rect.height() - 4), QuillTheme.SELECTION_BG);
			}
		}

		if (str.isEmpty() && placeholder != null && !placeholder.isEmpty()) {
			QuillDraw.text(graphics, font, placeholder, rect.x() + padX, textY, QuillTheme.TEXT_MUTED);
		} else if (!str.isEmpty()) {
			QuillDraw.text(graphics, font, str, drawX, textY, QuillTheme.TEXT_BRIGHT);
		}

		if (focused && isCaretVisible()) {
			QuillDraw.fill(graphics, drawX + cursorPx, rect.y() + 3, 1, Math.max(1, rect.height() - 6), QuillTheme.CURSOR_COLOR);
		}
		QuillDraw.endClip(graphics);
	}

	private int hitTestIndex(double mouseX, Font font) {
		int padX = 4;
		int relPx = (int) mouseX - (lastBoxRect.x() + padX - scrollX);
		return QuillDraw.charIndexAtPixel(font, text.toString(), Math.max(0, relPx), 1.0f);
	}

	private void deleteSelectionInternal() {
		int s = selectionStart();
		int e = selectionEnd();
		text.delete(s, e);
		cursor = s;
		selectionAnchor = -1;
	}

	private int clamp(int pos) {
		return Math.max(0, Math.min(text.length(), pos));
	}

	private int[] wordBoundsAt(int index) {
		int len = text.length();
		if (len == 0) {
			return new int[]{0, 0};
		}
		int probe = clamp(index);
		if (probe >= len) {
			probe = len - 1;
		} else if (probe > 0 && TextDocumentBuffer.charClass(text.charAt(probe)) == 0
				&& TextDocumentBuffer.charClass(text.charAt(probe - 1)) != 0) {
			probe--;
		}
		int cls = TextDocumentBuffer.charClass(text.charAt(probe));
		int start = probe;
		while (start > 0 && TextDocumentBuffer.charClass(text.charAt(start - 1)) == cls) {
			start--;
		}
		int end = probe + 1;
		while (end < len && TextDocumentBuffer.charClass(text.charAt(end)) == cls) {
			end++;
		}
		return new int[]{start, end};
	}

	private int findPrevWordBoundary(int pos) {
		int col = clamp(pos);
		while (col > 0 && TextDocumentBuffer.charClass(text.charAt(col - 1)) == 0) {
			col--;
		}
		if (col > 0) {
			int cls = TextDocumentBuffer.charClass(text.charAt(col - 1));
			while (col > 0 && TextDocumentBuffer.charClass(text.charAt(col - 1)) == cls) {
				col--;
			}
		}
		return col;
	}

	private int findNextWordBoundary(int pos) {
		int col = clamp(pos);
		int len = text.length();
		while (col < len && TextDocumentBuffer.charClass(text.charAt(col)) == 0) {
			col++;
		}
		if (col < len) {
			int cls = TextDocumentBuffer.charClass(text.charAt(col));
			while (col < len && TextDocumentBuffer.charClass(text.charAt(col)) == cls) {
				col++;
			}
		}
		return col;
	}

	private void touch() {
		lastInteractionMs = System.currentTimeMillis();
	}

	private boolean isCaretVisible() {
		long elapsed = System.currentTimeMillis() - lastInteractionMs;
		if (elapsed < 450L) {
			return true;
		}
		return ((elapsed / 500L) % 2L) == 0L;
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
