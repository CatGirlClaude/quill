package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.ReloadDiagnostic;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * VS Code-style bottom panel with {@code PROBLEMS} (clickable reload diagnostics)
 * and {@code OUTPUT} (live selectable &amp; copyable reload &amp; {@code /function} execution log) tabs.
 */
public final class BottomPanelWidget {
	private static final int HEADER_HEIGHT = 18;
	private static final int PROBLEM_ROW_HEIGHT = 13;
	private static final int OUTPUT_ROW_HEIGHT = 12;

	private record ProblemRowBounds(ReloadDiagnostic diagnostic, IdeLayout.Rect rect) {
	}

	public record OutputPosition(int line, int column) implements Comparable<OutputPosition> {
		@Override
		public int compareTo(OutputPosition other) {
			int cmp = Integer.compare(this.line, other.line);
			return cmp != 0 ? cmp : Integer.compare(this.column, other.column);
		}
	}

	private final ClientEditorSession session;
	private final List<ProblemRowBounds> problemRows = new ArrayList<>();

	private IdeLayout.Rect panelRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect bodyRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect problemsTabRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect outputTabRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect runFnBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect copyBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect clearBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect closeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);

	private int problemsScroll = 0;
	private int outputScroll = 0;
	private int lastOutputSize = 0;
	private boolean autoScrollOutput = true;

	private boolean outputFocused = false;
	private boolean draggingOutputSelection = false;
	private OutputPosition selectionAnchor = null;
	private OutputPosition selectionCaret = null;
	private Consumer<String> clipboardSetter = BottomPanelWidget::writeMinecraftClipboard;

	public BottomPanelWidget(ClientEditorSession session) {
		this.session = session;
	}

	void setClipboardSetterForTest(Consumer<String> clipboardSetter) {
		this.clipboardSetter = clipboardSetter != null ? clipboardSetter : BottomPanelWidget::writeMinecraftClipboard;
	}

	void setLayoutBoundsForTest(IdeLayout.Rect panelRect, IdeLayout.Rect bodyRect) {
		this.panelRect = panelRect;
		this.bodyRect = bodyRect;
	}

	public boolean isOutputFocused() {
		return outputFocused && session.bottomPanelTab() == ClientEditorSession.BottomPanelTab.OUTPUT;
	}

	public void unfocus() {
		this.outputFocused = false;
		this.draggingOutputSelection = false;
	}

	public boolean hasOutputSelection() {
		return selectionAnchor != null && selectionCaret != null && !selectionAnchor.equals(selectionCaret);
	}

	public static String formatEntryLine(ClientEditorSession.OutputLogEntry entry) {
		return "[" + entry.timestamp() + "] " + entry.message();
	}

	public String getSelectedOutputText() {
		List<ClientEditorSession.OutputLogEntry> entries = session.outputLog();
		if (entries.isEmpty() || !hasOutputSelection()) {
			return "";
		}
		OutputPosition start = selectionAnchor.compareTo(selectionCaret) <= 0 ? selectionAnchor : selectionCaret;
		OutputPosition end = selectionAnchor.compareTo(selectionCaret) <= 0 ? selectionCaret : selectionAnchor;
		int sLine = Math.max(0, Math.min(entries.size() - 1, start.line()));
		int eLine = Math.max(0, Math.min(entries.size() - 1, end.line()));
		StringBuilder sb = new StringBuilder();
		for (int i = sLine; i <= eLine; i++) {
			String lineText = formatEntryLine(entries.get(i));
			int fromCol = (i == sLine) ? Math.max(0, Math.min(lineText.length(), start.column())) : 0;
			int toCol = (i == eLine) ? Math.max(0, Math.min(lineText.length(), end.column())) : lineText.length();
			if (toCol < fromCol) {
				toCol = fromCol;
			}
			if (i > sLine) {
				sb.append('\n');
			}
			sb.append(lineText, fromCol, toCol);
		}
		return sb.toString();
	}

	public void selectAllOutput() {
		List<ClientEditorSession.OutputLogEntry> entries = session.outputLog();
		if (entries.isEmpty()) {
			clearSelection();
			return;
		}
		int lastIdx = entries.size() - 1;
		String lastLine = formatEntryLine(entries.get(lastIdx));
		selectionAnchor = new OutputPosition(0, 0);
		selectionCaret = new OutputPosition(lastIdx, lastLine.length());
	}

	public void clearSelection() {
		selectionAnchor = null;
		selectionCaret = null;
		draggingOutputSelection = false;
	}

	public void clearBounds() {
		panelRect = new IdeLayout.Rect(0, 0, 0, 0);
		bodyRect = new IdeLayout.Rect(0, 0, 0, 0);
		problemsTabRect = new IdeLayout.Rect(0, 0, 0, 0);
		outputTabRect = new IdeLayout.Rect(0, 0, 0, 0);
		runFnBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		copyBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		clearBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		closeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		problemRows.clear();
		unfocus();
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect area, int mouseX, int mouseY) {
		this.panelRect = area;
		problemRows.clear();

		ReloadReport report = session.lastReloadReport();
		int problemCount = (report != null && report.hasErrors()) ? report.diagnostics().size() : 0;
		List<ClientEditorSession.OutputLogEntry> logEntries = session.outputLog();
		int outputCount = logEntries.size();

		QuillDraw.fill(graphics, area.x(), area.y(), area.width(), area.height(), QuillTheme.BG_PANEL);

		// Header bar
		IdeLayout.Rect header = new IdeLayout.Rect(area.x(), area.y(), area.width(), HEADER_HEIGHT);
		QuillDraw.fill(graphics, header.x(), header.y(), header.width(), header.height(), QuillTheme.BG_PANEL_HEADER);
		QuillDraw.hLine(graphics, header.x(), header.width(), header.y(),
				problemCount > 0 ? QuillTheme.BG_STATUS_ERROR : QuillTheme.BORDER_SUBTLE);
		QuillDraw.hLine(graphics, header.x(), header.width(), header.bottom() - 1, QuillTheme.BORDER_SUBTLE);

		int textY = header.y() + (HEADER_HEIGHT - font.lineHeight) / 2;
		ClientEditorSession.BottomPanelTab activeTab = session.bottomPanelTab();

		// PROBLEMS tab
		String probLabel = "PROBLEMS (" + problemCount + ")";
		int probW = QuillDraw.textWidth(font, probLabel) + 12;
		problemsTabRect = new IdeLayout.Rect(header.x() + 4, header.y() + 1, probW, HEADER_HEIGHT - 2);
		boolean probActive = activeTab == ClientEditorSession.BottomPanelTab.PROBLEMS;
		boolean hoverProb = problemsTabRect.contains(mouseX, mouseY);
		if (hoverProb && !probActive) {
			QuillDraw.fill(graphics, problemsTabRect.x(), problemsTabRect.y(), problemsTabRect.width(), problemsTabRect.height(), QuillTheme.BG_HOVER);
		}
		if (probActive) {
			QuillDraw.fill(graphics, problemsTabRect.x(), problemsTabRect.bottom() - 2, problemsTabRect.width(), 2,
					problemCount > 0 ? QuillTheme.BG_STATUS_ERROR : QuillTheme.ACCENT_BLUE);
		}
		int probColor = problemCount > 0
				? QuillTheme.TEXT_ERROR
				: (probActive ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);
		QuillDraw.text(graphics, font, probLabel, problemsTabRect.x() + 6, textY, probColor);

		// OUTPUT tab
		String outLabel = "OUTPUT (" + outputCount + ")";
		int outW = QuillDraw.textWidth(font, outLabel) + 12;
		outputTabRect = new IdeLayout.Rect(problemsTabRect.right() + 4, header.y() + 1, outW, HEADER_HEIGHT - 2);
		boolean outActive = activeTab == ClientEditorSession.BottomPanelTab.OUTPUT;
		boolean hoverOut = outputTabRect.contains(mouseX, mouseY);
		if (hoverOut && !outActive) {
			QuillDraw.fill(graphics, outputTabRect.x(), outputTabRect.y(), outputTabRect.width(), outputTabRect.height(), QuillTheme.BG_HOVER);
		}
		if (outActive) {
			QuillDraw.fill(graphics, outputTabRect.x(), outputTabRect.bottom() - 2, outputTabRect.width(), 2, QuillTheme.ACCENT_BLUE);
		}
		QuillDraw.text(graphics, font, outLabel, outputTabRect.x() + 6, textY,
				outActive ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

		// Right-side header controls (from right to left)
		int rightCursor = header.right() - 4;
		closeBtnRect = new IdeLayout.Rect(rightCursor - 14, header.y() + 2, 14, HEADER_HEIGHT - 4);
		boolean hoverClose = closeBtnRect.contains(mouseX, mouseY);
		if (hoverClose) {
			QuillDraw.fill(graphics, closeBtnRect.x(), closeBtnRect.y(), closeBtnRect.width(), closeBtnRect.height(), QuillTheme.BG_HOVER);
		}
		QuillDraw.text(graphics, font, "x",
				closeBtnRect.x() + (closeBtnRect.width() - QuillDraw.textWidth(font, "x")) / 2, textY,
				hoverClose ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);
		rightCursor = closeBtnRect.x() - 4;

		if (outActive) {
			String clearLabel = "Clear";
			int cw = QuillDraw.textWidth(font, clearLabel) + 10;
			clearBtnRect = new IdeLayout.Rect(rightCursor - cw, header.y() + 2, cw, HEADER_HEIGHT - 4);
			boolean hoverClear = clearBtnRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, clearBtnRect.x(), clearBtnRect.y(), clearBtnRect.width(), clearBtnRect.height(),
					hoverClear ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);
			QuillDraw.text(graphics, font, clearLabel, clearBtnRect.x() + 5, textY, QuillTheme.TEXT_PRIMARY);
			rightCursor = clearBtnRect.x() - 4;

			String copyLabel = "Copy";
			int copyW = QuillDraw.textWidth(font, copyLabel) + 10;
			copyBtnRect = new IdeLayout.Rect(rightCursor - copyW, header.y() + 2, copyW, HEADER_HEIGHT - 4);
			boolean canCopy = !logEntries.isEmpty();
			boolean hoverCopy = canCopy && copyBtnRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, copyBtnRect.x(), copyBtnRect.y(), copyBtnRect.width(), copyBtnRect.height(),
					hoverCopy ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);
			QuillDraw.text(graphics, font, copyLabel, copyBtnRect.x() + 5, textY,
					canCopy ? QuillTheme.TEXT_PRIMARY : QuillTheme.TEXT_MUTED);
			rightCursor = copyBtnRect.x() - 4;
		} else {
			clearBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
			copyBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		}

		String fnId = session.activeFunctionId();
		int maxRunSpace = rightCursor - (outputTabRect.right() + 8);
		if (fnId != null && maxRunSpace > 46) {
			String fullLabel = "> Run /function " + fnId;
			String runLabel = QuillDraw.textWidth(font, fullLabel) + 10 <= maxRunSpace
					? fullLabel
					: QuillDraw.clipToWidth(font, "> Run " + fnId, maxRunSpace - 10);
			int rw = QuillDraw.textWidth(font, runLabel) + 10;
			runFnBtnRect = new IdeLayout.Rect(rightCursor - rw, header.y() + 2, rw, HEADER_HEIGHT - 4);
			boolean hoverRun = runFnBtnRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, runFnBtnRect.x(), runFnBtnRect.y(), runFnBtnRect.width(), runFnBtnRect.height(),
					hoverRun ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
			QuillDraw.text(graphics, font, runLabel, runFnBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
		} else {
			runFnBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		}

		if (hoverProb || hoverOut || hoverClose
				|| clearBtnRect.contains(mouseX, mouseY)
				|| (copyBtnRect.contains(mouseX, mouseY) && !logEntries.isEmpty())
				|| runFnBtnRect.contains(mouseX, mouseY)) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}

		// Body
		bodyRect = new IdeLayout.Rect(area.x(), header.bottom(), area.width(), Math.max(0, area.height() - HEADER_HEIGHT));
		if (bodyRect.height() <= 0) {
			return;
		}

		if (probActive) {
			renderProblemsBody(graphics, font, report, mouseX, mouseY);
		} else {
			if (bodyRect.contains(mouseX, mouseY) && !logEntries.isEmpty()) {
				QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
			}
			renderOutputBody(graphics, font, logEntries);
		}
	}

	private void renderProblemsBody(GuiGraphicsExtractor graphics, Font font, ReloadReport report, int mouseX, int mouseY) {
		QuillDraw.beginClip(graphics, bodyRect);
		if (report == null || !report.hasErrors() || report.diagnostics().isEmpty()) {
			QuillDraw.text(graphics, font, "No problems detected in the last reload.",
					bodyRect.x() + 8, bodyRect.y() + 6, QuillTheme.TEXT_MUTED);
			QuillDraw.endClip(graphics);
			return;
		}

		List<ReloadDiagnostic> diagnostics = report.diagnostics();
		int visibleRows = Math.max(1, (bodyRect.height() - 4) / PROBLEM_ROW_HEIGHT);
		int maxScroll = Math.max(0, diagnostics.size() - visibleRows);
		problemsScroll = Math.max(0, Math.min(maxScroll, problemsScroll));

		int y = bodyRect.y() + 3;
		int rowWidth = Math.max(20, bodyRect.width() - 12);
		int end = Math.min(diagnostics.size(), problemsScroll + visibleRows + 1);
		for (int i = problemsScroll; i < end; i++) {
			ReloadDiagnostic diag = diagnostics.get(i);
			IdeLayout.Rect rowRect = new IdeLayout.Rect(bodyRect.x() + 6, y, rowWidth, PROBLEM_ROW_HEIGHT);
			problemRows.add(new ProblemRowBounds(diag, rowRect));

			boolean hovered = bodyRect.contains(mouseX, mouseY) && rowRect.contains(mouseX, mouseY);
			if (hovered) {
				QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.BG_HOVER);
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}

			String loc = diag.line() > 0 ? " (Line " + diag.line() + ")" : "";
			String msg = "[!] " + diag.file() + loc + ": " + diag.message();
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, msg, rowWidth - 6),
					rowRect.x() + 3, rowRect.y() + (PROBLEM_ROW_HEIGHT - font.lineHeight) / 2, QuillTheme.TEXT_ERROR);
			y += PROBLEM_ROW_HEIGHT;
		}
		QuillDraw.endClip(graphics);
	}

	private void renderOutputBody(GuiGraphicsExtractor graphics, Font font, List<ClientEditorSession.OutputLogEntry> entries) {
		QuillDraw.beginClip(graphics, bodyRect);
		if (entries.isEmpty()) {
			QuillDraw.text(graphics, font, "No output yet. Save, reload, or run a function to see logs here.",
					bodyRect.x() + 8, bodyRect.y() + 6, QuillTheme.TEXT_MUTED);
			QuillDraw.endClip(graphics);
			return;
		}

		int visibleRows = Math.max(1, (bodyRect.height() - 4) / OUTPUT_ROW_HEIGHT);
		int maxScroll = Math.max(0, entries.size() - visibleRows);
		if (entries.size() != lastOutputSize) {
			lastOutputSize = entries.size();
			if (autoScrollOutput) {
				outputScroll = maxScroll;
			}
		}
		outputScroll = Math.max(0, Math.min(maxScroll, outputScroll));

		boolean hasSel = hasOutputSelection();
		OutputPosition selStart = null;
		OutputPosition selEnd = null;
		if (hasSel) {
			boolean anchorFirst = selectionAnchor.compareTo(selectionCaret) <= 0;
			selStart = anchorFirst ? selectionAnchor : selectionCaret;
			selEnd = anchorFirst ? selectionCaret : selectionAnchor;
		}

		int y = bodyRect.y() + 3;
		int rowWidth = Math.max(20, bodyRect.width() - 12);
		int end = Math.min(entries.size(), outputScroll + visibleRows + 1);
		for (int i = outputScroll; i < end; i++) {
			ClientEditorSession.OutputLogEntry entry = entries.get(i);
			String prefix = "[" + entry.timestamp() + "] ";
			String fullLine = prefix + entry.message();
			int prefixW = QuillDraw.textWidth(font, prefix);
			int textY = y + (OUTPUT_ROW_HEIGHT - font.lineHeight) / 2;

			if (hasSel && i >= selStart.line() && i <= selEnd.line()) {
				int sCol = (i == selStart.line()) ? Math.max(0, Math.min(fullLine.length(), selStart.column())) : 0;
				int eCol = (i == selEnd.line()) ? Math.max(0, Math.min(fullLine.length(), selEnd.column())) : fullLine.length();
				int x1 = bodyRect.x() + 6 + QuillDraw.textWidth(font, fullLine.substring(0, sCol));
				int x2 = bodyRect.x() + 6 + QuillDraw.textWidth(font, fullLine.substring(0, eCol));
				if (i < selEnd.line()) {
					x2 += 5;
				}
				if (x2 > x1) {
					QuillDraw.fill(graphics, x1, y, x2 - x1, OUTPUT_ROW_HEIGHT, QuillTheme.SELECTION_BG);
				}
			}

			QuillDraw.text(graphics, font, prefix, bodyRect.x() + 6, textY, QuillTheme.TEXT_MUTED);
			int msgColor = switch (entry.level()) {
				case ERROR -> QuillTheme.TEXT_ERROR;
				case WARN -> QuillTheme.TEXT_WARNING;
				case SUCCESS -> QuillTheme.TEXT_SUCCESS;
				case INFO -> QuillTheme.TEXT_PRIMARY;
			};
			String clippedMsg = QuillDraw.clipToWidth(font, entry.message(), Math.max(20, rowWidth - prefixW));
			QuillDraw.text(graphics, font, clippedMsg, bodyRect.x() + 6 + prefixW, textY, msgColor);
			y += OUTPUT_ROW_HEIGHT;
		}
		QuillDraw.endClip(graphics);
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || !panelRect.contains(event.x(), event.y())) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		if (problemsTabRect.contains(mx, my)) {
			session.setBottomPanelTab(ClientEditorSession.BottomPanelTab.PROBLEMS);
			unfocus();
			return true;
		}
		if (outputTabRect.contains(mx, my)) {
			session.setBottomPanelTab(ClientEditorSession.BottomPanelTab.OUTPUT);
			return true;
		}
		if (closeBtnRect.contains(mx, my)) {
			session.setDiagnosticsExpanded(false);
			unfocus();
			return true;
		}
		if (clearBtnRect.contains(mx, my)) {
			session.clearOutputLog();
			clearSelection();
			autoScrollOutput = true;
			return true;
		}
		if (copyBtnRect.contains(mx, my)) {
			copySelectionOrAll();
			return true;
		}
		if (runFnBtnRect.contains(mx, my)) {
			session.runCurrentFunction();
			autoScrollOutput = true;
			return true;
		}
		if (bodyRect.contains(mx, my)) {
			if (session.bottomPanelTab() == ClientEditorSession.BottomPanelTab.PROBLEMS) {
				unfocus();
				for (ProblemRowBounds row : problemRows) {
					if (row.rect().contains(mx, my)) {
						session.openFileAndJump(row.diagnostic().file(), row.diagnostic().line());
						return true;
					}
				}
			} else {
				outputFocused = true;
				List<ClientEditorSession.OutputLogEntry> entries = session.outputLog();
				if (!entries.isEmpty()) {
					OutputPosition pos = hitTestOutput(safeFont(), mx, my, entries);
					if (event.hasShiftDown() && selectionAnchor != null) {
						selectionCaret = pos;
						draggingOutputSelection = true;
					} else {
						selectionAnchor = pos;
						selectionCaret = pos;
						draggingOutputSelection = true;
					}
				}
				return true;
			}
		}
		return true;
	}

	public boolean mouseDragged(MouseButtonEvent event) {
		if (!draggingOutputSelection
				|| event.button() != InputConstants.MOUSE_BUTTON_LEFT
				|| session.bottomPanelTab() != ClientEditorSession.BottomPanelTab.OUTPUT) {
			return false;
		}
		List<ClientEditorSession.OutputLogEntry> entries = session.outputLog();
		if (entries.isEmpty()) {
			return false;
		}
		int visibleRows = Math.max(1, (bodyRect.height() - 4) / OUTPUT_ROW_HEIGHT);
		int maxScroll = Math.max(0, entries.size() - visibleRows);
		if (event.y() < bodyRect.y()) {
			outputScroll = Math.max(0, outputScroll - 1);
		} else if (event.y() > bodyRect.bottom()) {
			outputScroll = Math.min(maxScroll, outputScroll + 1);
		}
		autoScrollOutput = (outputScroll >= maxScroll);
		selectionCaret = hitTestOutput(safeFont(), event.x(), event.y(), entries);
		return true;
	}

	public void mouseReleased(MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			draggingOutputSelection = false;
		}
	}

	public boolean keyPressed(KeyEvent event) {
		if (!isOutputFocused()) {
			return false;
		}
		if (event.isEscape()) {
			if (hasOutputSelection()) {
				clearSelection();
			} else {
				unfocus();
			}
			return true;
		}
		if (event.isSelectAll()) {
			selectAllOutput();
			return true;
		}
		if (event.isCopy()) {
			if (hasOutputSelection()) {
				clipboardSetter.accept(getSelectedOutputText());
				return true;
			}
		}
		return false;
	}

	private void copySelectionOrAll() {
		List<ClientEditorSession.OutputLogEntry> entries = session.outputLog();
		if (entries.isEmpty()) {
			return;
		}
		String text;
		if (hasOutputSelection()) {
			text = getSelectedOutputText();
		} else {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < entries.size(); i++) {
				if (i > 0) {
					sb.append('\n');
				}
				sb.append(formatEntryLine(entries.get(i)));
			}
			text = sb.toString();
		}
		clipboardSetter.accept(text);
		session.setStatus("Copied output log to clipboard", false);
	}

	private OutputPosition hitTestOutput(Font font, double mouseX, double mouseY, List<ClientEditorSession.OutputLogEntry> entries) {
		int rowOffset = Math.max(0, ((int) mouseY - (bodyRect.y() + 3)) / OUTPUT_ROW_HEIGHT);
		int lineIdx = Math.max(0, Math.min(entries.size() - 1, outputScroll + rowOffset));
		String fullLine = formatEntryLine(entries.get(lineIdx));
		int relX = Math.max(0, (int) mouseX - (bodyRect.x() + 6));
		int col = font != null ? QuillDraw.charIndexAtPixel(font, fullLine, relX, 1.0f) : Math.min(fullLine.length(), relX / 6);
		return new OutputPosition(lineIdx, col);
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!panelRect.contains(mouseX, mouseY) || scrollY == 0) {
			return false;
		}
		int delta = -(int) Math.signum(scrollY) * 2;
		if (session.bottomPanelTab() == ClientEditorSession.BottomPanelTab.PROBLEMS) {
			ReloadReport report = session.lastReloadReport();
			int count = (report != null && report.hasErrors()) ? report.diagnostics().size() : 0;
			int visibleRows = Math.max(1, (bodyRect.height() - 4) / PROBLEM_ROW_HEIGHT);
			int maxScroll = Math.max(0, count - visibleRows);
			problemsScroll = Math.max(0, Math.min(maxScroll, problemsScroll + delta));
		} else {
			int count = session.outputLog().size();
			int visibleRows = Math.max(1, (bodyRect.height() - 4) / OUTPUT_ROW_HEIGHT);
			int maxScroll = Math.max(0, count - visibleRows);
			outputScroll = Math.max(0, Math.min(maxScroll, outputScroll + delta));
			autoScrollOutput = (outputScroll >= maxScroll);
		}
		return true;
	}

	private static Font safeFont() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null ? mc.font : null;
	}

	private static void writeMinecraftClipboard(String value) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.keyboardHandler != null) {
			mc.keyboardHandler.setClipboard(value != null ? value : "");
		}
	}
}
