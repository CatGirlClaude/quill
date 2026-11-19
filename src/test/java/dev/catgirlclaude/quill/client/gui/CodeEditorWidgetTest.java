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

	@Test
	void ctrlClickDispatchesNavigationHandlerWithoutModifyingSelection() {
		CodeEditorWidget widget = new CodeEditorWidget();
		OpenTabState tab = new OpenTabState(new FileDocument(
				"pack/data/demo/function/nav.mcfunction",
				"scoreboard players add @s coins 1",
				"sha",
				0L
		));
		widget.setViewportBoundsForTest(
				new IdeLayout.Rect(0, 0, 400, 120),
				new IdeLayout.Rect(0, 0, 32, 115),
				new IdeLayout.Rect(32, 0, 363, 115)
		);
		AtomicReference<CodeEditorWidget.CtrlClickRequest> requestRef = new AtomicReference<>();
		widget.setCtrlClickHandler(req -> {
			requestRef.set(req);
			return true;
		});

		int ctrl = net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER;
		MouseButtonEvent ctrlClick = new MouseButtonEvent(
				50, 6, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, ctrl));
		assertTrue(widget.mouseClicked(ctrlClick, null, tab));
		assertTrue(requestRef.get() != null, "Ctrl+Click over code viewport must dispatch ctrlClickHandler.");
		assertEquals(0, requestRef.get().position().line());
		assertFalse(tab.buffer().hasSelection(), "Handled Ctrl+Click must not create a text selection.");
	}

	@Test
	void hoverTooltipWaitsHalfSecondAndOnlyTriggersOnCommandFirstWordOrExecuteSubcommand() {
		CodeEditorWidget widget = new CodeEditorWidget();
		OpenTabState tab = new OpenTabState(new FileDocument(
				"pack/data/demo/function/hover.mcfunction",
				"scoreboard players set @a coins 1\nexecute as @a run scoreboard players set @s coins 1",
				"sha",
				0L
		));
		widget.setViewportBoundsForTest(
				new IdeLayout.Rect(0, 0, 500, 120),
				new IdeLayout.Rect(0, 0, 32, 115),
				new IdeLayout.Rect(32, 0, 463, 115)
		);

		java.util.concurrent.atomic.AtomicLong nowMs = new java.util.concurrent.atomic.AtomicLong(1_000L);
		widget.setClockForTest(nowMs::get);
		widget.setCommandHoverResolverForTest((line, col) -> {
			if (line.startsWith("execute") && col < 10) {
				return List.of("execute as <targets> run ...");
			}
			return List.of("scoreboard players set <targets> <objective> <score>");
		});

		// Coordinates: codeViewport.x=32, leftPadding=2 => col c is at x = 34 + c*6 + 2.
		// Line 0 y in [4..15), Line 1 y in [15..26)
		int xScoreboard = 34 + 3 * 6;
		int yLine0 = 8;
		int xPlayers = 34 + 13 * 6;

		// At t=1000ms and t=1499ms: no tooltip yet (< 500ms delay)
		assertFalse(widget.evaluateHoverAt(null, tab, xScoreboard, yLine0).isVisible());
		nowMs.set(1_499L);
		assertFalse(widget.evaluateHoverAt(null, tab, xScoreboard, yLine0).isVisible());

		// At t=1500ms (>= 500ms delay): tooltip appears for first command word
		nowMs.set(1_500L);
		var state = widget.evaluateHoverAt(null, tab, xScoreboard, yLine0);
		assertTrue(state.isVisible());
		assertEquals(List.of("scoreboard players set <targets> <objective> <score>"), state.signatures());

		// Moving cursor onto non-first argument token ('players') hides tooltip immediately even after waiting > 500ms
		assertFalse(widget.evaluateHoverAt(null, tab, xPlayers, yLine0).isVisible());
		nowMs.addAndGet(1_000L);
		assertFalse(widget.evaluateHoverAt(null, tab, xPlayers, yLine0).isVisible(),
				"Tooltip must not appear when hovering non-first command arguments.");

		// Moving back to 'scoreboard' restarts the 500ms timer from scratch
		assertFalse(widget.evaluateHoverAt(null, tab, xScoreboard, yLine0).isVisible());
		nowMs.addAndGet(499L);
		assertFalse(widget.evaluateHoverAt(null, tab, xScoreboard, yLine0).isVisible());
		nowMs.addAndGet(1L);
		assertTrue(widget.evaluateHoverAt(null, tab, xScoreboard, yLine0).isVisible());

		// Hovering empty space past end of line or below last line hides tooltip and does not trigger on short lines
		assertFalse(widget.evaluateHoverAt(null, tab, 460, yLine0).isVisible(),
				"Hovering past end-of-line empty canvas must not trigger tooltip.");
		assertFalse(widget.evaluateHoverAt(null, tab, xScoreboard, 90).isVisible(),
				"Hovering below last line must not trigger tooltip.");

		// Line 1: `execute as @a run scoreboard players set @s coins 1`
		int yLine1 = 19;
		int xExecute = 34 + 2 * 6;
		int xSubScore = 34 + ("execute as @a run ".length() + 3) * 6;
		int xRun = 34 + ("execute as @a ".length() + 1) * 6;

		assertFalse(widget.evaluateHoverAt(null, tab, xExecute, yLine1).isVisible());
		nowMs.addAndGet(500L);
		var execState = widget.evaluateHoverAt(null, tab, xExecute, yLine1);
		assertTrue(execState.isVisible());
		assertEquals(List.of("execute as <targets> run ..."), execState.signatures());

		assertFalse(widget.evaluateHoverAt(null, tab, xRun, yLine1).isVisible());
		nowMs.addAndGet(600L);
		assertFalse(widget.evaluateHoverAt(null, tab, xRun, yLine1).isVisible());

		assertFalse(widget.evaluateHoverAt(null, tab, xSubScore, yLine1).isVisible());
		nowMs.addAndGet(500L);
		var subState = widget.evaluateHoverAt(null, tab, xSubScore, yLine1);
		assertTrue(subState.isVisible());
		assertEquals(List.of("scoreboard players set <targets> <objective> <score>"), subState.signatures());
	}

	@Test
	void hoveringFunctionCommandOrIdShowsFunctionHeaderPreviewAfterHalfSecond() {
		CodeEditorWidget widget = new CodeEditorWidget();
		OpenTabState tab = new OpenTabState(new FileDocument(
				"pack/data/demo/function/caller.mcfunction",
				"/function demo:shop\nexecute as @a run function demo:shop",
				"sha",
				0L
		));
		widget.setViewportBoundsForTest(
				new IdeLayout.Rect(0, 0, 500, 120),
				new IdeLayout.Rect(0, 0, 32, 115),
				new IdeLayout.Rect(32, 0, 463, 115)
		);

		java.util.concurrent.atomic.AtomicLong nowMs = new java.util.concurrent.atomic.AtomicLong(2_000L);
		widget.setClockForTest(nowMs::get);
		widget.setFunctionPreviewResolver(fnId -> java.util.concurrent.CompletableFuture.completedFuture(
				"demo:shop".equals(fnId)
						? new CodeEditorWidget.FunctionPreview(
								"demo:shop",
								"pack/data/demo/function/shop.mcfunction",
								List.of("# Shop header", "say Welcome", "scoreboard players remove @s coins 5"),
								false)
						: null
		));

		int xFunctionVerb = 34 + 3 * 6; // on `function` in `/function demo:shop`
		int yLine0 = 8;

		assertFalse(widget.evaluateHoverAt(null, tab, xFunctionVerb, yLine0).isVisible());
		nowMs.set(2_499L);
		assertFalse(widget.evaluateHoverAt(null, tab, xFunctionVerb, yLine0).isVisible());

		nowMs.set(2_500L);
		var fnState = widget.evaluateHoverAt(null, tab, xFunctionVerb, yLine0);
		assertTrue(fnState.isVisible());
		assertTrue(fnState.functionPreview() != null);
		assertEquals("demo:shop", fnState.functionPreview().functionId());
		assertEquals(
				List.of("# Shop header", "say Welcome", "scoreboard players remove @s coins 5"),
				fnState.functionPreview().headerLines()
		);
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
