package dev.catgirlclaude.quill.client.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExplorerTreeWidgetTest {

	@Test
	void hoverOrSelectionFillDrawsBeforeIndentGuidesSoTreeLinesRemainVisible() {
		List<String> ops = new ArrayList<>();
		FileExplorerTreeWidget.RowChromeTarget recorder = new FileExplorerTreeWidget.RowChromeTarget() {
			@Override
			public void fillBackground(int x, int y, int width, int height, int color) {
				ops.add("FILL");
			}

			@Override
			public void drawIndentGuide(int x, int y, int height, int color) {
				ops.add("GUIDE");
			}
		};

		IdeLayout.Rect listRect = new IdeLayout.Rect(0, 20, 200, 300);

		// Hovered nested file row (depth 3)
		FileExplorerTreeWidget.renderRowChrome(recorder, listRect, 40, 3, false, false, true);
		assertEquals(List.of("FILL", "GUIDE", "GUIDE", "GUIDE"), ops,
				"Hover background fill must happen BEFORE tree indentation guides so guides are not painted over.");

		// Selected nested file row (depth 2)
		ops.clear();
		FileExplorerTreeWidget.renderRowChrome(recorder, listRect, 55, 2, false, true, false);
		assertTrue(ops.indexOf("FILL") < ops.indexOf("GUIDE"),
				"Selection background fill must also happen BEFORE indent guides: " + ops);
	}

	@Test
	void inlineFileInputLeavesClearanceForUnderscoreAndSizesCompactly() {
		// Inside a wide sidebar (maxBoxW = 240) for "new_fn.mcfunction" (~95px text width),
		// the focus border should hug the filename comfortably instead of stretching to 240px.
		IdeLayout.Rect box = FileExplorerTreeWidget.computeInlineBoxRect(30, 100, 240, 95);
		assertTrue(box.width() < 200,
				"Inline input focus frame should wrap content compactly when short, was: " + box.width());

		// TTF fonts in assets/quill/font/*.json apply shift [0, +1] downward; underscore sits at bottom of 9px line + 1px shift.
		int lineHeight = 9;
		int ttfVerticalShift = 1;
		int textY = dev.catgirlclaude.quill.client.editor.SingleLineTextInput.computeTextY(box.y(), box.height(), lineHeight);
		IdeLayout.Rect clip = dev.catgirlclaude.quill.client.editor.SingleLineTextInput.computeClipRect(box);
		int bottomOutlineY = box.bottom() - 1;
		int underscoreBottomY = textY + lineHeight + ttfVerticalShift;

		assertTrue(underscoreBottomY < bottomOutlineY,
				"Underscore glyph (y=" + underscoreBottomY + ") must stay strictly above bottom focus outline (y=" + bottomOutlineY + ").");
		assertTrue(underscoreBottomY <= clip.bottom() - 1,
				"Underscore glyph (y=" + underscoreBottomY + ") must not be sliced by bottom scissor clip (" + clip.bottom() + ").");
	}
}
