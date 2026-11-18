package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.editor.TextDocumentBuffer;
import dev.catgirlclaude.quill.client.session.OpenTabState;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeEditorWidgetTest {

	@AfterEach
	void resetConfig() {
		QuillClientConfig.setCurrent(QuillClientConfig.createDefault());
	}

	@Test
	void rightClickContextMenuWorksOverTextPreservesInsideSelectionAndProvidesCutCopyPasteNavigation() {
		CodeEditorWidget widget = new CodeEditorWidget();
		AtomicReference<String> clipboard = new AtomicReference<>("");
		widget.setClipboardBridge(clipboard::get, clipboard::set);
		AtomicReference<ContextMenuOverlay> openedMenu = new AtomicReference<>();
		widget.setContextMenuHooks(openedMenu::set, () -> openedMenu.set(null));

		OpenTabState tab = new OpenTabState(new FileDocument(
				"pack/data/demo/function/test.mcfunction",
				"say hello world\nsay second line",
				"sha",
				0L
		));

		// Pre-select "hello" on line 0: cols [4..9]
		tab.buffer().setCursor(0, 4, false);
		tab.buffer().setCursor(0, 9, true);

		// Helper check: position inside selection preserves selection; outside moves cursor
		assertTrue(CodeEditorWidget.isPositionWithinSelection(tab.buffer(), new TextDocumentBuffer.Position(0, 6)));
		assertFalse(CodeEditorWidget.isPositionWithinSelection(tab.buffer(), new TextDocumentBuffer.Position(1, 3)));

		// Simulate opening right-click context menu over the selected text
		List<ContextMenuOverlay.MenuEntry> entries = widget.buildContextMenuEntries(null, tab);
		List<String> labels = entries.stream()
				.filter(ContextMenuOverlay.ActionItem.class::isInstance)
				.map(e -> ((ContextMenuOverlay.ActionItem) e).label())
				.toList();
		for (String required : List.of("Cut", "Copy", "Paste", "Select All", "Find in File",
				"Go to Top", "Go to Bottom", "Go to Start of Line", "Go to End of Line")) {
			assertTrue(labels.contains(required), "Missing required editor context menu entry: " + required + " in " + labels);
		}

		// Copy selection -> clipboard holds "hello"
		ContextMenuOverlay.ActionItem copyItem = findItem(entries, "Copy");
		assertTrue(copyItem.enabled());
		copyItem.action().run();
		assertEquals("hello", clipboard.get());

		// Paste over selection / at cursor -> works cleanly, Cut removes selection
		tab.buffer().setCursor(1, 0, false);
		List<ContextMenuOverlay.MenuEntry> afterCopy = widget.buildContextMenuEntries(null, tab);
		assertFalse(findItem(afterCopy, "Cut").enabled(), "Cut must be disabled without active selection.");
		assertTrue(findItem(afterCopy, "Paste").enabled(), "Paste must be enabled when clipboard is non-empty.");
		findItem(afterCopy, "Paste").action().run();
		assertEquals("say hello world\nhellosay second line", tab.buffer().getText());

		// Navigation items
		findItem(afterCopy, "Go to Top").action().run();
		assertEquals(new TextDocumentBuffer.Position(0, 0), tab.buffer().cursor());
		findItem(afterCopy, "Go to Bottom").action().run();
		assertEquals(new TextDocumentBuffer.Position(1, "hellosay second line".length()), tab.buffer().cursor());
		findItem(afterCopy, "Go to Start of Line").action().run();
		assertEquals(new TextDocumentBuffer.Position(1, 0), tab.buffer().cursor());
		findItem(afterCopy, "Go to End of Line").action().run();
		assertEquals(new TextDocumentBuffer.Position(1, "hellosay second line".length()), tab.buffer().cursor());

		// Right-clicking directly over code area (with simulated bounds) opens ContextMenuOverlay even directly on text
		widget.setViewportBoundsForTest(
				new IdeLayout.Rect(0, 0, 400, 200),
				new IdeLayout.Rect(0, 0, 32, 195),
				new IdeLayout.Rect(32, 0, 363, 195)
		);
		MouseButtonEvent rightClickOnText = new MouseButtonEvent(
				56, 6, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
		assertTrue(widget.mouseClicked(rightClickOnText, null, tab),
				"Right-click directly over code text must open the editor context menu.");
		assertTrue(openedMenu.get() != null);
	}

	@Test
	void ctrlFInFileFindBarFindsMatchesJumpsNextPrevAndClosesWithEscape() {
		CodeEditorWidget widget = new CodeEditorWidget();
		OpenTabState tab = new OpenTabState(new FileDocument(
				"pack/data/demo/function/find.mcfunction",
				"say alpha\nsay beta\nsay alpha two",
				"sha",
				0L
		));
		widget.setViewportBoundsForTest(
				new IdeLayout.Rect(0, 0, 400, 200),
				new IdeLayout.Rect(0, 0, 32, 195),
				new IdeLayout.Rect(32, 0, 363, 195)
		);

		int ctrl = net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER;
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_F, 'f', ctrl), null, tab));
		assertTrue(widget.isFindBarOpen());
		assertTrue(widget.isFindBarFocused());

		for (int cp : "alpha".codePoints().toArray()) {
			assertTrue(widget.charTyped(new CharacterEvent(cp), null, tab));
		}
		assertEquals(2, widget.findMatches(tab).size());
		assertEquals(0, widget.activeFindMatchIndex());
		assertEquals(0, tab.buffer().cursor().line());

		// Enter advances to second match
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0), null, tab));
		assertEquals(1, widget.activeFindMatchIndex());
		assertEquals(2, tab.buffer().cursor().line());

		// Shift+Enter steps back to first match
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, InputConstants.MOD_SHIFT), null, tab));
		assertEquals(0, widget.activeFindMatchIndex());
		assertEquals(0, tab.buffer().cursor().line());

		// Escape dismisses Find bar without modifying buffer text
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0), null, tab));
		assertFalse(widget.isFindBarOpen());
		assertFalse(widget.isFindBarFocused());
		assertEquals("say alpha\nsay beta\nsay alpha two", tab.buffer().getText());
	}

	@Test
	void shiftWheelScrollsHorizontallyAndAltZTogglesWordWrap() {
		CodeEditorWidget widget = new CodeEditorWidget();
		OpenTabState tab = new OpenTabState(new FileDocument(
				"pack/data/demo/function/long.mcfunction",
				"say " + "word ".repeat(30) + "\n" + "say second\n".repeat(30),
				"sha",
				0L
		));
		widget.setViewportBoundsForTest(
				new IdeLayout.Rect(0, 0, 300, 120),
				new IdeLayout.Rect(0, 0, 32, 115),
				new IdeLayout.Rect(32, 0, 263, 115)
		);

		// Normal wheel scrolls vertically only
		assertTrue(widget.mouseScrolled(100, 50, 0, -1, null, tab, false));
		assertTrue(tab.scrollLine() > 0, "Regular vertical scroll must change scrollLine.");
		assertEquals(0, tab.scrollX(), "Regular scroll without Shift must keep scrollX at 0.");

		int prevScrollLine = tab.scrollLine();
		// Shift + Wheel scrolls horizontally only
		assertTrue(widget.mouseScrolled(100, 50, 0, -1, null, tab, true));
		assertEquals(prevScrollLine, tab.scrollLine(), "Shift+Wheel must not scroll vertically.");
		assertTrue(tab.scrollX() > 0, "Shift+Wheel must scroll horizontally.");

		// Alt+Z toggles word wrap
		assertFalse(QuillClientConfig.current().wordWrap());
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_Z, 'z', InputConstants.MOD_ALT), null, tab));
		assertTrue(QuillClientConfig.current().wordWrap(), "Alt+Z must enable soft word wrap.");

		// Word wrap layout breaks long lines cleanly into visual rows within maxWidthPx
		List<String> lines = List.of("say alpha beta gamma delta");
		var rows = CodeEditorWidget.WordWrapLayout.wrapLines(lines, true, 70, s -> s.length() * 6);
		assertTrue(rows.size() >= 2, "Long line must wrap into multiple visual rows: " + rows);
		for (var row : rows) {
			String seg = lines.get(row.logicalLine()).substring(row.startCol(), row.endCol());
			assertTrue(seg.length() * 6 <= 70 || seg.trim().length() * 6 <= 70, "Wrapped segment exceeded max width: '" + seg + "'");
		}
		assertEquals(0, rows.getFirst().startCol());
		assertEquals(lines.getFirst().length(), rows.getLast().endCol());
	}

	private static ContextMenuOverlay.ActionItem findItem(List<ContextMenuOverlay.MenuEntry> entries, String label) {
		return entries.stream()
				.filter(ContextMenuOverlay.ActionItem.class::isInstance)
				.map(ContextMenuOverlay.ActionItem.class::cast)
				.filter(a -> a.label().equals(label))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing menu item: " + label));
	}
}
