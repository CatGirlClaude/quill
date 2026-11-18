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

	@Test
	void dirtyChangesUseNeutralAsteriskInsteadOfRedErrorColors() {
		QuillTheme.applyPalette(dev.catgirlclaude.quill.client.config.QuillColorPalette.DARK_PLUS);
		assertEquals(QuillTheme.BG_STATUS_BAR, QuillIdeScreen.statusBarBackgroundColor(false, false, true),
				"Unsaved tabs must keep calm neutral status bar color, not red/orange error-like bar.");
		assertEquals(QuillTheme.BG_STATUS_ERROR, QuillIdeScreen.statusBarBackgroundColor(true, false, true),
				"Actual diagnostics errors must still highlight status bar as error.");

		assertEquals("hello.mcfunction *", FileExplorerTreeWidget.formatFileDisplayName("hello.mcfunction", true));
		assertEquals("hello.mcfunction", FileExplorerTreeWidget.formatFileDisplayName("hello.mcfunction", false));
		assertEquals("*", EditorTabBarWidget.dirtyIndicatorGlyph());
	}

	@Test
	void fileNamesStayNeutralWhiteWhileBadgesCarryTypeColorsAndJsonVersionB() {
		QuillTheme.applyPalette(dev.catgirlclaude.quill.client.config.QuillColorPalette.DARK_PLUS);
		assertEquals(QuillTheme.TEXT_PRIMARY, FileExplorerTreeWidget.colorForEntryLabel(false));
		assertEquals(QuillTheme.TEXT_BRIGHT, FileExplorerTreeWidget.colorForEntryLabel(true));

		var fnType = QuillFileTypes.classify("hello.mcfunction");
		assertEquals(QuillFileTypes.Category.FUNCTION, fnType.category());
		assertTrue(fnType.editable());
		assertFalse("fn".equals(fnType.shortLabel()), "Function files must use custom icon rather than literal 'fn' text.");

		var jsonType = QuillFileTypes.classify("load.json");
		assertEquals(QuillFileTypes.Category.JSON, jsonType.category());
		assertTrue(jsonType.editable());
		assertEquals(2, QuillDraw.jsonBracesGapPx(), "Approved JSON {} Version B uses a 2px gap between braces.");

		assertEquals(QuillFileTypes.Category.IMAGE, QuillFileTypes.classify("pack.png").category());
		assertFalse(QuillFileTypes.classify("pack.png").editable());
		assertEquals(QuillFileTypes.Category.AUDIO, QuillFileTypes.classify("click.ogg").category());
		assertFalse(QuillFileTypes.classify("click.ogg").editable());
		assertEquals(QuillFileTypes.Category.NBT, QuillFileTypes.classify("house.nbt").category());
		assertFalse(QuillFileTypes.classify("house.nbt").editable());
		assertEquals(QuillFileTypes.Category.ARCHIVE, QuillFileTypes.classify("backup.zip").category());
		assertFalse(QuillFileTypes.classify("backup.zip").editable());
	}

	@Test
	void geometricChevronEmitsVectorSegmentsInsteadOfAsciiGlyphs() {
		List<int[]> rects = new ArrayList<>();
		QuillDraw.drawChevron(
				(x, y, w, h, c) -> rects.add(new int[]{x, y, w, h, c}),
				10, 10, 17, false, 0xFFCCCCCC
		);
		assertTrue(rects.size() >= 6, "Collapsed right chevron must draw multi-segment diagonal geometry.");

		rects.clear();
		QuillDraw.drawChevron(
				(x, y, w, h, c) -> rects.add(new int[]{x, y, w, h, c}),
				10, 10, 17, true, 0xFFCCCCCC
		);
		assertTrue(rects.size() >= 6, "Expanded down chevron must draw multi-segment diagonal geometry.");
	}

	@Test
	void clickingUnsupportedFileShowsPersistentNotificationToastWithExactText() {
		var backend = new dev.catgirlclaude.quill.api.EditorBackend() {
			@Override
			public java.util.concurrent.CompletableFuture<dev.catgirlclaude.quill.api.FileTreeNode> listTree() {
				return java.util.concurrent.CompletableFuture.completedFuture(
						new dev.catgirlclaude.quill.api.FileTreeNode("datapacks", "", dev.catgirlclaude.quill.api.EntryKind.ROOT, List.of()));
			}

			@Override
			public java.util.concurrent.CompletableFuture<dev.catgirlclaude.quill.api.FileDocument> readFile(String relativePath) {
				throw new AssertionError("Unsupported file should not trigger backend.readFile: " + relativePath);
			}

			@Override
			public java.util.concurrent.CompletableFuture<dev.catgirlclaude.quill.api.SaveResult> writeFile(
					String relativePath, String content, String expectedBaseSha256, boolean reloadAfterSave) {
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
			}

			@Override
			public java.util.concurrent.CompletableFuture<Void> createEntry(String relativePath, dev.catgirlclaude.quill.api.EntryKind kind) {
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			}

			@Override
			public java.util.concurrent.CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath) {
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			}

			@Override
			public java.util.concurrent.CompletableFuture<Void> deleteEntry(String relativePath) {
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			}

			@Override
			public java.util.concurrent.CompletableFuture<String> createDatapack(String packName, String description) {
				return java.util.concurrent.CompletableFuture.completedFuture(packName);
			}

			@Override
			public java.util.concurrent.CompletableFuture<String> createNamespace(String packName, String namespace) {
				return java.util.concurrent.CompletableFuture.completedFuture(packName);
			}

			@Override
			public java.util.concurrent.CompletableFuture<String> createFunction(String packName, String namespace, String functionPath) {
				return java.util.concurrent.CompletableFuture.completedFuture(packName);
			}

			@Override
			public java.util.concurrent.CompletableFuture<List<dev.catgirlclaude.quill.api.PresetDescriptor>> listPresets() {
				return java.util.concurrent.CompletableFuture.completedFuture(List.of());
			}

			@Override
			public java.util.concurrent.CompletableFuture<dev.catgirlclaude.quill.api.FileDocument> applyPreset(
					String packName, String presetId, java.util.Map<String, String> variables) {
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
			}

			@Override
			public java.util.concurrent.CompletableFuture<dev.catgirlclaude.quill.api.ReloadReport> reloadDatapacks() {
				return java.util.concurrent.CompletableFuture.completedFuture(new dev.catgirlclaude.quill.api.ReloadReport(true, 1L, 0, List.of()));
			}
		};
		var session = new dev.catgirlclaude.quill.client.session.ClientEditorSession(backend, Runnable::run);

		session.openFile("my_pack/pack.png");
		assertEquals(0, session.openTabs().size(), "Unsupported file must not open a text tab.");
		var note = session.activeNotification();
		assertTrue(note != null, "Persistent notification banner should appear for unsupported format.");
		assertEquals("pack.png cannot be opened in the Quill text editor yet (unsupported format).", note.message());
		assertFalse(note.autoHide(), "Notification toast must not auto-hide per user decision.");

		session.dismissNotification();
		assertTrue(session.activeNotification() == null);
	}

	@Test
	void settingsFontPreviewsHaveEqualContrastAndUniformCapHeightAndStatusBarIsCompact() {
		QuillTheme.applyPalette(dev.catgirlclaude.quill.client.config.QuillColorPalette.DARK_PLUS);
		assertEquals(QuillTheme.TEXT_PRIMARY, SettingsPanelWidget.previewColorForFont(false));
		assertEquals(QuillTheme.TEXT_BRIGHT, SettingsPanelWidget.previewColorForFont(true));
		assertTrue(SettingsPanelWidget.previewScaleForFont(dev.catgirlclaude.quill.client.config.QuillClientConfig.FontChoice.MINECRAFT_UNIFORM) > 1.15f,
				"Minecraft Uniform preview must be scaled up to match cap-height of other fonts.");
		assertEquals("Dark+ (Default Dark)", SettingsPanelWidget.formatCardTitle("Dark+ (Default Dark)"));

		String right = QuillIdeScreen.formatStatusRightInfo(
				new dev.catgirlclaude.quill.client.session.OpenTabState(
						new dev.catgirlclaude.quill.api.FileDocument("pack/data/demo/function/tick.mcfunction", "say hi\nsay two", "sha", 0L)),
				100, false, false
		);
		assertFalse(right.contains("UTF-8"), "Compact status bar should omit UTF-8 bloat: " + right);
		assertFalse(right.contains("Spaces: 4"), "Compact status bar should omit Spaces: 4 bloat: " + right);
		assertTrue(right.contains("Ln 1, Col 1") && right.contains("mcfunction"));
	}

	private static void assertFalse(boolean cond) {
		org.junit.jupiter.api.Assertions.assertFalse(cond);
	}

	private static void assertFalse(boolean cond, String msg) {
		org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
	}
}
