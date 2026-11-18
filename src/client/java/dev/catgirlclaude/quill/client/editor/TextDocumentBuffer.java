package dev.catgirlclaude.quill.client.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Pure Java multi-line text document model with selection, navigation, and undo/redo history.
 * Normalized to LF ({@code \n}) line endings and unit-testable without launching Minecraft.
 */
public final class TextDocumentBuffer {
	private static final int MAX_UNDO_STEPS = 150;
	private static final String TAB_SPACES = "    ";

	public record Position(int line, int column) implements Comparable<Position> {
		@Override
		public int compareTo(Position other) {
			int lineCmp = Integer.compare(this.line, other.line);
			return lineCmp != 0 ? lineCmp : Integer.compare(this.column, other.column);
		}
	}

	private record Snapshot(
			String content,
			Position cursor,
			Position selectionAnchor
	) {
	}

	public record BracketMatch(Position open, Position close) {
	}

	private final List<String> lines = new ArrayList<>();
	private final Deque<Snapshot> undoStack = new ArrayDeque<>();
	private final Deque<Snapshot> redoStack = new ArrayDeque<>();

	private Position cursor = new Position(0, 0);
	private Position selectionAnchor = null;
	private int preferredColumn = -1;
	private String savedContent;
	private boolean lastActionWasCharInsert = false;

	public TextDocumentBuffer(String initialContent) {
		String normalized = normalizeLineEndings(initialContent == null ? "" : initialContent);
		this.savedContent = normalized;
		loadLines(normalized);
	}

	public static String normalizeLineEndings(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return text.replace("\r\n", "\n").replace('\r', '\n');
	}

	private void loadLines(String normalized) {
		lines.clear();
		if (normalized.isEmpty()) {
			lines.add("");
		} else {
			String[] split = normalized.split("\n", -1);
			lines.addAll(Arrays.asList(split));
		}
		cursor = new Position(0, 0);
		selectionAnchor = null;
		preferredColumn = -1;
	}

	public void resetFromDisk(String content) {
		String normalized = normalizeLineEndings(content == null ? "" : content);
		undoStack.clear();
		redoStack.clear();
		lastActionWasCharInsert = false;
		savedContent = normalized;
		loadLines(normalized);
	}

	public void markSaved(String content) {
		savedContent = normalizeLineEndings(content);
		lastActionWasCharInsert = false;
	}

	public boolean isDirty() {
		return !getText().equals(savedContent);
	}

	public String getText() {
		return String.join("\n", lines);
	}

	public int lineCount() {
		return lines.size();
	}

	public String line(int index) {
		return lines.get(index);
	}

	public Position cursor() {
		return cursor;
	}

	public int oneBasedLine() {
		return cursor.line() + 1;
	}

	public int oneBasedColumn() {
		return cursor.column() + 1;
	}

	public boolean hasSelection() {
		return selectionAnchor != null && !selectionAnchor.equals(cursor);
	}

	public Position selectionStart() {
		if (!hasSelection()) {
			return cursor;
		}
		return selectionAnchor.compareTo(cursor) <= 0 ? selectionAnchor : cursor;
	}

	public Position selectionEnd() {
		if (!hasSelection()) {
			return cursor;
		}
		return selectionAnchor.compareTo(cursor) >= 0 ? selectionAnchor : cursor;
	}

	public int selectedCharCount() {
		if (!hasSelection()) {
			return 0;
		}
		return getSelectedText().length();
	}

	public String getSelectedText() {
		if (!hasSelection()) {
			return "";
		}
		Position start = selectionStart();
		Position end = selectionEnd();
		if (start.line() == end.line()) {
			return lines.get(start.line()).substring(start.column(), end.column());
		}
		StringBuilder sb = new StringBuilder();
		sb.append(lines.get(start.line()).substring(start.column()));
		for (int i = start.line() + 1; i < end.line(); i++) {
			sb.append('\n').append(lines.get(i));
		}
		sb.append('\n').append(lines.get(end.line()), 0, end.column());
		return sb.toString();
	}

	public void setCursor(int line, int column, boolean extendSelection) {
		setCursorInternal(line, column, extendSelection, true);
	}

	private void setCursorInternal(int line, int column, boolean extendSelection, boolean resetPreferredColumn) {
		Position clamped = clampPosition(line, column);
		if (extendSelection) {
			if (selectionAnchor == null) {
				selectionAnchor = cursor;
			}
		} else {
			selectionAnchor = null;
		}
		cursor = clamped;
		if (resetPreferredColumn) {
			preferredColumn = -1;
		}
		lastActionWasCharInsert = false;
	}

	public void jumpToLine(int oneBasedLine) {
		int targetLine = Math.max(0, Math.min(lines.size() - 1, oneBasedLine - 1));
		cursor = new Position(targetLine, 0);
		selectionAnchor = null;
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void selectAll() {
		selectionAnchor = new Position(0, 0);
		int lastLine = lines.size() - 1;
		cursor = new Position(lastLine, lines.get(lastLine).length());
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void clearSelection() {
		selectionAnchor = null;
	}

	/**
	 * Computes the {@code [startCol, endCol]} token bounds at {@code (line, col)} for double-click word selection.
	 */
	public int[] wordBoundsAt(int line, int col) {
		Position clamped = clampPosition(line, col);
		String text = lines.get(clamped.line());
		int len = text.length();
		if (len == 0) {
			return new int[]{0, 0};
		}
		int probe = clamped.column();
		if (probe >= len) {
			probe = len - 1;
		} else if (probe > 0 && charClass(text.charAt(probe)) == 0 && charClass(text.charAt(probe - 1)) != 0) {
			probe--;
		}
		int cls = charClass(text.charAt(probe));
		int start = probe;
		while (start > 0 && charClass(text.charAt(start - 1)) == cls) {
			start--;
		}
		int end = probe + 1;
		while (end < len && charClass(text.charAt(end)) == cls) {
			end++;
		}
		return new int[]{start, end};
	}

	public void selectWordAt(int line, int col) {
		Position clamped = clampPosition(line, col);
		int[] bounds = wordBoundsAt(clamped.line(), clamped.column());
		selectionAnchor = new Position(clamped.line(), bounds[0]);
		cursor = new Position(clamped.line(), bounds[1]);
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void extendWordSelection(int anchorLine, int anchorStartCol, int anchorEndCol, int targetLine, int targetCol) {
		Position aStart = clampPosition(anchorLine, anchorStartCol);
		Position aEnd = clampPosition(anchorLine, anchorEndCol);
		Position target = clampPosition(targetLine, targetCol);
		int[] tBounds = wordBoundsAt(target.line(), target.column());
		Position tStart = new Position(target.line(), tBounds[0]);
		Position tEnd = new Position(target.line(), tBounds[1]);

		if (tStart.compareTo(aStart) < 0) {
			selectionAnchor = aEnd;
			cursor = tStart;
		} else {
			selectionAnchor = aStart;
			cursor = tEnd;
		}
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void selectLine(int line) {
		int clampedLine = Math.max(0, Math.min(lines.size() - 1, line));
		selectionAnchor = new Position(clampedLine, 0);
		if (clampedLine < lines.size() - 1) {
			cursor = new Position(clampedLine + 1, 0);
		} else {
			cursor = new Position(clampedLine, lines.get(clampedLine).length());
		}
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void extendLineSelection(int anchorLine, int targetLine) {
		int a = Math.max(0, Math.min(lines.size() - 1, anchorLine));
		int t = Math.max(0, Math.min(lines.size() - 1, targetLine));
		if (t < a) {
			selectionAnchor = (a < lines.size() - 1)
					? new Position(a + 1, 0)
					: new Position(a, lines.get(a).length());
			cursor = new Position(t, 0);
		} else {
			selectionAnchor = new Position(a, 0);
			cursor = (t < lines.size() - 1)
					? new Position(t + 1, 0)
					: new Position(t, lines.get(t).length());
		}
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void insertText(String rawText) {
		if (rawText == null || rawText.isEmpty()) {
			return;
		}
		String normalized = normalizeLineEndings(rawText);
		boolean coalesce = normalized.length() == 1 && normalized.charAt(0) != '\n' && !hasSelection() && lastActionWasCharInsert;
		if (!coalesce) {
			pushUndoSnapshot();
		} else {
			redoStack.clear();
		}

		if (hasSelection()) {
			deleteSelectionInternal();
		}

		String currentLine = lines.get(cursor.line());
		String prefix = currentLine.substring(0, cursor.column());
		String suffix = currentLine.substring(cursor.column());
		String[] parts = normalized.split("\n", -1);

		if (parts.length == 1) {
			lines.set(cursor.line(), prefix + parts[0] + suffix);
			cursor = new Position(cursor.line(), prefix.length() + parts[0].length());
			lastActionWasCharInsert = normalized.length() == 1 && !Character.isWhitespace(normalized.charAt(0));
		} else {
			lines.set(cursor.line(), prefix + parts[0]);
			for (int i = 1; i < parts.length - 1; i++) {
				lines.add(cursor.line() + i, parts[i]);
			}
			int endLine = cursor.line() + parts.length - 1;
			String lastPart = parts[parts.length - 1];
			lines.add(endLine, lastPart + suffix);
			cursor = new Position(endLine, lastPart.length());
			lastActionWasCharInsert = false;
		}
		selectionAnchor = null;
		preferredColumn = -1;
	}

	public void insertNewline() {
		insertText("\n");
	}

	public void insertTab() {
		if (hasSelection() && selectionStart().line() != selectionEnd().line()) {
			indentSelection();
			return;
		}
		insertText(TAB_SPACES);
	}

	/**
	 * Indents the current line or all lines spanned by the active selection by 4 spaces.
	 */
	public void indentSelection() {
		Position start = selectionStart();
		Position end = selectionEnd();
		int firstLine = start.line();
		int lastLine = (hasSelection() && end.column() == 0 && end.line() > firstLine) ? end.line() - 1 : end.line();
		pushUndoSnapshot();
		for (int i = firstLine; i <= lastLine; i++) {
			lines.set(i, TAB_SPACES + lines.get(i));
		}
		if (hasSelection()) {
			int shiftAnchor = (selectionAnchor.line() >= firstLine && selectionAnchor.line() <= lastLine) ? TAB_SPACES.length() : 0;
			int shiftCursor = (cursor.line() >= firstLine && cursor.line() <= lastLine) ? TAB_SPACES.length() : 0;
			selectionAnchor = clampPosition(selectionAnchor.line(), selectionAnchor.column() + shiftAnchor);
			cursor = clampPosition(cursor.line(), cursor.column() + shiftCursor);
		} else {
			cursor = new Position(cursor.line(), cursor.column() + TAB_SPACES.length());
		}
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	/**
	 * Unindents the current line or all lines spanned by the active selection by up to 4 spaces (or 1 tab).
	 */
	public void unindentSelection() {
		Position start = selectionStart();
		Position end = selectionEnd();
		int firstLine = start.line();
		int lastLine = (hasSelection() && end.column() == 0 && end.line() > firstLine) ? end.line() - 1 : end.line();

		int[] removed = new int[lastLine - firstLine + 1];
		boolean anyRemoved = false;
		for (int i = firstLine; i <= lastLine; i++) {
			String lineText = lines.get(i);
			int count = 0;
			if (!lineText.isEmpty() && lineText.charAt(0) == '\t') {
				count = 1;
			} else {
				while (count < TAB_SPACES.length() && count < lineText.length() && lineText.charAt(count) == ' ') {
					count++;
				}
			}
			removed[i - firstLine] = count;
			if (count > 0) {
				anyRemoved = true;
			}
		}
		if (!anyRemoved) {
			return;
		}
		pushUndoSnapshot();
		for (int i = firstLine; i <= lastLine; i++) {
			int r = removed[i - firstLine];
			if (r > 0) {
				lines.set(i, lines.get(i).substring(r));
			}
		}
		if (hasSelection()) {
			int anchorDel = (selectionAnchor.line() >= firstLine && selectionAnchor.line() <= lastLine)
					? removed[selectionAnchor.line() - firstLine] : 0;
			int cursorDel = (cursor.line() >= firstLine && cursor.line() <= lastLine)
					? removed[cursor.line() - firstLine] : 0;
			selectionAnchor = clampPosition(selectionAnchor.line(), Math.max(0, selectionAnchor.column() - anchorDel));
			cursor = clampPosition(cursor.line(), Math.max(0, cursor.column() - cursorDel));
		} else {
			cursor = clampPosition(cursor.line(), Math.max(0, cursor.column() - removed[0]));
		}
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	/**
	 * Replaces the character range {@code [startCol, endCol)} on {@code lineIndex} with {@code replacement}
	 * as a single undoable operation and places the caret at the end of the inserted text.
	 */
	public void replaceRange(int lineIndex, int startCol, int endCol, String replacement) {
		if (lineIndex < 0 || lineIndex >= lines.size()) {
			return;
		}
		String line = lines.get(lineIndex);
		int s = Math.max(0, Math.min(line.length(), startCol));
		int e = Math.max(s, Math.min(line.length(), endCol));
		String clean = normalizeLineEndings(replacement == null ? "" : replacement);
		pushUndoSnapshot();
		selectionAnchor = new Position(lineIndex, s);
		cursor = new Position(lineIndex, e);
		deleteSelectionInternal();
		String currentLine = lines.get(cursor.line());
		String prefix = currentLine.substring(0, cursor.column());
		String suffix = currentLine.substring(cursor.column());
		String[] parts = clean.split("\n", -1);
		if (parts.length == 1) {
			lines.set(cursor.line(), prefix + parts[0] + suffix);
			cursor = new Position(cursor.line(), prefix.length() + parts[0].length());
		} else {
			lines.set(cursor.line(), prefix + parts[0]);
			for (int i = 1; i < parts.length - 1; i++) {
				lines.add(cursor.line() + i, parts[i]);
			}
			int endLine = cursor.line() + parts.length - 1;
			String lastPart = parts[parts.length - 1];
			lines.add(endLine, lastPart + suffix);
			cursor = new Position(endLine, lastPart.length());
		}
		selectionAnchor = null;
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	/**
	 * Replaces the entire document text as a single undoable operation, preserving cursor bounds.
	 */
	public void replaceAllText(String newText) {
		String normalized = normalizeLineEndings(newText == null ? "" : newText);
		if (normalized.equals(getText())) {
			return;
		}
		pushUndoSnapshot();
		Position previousCursor = cursor;
		loadLines(normalized);
		cursor = clampPosition(previousCursor.line(), previousCursor.column());
		selectionAnchor = null;
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void backspace() {
		if (hasSelection()) {
			pushUndoSnapshot();
			deleteSelectionInternal();
			preferredColumn = -1;
			lastActionWasCharInsert = false;
			return;
		}
		if (cursor.line() == 0 && cursor.column() == 0) {
			return;
		}
		pushUndoSnapshot();
		if (cursor.column() > 0) {
			String line = lines.get(cursor.line());
			lines.set(cursor.line(), line.substring(0, cursor.column() - 1) + line.substring(cursor.column()));
			cursor = new Position(cursor.line(), cursor.column() - 1);
		} else {
			String current = lines.remove(cursor.line());
			int prevLineIdx = cursor.line() - 1;
			String prev = lines.get(prevLineIdx);
			lines.set(prevLineIdx, prev + current);
			cursor = new Position(prevLineIdx, prev.length());
		}
		selectionAnchor = null;
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void deleteWordBackward() {
		if (hasSelection()) {
			deleteSelection();
			return;
		}
		Position target = findPreviousWordBoundary(cursor);
		if (target.equals(cursor)) {
			return;
		}
		pushUndoSnapshot();
		selectionAnchor = cursor;
		cursor = target;
		deleteSelectionInternal();
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void deleteForward() {
		if (hasSelection()) {
			pushUndoSnapshot();
			deleteSelectionInternal();
			preferredColumn = -1;
			lastActionWasCharInsert = false;
			return;
		}
		String current = lines.get(cursor.line());
		if (cursor.line() == lines.size() - 1 && cursor.column() == current.length()) {
			return;
		}
		pushUndoSnapshot();
		if (cursor.column() < current.length()) {
			lines.set(cursor.line(), current.substring(0, cursor.column()) + current.substring(cursor.column() + 1));
		} else {
			String next = lines.remove(cursor.line() + 1);
			lines.set(cursor.line(), current + next);
		}
		selectionAnchor = null;
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void deleteWordForward() {
		if (hasSelection()) {
			deleteSelection();
			return;
		}
		Position target = findNextWordBoundary(cursor);
		if (target.equals(cursor)) {
			return;
		}
		pushUndoSnapshot();
		selectionAnchor = cursor;
		cursor = target;
		deleteSelectionInternal();
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void deleteSelection() {
		if (!hasSelection()) {
			return;
		}
		pushUndoSnapshot();
		deleteSelectionInternal();
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	public void moveLeft(boolean extendSelection, boolean wordJump) {
		if (!extendSelection && hasSelection()) {
			Position start = selectionStart();
			setCursor(start.line(), start.column(), false);
			return;
		}
		if (wordJump) {
			Position target = findPreviousWordBoundary(cursor);
			setCursor(target.line(), target.column(), extendSelection);
			return;
		}
		if (cursor.column() > 0) {
			setCursor(cursor.line(), cursor.column() - 1, extendSelection);
		} else if (cursor.line() > 0) {
			int prevLine = cursor.line() - 1;
			setCursor(prevLine, lines.get(prevLine).length(), extendSelection);
		} else if (!extendSelection) {
			clearSelection();
		}
	}

	public void moveRight(boolean extendSelection, boolean wordJump) {
		if (!extendSelection && hasSelection()) {
			Position end = selectionEnd();
			setCursor(end.line(), end.column(), false);
			return;
		}
		if (wordJump) {
			Position target = findNextWordBoundary(cursor);
			setCursor(target.line(), target.column(), extendSelection);
			return;
		}
		String current = lines.get(cursor.line());
		if (cursor.column() < current.length()) {
			setCursor(cursor.line(), cursor.column() + 1, extendSelection);
		} else if (cursor.line() < lines.size() - 1) {
			setCursor(cursor.line() + 1, 0, extendSelection);
		} else if (!extendSelection) {
			clearSelection();
		}
	}

	public void moveVertical(int deltaLines, boolean extendSelection) {
		if (!extendSelection && hasSelection()) {
			Position edge = deltaLines < 0 ? selectionStart() : selectionEnd();
			cursor = edge;
			selectionAnchor = null;
			preferredColumn = edge.column();
		}
		if (preferredColumn < 0) {
			preferredColumn = cursor.column();
		}
		int targetLine = Math.max(0, Math.min(lines.size() - 1, cursor.line() + deltaLines));
		setCursorInternal(targetLine, preferredColumn, extendSelection, false);
	}

	public void moveHome(boolean extendSelection, boolean toDocumentStart) {
		if (toDocumentStart) {
			setCursor(0, 0, extendSelection);
		} else {
			String text = lines.get(cursor.line());
			int firstNonWs = 0;
			while (firstNonWs < text.length() && (text.charAt(firstNonWs) == ' ' || text.charAt(firstNonWs) == '\t')) {
				firstNonWs++;
			}
			int targetCol = (cursor.column() == firstNonWs || firstNonWs == text.length()) ? 0 : firstNonWs;
			setCursor(cursor.line(), targetCol, extendSelection);
		}
	}

	public void moveEnd(boolean extendSelection, boolean toDocumentEnd) {
		if (toDocumentEnd) {
			int last = lines.size() - 1;
			setCursor(last, lines.get(last).length(), extendSelection);
		} else {
			setCursor(cursor.line(), lines.get(cursor.line()).length(), extendSelection);
		}
	}

	/**
	 * Finds matching {@code ()}, {@code []}, or {@code {}} bracket pair adjacent to the caret.
	 */
	public BracketMatch findMatchingBracket() {
		String lineText = lines.get(cursor.line());
		int col = cursor.column();
		int probeCol = -1;
		if (col < lineText.length() && isBracket(lineText.charAt(col))) {
			probeCol = col;
		} else if (col > 0 && isBracket(lineText.charAt(col - 1))) {
			probeCol = col - 1;
		}
		if (probeCol < 0) {
			return null;
		}
		char ch = lineText.charAt(probeCol);
		boolean forward = (ch == '(' || ch == '[' || ch == '{');
		char target = switch (ch) {
			case '(' -> ')';
			case '[' -> ']';
			case '{' -> '}';
			case ')' -> '(';
			case ']' -> '[';
			case '}' -> '{';
			default -> 0;
		};
		int depth = 0;
		int maxScanLines = 400;
		if (forward) {
			int endLine = Math.min(lines.size() - 1, cursor.line() + maxScanLines);
			for (int l = cursor.line(); l <= endLine; l++) {
				String s = lines.get(l);
				int startC = (l == cursor.line()) ? probeCol : 0;
				for (int c = startC; c < s.length(); c++) {
					char cur = s.charAt(c);
					if (cur == ch) {
						depth++;
					} else if (cur == target) {
						depth--;
						if (depth == 0) {
							return new BracketMatch(new Position(cursor.line(), probeCol), new Position(l, c));
						}
					}
				}
			}
		} else {
			int startLine = Math.max(0, cursor.line() - maxScanLines);
			for (int l = cursor.line(); l >= startLine; l--) {
				String s = lines.get(l);
				int startC = (l == cursor.line()) ? probeCol : s.length() - 1;
				for (int c = startC; c >= 0; c--) {
					char cur = s.charAt(c);
					if (cur == ch) {
						depth++;
					} else if (cur == target) {
						depth--;
						if (depth == 0) {
							return new BracketMatch(new Position(l, c), new Position(cursor.line(), probeCol));
						}
					}
				}
			}
		}
		return null;
	}

	private static boolean isBracket(char c) {
		return c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}';
	}

	public boolean undo() {
		if (undoStack.isEmpty()) {
			return false;
		}
		redoStack.push(new Snapshot(getText(), cursor, selectionAnchor));
		Snapshot previous = undoStack.pop();
		restoreSnapshot(previous);
		return true;
	}

	public boolean redo() {
		if (redoStack.isEmpty()) {
			return false;
		}
		undoStack.push(new Snapshot(getText(), cursor, selectionAnchor));
		Snapshot next = redoStack.pop();
		restoreSnapshot(next);
		return true;
	}

	private void restoreSnapshot(Snapshot snapshot) {
		loadLines(snapshot.content());
		cursor = clampPosition(snapshot.cursor().line(), snapshot.cursor().column());
		selectionAnchor = snapshot.selectionAnchor() == null
				? null
				: clampPosition(snapshot.selectionAnchor().line(), snapshot.selectionAnchor().column());
		preferredColumn = -1;
		lastActionWasCharInsert = false;
	}

	private void pushUndoSnapshot() {
		if (undoStack.size() >= MAX_UNDO_STEPS) {
			undoStack.removeLast();
		}
		undoStack.push(new Snapshot(getText(), cursor, selectionAnchor));
		redoStack.clear();
	}

	private void deleteSelectionInternal() {
		Position start = selectionStart();
		Position end = selectionEnd();
		if (start.line() == end.line()) {
			String line = lines.get(start.line());
			lines.set(start.line(), line.substring(0, start.column()) + line.substring(end.column()));
		} else {
			String startPrefix = lines.get(start.line()).substring(0, start.column());
			String endSuffix = lines.get(end.line()).substring(end.column());
			lines.set(start.line(), startPrefix + endSuffix);
			lines.subList(start.line() + 1, end.line() + 1).clear();
		}
		cursor = start;
		selectionAnchor = null;
	}

	private Position clampPosition(int line, int column) {
		int clampedLine = Math.max(0, Math.min(lines.size() - 1, line));
		int clampedCol = Math.max(0, Math.min(lines.get(clampedLine).length(), column));
		return new Position(clampedLine, clampedCol);
	}

	private Position findPreviousWordBoundary(Position pos) {
		int line = pos.line();
		int col = pos.column();
		if (col == 0) {
			if (line == 0) {
				return pos;
			}
			return new Position(line - 1, lines.get(line - 1).length());
		}
		String text = lines.get(line);
		while (col > 0 && charClass(text.charAt(col - 1)) == 0) {
			col--;
		}
		if (col > 0) {
			int cls = charClass(text.charAt(col - 1));
			while (col > 0 && charClass(text.charAt(col - 1)) == cls) {
				col--;
			}
		}
		return new Position(line, col);
	}

	private Position findNextWordBoundary(Position pos) {
		int line = pos.line();
		int col = pos.column();
		String text = lines.get(line);
		if (col >= text.length()) {
			if (line >= lines.size() - 1) {
				return pos;
			}
			return new Position(line + 1, 0);
		}
		while (col < text.length() && charClass(text.charAt(col)) == 0) {
			col++;
		}
		if (col < text.length()) {
			int cls = charClass(text.charAt(col));
			while (col < text.length() && charClass(text.charAt(col)) == cls) {
				col++;
			}
		}
		return new Position(line, col);
	}

	public static int charClass(char c) {
		if (Character.isWhitespace(c)) {
			return 0;
		}
		if (Character.isLetterOrDigit(c) || c == '_') {
			return 1;
		}
		return 2;
	}
}
