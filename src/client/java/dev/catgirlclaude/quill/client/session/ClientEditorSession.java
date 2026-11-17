package dev.catgirlclaude.quill.client.session;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Persistent client-side IDE state shared across open/close cycles of {@code QuillIdeScreen}
 * within the same world session.
 */
public final class ClientEditorSession {
	public enum SidebarView {
		EXPLORER,
		PRESETS
	}

	private final EditorBackend backend;
	private final List<OpenTabState> openTabs = new ArrayList<>();
	private final Set<String> expandedPaths = new HashSet<>();

	private FileTreeNode rootTree = new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of());
	private List<PresetDescriptor> presets = List.of();
	private int activeTabIndex = -1;
	private String selectedTreePath = "";
	private ReloadReport lastReloadReport = null;
	private String statusMessage = "Ready";
	private boolean statusIsError = false;
	private boolean busy = false;
	private boolean sidebarCollapsed = false;
	private boolean maximized = false;
	private boolean diagnosticsExpanded = true;
	private SidebarView sidebarView = SidebarView.EXPLORER;

	public ClientEditorSession(EditorBackend backend) {
		this.backend = Objects.requireNonNull(backend, "backend");
		this.expandedPaths.add("");
	}

	public EditorBackend backend() {
		return backend;
	}

	public FileTreeNode rootTree() {
		return rootTree;
	}

	public List<PresetDescriptor> presets() {
		return presets;
	}

	public List<OpenTabState> openTabs() {
		return openTabs;
	}

	public int activeTabIndex() {
		return activeTabIndex;
	}

	public OpenTabState activeTab() {
		if (activeTabIndex < 0 || activeTabIndex >= openTabs.size()) {
			return null;
		}
		return openTabs.get(activeTabIndex);
	}

	public String selectedTreePath() {
		return selectedTreePath;
	}

	public void setSelectedTreePath(String selectedTreePath) {
		this.selectedTreePath = selectedTreePath == null ? "" : selectedTreePath;
	}

	public Set<String> expandedPaths() {
		return expandedPaths;
	}

	public boolean isExpanded(String relativePath) {
		return expandedPaths.contains(relativePath);
	}

	public void toggleExpanded(String relativePath) {
		if (!expandedPaths.add(relativePath)) {
			expandedPaths.remove(relativePath);
		}
	}

	public void expandAncestors(String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			return;
		}
		String[] parts = relativePath.split("/");
		StringBuilder path = new StringBuilder();
		for (int i = 0; i < parts.length - 1; i++) {
			if (i > 0) {
				path.append('/');
			}
			path.append(parts[i]);
			expandedPaths.add(path.toString());
		}
	}

	public ReloadReport lastReloadReport() {
		return lastReloadReport;
	}

	public String statusMessage() {
		return statusMessage;
	}

	public boolean statusIsError() {
		return statusIsError;
	}

	public boolean isBusy() {
		return busy;
	}

	public boolean isSidebarCollapsed() {
		return sidebarCollapsed;
	}

	public void setSidebarCollapsed(boolean sidebarCollapsed) {
		this.sidebarCollapsed = sidebarCollapsed;
	}

	public void toggleSidebarCollapsed() {
		this.sidebarCollapsed = !this.sidebarCollapsed;
	}

	public boolean isMaximized() {
		return maximized;
	}

	public void toggleMaximized() {
		this.maximized = !this.maximized;
	}

	public boolean isDiagnosticsExpanded() {
		return diagnosticsExpanded;
	}

	public void setDiagnosticsExpanded(boolean diagnosticsExpanded) {
		this.diagnosticsExpanded = diagnosticsExpanded;
	}

	public SidebarView sidebarView() {
		return sidebarView;
	}

	public void setSidebarView(SidebarView sidebarView) {
		this.sidebarView = sidebarView;
		this.sidebarCollapsed = false;
	}

	public void setActiveTab(int index) {
		if (index >= 0 && index < openTabs.size()) {
			this.activeTabIndex = index;
		}
	}

	public void closeTab(int index) {
		if (index < 0 || index >= openTabs.size()) {
			return;
		}
		openTabs.remove(index);
		if (openTabs.isEmpty()) {
			activeTabIndex = -1;
		} else if (activeTabIndex >= openTabs.size()) {
			activeTabIndex = openTabs.size() - 1;
		} else if (index < activeTabIndex) {
			activeTabIndex--;
		}
	}

	public boolean hasAnyDirtyTab() {
		for (OpenTabState tab : openTabs) {
			if (tab.isDirty()) {
				return true;
			}
		}
		return false;
	}

	public void refreshAll() {
		refreshTree();
		loadPresets();
	}

	public void refreshTree() {
		backend.listTree().whenComplete((tree, error) -> runOnClient(() -> {
			if (error != null) {
				setError("Failed to scan datapacks: " + unwrapMessage(error));
				return;
			}
			this.rootTree = tree;
			if (tree.children().size() == 1) {
				this.expandedPaths.add(tree.children().getFirst().relativePath());
			}
		}));
	}

	public void loadPresets() {
		backend.listPresets().whenComplete((loaded, error) -> runOnClient(() -> {
			if (error != null) {
				setError("Failed to load presets: " + unwrapMessage(error));
				return;
			}
			this.presets = loaded;
		}));
	}

	public void openFile(String relativePath) {
		openFileAndJump(relativePath, 0);
	}

	public void openFileAndJump(String relativePath, int oneBasedLine) {
		for (int i = 0; i < openTabs.size(); i++) {
			if (openTabs.get(i).relativePath().equals(relativePath)) {
				activeTabIndex = i;
				if (oneBasedLine > 0) {
					openTabs.get(i).buffer().jumpToLine(oneBasedLine);
				}
				return;
			}
		}

		setStatus("Opening " + relativePath + "...", false);
		backend.readFile(relativePath).whenComplete((doc, error) -> runOnClient(() -> {
			if (error != null) {
				setError("Open failed: " + unwrapMessage(error));
				return;
			}
			OpenTabState tab = openOrFocusDocument(doc);
			if (oneBasedLine > 0) {
				tab.buffer().jumpToLine(oneBasedLine);
			}
			setStatus("Opened " + tab.fileName(), false);
		}));
	}

	public OpenTabState openOrFocusDocument(FileDocument document) {
		for (int i = 0; i < openTabs.size(); i++) {
			OpenTabState existing = openTabs.get(i);
			if (existing.relativePath().equals(document.relativePath())) {
				activeTabIndex = i;
				return existing;
			}
		}
		OpenTabState tab = new OpenTabState(document);
		openTabs.add(tab);
		activeTabIndex = openTabs.size() - 1;
		expandAncestors(document.relativePath());
		selectedTreePath = document.relativePath();
		return tab;
	}

	public void saveActiveTab(boolean reloadAfterSave) {
		OpenTabState tab = activeTab();
		if (tab == null) {
			if (reloadAfterSave) {
				reloadDatapacks();
			}
			return;
		}
		busy = true;
		setStatus(reloadAfterSave ? "Saving & reloading..." : "Saving...", false);
		String content = tab.buffer().getText();

		backend.writeFile(tab.relativePath(), content, reloadAfterSave).whenComplete((result, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Save failed: " + unwrapMessage(error));
				return;
			}
			tab.applySavedDocument(result.document());
			if (result.reloadReport() != null) {
				applyReloadReport(result.reloadReport(), true);
			} else {
				setStatus("Saved " + tab.fileName(), false);
			}
		}));
	}

	public void reloadDatapacks() {
		busy = true;
		setStatus("Reloading datapacks...", false);
		backend.reloadDatapacks().whenComplete((report, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Reload failed: " + unwrapMessage(error));
				return;
			}
			applyReloadReport(report, false);
		}));
	}

	public CompletableFuture<Void> createDatapack(String packName, String description) {
		busy = true;
		setStatus("Creating datapack " + packName + "...", false);
		return backend.createDatapack(packName, description).whenComplete((createdPack, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Create datapack failed: " + unwrapMessage(error));
				return;
			}
			expandedPaths.add(createdPack);
			selectedTreePath = createdPack;
			refreshTree();
			openFile(createdPack + "/pack.mcmeta");
			setStatus("Created datapack " + createdPack, false);
		})).thenApply(ignored -> null);
	}

	public CompletableFuture<Void> createNamespace(String packName, String namespace) {
		busy = true;
		setStatus("Creating namespace " + namespace + "...", false);
		return backend.createNamespace(packName, namespace).whenComplete((createdDir, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Create namespace failed: " + unwrapMessage(error));
				return;
			}
			expandAncestors(createdDir + "/dummy");
			expandedPaths.add(createdDir);
			selectedTreePath = createdDir;
			refreshTree();
			setStatus("Created namespace " + namespace, false);
		})).thenApply(ignored -> null);
	}

	public CompletableFuture<Void> createFunction(String packName, String namespace, String functionPath) {
		busy = true;
		setStatus("Creating function " + namespace + ":" + functionPath + "...", false);
		return backend.createFunction(packName, namespace, functionPath).whenComplete((createdFile, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Create function failed: " + unwrapMessage(error));
				return;
			}
			expandAncestors(createdFile);
			refreshTree();
			openFile(createdFile);
			setStatus("Created " + createdFile, false);
		})).thenApply(ignored -> null);
	}

	public CompletableFuture<Void> applyPreset(String packName, String presetId, Map<String, String> variables) {
		busy = true;
		setStatus("Applying preset " + presetId + "...", false);
		return backend.applyPreset(packName, presetId, variables).whenComplete((doc, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Preset failed: " + unwrapMessage(error));
				return;
			}
			expandAncestors(doc.relativePath());
			refreshTree();
			openOrFocusDocument(doc);
			setStatus("Applied preset " + presetId, false);
		})).thenApply(ignored -> null);
	}

	public CompletableFuture<Void> createEntry(String relativePath, EntryKind kind) {
		busy = true;
		return backend.createEntry(relativePath, kind).whenComplete((ignored, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Create failed: " + unwrapMessage(error));
				return;
			}
			expandAncestors(relativePath);
			if (kind != EntryKind.FILE) {
				expandedPaths.add(relativePath);
			}
			selectedTreePath = relativePath;
			refreshTree();
			if (kind == EntryKind.FILE) {
				openFile(relativePath);
			}
			setStatus("Created " + relativePath, false);
		}));
	}

	public CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath) {
		busy = true;
		return backend.renameEntry(sourceRelativePath, targetRelativePath).whenComplete((ignored, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Rename failed: " + unwrapMessage(error));
				return;
			}
			closeTabsUnderPrefix(sourceRelativePath);
			selectedTreePath = targetRelativePath;
			expandAncestors(targetRelativePath);
			refreshTree();
			setStatus("Renamed to " + targetRelativePath, false);
		}));
	}

	public CompletableFuture<Void> deleteEntry(String relativePath) {
		busy = true;
		return backend.deleteEntry(relativePath).whenComplete((ignored, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Delete failed: " + unwrapMessage(error));
				return;
			}
			closeTabsUnderPrefix(relativePath);
			if (selectedTreePath.equals(relativePath) || selectedTreePath.startsWith(relativePath + "/")) {
				selectedTreePath = "";
			}
			refreshTree();
			setStatus("Deleted " + relativePath, false);
		}));
	}

	private void closeTabsUnderPrefix(String prefix) {
		for (int i = openTabs.size() - 1; i >= 0; i--) {
			String path = openTabs.get(i).relativePath();
			if (path.equals(prefix) || path.startsWith(prefix + "/")) {
				closeTab(i);
			}
		}
	}

	private void applyReloadReport(ReloadReport report, boolean afterSave) {
		this.lastReloadReport = report;
		if (report.hasErrors()) {
			this.diagnosticsExpanded = true;
			int count = report.diagnostics().size();
			setError("Reload errors (" + count + ") in " + report.durationMs() + "ms");
		} else {
			String prefix = afterSave ? "Saved & Reloaded" : "Reloaded";
			setStatus(prefix + " (" + report.durationMs() + "ms, " + report.enabledPackCount() + " packs)", false);
		}
	}

	public void setStatus(String message, boolean error) {
		this.statusMessage = message;
		this.statusIsError = error;
	}

	private void setError(String message) {
		setStatus(message, true);
	}

	private static void runOnClient(Runnable task) {
		Minecraft.getInstance().execute(task);
	}

	private static String unwrapMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current instanceof CompletionException && current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
	}
}
