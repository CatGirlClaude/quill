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

	@Test
	void explorerRemovesCrudToolbarButtonsAndStartsListBelowHeader() {
		assertFalse(FileExplorerTreeWidget.hasToolbarButtons(),
				"Explorer must remove +File/+Dir CRUD toolbar buttons per P1 explorer cleanup.");
		IdeLayout.Rect area = new IdeLayout.Rect(40, 24, 200, 300);
		IdeLayout.Rect listRect = FileExplorerTreeWidget.computeListRect(area);
		assertEquals(area.y() + FileExplorerTreeWidget.HEADER_HEIGHT, listRect.y(),
				"Explorer tree viewport should start immediately below header without toolbar gap.");
	}

	@Test
	void explorerContextMenuCoversMinimumActionsAndRespectsLocalVsRemoteReveal() {
		var backend = inMemoryBackend(
				new java.util.LinkedHashMap<>(java.util.Map.of(
						"my_pack/pack.mcmeta", "{\"pack\":{}}",
						"my_pack/data/demo/function/tick.mcfunction", "say tick"
				)),
				new java.util.LinkedHashSet<>(List.of("my_pack", "my_pack/data", "my_pack/data/demo", "my_pack/data/demo/function"))
		);

		var localSession = new dev.catgirlclaude.quill.client.session.ClientEditorSession(
				backend, Runnable::run, "Local", java.nio.file.Path.of("/tmp/quill-test-datapacks"));
		localSession.refreshTree();

		List<ContextMenuOverlay.MenuEntry> fileMenu = FileExplorerTreeWidget.buildContextMenuEntries(
				localSession,
				new dev.catgirlclaude.quill.api.FileTreeNode(
						"tick.mcfunction", "my_pack/data/demo/function/tick.mcfunction",
						dev.catgirlclaude.quill.api.EntryKind.FILE, List.of()),
				new FileExplorerTreeWidget.ContextMenuActions(
						() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}
				)
		);
		List<String> fileLabels = fileMenu.stream()
				.filter(ContextMenuOverlay.ActionItem.class::isInstance)
				.map(e -> ((ContextMenuOverlay.ActionItem) e).label())
				.toList();
		for (String required : List.of("Run Function", "Open to Side", "Reveal in File Explorer",
				"Find File References", "Cut", "Copy", "Copy Path", "Delete", "Rename")) {
			assertTrue(fileLabels.contains(required), "Missing required file context menu entry '" + required + "' in " + fileLabels);
		}
		boolean revealLocalEnabled = fileMenu.stream()
				.filter(ContextMenuOverlay.ActionItem.class::isInstance)
				.map(ContextMenuOverlay.ActionItem.class::cast)
				.filter(a -> a.label().equals("Reveal in File Explorer"))
				.findFirst().orElseThrow().enabled();
		assertTrue(revealLocalEnabled, "Reveal in File Explorer must be enabled on local workspace.");

		var remoteSession = new dev.catgirlclaude.quill.client.session.ClientEditorSession(backend, Runnable::run, "Remote", null);
		remoteSession.refreshTree();
		List<ContextMenuOverlay.MenuEntry> remoteFolderMenu = FileExplorerTreeWidget.buildContextMenuEntries(
				remoteSession,
				new dev.catgirlclaude.quill.api.FileTreeNode(
						"function", "my_pack/data/demo/function",
						dev.catgirlclaude.quill.api.EntryKind.DIRECTORY, List.of()),
				new FileExplorerTreeWidget.ContextMenuActions(
						() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}
				)
		);
		boolean revealRemoteEnabled = remoteFolderMenu.stream()
				.filter(ContextMenuOverlay.ActionItem.class::isInstance)
				.map(ContextMenuOverlay.ActionItem.class::cast)
				.filter(a -> a.label().equals("Reveal in File Explorer"))
				.findFirst().orElseThrow().enabled();
		assertFalse(revealRemoteEnabled, "Reveal in File Explorer must be disabled when editing a remote server.");

		assertEquals("demo:tick", FileExplorerTreeWidget.inferReferenceQuery("my_pack/data/demo/function/tick.mcfunction"));
	}

	@Test
	void explorerShortcutsCopyCutPasteDeleteAndOpenToSideWorkWithUndo() {
		var files = new java.util.LinkedHashMap<String, String>();
		files.put("pack/data/demo/function/alpha.mcfunction", "say alpha");
		files.put("pack/data/demo/function/beta.mcfunction", "say beta");
		var dirs = new java.util.LinkedHashSet<String>(List.of(
				"pack", "pack/data", "pack/data/demo", "pack/data/demo/function", "pack/data/demo/function/sub"
		));
		var backend = inMemoryBackend(files, dirs);
		var session = new dev.catgirlclaude.quill.client.session.ClientEditorSession(backend, Runnable::run);
		session.refreshTree();

		List<ModalPromptOverlay> openedModals = new ArrayList<>();
		var tree = new FileExplorerTreeWidget(session, openedModals::add, openedModals::clear);

		// Open alpha normally, then open beta to side without closing alpha
		session.openFile("pack/data/demo/function/alpha.mcfunction");
		session.openFileToSide("pack/data/demo/function/beta.mcfunction");
		assertEquals("pack/data/demo/function/alpha.mcfunction", session.activeTab().relativePath());
		assertEquals("pack/data/demo/function/beta.mcfunction", session.secondaryTab().relativePath());

		int ctrl = net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER;
		// Select alpha and press Ctrl+C, then select same folder and press Ctrl+V -> alpha_copy.mcfunction
		session.setSelectedTreePath("pack/data/demo/function/alpha.mcfunction");
		assertTrue(tree.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_C, com.mojang.blaze3d.platform.InputConstants.KEYCODE_C, ctrl)));
		assertTrue(tree.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_V, com.mojang.blaze3d.platform.InputConstants.KEYCODE_V, ctrl)));
		assertEquals("say alpha", files.get("pack/data/demo/function/alpha_copy.mcfunction"));

		// Undo paste removes alpha_copy.mcfunction
		assertTrue(session.undoFileOperation());
		assertFalse(files.containsKey("pack/data/demo/function/alpha_copy.mcfunction"));

		// Cut alpha_copy after redoing paste -> move into sub/
		assertTrue(session.redoFileOperation());
		assertTrue(files.containsKey("pack/data/demo/function/alpha_copy.mcfunction"));
		session.setSelectedTreePath("pack/data/demo/function/alpha_copy.mcfunction");
		assertTrue(tree.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_X, com.mojang.blaze3d.platform.InputConstants.KEYCODE_X, ctrl)));
		session.setSelectedTreePath("pack/data/demo/function/sub");
		assertTrue(tree.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_V, com.mojang.blaze3d.platform.InputConstants.KEYCODE_V, ctrl)));
		assertEquals("say alpha", files.get("pack/data/demo/function/sub/alpha_copy.mcfunction"));
		assertFalse(files.containsKey("pack/data/demo/function/alpha_copy.mcfunction"));

		// Delete key opens confirmation modal; confirming deletes file; undo restores it
		session.setSelectedTreePath("pack/data/demo/function/sub/alpha_copy.mcfunction");
		assertTrue(tree.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_DELETE, 0, 0)));
		assertEquals(1, openedModals.size());
		// Press Enter on confirmation modal to delete
		openedModals.getFirst().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, com.mojang.blaze3d.platform.InputConstants.KEYCODE_RETURN, 0));
		assertFalse(files.containsKey("pack/data/demo/function/sub/alpha_copy.mcfunction"));
		assertTrue(session.undoFileOperation());
		assertEquals("say alpha", files.get("pack/data/demo/function/sub/alpha_copy.mcfunction"));
	}

	private static dev.catgirlclaude.quill.api.EditorBackend inMemoryBackend(
			java.util.Map<String, String> files,
			java.util.Set<String> dirs
	) {
		return new dev.catgirlclaude.quill.api.EditorBackend() {
			@Override
			public java.util.concurrent.CompletableFuture<dev.catgirlclaude.quill.api.FileTreeNode> listTree() {
				var childrenByDir = new java.util.HashMap<String, List<dev.catgirlclaude.quill.api.FileTreeNode>>();
				for (String d : dirs) {
					childrenByDir.putIfAbsent(d, new ArrayList<>());
				}
				List<String> sortedDirs = dirs.stream().sorted().toList();
				for (String d : sortedDirs) {
					int slash = d.lastIndexOf('/');
					if (slash > 0) {
						String parent = d.substring(0, slash);
						childrenByDir.computeIfAbsent(parent, k -> new ArrayList<>());
					}
				}
				return java.util.concurrent.CompletableFuture.completedFuture(buildTree("", "datapacks", dev.catgirlclaude.quill.api.EntryKind.ROOT, files, dirs));
			}

			private dev.catgirlclaude.quill.api.FileTreeNode buildTree(
					String path, String name, dev.catgirlclaude.quill.api.EntryKind kind,
					java.util.Map<String, String> fMap, java.util.Set<String> dSet) {
				List<dev.catgirlclaude.quill.api.FileTreeNode> kids = new ArrayList<>();
				String prefix = path.isEmpty() ? "" : path + "/";
				for (String d : dSet.stream().sorted().toList()) {
					if (d.startsWith(prefix) && !d.equals(path) && !d.substring(prefix.length()).contains("/")) {
						String childName = d.substring(prefix.length());
						dev.catgirlclaude.quill.api.EntryKind childKind = path.isEmpty()
								? dev.catgirlclaude.quill.api.EntryKind.DATAPACK
								: dev.catgirlclaude.quill.api.EntryKind.DIRECTORY;
						kids.add(buildTree(d, childName, childKind, fMap, dSet));
					}
				}
				for (String f : fMap.keySet().stream().sorted().toList()) {
					if (f.startsWith(prefix) && !f.substring(prefix.length()).contains("/")) {
						kids.add(new dev.catgirlclaude.quill.api.FileTreeNode(
								f.substring(prefix.length()), f, dev.catgirlclaude.quill.api.EntryKind.FILE, List.of()));
					}
				}
				return new dev.catgirlclaude.quill.api.FileTreeNode(name, path, kind, kids);
			}

			@Override
			public java.util.concurrent.CompletableFuture<dev.catgirlclaude.quill.api.FileDocument> readFile(String relativePath) {
				String c = files.get(relativePath);
				if (c == null) {
					return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Missing: " + relativePath));
				}
				return java.util.concurrent.CompletableFuture.completedFuture(
						new dev.catgirlclaude.quill.api.FileDocument(relativePath, c, "sha:" + c.hashCode(), 0L));
			}

			@Override
			public java.util.concurrent.CompletableFuture<dev.catgirlclaude.quill.api.SaveResult> writeFile(
					String relativePath, String content, String expectedBaseSha256, boolean reloadAfterSave) {
				files.put(relativePath, content);
				return java.util.concurrent.CompletableFuture.completedFuture(
						new dev.catgirlclaude.quill.api.SaveResult(
								new dev.catgirlclaude.quill.api.FileDocument(relativePath, content, "sha:" + content.hashCode(), 0L), null));
			}

			@Override
			public java.util.concurrent.CompletableFuture<Void> createEntry(String relativePath, dev.catgirlclaude.quill.api.EntryKind kind) {
				if (kind == dev.catgirlclaude.quill.api.EntryKind.FILE) {
					files.put(relativePath, "");
				} else {
					dirs.add(relativePath);
				}
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			}

			@Override
			public java.util.concurrent.CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath) {
				if (files.containsKey(sourceRelativePath)) {
					files.put(targetRelativePath, files.remove(sourceRelativePath));
				} else if (dirs.contains(sourceRelativePath)) {
					dirs.remove(sourceRelativePath);
					dirs.add(targetRelativePath);
					String srcPrefix = sourceRelativePath + "/";
					for (String k : new ArrayList<>(files.keySet())) {
						if (k.startsWith(srcPrefix)) {
							files.put(targetRelativePath + "/" + k.substring(srcPrefix.length()), files.remove(k));
						}
					}
				}
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			}

			@Override
			public java.util.concurrent.CompletableFuture<Void> deleteEntry(String relativePath) {
				files.remove(relativePath);
				dirs.remove(relativePath);
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
				return java.util.concurrent.CompletableFuture.completedFuture(
						new dev.catgirlclaude.quill.api.ReloadReport(true, 1L, 0, List.of()));
			}
		};
	}

	private static void assertFalse(boolean cond) {
		org.junit.jupiter.api.Assertions.assertFalse(cond);
	}

	private static void assertFalse(boolean cond, String msg) {
		org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
	}
}
