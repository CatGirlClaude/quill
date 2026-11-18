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
}
