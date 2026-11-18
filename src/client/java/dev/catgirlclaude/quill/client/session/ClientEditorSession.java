package dev.catgirlclaude.quill.client.session;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadDiagnostic;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.RevisionConflictException;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Persistent client-side IDE state shared across open/close cycles of {@code QuillIdeScreen}
 * within the same world session.
 */
public final class ClientEditorSession {
	private static final int MAX_OUTPUT_LOG_ENTRIES = 400;
	private static final long GAME_MESSAGE_CAPTURE_WINDOW_MS = 3_500L;
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

	public enum SidebarView {
		EXPLORER,
		SEARCH,
		INSPECTOR,
		PRESETS
	}

	public enum BottomPanelTab {
		PROBLEMS,
		OUTPUT
	}

	public enum LogLevel {
		INFO,
		SUCCESS,
		WARN,
		ERROR
	}

	public record OutputLogEntry(String timestamp, LogLevel level, String message) {
	}

	public record PendingConflict(
			String relativePath,
			String expectedSha256,
			String actualSha256,
			boolean reloadAfterSave
	) {
	}

	private final EditorBackend backend;
	private final Executor uiExecutor;
	private final String backendLabel;
	private final List<OpenTabState> openTabs = new ArrayList<>();
	private final Set<String> expandedPaths = new HashSet<>();
	private final List<OutputLogEntry> outputLog = new ArrayList<>();
	private final Set<String> autosaveInFlight = new HashSet<>();
	private final Set<String> autosaveQueued = new HashSet<>();
	private Consumer<String> commandDispatcher = ClientEditorSession::sendDefaultMinecraftCommand;

	private FileTreeNode rootTree = new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of());
	private List<PresetDescriptor> presets = List.of();
	private int activeTabIndex = -1;
	private String selectedTreePath = "";
	private ReloadReport lastReloadReport = null;
	private PendingConflict pendingConflict = null;
	private String statusMessage = "Ready";
	private boolean statusIsError = false;
	private boolean busy = false;
	private boolean sidebarCollapsed = false;
	private boolean maximized = false;
	private boolean diagnosticsExpanded = false;
	private BottomPanelTab bottomPanelTab = BottomPanelTab.PROBLEMS;
	private SidebarView sidebarView = SidebarView.EXPLORER;
	private long lastCommandDispatchMs = 0L;
	private long workspaceRevision = 0L;
	private boolean reloadPendingOnClose = false;

	public ClientEditorSession(EditorBackend backend) {
		this(backend, task -> Minecraft.getInstance().execute(task), "Local");
	}

	public ClientEditorSession(EditorBackend backend, String backendLabel) {
		this(backend, task -> Minecraft.getInstance().execute(task), backendLabel);
	}

	public ClientEditorSession(EditorBackend backend, Executor uiExecutor) {
		this(backend, uiExecutor, "Local");
	}

	public ClientEditorSession(EditorBackend backend, Executor uiExecutor, String backendLabel) {
		this.backend = Objects.requireNonNull(backend, "backend");
		this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
		this.backendLabel = (backendLabel == null || backendLabel.isBlank()) ? "Local" : backendLabel;
		this.expandedPaths.add("");
		appendOutput(LogLevel.INFO, "Quill session started (" + this.backendLabel + ")");
	}

	public EditorBackend backend() {
		return backend;
	}

	public String backendLabel() {
		return backendLabel;
	}

	public void setCommandDispatcher(Consumer<String> commandDispatcher) {
		this.commandDispatcher = Objects.requireNonNull(commandDispatcher, "commandDispatcher");
	}

	public long workspaceRevision() {
		return workspaceRevision;
	}

	public PendingConflict consumePendingConflict() {
		PendingConflict current = this.pendingConflict;
		this.pendingConflict = null;
		return current;
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

	public boolean isBottomPanelOpen() {
		return diagnosticsExpanded;
	}

	public BottomPanelTab bottomPanelTab() {
		return bottomPanelTab;
	}

	public void setBottomPanelTab(BottomPanelTab tab) {
		this.bottomPanelTab = Objects.requireNonNull(tab, "tab");
	}

	public void openBottomPanel(BottomPanelTab tab) {
		this.bottomPanelTab = Objects.requireNonNull(tab, "tab");
		this.diagnosticsExpanded = true;
	}

	public void toggleBottomPanel() {
		this.diagnosticsExpanded = !this.diagnosticsExpanded;
	}

	public List<OutputLogEntry> outputLog() {
		return Collections.unmodifiableList(outputLog);
	}

	public void appendOutput(LogLevel level, String message) {
		if (message == null || message.isBlank()) {
			return;
		}
		String ts = LocalTime.now().format(TIME_FMT);
		String[] lines = message.replace("\r\n", "\n").replace('\r', '\n').split("\n");
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			outputLog.add(new OutputLogEntry(ts, level, line));
		}
		while (outputLog.size() > MAX_OUTPUT_LOG_ENTRIES) {
			outputLog.removeFirst();
		}
	}

	public void clearOutputLog() {
		outputLog.clear();
		appendOutput(LogLevel.INFO, "Output log cleared.");
	}

	/**
	 * Captures game/command feedback messages arriving shortly after Quill dispatches a command or reload.
	 */
	public void onGameMessageReceived(String message) {
		if (message == null || message.isBlank()) {
			return;
		}
		long elapsed = System.currentTimeMillis() - lastCommandDispatchMs;
		if (elapsed < 0 || elapsed > GAME_MESSAGE_CAPTURE_WINDOW_MS) {
			return;
		}
		runOnClient(() -> {
			String lower = message.toLowerCase(Locale.ROOT);
			LogLevel level = (lower.contains("unknown function") || lower.contains("failed") || lower.contains("error") || lower.contains("incorrect argument"))
					? LogLevel.ERROR
					: LogLevel.INFO;
			appendOutput(level, "[MC] " + message);
		});
	}

	public String activeFunctionId() {
		OpenTabState tab = activeTab();
		if (tab == null) {
			return null;
		}
		return DatapackScaffolder.inferFunctionId(tab.relativePath());
	}

	/**
	 * Runs the active {@code .mcfunction} tab via {@code /function <namespace>:<path>}.
	 * If the tab has unsaved edits, saves and reloads first before running.
	 */
	public void runCurrentFunction() {
		OpenTabState tab = activeTab();
		if (tab == null) {
			setError("No active file to run.");
			return;
		}
		String fnId = DatapackScaffolder.inferFunctionId(tab.relativePath());
		if (fnId == null) {
			setError("Active file is not under data/<namespace>/function/*.mcfunction.");
			return;
		}
		openBottomPanel(BottomPanelTab.OUTPUT);
		if (tab.isDirty()) {
			appendOutput(LogLevel.INFO, "Saving & reloading before running /function " + fnId + "...");
			saveTabInternal(tab, tab.sha256(), true, () -> dispatchFunctionCommand(fnId));
		} else {
			dispatchFunctionCommand(fnId);
		}
	}

	private void dispatchFunctionCommand(String fnId) {
		String command = "function " + fnId;
		this.lastCommandDispatchMs = System.currentTimeMillis();
		appendOutput(LogLevel.INFO, "> /" + command);
		try {
			commandDispatcher.accept(command);
			setStatus("Ran /" + command, false);
		} catch (Exception e) {
			setError("Run failed: " + unwrapMessage(e));
		}
	}

	public List<String> listAllFilePaths() {
		List<String> files = new ArrayList<>();
		collectFilePaths(rootTree, files);
		return files;
	}

	private static void collectFilePaths(FileTreeNode node, List<String> out) {
		if (node == null) {
			return;
		}
		if (node.kind() == EntryKind.FILE && !node.relativePath().isEmpty()) {
			out.add(node.relativePath());
		}
		for (FileTreeNode child : node.children()) {
			collectFilePaths(child, out);
		}
	}

	public void bumpWorkspaceRevision() {
		this.workspaceRevision++;
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
			bumpWorkspaceRevision();
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
		openFileAndSelect(relativePath, oneBasedLine, -1, -1);
	}

	public void openFileAndSelect(String relativePath, int oneBasedLine, int startCol, int endCol) {
		for (int i = 0; i < openTabs.size(); i++) {
			if (openTabs.get(i).relativePath().equals(relativePath)) {
				activeTabIndex = i;
				applyJumpSelection(openTabs.get(i), oneBasedLine, startCol, endCol);
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
			applyJumpSelection(tab, oneBasedLine, startCol, endCol);
			setStatus("Opened " + tab.fileName(), false);
		}));
	}

	private static void applyJumpSelection(OpenTabState tab, int oneBasedLine, int startCol, int endCol) {
		if (oneBasedLine <= 0) {
			return;
		}
		if (startCol >= 0 && endCol > startCol) {
			int lineIdx = Math.max(0, Math.min(tab.buffer().lineCount() - 1, oneBasedLine - 1));
			tab.buffer().setCursor(lineIdx, startCol, false);
			tab.buffer().setCursor(lineIdx, endCol, true);
		} else {
			tab.buffer().jumpToLine(oneBasedLine);
		}
		tab.requestScrollToCaret();
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
		saveTabInternal(tab, tab.sha256(), reloadAfterSave, null);
	}

	/**
	 * Saves the active tab honoring {@link QuillClientConfig#reloadTriggerMode()}.
	 */
	public void saveActiveTabAccordingToConfig() {
		saveActiveTab(QuillClientConfig.current().shouldReloadOnSave());
	}

	public boolean isReloadPendingOnClose() {
		return reloadPendingOnClose;
	}

	/**
	 * Called after any user edit to an open tab buffer. Triggers coalesced instant autosave when
	 * {@link QuillClientConfig.AutosaveMode#ON_KEYPRESS} is active.
	 */
	public void notifyTabBufferEdited(OpenTabState tab) {
		if (tab == null) {
			return;
		}
		if (QuillClientConfig.current().autosaveMode() != QuillClientConfig.AutosaveMode.ON_KEYPRESS) {
			return;
		}
		triggerCoalescedAutosave(tab, QuillClientConfig.current().shouldReloadOnSave());
	}

	private void triggerCoalescedAutosave(OpenTabState tab, boolean reloadAfterSave) {
		String path = tab.relativePath();
		if (autosaveInFlight.contains(path)) {
			autosaveQueued.add(path);
			return;
		}
		if (!tab.isDirty()) {
			return;
		}
		autosaveInFlight.add(path);
		String content = tab.buffer().getText();
		backend.writeFile(path, content, tab.sha256(), reloadAfterSave).whenComplete((result, error) -> runOnClient(() -> {
			autosaveInFlight.remove(path);
			if (error != null) {
				autosaveQueued.remove(path);
				Throwable unwrapped = unwrapThrowable(error);
				if (unwrapped instanceof RevisionConflictException rce) {
					this.pendingConflict = new PendingConflict(
							rce.relativePath(),
							rce.expectedSha256(),
							rce.actualSha256(),
							reloadAfterSave
					);
					setError(rce.getMessage());
				} else {
					setError("Autosave failed: " + unwrapMessage(error));
				}
				return;
			}
			tab.applySavedDocument(result.document());
			bumpWorkspaceRevision();
			if (result.reloadReport() != null) {
				applyReloadReport(result.reloadReport(), true);
			} else {
				reloadPendingOnClose = true;
				setStatus("Autosaved " + tab.fileName(), false);
			}
			boolean hadQueued = autosaveQueued.remove(path);
			if ((hadQueued || tab.isDirty())
					&& QuillClientConfig.current().autosaveMode() == QuillClientConfig.AutosaveMode.ON_KEYPRESS) {
				triggerCoalescedAutosave(tab, QuillClientConfig.current().shouldReloadOnSave());
			} else if (autosaveInFlight.isEmpty() && pendingReloadAfterAutosave) {
				pendingReloadAfterAutosave = false;
				reloadDatapacks();
			}
		}));
	}

	private boolean pendingReloadAfterAutosave = false;

	/**
	 * Saves all currently dirty tabs without per-file reloads, invoking {@code onAllComplete}
	 * once every write has completed (and at least one succeeded or none were skipped).
	 */
	public void saveAllDirtyTabs(boolean reloadAfterLastSave, Runnable onAllComplete) {
		List<OpenTabState> dirtyTabs = new ArrayList<>();
		for (OpenTabState tab : openTabs) {
			if (tab.isDirty()) {
				if (autosaveInFlight.contains(tab.relativePath())) {
					autosaveQueued.add(tab.relativePath());
				} else {
					dirtyTabs.add(tab);
				}
			}
		}
		if (dirtyTabs.isEmpty()) {
			if (!autosaveInFlight.isEmpty() && reloadAfterLastSave) {
				pendingReloadAfterAutosave = true;
			} else if (onAllComplete != null) {
				onAllComplete.run();
			}
			return;
		}
		int[] remaining = {dirtyTabs.size()};
		boolean[] anySaved = {false};
		for (OpenTabState tab : dirtyTabs) {
			String path = tab.relativePath();
			autosaveInFlight.add(path);
			String content = tab.buffer().getText();
			backend.writeFile(path, content, tab.sha256(), false).whenComplete((result, error) -> runOnClient(() -> {
				autosaveInFlight.remove(path);
				if (error != null) {
					setError("Autosave failed (" + tab.fileName() + "): " + unwrapMessage(error));
				} else {
					tab.applySavedDocument(result.document());
					bumpWorkspaceRevision();
					reloadPendingOnClose = true;
					anySaved[0] = true;
					appendOutput(LogLevel.SUCCESS, "Autosaved " + tab.relativePath());
				}
				remaining[0]--;
				if (remaining[0] == 0) {
					if (reloadAfterLastSave && anySaved[0]) {
						reloadDatapacks();
					}
					if (onAllComplete != null) {
						onAllComplete.run();
					}
				}
			}));
		}
	}

	/**
	 * Invoked when {@code QuillIdeScreen} closes; applies configured autosave-on-close
	 * and reload-on-close triggers.
	 */
	public void onIdeClosed() {
		QuillClientConfig cfg = QuillClientConfig.current();
		boolean shouldAutosave = cfg.autosaveMode() == QuillClientConfig.AutosaveMode.ON_GUI_CLOSE
				|| cfg.autosaveMode() == QuillClientConfig.AutosaveMode.ON_KEYPRESS;
		boolean reloadOnClose = cfg.reloadTriggerMode() == QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE;

		if (shouldAutosave && hasAnyDirtyTab()) {
			boolean needReload = reloadOnClose || cfg.shouldReloadOnSave();
			saveAllDirtyTabs(false, () -> {
				if ((needReload || reloadPendingOnClose) && autosaveInFlight.isEmpty()) {
					reloadDatapacks();
				} else if (needReload || reloadPendingOnClose) {
					pendingReloadAfterAutosave = true;
				}
			});
			return;
		}
		if (reloadOnClose && (reloadPendingOnClose || !autosaveInFlight.isEmpty())) {
			if (autosaveInFlight.isEmpty()) {
				reloadDatapacks();
			} else {
				pendingReloadAfterAutosave = true;
			}
		}
	}

	public void forceSaveActiveTab(boolean reloadAfterSave) {
		OpenTabState tab = activeTab();
		if (tab == null) {
			return;
		}
		saveTabInternal(tab, null, reloadAfterSave, null);
	}

	public void forceSaveTab(String relativePath, boolean reloadAfterSave) {
		OpenTabState tab = findOpenTab(relativePath);
		if (tab != null) {
			saveTabInternal(tab, null, reloadAfterSave, null);
		}
	}

	public void reloadTabFromBackend(String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			return;
		}
		busy = true;
		setStatus("Reloading " + relativePath + "...", false);
		backend.readFile(relativePath).whenComplete((doc, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				setError("Reload file failed: " + unwrapMessage(error));
				return;
			}
			bumpWorkspaceRevision();
			OpenTabState tab = findOpenTab(relativePath);
			if (tab != null) {
				tab.replaceFromDocument(doc);
				setStatus("Reloaded " + tab.fileName() + " from server", false);
				appendOutput(LogLevel.INFO, "Reloaded " + relativePath + " from backend.");
			} else {
				openOrFocusDocument(doc);
				setStatus("Opened latest " + doc.relativePath(), false);
			}
		}));
	}

	/**
	 * Applies a server-pushed datapack change broadcast on the client UI thread.
	 */
	public void onRemoteTreeChanged(String actorName, String changeKind, String affectedPath, String newSha256) {
		runOnClient(() -> {
			refreshTree();
			String actor = (actorName == null || actorName.isBlank()) ? "another editor" : actorName;
			String kind = changeKind == null ? "" : changeKind.toUpperCase(Locale.ROOT);
			String path = affectedPath == null ? "" : affectedPath;
			String sha = newSha256 == null ? "" : newSha256;

			if ("WRITE".equals(kind) || "APPLY_PRESET".equals(kind)) {
				OpenTabState tab = findOpenTab(path);
				if (tab != null) {
					if (!tab.isDirty()) {
						if (sha.isEmpty() || !sha.equals(tab.sha256())) {
							backend.readFile(path).whenComplete((doc, error) -> runOnClient(() -> {
								if (error == null) {
									OpenTabState current = findOpenTab(path);
									if (current != null && !current.isDirty()) {
										current.replaceFromDocument(doc);
										bumpWorkspaceRevision();
										String msg = "Synced " + current.fileName() + " (edited by " + actor + ")";
										setStatus(msg, false);
										appendOutput(LogLevel.INFO, msg);
									}
								}
							}));
						}
					} else {
						setError("Remote edit by " + actor + " on " + tab.fileName() + " (unsaved local changes)");
					}
				}
			} else if ("DELETE".equals(kind) || "RENAME".equals(kind)) {
				if (!path.isEmpty()) {
					boolean warnedDirty = false;
					for (int i = openTabs.size() - 1; i >= 0; i--) {
						OpenTabState tab = openTabs.get(i);
						if (tab.relativePath().equals(path) || tab.relativePath().startsWith(path + "/")) {
							if (!tab.isDirty()) {
								closeTab(i);
							} else {
								warnedDirty = true;
							}
						}
					}
					if (warnedDirty) {
						setError("Remote " + kind.toLowerCase(Locale.ROOT) + " by " + actor + " affected unsaved tab(s)");
					}
				}
			} else if ("RELOAD".equals(kind)) {
				String msg = "Datapacks reloaded by " + actor;
				setStatus(msg, false);
				appendOutput(LogLevel.INFO, msg);
			}
		});
	}

	private void saveTabInternal(
			OpenTabState tab,
			String expectedBaseSha256,
			boolean reloadAfterSave,
			Runnable afterCleanSaveAndReload
	) {
		busy = true;
		setStatus(reloadAfterSave ? "Saving & reloading..." : "Saving...", false);
		String content = tab.buffer().getText();

		backend.writeFile(tab.relativePath(), content, expectedBaseSha256, reloadAfterSave).whenComplete((result, error) -> runOnClient(() -> {
			busy = false;
			if (error != null) {
				Throwable unwrapped = unwrapThrowable(error);
				if (unwrapped instanceof RevisionConflictException rce) {
					this.pendingConflict = new PendingConflict(
							rce.relativePath(),
							rce.expectedSha256(),
							rce.actualSha256(),
							reloadAfterSave
					);
					setError(rce.getMessage());
					return;
				}
				setError("Save failed: " + unwrapMessage(error));
				return;
			}
			tab.applySavedDocument(result.document());
			bumpWorkspaceRevision();
			if (result.reloadReport() != null) {
				applyReloadReport(result.reloadReport(), true);
				if (!result.reloadReport().hasErrors() && afterCleanSaveAndReload != null) {
					afterCleanSaveAndReload.run();
				}
			} else {
				reloadPendingOnClose = true;
				String msg = "Saved " + tab.relativePath();
				setStatus("Saved " + tab.fileName(), false);
				appendOutput(LogLevel.SUCCESS, msg);
				if (afterCleanSaveAndReload != null) {
					afterCleanSaveAndReload.run();
				}
			}
		}));
	}

	public OpenTabState findOpenTab(String relativePath) {
		for (OpenTabState tab : openTabs) {
			if (tab.relativePath().equals(relativePath)) {
				return tab;
			}
		}
		return null;
	}

	public void reloadDatapacks() {
		busy = true;
		reloadPendingOnClose = false;
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
			appendOutput(LogLevel.SUCCESS, "Created datapack " + createdPack);
		})).thenApply(ignored -> null);
	}

	/**
	 * Creates a datapack and optionally applies the {@code minecraft:load} and {@code minecraft:tick}
	 * template presets using the chosen namespace and function names (works identically in SP and MP).
	 */
	public CompletableFuture<Void> createDatapackWithTemplate(
			String packName,
			String description,
			boolean useTemplate,
			String namespace,
			String loadFunctionName,
			String tickFunctionName
	) {
		if (!useTemplate) {
			return createDatapack(packName, description);
		}
		record TemplateResult(String pack, FileDocument loadDoc) {
		}
		busy = true;
		setStatus("Creating templated datapack " + packName + "...", false);
		return backend.createDatapack(packName, description)
				.thenCompose(createdPack -> backend.applyPreset(
								createdPack,
								"minecraft:load",
								Map.of("namespace", namespace, "functionName", loadFunctionName)
						)
						.thenCompose(loadDoc -> backend.applyPreset(
										createdPack,
										"minecraft:tick",
										Map.of("namespace", namespace, "functionName", tickFunctionName)
								)
								.thenApply(ignoredTick -> new TemplateResult(createdPack, loadDoc))))
				.whenComplete((result, error) -> runOnClient(() -> {
					busy = false;
					if (error != null) {
						setError("Create datapack template failed: " + unwrapMessage(error));
						return;
					}
					expandedPaths.add(result.pack());
					expandAncestors(result.loadDoc().relativePath());
					selectedTreePath = result.loadDoc().relativePath();
					openOrFocusDocument(result.loadDoc());
					refreshTree();
					String msg = "Created datapack " + result.pack() + " (" + namespace + ":" + loadFunctionName + ", " + namespace + ":" + tickFunctionName + ")";
					setStatus(msg, false);
					appendOutput(LogLevel.SUCCESS, msg);
				}))
				.thenApply(ignored -> null);
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
			appendOutput(LogLevel.SUCCESS, "Created namespace " + createdDir);
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
			appendOutput(LogLevel.SUCCESS, "Created function " + createdFile);
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
			appendOutput(LogLevel.SUCCESS, "Applied preset " + presetId + " -> " + doc.relativePath());
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
			appendOutput(LogLevel.SUCCESS, "Created " + relativePath);
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
			appendOutput(LogLevel.INFO, "Renamed " + sourceRelativePath + " -> " + targetRelativePath);
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
			appendOutput(LogLevel.WARN, "Deleted " + relativePath);
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
		this.reloadPendingOnClose = false;
		if (report.hasErrors()) {
			this.bottomPanelTab = BottomPanelTab.PROBLEMS;
			this.diagnosticsExpanded = true;
			int count = report.diagnostics().size();
			setError("Reload errors (" + count + ") in " + report.durationMs() + "ms");
			for (ReloadDiagnostic diag : report.diagnostics()) {
				String loc = diag.line() > 0 ? ":" + diag.line() : "";
				appendOutput(LogLevel.ERROR, "  [!] " + diag.file() + loc + " - " + diag.message());
			}
		} else {
			String prefix = afterSave ? "Saved & Reloaded" : "Reloaded";
			String summary = prefix + " (" + report.durationMs() + "ms, " + report.enabledPackCount() + " packs)";
			setStatus(summary, false);
			appendOutput(LogLevel.SUCCESS, summary);
		}
	}

	public void setStatus(String message, boolean error) {
		this.statusMessage = message;
		this.statusIsError = error;
	}

	private void setError(String message) {
		setStatus(message, true);
		appendOutput(LogLevel.ERROR, message);
	}

	public void runOnUi(Runnable task) {
		runOnClient(task);
	}

	private void runOnClient(Runnable task) {
		uiExecutor.execute(task);
	}

	private static void sendDefaultMinecraftCommand(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			throw new IllegalStateException("Minecraft client is not running.");
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			throw new IllegalStateException("Not connected to a Minecraft world.");
		}
		connection.sendCommand(command);
	}

	private static Throwable unwrapThrowable(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null && (current instanceof CompletionException || current instanceof ExecutionException)) {
			current = current.getCause();
		}
		return current;
	}

	private static String unwrapMessage(Throwable throwable) {
		Throwable current = unwrapThrowable(throwable);
		return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
	}
}
