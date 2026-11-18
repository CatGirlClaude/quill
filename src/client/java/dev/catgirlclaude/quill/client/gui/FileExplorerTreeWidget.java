package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.client.editor.SingleLineTextInput;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Interactive VS Code-style datapack tree widget for browsing, inline creating (folders &amp; files),
 * inline renaming, keyboard copy/cut/paste/delete, and right-click context menu actions.
 */
public final class FileExplorerTreeWidget {
	public static final int HEADER_HEIGHT = 18;
	private static final int ROW_HEIGHT = QuillTheme.Metrics.EXPLORER_ROW_HEIGHT;

	public enum InlineEditKind {
		NEW_FOLDER,
		NEW_FILE,
		RENAME
	}

	public record ContextMenuActions(
			Runnable openToSide,
			Runnable runFunction,
			Runnable revealInFileExplorer,
			Runnable findFileReferences,
			Runnable newFile,
			Runnable newFolder,
			Runnable cut,
			Runnable copy,
			Runnable paste,
			Runnable copyPath,
			Runnable rename,
			Runnable delete
	) {
	}

	private sealed interface VisibleRow permits NodeRow, InlineInputRow {
		int depth();
	}

	private record NodeRow(FileTreeNode node, int depth) implements VisibleRow {
	}

	private record InlineInputRow(InlineEditState edit, int depth) implements VisibleRow {
	}

	private static final class InlineEditState {
		final InlineEditKind kind;
		final String parentPath;
		final String targetExistingPath;
		final String originalName;
		final boolean targetIsFile;
		final EntryKind createKind;
		final SingleLineTextInput input;
		String validationError = null;
		IdeLayout.Rect boxRect = new IdeLayout.Rect(0, 0, 0, 0);

		InlineEditState(
				InlineEditKind kind,
				String parentPath,
				String targetExistingPath,
				String originalName,
				boolean targetIsFile,
				EntryKind createKind,
				String initialText
		) {
			this.kind = kind;
			this.parentPath = parentPath == null ? "" : parentPath;
			this.targetExistingPath = targetExistingPath;
			this.originalName = originalName;
			this.targetIsFile = targetIsFile;
			this.createKind = createKind;
			this.input = new SingleLineTextInput(initialText);
		}

		String placeholder() {
			return switch (kind) {
				case NEW_FOLDER -> parentPath.isEmpty() ? "datapack_name" : "folder_name";
				case NEW_FILE -> "hello.mcfunction";
				case RENAME -> originalName != null ? originalName : "new_name";
			};
		}
	}

	private final ClientEditorSession session;
	private final Consumer<ModalPromptOverlay> modalOpener;
	private final Runnable modalCloser;

	private Consumer<ContextMenuOverlay> contextMenuOpener = menu -> {};
	private Runnable contextMenuDismisser = () -> {};
	private Consumer<String> findReferencesHandler = query -> {};
	private Consumer<String> systemClipboardCopier = FileExplorerTreeWidget::copyToMinecraftClipboard;
	private Consumer<String> revealPathHandler = null;

	private final List<VisibleRow> visibleRows = new ArrayList<>();
	private IdeLayout.Rect listRect = new IdeLayout.Rect(0, 0, 0, 0);
	private int scrollPixels = 0;

	private InlineEditState inlineEdit = null;
	private boolean pendingScrollToInline = false;

	public FileExplorerTreeWidget(
			ClientEditorSession session,
			Consumer<ModalPromptOverlay> modalOpener,
			Runnable modalCloser
	) {
		this.session = session;
		this.modalOpener = modalOpener;
		this.modalCloser = modalCloser;
	}

	public void setContextMenuHooks(
			Consumer<ContextMenuOverlay> contextMenuOpener,
			Runnable contextMenuDismisser,
			Consumer<String> findReferencesHandler
	) {
		this.contextMenuOpener = contextMenuOpener != null ? contextMenuOpener : menu -> {};
		this.contextMenuDismisser = contextMenuDismisser != null ? contextMenuDismisser : () -> {};
		this.findReferencesHandler = findReferencesHandler != null ? findReferencesHandler : q -> {};
	}

	void setSystemClipboardCopier(Consumer<String> systemClipboardCopier) {
		this.systemClipboardCopier = systemClipboardCopier != null
				? systemClipboardCopier
				: FileExplorerTreeWidget::copyToMinecraftClipboard;
	}

	void setRevealPathHandler(Consumer<String> revealPathHandler) {
		this.revealPathHandler = revealPathHandler;
	}

	public static boolean hasToolbarButtons() {
		return false;
	}

	public static IdeLayout.Rect computeListRect(IdeLayout.Rect area) {
		int listY = area.y() + HEADER_HEIGHT;
		return new IdeLayout.Rect(area.x(), listY, Math.max(0, area.width() - 1), Math.max(0, area.bottom() - listY));
	}

	public boolean hasActiveInlineInput() {
		return inlineEdit != null;
	}

	public InlineEditKind activeInlineEditKind() {
		return inlineEdit != null ? inlineEdit.kind : null;
	}

	public String activeInlineValidationError() {
		return inlineEdit != null ? inlineEdit.validationError : null;
	}

	public void cancelInlineInput() {
		if (inlineEdit != null) {
			inlineEdit.input.mouseReleased();
			inlineEdit = null;
		}
		pendingScrollToInline = false;
	}

	public void beginInlineCreateFolder() {
		beginInlineCreateFolderIn(resolveTargetDirectory());
	}

	public void beginInlineCreateFolderIn(String baseDir) {
		String dir = baseDir == null ? "" : baseDir;
		EntryKind kind = dir.isEmpty() ? EntryKind.DATAPACK : EntryKind.DIRECTORY;
		if (!dir.isEmpty()) {
			session.expandAncestors(dir + "/dummy");
			session.expandedPaths().add(dir);
		}
		inlineEdit = new InlineEditState(
				InlineEditKind.NEW_FOLDER,
				dir,
				null,
				null,
				false,
				kind,
				""
		);
		pendingScrollToInline = true;
	}

	public void beginInlineCreateFile() {
		beginInlineCreateFileIn(resolveTargetDirectory());
	}

	public void beginInlineCreateFileIn(String baseDir) {
		String dir = baseDir == null ? "" : baseDir;
		if (dir.isEmpty()) {
			session.setStatus("Select a datapack or folder first.", true);
			return;
		}
		session.expandAncestors(dir + "/dummy");
		session.expandedPaths().add(dir);
		inlineEdit = new InlineEditState(
				InlineEditKind.NEW_FILE,
				dir,
				null,
				null,
				true,
				EntryKind.FILE,
				"new_fn.mcfunction"
		);
		// Select stem ("new_fn") just like VS Code so user can immediately type name while keeping extension
		inlineEdit.input.selectRange(0, "new_fn".length());
		pendingScrollToInline = true;
	}

	public void beginInlineRename() {
		String selected = session.selectedTreePath();
		if (selected == null || selected.isEmpty()) {
			session.setStatus("Select a file or folder to rename.", true);
			return;
		}
		int lastSlash = selected.lastIndexOf('/');
		String parent = lastSlash >= 0 ? selected.substring(0, lastSlash) : "";
		String currentName = lastSlash >= 0 ? selected.substring(lastSlash + 1) : selected;
		FileTreeNode node = findNode(session.rootTree(), selected);
		boolean isFile = node != null && node.kind() == EntryKind.FILE;

		inlineEdit = new InlineEditState(
				InlineEditKind.RENAME,
				parent,
				selected,
				currentName,
				isFile,
				isFile ? EntryKind.FILE : EntryKind.DIRECTORY,
				currentName
		);
		int dot = isFile ? currentName.lastIndexOf('.') : -1;
		if (dot > 0) {
			inlineEdit.input.selectRange(0, dot);
		} else {
			inlineEdit.input.selectAll();
		}
		pendingScrollToInline = true;
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect area, int mouseX, int mouseY) {
		rebuildVisibleRows();

		// Clean VS Code EXPLORER header without button clutter
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, "EXPLORER", area.width() - 12),
				area.x() + 6, area.y() + 5, QuillTheme.TEXT_MUTED);
		QuillDraw.hLine(graphics, area.x(), Math.max(0, area.width() - 1), area.y() + HEADER_HEIGHT - 1, QuillTheme.BORDER_SUBTLE);

		// Tree list viewport starts directly below header
		listRect = computeListRect(area);

		if (pendingScrollToInline && inlineEdit != null) {
			pendingScrollToInline = false;
			scrollInlineRowIntoView();
		}

		int totalHeight = visibleRows.size() * ROW_HEIGHT + (inlineEdit != null && inlineEdit.validationError != null ? 18 : 0);
		int maxScroll = Math.max(0, totalHeight - listRect.height());
		scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels));

		IdeLayout.Rect inlineErrorAnchor = null;

		QuillDraw.beginClip(graphics, listRect);
		if (visibleRows.isEmpty()) {
			QuillDraw.text(graphics, font, "No datapacks yet.", listRect.x() + 8, listRect.y() + 8, QuillTheme.TEXT_MUTED);
			QuillDraw.text(graphics, font, "Right-click or open Presets.", listRect.x() + 8, listRect.y() + 22, QuillTheme.TEXT_MUTED);
		} else {
			int firstRow = scrollPixels / ROW_HEIGHT;
			int lastRow = Math.min(visibleRows.size() - 1, (scrollPixels + listRect.height()) / ROW_HEIGHT);
			for (int i = firstRow; i <= lastRow; i++) {
				VisibleRow row = visibleRows.get(i);
				int ry = listRect.y() + i * ROW_HEIGHT - scrollPixels;
				int indentX = listRect.x() + 6 + row.depth() * 10;
				boolean selected = row instanceof NodeRow nr && nr.node().relativePath().equals(session.selectedTreePath());
				boolean hovered = row instanceof NodeRow && listRect.contains(mouseX, mouseY) && mouseY >= ry && mouseY < ry + ROW_HEIGHT;

				renderRowChrome(
						new RowChromeTarget() {
							@Override
							public void fillBackground(int x, int y, int width, int height, int color) {
								QuillDraw.fill(graphics, x, y, width, height, color);
							}

							@Override
							public void drawIndentGuide(int x, int y, int height, int color) {
								QuillDraw.vLine(graphics, x, y, height, color);
							}
						},
						listRect,
						ry,
						row.depth(),
						row instanceof InlineInputRow,
						selected,
						hovered
				);

				if (row instanceof InlineInputRow inlineRow) {
					InlineEditState edit = inlineRow.edit();
					int iconW;
					if (edit.targetIsFile) {
						String sample = edit.input.text().isBlank() ? "file.mcfunction" : edit.input.text();
						iconW = QuillDraw.drawFileTypeIcon(graphics, font, sample, indentX + 2, ry, ROW_HEIGHT) + 2;
					} else {
						iconW = QuillDraw.drawFolderIcon(graphics, indentX, ry, ROW_HEIGHT, true, QuillTheme.TEXT_FOLDER);
					}
					int boxX = indentX + iconW + 4;
					int maxBoxW = Math.max(36, listRect.right() - boxX - 6);
					int contentW = Math.max(QuillDraw.textWidth(font, edit.input.text()), QuillDraw.textWidth(font, edit.placeholder()));
					edit.boxRect = computeInlineBoxRect(boxX, ry, maxBoxW, contentW);
					edit.input.render(graphics, font, edit.boxRect, edit.placeholder(), true);
					if (edit.validationError != null) {
						QuillDraw.outline(graphics, edit.boxRect.x(), edit.boxRect.y(), edit.boxRect.width(), edit.boxRect.height(), QuillTheme.BG_STATUS_ERROR);
						inlineErrorAnchor = edit.boxRect;
					}
					if (edit.boxRect.contains(mouseX, mouseY)) {
						QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
					}
				} else if (row instanceof NodeRow nodeRow) {
					FileTreeNode node = nodeRow.node();
					boolean cut = session.isCutEntry(node.relativePath());
					if (hovered) {
						QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
					}

					int textY = ry + (ROW_HEIGHT - font.lineHeight) / 2;
					int labelColor = cut ? QuillTheme.TEXT_MUTED : colorForEntryLabel(selected);
					if (node.kind() == EntryKind.FILE) {
						int iconW = QuillDraw.drawFileTypeIcon(graphics, font, node.name(), indentX + 2, ry, ROW_HEIGHT);
						int nameX = indentX + 2 + iconW + 4;
						int maxW = Math.max(0, listRect.right() - nameX - 6);
						var openTab = session.findOpenTab(node.relativePath());
						boolean dirty = openTab != null && openTab.isDirty();
						String displayName = formatFileDisplayName(node.name(), dirty);
						QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, displayName, maxW),
								nameX, textY, labelColor);
					} else {
						boolean expanded = session.isExpanded(node.relativePath());
						QuillDraw.drawChevron(graphics, indentX - 1, ry, ROW_HEIGHT, expanded, QuillTheme.TEXT_MUTED);
						int folderColor = node.kind() == EntryKind.DATAPACK ? QuillTheme.ACCENT_BLUE : QuillTheme.TEXT_FOLDER;
						int folderW = QuillDraw.drawFolderIcon(graphics, indentX + 10, ry, ROW_HEIGHT, expanded, folderColor);
						int nameX = indentX + 10 + folderW + 4;
						int maxW = Math.max(0, listRect.right() - nameX - 6);
						QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, node.name(), maxW),
								nameX, textY, labelColor);
					}
				}
			}
		}
		QuillDraw.endClip(graphics);

		// Render inline validation banner directly below the active inline input row (VS Code style)
		if (inlineEdit != null && inlineEdit.validationError != null && inlineErrorAnchor != null) {
			int errX = Math.max(listRect.x() + 2, inlineErrorAnchor.x());
			int errW = Math.max(40, listRect.right() - errX - 4);
			int errY = Math.min(listRect.bottom() - 16, inlineErrorAnchor.bottom() + 1);
			QuillDraw.fill(graphics, errX, errY, errW, 15, 0xFF5A1D1D);
			QuillDraw.outline(graphics, errX, errY, errW, 15, QuillTheme.BG_STATUS_ERROR);
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, inlineEdit.validationError, errW - 6),
					errX + 3, errY + (15 - font.lineHeight) / 2, QuillTheme.TEXT_BRIGHT);
		}

		// Vertical scrollbar
		if (maxScroll > 0 && listRect.height() > 8) {
			int trackX = listRect.right() - 4;
			int thumbH = Math.max(12, (int) ((long) listRect.height() * listRect.height() / Math.max(1, totalHeight)));
			int thumbY = listRect.y() + (int) ((long) scrollPixels * (listRect.height() - thumbH) / maxScroll);
			QuillDraw.fill(graphics, trackX, thumbY, 3, thumbH, QuillTheme.TEXT_MUTED);
		}
	}

	interface RowChromeTarget {
		void fillBackground(int x, int y, int width, int height, int color);

		void drawIndentGuide(int x, int y, int height, int color);
	}

	static void renderRowChrome(
			RowChromeTarget target,
			IdeLayout.Rect listRect,
			int ry,
			int depth,
			boolean inlineInput,
			boolean selected,
			boolean hovered
	) {
		if (inlineInput || selected) {
			target.fillBackground(listRect.x(), ry, listRect.width(), ROW_HEIGHT, QuillTheme.ROW_SELECTED);
		} else if (hovered) {
			target.fillBackground(listRect.x(), ry, listRect.width(), ROW_HEIGHT, QuillTheme.BG_HOVER);
		}
		for (int g = 0; g < depth; g++) {
			int gx = listRect.x() + 8 + g * 10;
			target.drawIndentGuide(gx, ry, ROW_HEIGHT, QuillTheme.BORDER_SUBTLE);
		}
	}

	static IdeLayout.Rect computeInlineBoxRect(int boxX, int ry, int maxBoxW, int contentWidth) {
		int desiredW = Math.max(88, contentWidth + 20);
		int width = Math.max(Math.min(maxBoxW, 88), Math.min(maxBoxW, desiredW));
		return new IdeLayout.Rect(boxX, ry + 1, width, ROW_HEIGHT - 2);
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		double mx = event.x();
		double my = event.y();

		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			if (!listRect.contains(mx, my)) {
				return false;
			}
			cancelInlineInput();
			int idx = ((int) my - listRect.y() + scrollPixels) / ROW_HEIGHT;
			FileTreeNode clickedNode = null;
			if (idx >= 0 && idx < visibleRows.size() && visibleRows.get(idx) instanceof NodeRow nodeRow) {
				clickedNode = nodeRow.node();
				session.setSelectedTreePath(clickedNode.relativePath());
			}
			openContextMenu((int) Math.round(mx), (int) Math.round(my), clickedNode);
			return true;
		}

		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}

		if (inlineEdit != null) {
			if (inlineEdit.boxRect.contains(mx, my)) {
				inlineEdit.input.mouseClicked(mx, safeFont(), event.hasShiftDown());
				return true;
			}
			cancelInlineInput();
		}

		if (listRect.contains(mx, my)) {
			int idx = ((int) my - listRect.y() + scrollPixels) / ROW_HEIGHT;
			if (idx >= 0 && idx < visibleRows.size()) {
				VisibleRow row = visibleRows.get(idx);
				if (row instanceof NodeRow nodeRow) {
					FileTreeNode node = nodeRow.node();
					session.setSelectedTreePath(node.relativePath());
					if (node.kind() == EntryKind.FILE) {
						session.openFile(node.relativePath());
					} else {
						session.toggleExpanded(node.relativePath());
					}
					return true;
				}
			}
		}
		return false;
	}

	private void openContextMenu(int anchorX, int anchorY, FileTreeNode node) {
		List<ContextMenuOverlay.MenuEntry> entries;
		if (node != null) {
			String targetPath = node.relativePath();
			String parentOrSelfDir = node.kind() == EntryKind.FILE ? parentPathOf(targetPath) : targetPath;
			ContextMenuActions actions = new ContextMenuActions(
					() -> session.openFileToSide(targetPath),
					() -> session.runFunction(targetPath),
					() -> revealInFileExplorer(targetPath),
					() -> findReferencesForPath(targetPath),
					() -> beginInlineCreateFileIn(parentOrSelfDir),
					() -> beginInlineCreateFolderIn(parentOrSelfDir),
					() -> session.cutTreeEntryToClipboard(targetPath),
					() -> session.copyTreeEntryToClipboard(targetPath),
					() -> session.pasteClipboard(parentOrSelfDir),
					() -> copyRelativePath(targetPath),
					() -> {
						session.setSelectedTreePath(targetPath);
						beginInlineRename();
					},
					() -> {
						session.setSelectedTreePath(targetPath);
						promptDeleteSelected();
					}
			);
			entries = buildContextMenuEntries(session, node, actions);
		} else {
			String fallbackDir = resolveTargetDirectory();
			entries = buildEmptySpaceContextMenuEntries(
					session,
					fallbackDir,
					() -> beginInlineCreateFileIn(fallbackDir),
					() -> beginInlineCreateFolderIn(""),
					() -> session.pasteClipboard(fallbackDir),
					() -> revealInFileExplorer(""),
					session::refreshTree
			);
		}
		contextMenuOpener.accept(new ContextMenuOverlay(anchorX, anchorY, entries, contextMenuDismisser));
	}

	public static List<ContextMenuOverlay.MenuEntry> buildContextMenuEntries(
			ClientEditorSession session,
			FileTreeNode node,
			ContextMenuActions actions
	) {
		List<ContextMenuOverlay.MenuEntry> items = new ArrayList<>();
		boolean isFile = node.kind() == EntryKind.FILE;
		boolean canPaste = session.explorerClipboard() != null;
		boolean canReveal = session.isLocalWorkspace();

		if (isFile) {
			boolean editable = PathSandbox.hasAllowedExtension(node.relativePath());
			boolean runnableFn = DatapackScaffolder.inferFunctionId(node.relativePath()) != null;
			items.add(new ContextMenuOverlay.ActionItem("Open to Side", "", editable, actions.openToSide()));
			items.add(new ContextMenuOverlay.ActionItem("Run Function", "", runnableFn, actions.runFunction()));
			items.add(new ContextMenuOverlay.Separator());
		} else {
			items.add(new ContextMenuOverlay.ActionItem("New File...", "", true, actions.newFile()));
			items.add(new ContextMenuOverlay.ActionItem("New Folder...", "", true, actions.newFolder()));
			items.add(new ContextMenuOverlay.Separator());
		}

		items.add(new ContextMenuOverlay.ActionItem("Reveal in File Explorer", "", canReveal, actions.revealInFileExplorer()));
		items.add(new ContextMenuOverlay.ActionItem("Find File References", "", true, actions.findFileReferences()));
		items.add(new ContextMenuOverlay.Separator());

		if (isFile) {
			items.add(new ContextMenuOverlay.ActionItem("New File...", "", true, actions.newFile()));
			items.add(new ContextMenuOverlay.ActionItem("New Folder...", "", true, actions.newFolder()));
			items.add(new ContextMenuOverlay.Separator());
		}

		items.add(new ContextMenuOverlay.ActionItem("Cut", "Ctrl+X", true, actions.cut()));
		items.add(new ContextMenuOverlay.ActionItem("Copy", "Ctrl+C", true, actions.copy()));
		items.add(new ContextMenuOverlay.ActionItem("Paste", "Ctrl+V", canPaste, actions.paste()));
		items.add(new ContextMenuOverlay.ActionItem("Copy Path", "", true, actions.copyPath()));
		items.add(new ContextMenuOverlay.Separator());
		items.add(new ContextMenuOverlay.ActionItem("Rename", "F2", true, actions.rename()));
		items.add(new ContextMenuOverlay.ActionItem("Delete", "Del", true, actions.delete()));
		return List.copyOf(items);
	}

	private static List<ContextMenuOverlay.MenuEntry> buildEmptySpaceContextMenuEntries(
			ClientEditorSession session,
			String fallbackDir,
			Runnable newFile,
			Runnable newFolder,
			Runnable paste,
			Runnable reveal,
			Runnable refresh
	) {
		List<ContextMenuOverlay.MenuEntry> items = new ArrayList<>();
		items.add(new ContextMenuOverlay.ActionItem("New Datapack Folder...", "", true, newFolder));
		items.add(new ContextMenuOverlay.ActionItem("New File...", "", !fallbackDir.isEmpty(), newFile));
		items.add(new ContextMenuOverlay.Separator());
		items.add(new ContextMenuOverlay.ActionItem("Paste", "Ctrl+V", session.explorerClipboard() != null, paste));
		items.add(new ContextMenuOverlay.ActionItem("Reveal in File Explorer", "", session.isLocalWorkspace(), reveal));
		items.add(new ContextMenuOverlay.Separator());
		items.add(new ContextMenuOverlay.ActionItem("Refresh Explorer", "", true, refresh));
		return List.copyOf(items);
	}

	private void revealInFileExplorer(String relativePath) {
		if (revealPathHandler != null) {
			revealPathHandler.accept(relativePath);
			return;
		}
		Path path = session.resolveLocalPathForReveal(relativePath);
		if (path == null) {
			session.setStatus("Reveal in file explorer is only available in singleplayer.", true);
			return;
		}
		try {
			Blaze3D.openPath(path);
			session.setStatus("Revealed in file explorer", false);
		} catch (Exception e) {
			session.setStatus("Could not open file explorer: " + e.getMessage(), true);
		}
	}

	private void findReferencesForPath(String relativePath) {
		String query = inferReferenceQuery(relativePath);
		findReferencesHandler.accept(query);
	}

	private void copyRelativePath(String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			return;
		}
		systemClipboardCopier.accept(relativePath);
		session.setStatus("Copied path: " + relativePath, false);
	}

	public static String inferReferenceQuery(String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			return "";
		}
		String fnId = DatapackScaffolder.inferFunctionId(relativePath);
		if (fnId != null) {
			return fnId;
		}
		String[] parts = relativePath.split("/");
		// Match <pack>/data/<namespace>/tags/<type>/<path>.json or <pack>/data/<namespace>/<registry>/<path>.json
		if (parts.length >= 5 && "data".equals(parts[1])) {
			String ns = parts[2];
			String category = parts[3];
			int offset = "tags".equals(category) && parts.length >= 6 ? 5 : 4;
			StringBuilder rel = new StringBuilder();
			for (int i = offset; i < parts.length; i++) {
				if (i > offset) {
					rel.append('/');
				}
				rel.append(parts[i]);
			}
			String name = rel.toString();
			if (name.endsWith(".json")) {
				name = name.substring(0, name.length() - ".json".length());
				return ("tags".equals(category) ? "#" : "") + ns + ":" + name;
			}
		}
		int slash = relativePath.lastIndexOf('/');
		String fileName = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	public boolean mouseDragged(MouseButtonEvent event) {
		if (inlineEdit != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT && inlineEdit.input.isDragging()) {
			return inlineEdit.input.mouseDragged(event.x(), safeFont());
		}
		return false;
	}

	public void mouseReleased(MouseButtonEvent event) {
		if (inlineEdit != null) {
			inlineEdit.input.mouseReleased();
		}
	}

	public boolean keyPressed(KeyEvent event) {
		if (inlineEdit == null) {
			String selected = session.selectedTreePath();
			boolean ctrl = event.hasControlDownWithQuirk();
			boolean shift = event.hasShiftDown();
			boolean alt = event.hasAltDown();

			if (event.key() == InputConstants.KEY_F2 && !ctrl && !shift && !alt && !selected.isEmpty()) {
				beginInlineRename();
				return true;
			}
			if (event.key() == InputConstants.KEY_DELETE && !ctrl && !shift && !alt && !selected.isEmpty()) {
				promptDeleteSelected();
				return true;
			}
			if (ctrl && !shift && !alt) {
				if (matchesLetter(event, InputConstants.KEY_C, 'c') && !selected.isEmpty()) {
					session.copyTreeEntryToClipboard(selected);
					return true;
				}
				if (matchesLetter(event, InputConstants.KEY_X, 'x') && !selected.isEmpty()) {
					session.cutTreeEntryToClipboard(selected);
					return true;
				}
				if (matchesLetter(event, InputConstants.KEY_V, 'v') && session.explorerClipboard() != null) {
					session.pasteClipboard(resolveTargetDirectory());
					return true;
				}
			}
			return false;
		}
		if (event.isEscape()) {
			cancelInlineInput();
			return true;
		}
		if (event.isConfirmation()) {
			commitInlineEdit();
			return true;
		}
		SingleLineTextInput.KeyActionResult res = inlineEdit.input.keyPressed(event);
		if (res.textChanged()) {
			refreshLiveValidation();
		}
		return true;
	}

	private static boolean matchesLetter(KeyEvent event, int keyCode, char lower) {
		if (event.key() == keyCode) {
			return true;
		}
		int ch = event.shortcutKey();
		return ch == lower || ch == Character.toUpperCase(lower);
	}

	public boolean charTyped(CharacterEvent event) {
		if (inlineEdit == null) {
			return false;
		}
		if (inlineEdit.input.charTyped(event)) {
			refreshLiveValidation();
		}
		return true;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!listRect.contains(mouseX, mouseY)) {
			return false;
		}
		int maxScroll = Math.max(0, visibleRows.size() * ROW_HEIGHT - listRect.height());
		scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels - (int) (scrollY * ROW_HEIGHT * 2)));
		return true;
	}

	private void refreshLiveValidation() {
		if (inlineEdit == null) {
			return;
		}
		String trimmed = inlineEdit.input.text().trim();
		if (trimmed.isEmpty()) {
			inlineEdit.validationError = null;
			return;
		}
		inlineEdit.validationError = validateInlineName(inlineEdit, trimmed);
	}

	private void commitInlineEdit() {
		if (inlineEdit == null) {
			return;
		}
		InlineEditState edit = inlineEdit;
		String val = edit.input.text().trim();
		String err = validateInlineName(edit, val);
		if (err != null) {
			edit.validationError = err;
			return;
		}

		inlineEdit = null;
		if (edit.kind == InlineEditKind.RENAME) {
			if (val.equals(edit.originalName)) {
				return;
			}
			String target = edit.parentPath.isEmpty() ? val : edit.parentPath + "/" + val;
			session.renameEntry(edit.targetExistingPath, target);
		} else {
			String fullPath = edit.parentPath.isEmpty() ? val : edit.parentPath + "/" + val;
			session.createEntry(fullPath, edit.createKind);
		}
	}

	private static String validateInlineName(InlineEditState edit, String val) {
		if (val.isEmpty()) {
			return switch (edit.kind) {
				case NEW_FOLDER -> "Folder name is required.";
				case NEW_FILE -> "File name is required.";
				case RENAME -> "New name is required.";
			};
		}
		if (edit.kind == InlineEditKind.NEW_FILE) {
			if (!PathSandbox.hasAllowedExtension(val)) {
				return "Use .mcfunction, .json, .mcmeta, .txt, or .md";
			}
			try {
				PathSandbox.validateSegment(val);
			} catch (EditorSecurityException e) {
				return e.getMessage();
			}
			return null;
		}
		try {
			PathSandbox.validatePackOrFolderName(val);
		} catch (EditorSecurityException e) {
			return e.getMessage();
		}
		if (edit.kind == InlineEditKind.RENAME && edit.targetIsFile && !PathSandbox.hasAllowedExtension(val)) {
			return "Use .mcfunction, .json, .mcmeta, .txt, or .md";
		}
		return null;
	}

	private void scrollInlineRowIntoView() {
		rebuildVisibleRows();
		for (int i = 0; i < visibleRows.size(); i++) {
			if (visibleRows.get(i) instanceof InlineInputRow) {
				int rowTop = i * ROW_HEIGHT;
				int rowBottom = rowTop + ROW_HEIGHT + 18;
				if (rowTop < scrollPixels) {
					scrollPixels = rowTop;
				} else if (listRect.height() > 0 && rowBottom > scrollPixels + listRect.height()) {
					scrollPixels = Math.max(0, rowBottom - listRect.height());
				}
				return;
			}
		}
	}

	private void rebuildVisibleRows() {
		visibleRows.clear();
		if (inlineEdit != null
				&& (inlineEdit.kind == InlineEditKind.NEW_FOLDER || inlineEdit.kind == InlineEditKind.NEW_FILE)
				&& inlineEdit.parentPath.isEmpty()) {
			visibleRows.add(new InlineInputRow(inlineEdit, 0));
		}
		for (FileTreeNode child : session.rootTree().children()) {
			appendVisible(child, 0);
		}
	}

	private void appendVisible(FileTreeNode node, int depth) {
		if (inlineEdit != null
				&& inlineEdit.kind == InlineEditKind.RENAME
				&& node.relativePath().equals(inlineEdit.targetExistingPath)) {
			visibleRows.add(new InlineInputRow(inlineEdit, depth));
		} else {
			visibleRows.add(new NodeRow(node, depth));
		}

		if (node.kind() != EntryKind.FILE && session.isExpanded(node.relativePath())) {
			if (inlineEdit != null
					&& (inlineEdit.kind == InlineEditKind.NEW_FOLDER || inlineEdit.kind == InlineEditKind.NEW_FILE)
					&& node.relativePath().equals(inlineEdit.parentPath)) {
				visibleRows.add(new InlineInputRow(inlineEdit, depth + 1));
			}
			for (FileTreeNode child : node.children()) {
				appendVisible(child, depth + 1);
			}
		}
	}

	private void promptDeleteSelected() {
		String selected = session.selectedTreePath();
		if (selected == null || selected.isEmpty()) {
			session.setStatus("Select a file or folder to delete.", true);
			return;
		}
		modalOpener.accept(ModalPromptOverlay.confirm(
				"Delete Entry",
				"Delete '" + selected + "' permanently?",
				"Delete",
				true,
				() -> {
					modalCloser.run();
					session.deleteEntry(selected);
				},
				modalCloser
		));
	}

	private String resolveTargetDirectory() {
		String selected = session.selectedTreePath();
		if (selected != null && !selected.isEmpty()) {
			FileTreeNode node = findNode(session.rootTree(), selected);
			if (node != null) {
				if (node.kind() != EntryKind.FILE) {
					return node.relativePath();
				}
				return parentPathOf(selected);
			}
		}
		if (!session.rootTree().children().isEmpty()) {
			return session.rootTree().children().getFirst().relativePath();
		}
		return "";
	}

	private static String parentPathOf(String path) {
		if (path == null) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(0, slash) : "";
	}

	private static FileTreeNode findNode(FileTreeNode root, String relativePath) {
		if (root.relativePath().equals(relativePath)) {
			return root;
		}
		for (FileTreeNode child : root.children()) {
			FileTreeNode found = findNode(child, relativePath);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static void copyToMinecraftClipboard(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.keyboardHandler != null) {
			mc.keyboardHandler.setClipboard(text);
		}
	}

	static String formatFileDisplayName(String name, boolean dirty) {
		if (name == null) {
			return "";
		}
		return dirty ? name + " *" : name;
	}

	public static String fileBadge(String name) {
		return QuillFileTypes.classify(name).shortLabel();
	}

	static int colorForEntryLabel(boolean selected) {
		return selected ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY;
	}

	private static Font safeFont() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null ? mc.font : null;
	}
}
