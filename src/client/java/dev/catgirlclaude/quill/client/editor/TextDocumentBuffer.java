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

	private final List<String> lines = new ArrayList<>();
	private final Deque<Snapshot> undoStack = new ArrayDeque<>();
	private final Deque<Snapshot> redoStack = new ArrayDeque<>();

	private Position cursor = new Position(0, 0);
	private Position selectionAnchor = null;
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
		Position clamped = clampPosition(line, column);
		if (extendSelection) {
			if (selectionAnchor == null) {
				selectionAnchor = cursor;
			}
		} else {
			selectionAnchor = null;
		}
		cursor = clamped;
		lastActionWasCharInsert = false;
	}

	public void jumpToLine(int oneBasedLine) {
		int targetLine = Math.max(0, Math.min(lines.size() - 1, oneBasedLine - 1));
		cursor = new Position(targetLine, 0);
		selectionAnchor = null;
		lastActionWasCharInsert = false;
	}

	public void selectAll() {
		selectionAnchor = new Position(0, 0);
		int lastLine = lines.size() - 1;
		cursor = new Position(lastLine, lines.get(lastLine).length());
		lastActionWasCharInsert = false;
	}

	public void clearSelection() {
		selectionAnchor = null;
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
	}

	public void insertNewline() {
		insertText("\n");
	}

	public void insertTab() {
		insertText(TAB_SPACES);
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
		lastActionWasCharInsert = false;
	}

	public void backspace() {
		if (hasSelection()) {
			pushUndoSnapshot();
			deleteSelectionInternal();
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
		lastActionWasCharInsert = false;
	}

	public void deleteForward() {
		if (hasSelection()) {
			pushUndoSnapshot();
			deleteSelectionInternal();
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
		lastActionWasCharInsert = false;
	}

	public void deleteSelection() {
		if (!hasSelection()) {
			return;
		}
		pushUndoSnapshot();
		deleteSelectionInternal();
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
		int targetLine = Math.max(0, Math.min(lines.size() - 1, cursor.line() + deltaLines));
		setCursor(targetLine, cursor.column(), extendSelection);
	}

	public void moveHome(boolean extendSelection, boolean toDocumentStart) {
		if (toDocumentStart) {
			setCursor(0, 0, extendSelection);
		} else {
			setCursor(cursor.line(), 0, extendSelection);
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
		while (col > 0 && Character.isWhitespace(text.charAt(col - 1))) {
			col--;
		}
		while (col > 0 && !Character.isWhitespace(text.charAt(col - 1))) {
			col--;
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
		while (col < text.length() && !Character.isWhitespace(text.charAt(col))) {
			col++;
		}
		while (col < text.length() && Character.isWhitespace(text.charAt(col))) {
			col++;
		}
		return new Position(line, col);
	}
}
