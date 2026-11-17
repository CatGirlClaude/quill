package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interactive datapack tree widget for browsing, creating, renaming, and deleting files and folders.
 */
public final class FileExplorerTreeWidget {
	private static final int ROW_HEIGHT = 14;
	private static final int TOOLBAR_HEIGHT = 18;

	private record VisibleRow(FileTreeNode node, int depth) {
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

	public FileExplorerTreeWidget(
			ClientEditorSession session,
			Consumer<ModalPromptOverlay> modalOpener,
			Runnable modalCloser
	) {
		this.session = session;
		this.modalOpener = modalOpener;
		this.modalCloser = modalCloser;
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
			int tx = btn.rect().x() + (btn.rect().width() - font.width(btn.label())) / 2;
			int ty = btn.rect().y() + (btn.rect().height() - font.lineHeight) / 2;
			QuillDraw.text(graphics, font, btn.label(), tx, ty, QuillTheme.TEXT_BRIGHT);
			if (hover) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}
		}

		// Tree list viewport
		int listY = toolbarY + TOOLBAR_HEIGHT;
		listRect = new IdeLayout.Rect(area.x(), listY, area.width() - 1, Math.max(0, area.bottom() - listY));

		int totalHeight = visibleRows.size() * ROW_HEIGHT;
		int maxScroll = Math.max(0, totalHeight - listRect.height());
		scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels));

		QuillDraw.beginClip(graphics, listRect);
		if (visibleRows.isEmpty()) {
			QuillDraw.text(graphics, font, "No datapacks yet.", listRect.x() + 8, listRect.y() + 8, QuillTheme.TEXT_MUTED);
			QuillDraw.text(graphics, font, "Use Presets [P] to create one.", listRect.x() + 8, listRect.y() + 22, QuillTheme.TEXT_MUTED);
		} else {
			int firstRow = scrollPixels / ROW_HEIGHT;
			int lastRow = Math.min(visibleRows.size() - 1, (scrollPixels + listRect.height()) / ROW_HEIGHT);
			for (int i = firstRow; i <= lastRow; i++) {
				VisibleRow row = visibleRows.get(i);
				int ry = listRect.y() + i * ROW_HEIGHT - scrollPixels;
				boolean selected = row.node().relativePath().equals(session.selectedTreePath());
				boolean hovered = listRect.contains(mouseX, mouseY) && mouseY >= ry && mouseY < ry + ROW_HEIGHT;

				if (selected) {
					QuillDraw.fill(graphics, listRect.x(), ry, listRect.width(), ROW_HEIGHT, QuillTheme.ROW_SELECTED);
				} else if (hovered) {
					QuillDraw.fill(graphics, listRect.x(), ry, listRect.width(), ROW_HEIGHT, QuillTheme.BG_HOVER);
				}
				if (hovered) {
					QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
				}

				int indentX = listRect.x() + 6 + row.depth() * 10;
				for (int g = 0; g < row.depth(); g++) {
					int gx = listRect.x() + 8 + g * 10;
					QuillDraw.vLine(graphics, gx, ry, ROW_HEIGHT, QuillTheme.BORDER_SUBTLE);
				}

				int textY = ry + (ROW_HEIGHT - font.lineHeight) / 2;
				String prefix;
				int textColor;
				if (row.node().kind() == EntryKind.FILE) {
					prefix = "  ";
					textColor = colorForFile(row.node().name(), selected);
				} else {
					boolean expanded = session.isExpanded(row.node().relativePath());
					prefix = expanded ? "v " : "> ";
					textColor = row.node().kind() == EntryKind.DATAPACK ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_FOLDER;
				}

				String label = prefix + row.node().name();
				int maxW = Math.max(0, listRect.right() - indentX - 8);
				QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, label, maxW), indentX, textY, textColor);
			}
		}
		QuillDraw.endClip(graphics);

		// Vertical scrollbar
		if (maxScroll > 0 && listRect.height() > 8) {
			int trackX = listRect.right() - 4;
			int thumbH = Math.max(12, (int) ((long) listRect.height() * listRect.height() / totalHeight));
			int thumbY = listRect.y() + (int) ((long) scrollPixels * (listRect.height() - thumbH) / maxScroll);
			QuillDraw.fill(graphics, trackX, thumbY, 3, thumbH, QuillTheme.TEXT_MUTED);
		}
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

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
				FileTreeNode node = row.node();
				session.setSelectedTreePath(node.relativePath());
				if (node.kind() == EntryKind.FILE) {
					session.openFile(node.relativePath());
				} else {
					session.toggleExpanded(node.relativePath());
				}
				return true;
			}
		}
		return false;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!listRect.contains(mouseX, mouseY)) {
			return false;
		}
		int maxScroll = Math.max(0, visibleRows.size() * ROW_HEIGHT - listRect.height());
		scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels - (int) (scrollY * ROW_HEIGHT * 2)));
		return true;
	}

	private void buildToolbarActions(Font font, int startX, int y, int maxWidth) {
		toolbarActions.clear();
		String[] labels = {"+File", "+Dir", "Ren", "Del", "Ref"};
		Runnable[] runners = {
				this::promptCreateFile,
				this::promptCreateDir,
				this::promptRenameSelected,
				this::promptDeleteSelected,
				session::refreshTree
		};
		int x = startX;
		for (int i = 0; i < labels.length; i++) {
			int w = font.width(labels[i]) + 8;
			if (x + w > startX + maxWidth) {
				break;
			}
			toolbarActions.add(new ToolbarAction(labels[i], labels[i], runners[i], new IdeLayout.Rect(x, y, w, TOOLBAR_HEIGHT - 4)));
			x += w + 3;
		}
	}

	private void rebuildVisibleRows() {
		visibleRows.clear();
		for (FileTreeNode child : session.rootTree().children()) {
			appendVisible(child, 0);
		}
	}

	private void appendVisible(FileTreeNode node, int depth) {
		visibleRows.add(new VisibleRow(node, depth));
		if (node.kind() != EntryKind.FILE && session.isExpanded(node.relativePath())) {
			for (FileTreeNode child : node.children()) {
				appendVisible(child, depth + 1);
			}
		}
	}

	private void promptCreateFile() {
		String baseDir = resolveTargetDirectory();
		if (baseDir.isEmpty()) {
			session.setStatus("Select a datapack or folder first.", true);
			return;
		}
		modalOpener.accept(new ModalPromptOverlay(
				"New File",
				"Parent: datapacks/" + baseDir,
				"Create",
				false,
				List.of(new ModalPromptOverlay.FieldSpec("name", "File Name (.mcfunction, .json, .mcmeta)", "new_fn.mcfunction", "hello.mcfunction", val -> {
					if (val.isEmpty()) {
						return "File name is required.";
					}
					if (!PathSandbox.hasAllowedExtension(val)) {
						return "Allowed extensions: .mcfunction, .json, .mcmeta, .txt, .md";
					}
					try {
						PathSandbox.validateSegment(val);
					} catch (EditorSecurityException e) {
						return e.getMessage();
					}
					return null;
				})),
				values -> {
					modalCloser.run();
					session.createEntry(baseDir + "/" + values.get("name"), EntryKind.FILE);
				},
				modalCloser
		));
	}

	private void promptCreateDir() {
		String baseDir = resolveTargetDirectory();
		String message = baseDir.isEmpty() ? "Create new datapack folder in datapacks/" : "Parent: datapacks/" + baseDir;
		EntryKind kind = baseDir.isEmpty() ? EntryKind.DATAPACK : EntryKind.DIRECTORY;
		modalOpener.accept(new ModalPromptOverlay(
				baseDir.isEmpty() ? "New Datapack Folder" : "New Folder",
				message,
				"Create",
				false,
				List.of(new ModalPromptOverlay.FieldSpec("name", "Folder Name", "", "my_folder", val -> {
					if (val.isEmpty()) {
						return "Folder name is required.";
					}
					try {
						PathSandbox.validatePackOrFolderName(val);
					} catch (EditorSecurityException e) {
						return e.getMessage();
					}
					return null;
				})),
				values -> {
					modalCloser.run();
					String fullPath = baseDir.isEmpty() ? values.get("name") : baseDir + "/" + values.get("name");
					session.createEntry(fullPath, kind);
				},
				modalCloser
		));
	}

	private void promptRenameSelected() {
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

		modalOpener.accept(new ModalPromptOverlay(
				"Rename Entry",
				"Current: " + selected,
				"Rename",
				false,
				List.of(new ModalPromptOverlay.FieldSpec("name", "New Name", currentName, currentName, val -> {
					if (val.isEmpty()) {
						return "New name is required.";
					}
					try {
						PathSandbox.validatePackOrFolderName(val);
					} catch (EditorSecurityException e) {
						return e.getMessage();
					}
					if (isFile && !PathSandbox.hasAllowedExtension(val)) {
						return "Allowed file extensions: .mcfunction, .json, .mcmeta, .txt, .md";
					}
					return null;
				})),
				values -> {
					modalCloser.run();
					String target = parent.isEmpty() ? values.get("name") : parent + "/" + values.get("name");
					session.renameEntry(selected, target);
				},
				modalCloser
		));
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

	private static int colorForFile(String name, boolean selected) {
		if (selected) {
			return QuillTheme.TEXT_BRIGHT;
		}
		if (name.endsWith(".mcfunction")) {
			return QuillTheme.TEXT_FUNCTION;
		}
		if (name.endsWith(".json") || name.endsWith(".mcmeta")) {
			return QuillTheme.TEXT_JSON;
		}
		return QuillTheme.TEXT_PRIMARY;
	}
}
