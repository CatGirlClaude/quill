package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.client.editor.SingleLineTextInput;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interactive VS Code-style datapack tree widget for browsing, inline creating (folders &amp; files),
 * inline renaming, and deleting files and folders directly inside the explorer tree.
 */
public final class FileExplorerTreeWidget {
	private static final int ROW_HEIGHT = QuillTheme.Metrics.EXPLORER_ROW_HEIGHT;
	private static final int TOOLBAR_HEIGHT = 18;

	public enum InlineEditKind {
		NEW_FOLDER,
		NEW_FILE,
		RENAME
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

	private record ToolbarAction(String label, String tooltip, Runnable action, IdeLayout.Rect rect) {
	}

	private final ClientEditorSession session;
	private final Consumer<ModalPromptOverlay> modalOpener;
	private final Runnable modalCloser;

	private final List<VisibleRow> visibleRows = new ArrayList<>();
	private final List<ToolbarAction> toolbarActions = new ArrayList<>();
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
		String baseDir = resolveTargetDirectory();
		EntryKind kind = baseDir.isEmpty() ? EntryKind.DATAPACK : EntryKind.DIRECTORY;
		if (!baseDir.isEmpty()) {
			session.expandAncestors(baseDir + "/dummy");
			session.expandedPaths().add(baseDir);
		}
		inlineEdit = new InlineEditState(
				InlineEditKind.NEW_FOLDER,
				baseDir,
				null,
				null,
				false,
				kind,
				""
		);
		pendingScrollToInline = true;
	}

	public void beginInlineCreateFile() {
		String baseDir = resolveTargetDirectory();
		if (baseDir.isEmpty()) {
			session.setStatus("Select a datapack or folder first.", true);
			return;
		}
		session.expandAncestors(baseDir + "/dummy");
		session.expandedPaths().add(baseDir);
		inlineEdit = new InlineEditState(
				InlineEditKind.NEW_FILE,
				baseDir,
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

		// Header
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, "EXPLORER", area.width() - 12),
				area.x() + 6, area.y() + 5, QuillTheme.TEXT_MUTED);

		// Compact CRUD Toolbar
		int toolbarY = area.y() + 17;
		QuillDraw.fill(graphics, area.x(), toolbarY, area.width() - 1, TOOLBAR_HEIGHT, QuillTheme.BG_TAB_INACTIVE);
		QuillDraw.hLine(graphics, area.x(), area.width() - 1, toolbarY + TOOLBAR_HEIGHT - 1, QuillTheme.BORDER_SUBTLE);

		buildToolbarActions(font, area.x() + 4, toolbarY + 2, area.width() - 8);
		for (ToolbarAction btn : toolbarActions) {
			boolean hover = btn.rect().contains(mouseX, mouseY);
			QuillDraw.fill(graphics, btn.rect().x(), btn.rect().y(), btn.rect().width(), btn.rect().height(),
					hover ? QuillTheme.BG_BUTTON : QuillTheme.BG_BUTTON_SECONDARY);
			int tx = btn.rect().x() + (btn.rect().width() - QuillDraw.textWidth(font, btn.label())) / 2;
			int ty = btn.rect().y() + (btn.rect().height() - font.lineHeight) / 2;
			QuillDraw.text(graphics, font, btn.label(), tx, ty, QuillTheme.TEXT_BRIGHT);
			if (hover) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}
		}

		// Tree list viewport
		int listY = toolbarY + TOOLBAR_HEIGHT;
		listRect = new IdeLayout.Rect(area.x(), listY, area.width() - 1, Math.max(0, area.bottom() - listY));

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
			QuillDraw.text(graphics, font, "Click +Dir or open Presets.", listRect.x() + 8, listRect.y() + 22, QuillTheme.TEXT_MUTED);
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
					if (hovered) {
						QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
					}

					int textY = ry + (ROW_HEIGHT - font.lineHeight) / 2;
					if (node.kind() == EntryKind.FILE) {
						int iconW = QuillDraw.drawFileTypeIcon(graphics, font, node.name(), indentX + 2, ry, ROW_HEIGHT);
						int nameX = indentX + 2 + iconW + 4;
						int maxW = Math.max(0, listRect.right() - nameX - 6);
						var openTab = session.findOpenTab(node.relativePath());
						boolean dirty = openTab != null && openTab.isDirty();
						String displayName = formatFileDisplayName(node.name(), dirty);
						QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, displayName, maxW),
								nameX, textY, colorForEntryLabel(selected));
					} else {
						boolean expanded = session.isExpanded(node.relativePath());
						QuillDraw.drawChevron(graphics, indentX - 1, ry, ROW_HEIGHT, expanded, QuillTheme.TEXT_MUTED);
						int folderColor = node.kind() == EntryKind.DATAPACK ? QuillTheme.ACCENT_BLUE : QuillTheme.TEXT_FOLDER;
						int folderW = QuillDraw.drawFolderIcon(graphics, indentX + 10, ry, ROW_HEIGHT, expanded, folderColor);
						int nameX = indentX + 10 + folderW + 4;
						int maxW = Math.max(0, listRect.right() - nameX - 6);
						QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, node.name(), maxW),
								nameX, textY, colorForEntryLabel(selected));
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
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		if (inlineEdit != null) {
			if (inlineEdit.boxRect.contains(mx, my)) {
				inlineEdit.input.mouseClicked(mx, safeFont(), event.hasShiftDown());
				return true;
			}
			cancelInlineInput();
		}

		for (ToolbarAction action : toolbarActions) {
			if (action.rect().contains(mx, my)) {
				action.action().run();
				return true;
			}
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
			if (event.key() == InputConstants.KEY_F2
					&& !event.hasControlDownWithQuirk()
					&& !event.hasShiftDown()
					&& !event.hasAltDown()
					&& !session.selectedTreePath().isEmpty()) {
				beginInlineRename();
				return true;
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

	private void buildToolbarActions(Font font, int startX, int y, int maxWidth) {
		toolbarActions.clear();
		String[] labels = {"+File", "+Dir", "Ren", "Del", "Ref"};
		Runnable[] runners = {
				this::beginInlineCreateFile,
				this::beginInlineCreateFolder,
				this::beginInlineRename,
				this::promptDeleteSelected,
				session::refreshTree
		};
		int x = startX;
		for (int i = 0; i < labels.length; i++) {
			int w = QuillDraw.textWidth(font, labels[i]) + 8;
			if (x + w > startX + maxWidth) {
				break;
			}
			toolbarActions.add(new ToolbarAction(labels[i], labels[i], runners[i], new IdeLayout.Rect(x, y, w, TOOLBAR_HEIGHT - 4)));
			x += w + 3;
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
				int slash = selected.lastIndexOf('/');
				return slash >= 0 ? selected.substring(0, slash) : "";
			}
		}
		if (!session.rootTree().children().isEmpty()) {
			return session.rootTree().children().getFirst().relativePath();
		}
		return "";
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
