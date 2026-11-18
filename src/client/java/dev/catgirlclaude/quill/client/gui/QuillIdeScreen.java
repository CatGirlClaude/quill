package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.editor.WorkspaceFileCache;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.OpenTabState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Visual Studio Code-inspired Datapack IDE screen rendered over the Minecraft world.
 * Supports movable &amp; resizable floating window geometry, draggable splitters,
 * configurable themes/fonts/shortcuts/autosave/pause settings, breadcrumbs, and live tools.
 */
public class QuillIdeScreen extends Screen {
	interface TextInputBridge {
		void startTextInput(Object owner);

		void stopTextInput(Object owner);

		static TextInputBridge forMinecraft(Minecraft minecraft) {
			return new TextInputBridge() {
				@Override
				public void startTextInput(Object owner) {
					if (minecraft != null) {
						minecraft.textInputManager().startTextInput(owner);
					}
				}

				@Override
				public void stopTextInput(Object owner) {
					if (minecraft != null) {
						minecraft.textInputManager().stopTextInput(owner);
					}
				}
			};
		}
	}

	private enum DragMode {
		NONE,
		MOVE_WINDOW,
		RESIZE_WINDOW,
		RESIZE_SIDEBAR,
		RESIZE_BOTTOM_PANEL
	}

	private record BreadcrumbSegment(String label, String fullPath, boolean isFile, IdeLayout.Rect rect) {
	}

	private final ClientEditorSession session;
	private final TextInputBridge textInputBridge;
	private final WorkspaceFileCache workspaceFileCache = new WorkspaceFileCache();
	private final FileExplorerTreeWidget explorerTree;
	private final SearchSidebarWidget searchSidebar;
	private final InspectorSidebarWidget inspectorSidebar;
	private final QuickActionsAndPresetsWidget quickActions;
	private final EditorTabBarWidget tabBarWidget;
	private final CodeEditorWidget codeEditorWidget;
	private final SettingsPanelWidget settingsPanel;
	private final BottomPanelWidget bottomPanelWidget;
	private final QuillNotificationBanner notificationBanner;

	private IdeModal activeModal = null;
	private QuickPickOverlay activeQuickPick = null;
	private boolean settingsOpen = false;
	private boolean sidebarFocused = false;
	private OpenTabState lastObservedActiveTab = null;
	private boolean textInputActive = false;

	private DragMode dragMode = DragMode.NONE;
	private IdeLayout.ResizeEdge activeResizeEdge = IdeLayout.ResizeEdge.NONE;
	private double dragStartMouseX = 0;
	private double dragStartMouseY = 0;
	private IdeLayout.WindowBounds dragStartBounds = null;
	private int dragStartSplitterSize = 0;

	private IdeLayout lastLayout = IdeLayout.compute(854, 480, false, false, false);
	private IdeLayout.Rect commandCenterRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect runTitleBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect maximizeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect closeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect explorerIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect searchIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect inspectorIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect presetsIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect settingsIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect sidebarSplitterRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect bottomSplitterRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect statusRemoteBadgeRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect saveReloadBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect reloadBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect statusProblemsBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect statusOutputBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect statusWarningPillRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect welcomeNewPackRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect welcomeNewFnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect welcomeSettingsRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect lastSidebarRect = new IdeLayout.Rect(0, 0, 0, 0);
	private final List<BreadcrumbSegment> breadcrumbSegments = new ArrayList<>();

	private String hoveredActivityTooltip = null;
	private int hoveredActivityTooltipX = 0;
	private int hoveredActivityTooltipY = 0;

	public QuillIdeScreen(ClientEditorSession session) {
		this(Minecraft.getInstance(), Minecraft.getInstance().font, session, TextInputBridge.forMinecraft(Minecraft.getInstance()));
	}

	QuillIdeScreen(Minecraft minecraft, Font font, ClientEditorSession session, TextInputBridge textInputBridge) {
		super(minecraft, font, Component.literal("Quill IDE"));
		this.session = Objects.requireNonNull(session, "session");
		this.textInputBridge = Objects.requireNonNull(textInputBridge, "textInputBridge");
		this.explorerTree = new FileExplorerTreeWidget(session, this::openModal, this::closeModal);
		this.searchSidebar = new SearchSidebarWidget(session, workspaceFileCache, this::openModal, this::closeModal);
		this.inspectorSidebar = new InspectorSidebarWidget(session, workspaceFileCache);
		this.quickActions = new QuickActionsAndPresetsWidget(session, this::openModal, this::closeModal);
		this.tabBarWidget = new EditorTabBarWidget(session, this::openModal, this::closeModal);
		this.codeEditorWidget = new CodeEditorWidget();
		this.codeEditorWidget.setOnBufferEdited(session::notifyTabBufferEdited);
		this.settingsPanel = new SettingsPanelWidget(this::closeSettings, this::onResetWindowBounds);
		this.bottomPanelWidget = new BottomPanelWidget(session);
		this.notificationBanner = new QuillNotificationBanner(session);
		this.lastObservedActiveTab = session.activeTab();
		this.tabBarWidget.setSettingsHooks(
				() -> settingsOpen,
				this::openSettings,
				this::closeSettings,
				this::closeSettings
		);
	}

	public ClientEditorSession session() {
		return session;
	}

	QuickActionsAndPresetsWidget quickActions() {
		return quickActions;
	}

	FileExplorerTreeWidget explorerTree() {
		return explorerTree;
	}

	SearchSidebarWidget searchSidebar() {
		return searchSidebar;
	}

	InspectorSidebarWidget inspectorSidebar() {
		return inspectorSidebar;
	}

	SettingsPanelWidget settingsPanel() {
		return settingsPanel;
	}

	IdeModal activeModal() {
		return activeModal;
	}

	public boolean isSettingsOpen() {
		return settingsOpen;
	}

	public void openSettings() {
		this.codeEditorWidget.dismissAutocomplete();
		this.explorerTree.cancelInlineInput();
		this.searchSidebar.unfocus();
		this.activeQuickPick = null;
		this.settingsPanel.syncFromConfig();
		this.settingsOpen = true;
		this.lastObservedActiveTab = session.activeTab();
		syncTextInputState();
	}

	public void closeSettings() {
		if (!settingsOpen) {
			return;
		}
		this.settingsOpen = false;
		this.settingsPanel.unfocus();
		syncTextInputState();
	}

	public void toggleSettings() {
		if (settingsOpen) {
			closeSettings();
		} else {
			openSettings();
		}
	}

	private void onResetWindowBounds() {
		if (session.isMaximized()) {
			session.toggleMaximized();
		}
		dragMode = DragMode.NONE;
		activeResizeEdge = IdeLayout.ResizeEdge.NONE;
	}

	protected void openModal(IdeModal modal) {
		this.codeEditorWidget.dismissAutocomplete();
		this.explorerTree.cancelInlineInput();
		this.settingsPanel.unfocus();
		this.activeQuickPick = null;
		this.activeModal = modal;
		syncTextInputState();
	}

	protected void closeModal() {
		this.activeModal = null;
		syncTextInputState();
	}

	protected void openQuickPick(QuickPickOverlay.Mode mode) {
		if (activeModal != null) {
			return;
		}
		this.codeEditorWidget.dismissAutocomplete();
		this.searchSidebar.unfocus();
		this.explorerTree.cancelInlineInput();
		this.settingsPanel.unfocus();
		this.activeQuickPick = new QuickPickOverlay(
				mode,
				this::buildCommandPaletteItems,
				this::buildQuickOpenFileItems,
				this::closeQuickPick
		);
		syncTextInputState();
	}

	protected void closeQuickPick() {
		this.activeQuickPick = null;
		syncTextInputState();
	}

	QuickPickOverlay activeQuickPick() {
		return activeQuickPick;
	}

	protected void openSearchSidebar() {
		this.codeEditorWidget.dismissAutocomplete();
		this.explorerTree.cancelInlineInput();
		this.settingsPanel.unfocus();
		session.setSidebarView(ClientEditorSession.SidebarView.SEARCH);
		OpenTabState active = session.activeTab();
		String selected = (active != null && active.buffer().hasSelection()) ? active.buffer().getSelectedText() : null;
		searchSidebar.focusSearchInput(selected);
		syncTextInputState();
	}

	protected void openInspectorSidebar(boolean invalidateCache) {
		this.codeEditorWidget.dismissAutocomplete();
		this.searchSidebar.unfocus();
		this.explorerTree.cancelInlineInput();
		this.settingsPanel.unfocus();
		session.setSidebarView(ClientEditorSession.SidebarView.INSPECTOR);
		inspectorSidebar.refresh(invalidateCache);
		syncTextInputState();
	}

	private boolean isSearchInputFocused() {
		return !session.isSidebarCollapsed()
				&& session.sidebarView() == ClientEditorSession.SidebarView.SEARCH
				&& searchSidebar.hasFocusedTextInput();
	}

	private boolean isExplorerInlineInputActive() {
		return !session.isSidebarCollapsed()
				&& session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER
				&& explorerTree.hasActiveInlineInput();
	}

	CodeEditorWidget codeEditorWidget() {
		return codeEditorWidget;
	}

	void setSidebarFocused(boolean sidebarFocused) {
		this.sidebarFocused = sidebarFocused;
	}

	boolean isSidebarFocused() {
		return sidebarFocused;
	}

	boolean isCodeEditorFocused() {
		return activeModal == null
				&& activeQuickPick == null
				&& !settingsOpen
				&& !isExplorerInlineInputActive()
				&& !isSearchInputFocused()
				&& !sidebarFocused
				&& session.activeTab() != null;
	}

	private boolean wantsTextInput() {
		if (activeModal != null) {
			return activeModal.hasTextFields();
		}
		if (activeQuickPick != null) {
			return true;
		}
		if (isExplorerInlineInputActive()) {
			return true;
		}
		if (isSearchInputFocused()) {
			return true;
		}
		if (settingsOpen) {
			return settingsPanel.hasFocusedTextInput();
		}
		return session.activeTab() != null;
	}

	private void syncTextInputState() {
		boolean shouldCapture = wantsTextInput();
		if (shouldCapture == textInputActive) {
			return;
		}
		textInputActive = shouldCapture;
		if (shouldCapture) {
			textInputBridge.startTextInput(this);
		} else {
			textInputBridge.stopTextInput(this);
		}
	}

	private void syncActiveTabSelection() {
		OpenTabState current = session.activeTab();
		if (settingsOpen && current != null && current != lastObservedActiveTab) {
			settingsOpen = false;
			settingsPanel.unfocus();
		}
		lastObservedActiveTab = current;
	}

	private void pollPendingConflictModal() {
		if (activeModal != null) {
			return;
		}
		ClientEditorSession.PendingConflict conflict = session.consumePendingConflict();
		if (conflict == null) {
			return;
		}
		String fileName = conflict.relativePath();
		int slash = fileName.lastIndexOf('/');
		if (slash >= 0) {
			fileName = fileName.substring(slash + 1);
		}
		String msg = conflict.actualSha256().isEmpty()
				? "'" + fileName + "' was deleted on server. Overwrite to recreate?"
				: "'" + fileName + "' changed on server. Overwrite or reload latest?";
		openModal(ModalPromptOverlay.confirmWithSecondary(
				"Revision Conflict",
				msg,
				"Overwrite",
				true,
				() -> {
					closeModal();
					session.forceSaveTab(conflict.relativePath(), conflict.reloadAfterSave());
				},
				"Reload",
				() -> {
					closeModal();
					session.reloadTabFromBackend(conflict.relativePath());
				},
				this::closeModal
		));
	}

	@Override
	protected void init() {
		super.init();
		syncTextInputState();
	}

	@Override
	public void tick() {
		super.tick();
		syncActiveTabSelection();
		pollPendingConflictModal();
		syncTextInputState();
	}

	@Override
	public void removed() {
		textInputActive = false;
		textInputBridge.stopTextInput(this);
		settingsPanel.unfocus();
		dragMode = DragMode.NONE;
		session.onIdeClosed();
		super.removed();
	}

	@Override
	public boolean isInputCaptured() {
		return wantsTextInput() || (settingsOpen && settingsPanel.isRecordingKeybind()) || super.isInputCaptured();
	}

	@Override
	public boolean isPauseScreen() {
		return QuillClientConfig.current().pauseGameWhileOpen();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (!session.isMaximized()) {
			QuillDraw.fill(graphics, 0, 0, width, height, QuillTheme.BG_BACKDROP);
		}
	}

	private int mcGuiScale() {
		return minecraft != null && minecraft.getWindow() != null
				? Math.max(1, minecraft.getWindow().getGuiScale())
				: IdeLayout.DEFAULT_TEST_MC_GUI_SCALE;
	}

	private float uiScale() {
		return IdeLayout.computeEffectiveScale(mcGuiScale(), QuillClientConfig.current().uiScale());
	}

	private int virtualWidth() {
		return IdeLayout.toVirtualSize(width, uiScale());
	}

	private int virtualHeight() {
		return IdeLayout.toVirtualSize(height, uiScale());
	}

	private MouseButtonEvent unscaleMouseEvent(MouseButtonEvent event) {
		float scale = uiScale();
		if (Math.abs(scale - 1.0f) <= 0.001f) {
			return event;
		}
		return new MouseButtonEvent(event.x() / scale, event.y() / scale, event.buttonInfo());
	}

	private IdeLayout computeCurrentLayout() {
		return computeCurrentLayout(virtualWidth(), virtualHeight());
	}

	private IdeLayout computeCurrentLayout(int vw, int vh) {
		QuillClientConfig cfg = QuillClientConfig.current();
		float physScale = IdeLayout.physicalToVirtualScale(cfg.uiScale());
		IdeLayout.WindowBounds custom = cfg.hasCustomWindowBounds()
				? new IdeLayout.WindowBounds(
						Math.round(cfg.windowX() / physScale),
						Math.round(cfg.windowY() / physScale),
						Math.round(cfg.windowWidth() / physScale),
						Math.round(cfg.windowHeight() / physScale))
				: null;
		boolean showBreadcrumbs = !settingsOpen && session.activeTab() != null;
		return IdeLayout.compute(
				vw,
				vh,
				session.isMaximized(),
				session.isSidebarCollapsed(),
				session.isBottomPanelOpen(),
				showBreadcrumbs,
				custom,
				cfg.sidebarWidth(),
				cfg.bottomPanelHeight()
		);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		syncActiveTabSelection();
		pollPendingConflictModal();
		syncTextInputState();
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		float scale = uiScale();
		int vw = virtualWidth();
		int vh = virtualHeight();
		int vmX = mouseX < 0 ? -1 : (int) Math.floor(mouseX / scale);
		int vmY = mouseY < 0 ? -1 : (int) Math.floor(mouseY / scale);
		boolean scaled = Math.abs(scale - 1.0f) > 0.001f;

		if (scaled) {
			graphics.pose().pushMatrix();
			graphics.pose().scale(scale, scale);
		}
		try {
			IdeLayout layout = computeCurrentLayout(vw, vh);
			lastLayout = layout;
			lastSidebarRect = layout.sidebar();
			sidebarSplitterRect = layout.sidebar().width() > 0
					? new IdeLayout.Rect(layout.sidebar().right() - 3, layout.sidebar().y(), 5, layout.sidebar().height())
					: new IdeLayout.Rect(0, 0, 0, 0);
			bottomSplitterRect = layout.diagnosticsBanner().height() > 0
					? new IdeLayout.Rect(layout.window().x(), layout.diagnosticsBanner().y() - 2, layout.window().width(), 5)
					: new IdeLayout.Rect(0, 0, 0, 0);

			boolean overlayOpen = activeModal != null || activeQuickPick != null;
			int uiMouseX = overlayOpen ? -1 : vmX;
			int uiMouseY = overlayOpen ? -1 : vmY;

			hoveredActivityTooltip = null;

			// Soft multi-layer desktop drop shadow + crisp hairline window frame
			IdeLayout.Rect win = layout.window();
			if (!session.isMaximized()) {
				QuillDraw.fill(graphics, win.x() - 2, win.y() + 1, win.width() + 6, win.height() + 6, QuillTheme.Metrics.SHADOW_OUTER);
				QuillDraw.fill(graphics, win.x() + 1, win.y() + 2, win.width() + 2, win.height() + 3, QuillTheme.Metrics.SHADOW_INNER);
			}
			QuillDraw.fill(graphics, win.x(), win.y(), win.width(), win.height(), QuillTheme.BG_EDITOR);
			QuillDraw.outline(graphics, win.x(), win.y(), win.width(), win.height(),
					dragMode != DragMode.NONE ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE);

			renderTitleBar(graphics, layout.titleBar(), uiMouseX, uiMouseY);
			renderActivityBar(graphics, layout.activityBar(), uiMouseX, uiMouseY);
			if (layout.sidebar().width() > 0) {
				renderSidebar(graphics, layout.sidebar(), uiMouseX, uiMouseY);
			}
			renderTabBar(graphics, layout.tabBar(), uiMouseX, uiMouseY);
			if (layout.breadcrumbs().height() > 0) {
				renderBreadcrumbs(graphics, layout.breadcrumbs(), uiMouseX, uiMouseY);
			} else {
				breadcrumbSegments.clear();
			}
			renderEditorArea(graphics, layout.editor(), uiMouseX, uiMouseY);
			if (layout.diagnosticsBanner().height() > 0) {
				bottomPanelWidget.render(graphics, font, layout.diagnosticsBanner(), uiMouseX, uiMouseY);
			} else {
				bottomPanelWidget.clearBounds();
			}
			renderStatusBar(graphics, layout.statusBar(), uiMouseX, uiMouseY);

			// Splitter hover / active highlights and resize cursors
			if (!overlayOpen) {
				boolean hoverSidebarSplit = dragMode == DragMode.RESIZE_SIDEBAR
						|| (dragMode == DragMode.NONE && sidebarSplitterRect.contains(vmX, vmY));
				boolean hoverBottomSplit = dragMode == DragMode.RESIZE_BOTTOM_PANEL
						|| (dragMode == DragMode.NONE && bottomSplitterRect.contains(vmX, vmY));
				if (hoverSidebarSplit && layout.sidebar().width() > 0) {
					QuillDraw.fill(graphics, layout.sidebar().right() - 1, layout.sidebar().y(), 2, layout.sidebar().height(), QuillTheme.ACCENT_BLUE);
					QuillDraw.requestCursor(graphics, CursorTypes.RESIZE_EW);
				} else if (hoverBottomSplit && layout.diagnosticsBanner().height() > 0) {
					QuillDraw.fill(graphics, layout.window().x(), layout.diagnosticsBanner().y(), layout.window().width(), 2, QuillTheme.ACCENT_BLUE);
					QuillDraw.requestCursor(graphics, CursorTypes.RESIZE_NS);
				} else if (dragMode == DragMode.MOVE_WINDOW) {
					QuillDraw.requestCursor(graphics, CursorTypes.RESIZE_ALL);
				} else if (dragMode == DragMode.RESIZE_WINDOW) {
					QuillDraw.requestCursor(graphics, cursorForResizeEdge(activeResizeEdge));
				} else if (!session.isMaximized() && !isTitleBarInteractiveHit(vmX, vmY)) {
					IdeLayout.ResizeEdge hoverEdge = IdeLayout.hitTestResizeEdge(layout.window(), vmX, vmY, IdeLayout.RESIZE_BORDER);
					if (hoverEdge != IdeLayout.ResizeEdge.NONE) {
						QuillDraw.requestCursor(graphics, cursorForResizeEdge(hoverEdge));
					}
				}
			}

			// Floating Activity Bar tooltip on top of sidebar/editor
			if (!overlayOpen && hoveredActivityTooltip != null) {
				int tw = QuillDraw.textWidth(font, hoveredActivityTooltip) + 10;
				int th = 16;
				int tx = Math.min(vw - tw - 4, hoveredActivityTooltipX);
				int ty = Math.max(4, Math.min(vh - th - 4, hoveredActivityTooltipY));
				QuillDraw.fill(graphics, tx, ty, tw, th, QuillTheme.BG_POPUP);
				QuillDraw.outline(graphics, tx, ty, tw, th, QuillTheme.BORDER_SUBTLE);
				QuillDraw.text(graphics, font, hoveredActivityTooltip, tx + 5, ty + (th - font.lineHeight) / 2, QuillTheme.TEXT_BRIGHT);
			}

			notificationBanner.render(graphics, font, win, layout.titleBar().bottom(), uiMouseX, uiMouseY);

			if (activeQuickPick != null) {
				activeQuickPick.render(graphics, font, vw, vh, vmX, vmY);
			}
			if (activeModal != null) {
				activeModal.render(graphics, font, vw, vh, vmX, vmY);
			}
		} finally {
			if (scaled) {
				graphics.pose().popMatrix();
			}
		}
	}

	protected void renderTitleBar(GuiGraphicsExtractor graphics, IdeLayout.Rect bar, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, bar.x(), bar.y(), bar.width(), bar.height(), QuillTheme.BG_TITLE_BAR);
		QuillDraw.hLine(graphics, bar.x(), bar.width(), bar.bottom() - 1, QuillTheme.BORDER_SUBTLE);

		int btnSize = bar.height() - 4;
		closeBtnRect = new IdeLayout.Rect(bar.right() - btnSize - 4, bar.y() + 2, btnSize, btnSize);
		maximizeBtnRect = new IdeLayout.Rect(closeBtnRect.x() - btnSize - 3, bar.y() + 2, btnSize, btnSize);

		int textY = bar.y() + (bar.height() - font.lineHeight) / 2;
		int rightBoundary = maximizeBtnRect.x();

		// Optional "> Run" button when viewing an .mcfunction
		String fnId = settingsOpen ? null : session.activeFunctionId();
		boolean hoverRun = false;
		if (fnId != null && bar.width() > 260) {
			String runLabel = "> Run";
			int rw = QuillDraw.textWidth(font, runLabel) + 10;
			runTitleBtnRect = new IdeLayout.Rect(maximizeBtnRect.x() - rw - 5, bar.y() + 2, rw, btnSize);
			hoverRun = runTitleBtnRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, runTitleBtnRect.x(), runTitleBtnRect.y(), runTitleBtnRect.width(), runTitleBtnRect.height(),
					hoverRun ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
			QuillDraw.text(graphics, font, runLabel, runTitleBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
			rightBoundary = runTitleBtnRect.x();
		} else {
			runTitleBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		}

		// Left brand badge ("Q") + "Quill"
		int badgeX = bar.x() + 6;
		int badgeY = bar.y() + 4;
		QuillDraw.fill(graphics, badgeX, badgeY, 13, 13, QuillTheme.ACCENT_BLUE);
		QuillDraw.text(graphics, font, "Q", badgeX + (13 - QuillDraw.textWidth(font, "Q")) / 2, badgeY + 2, QuillTheme.TEXT_BRIGHT);
		int leftBoundary = badgeX + 18;
		if (bar.width() >= 420) {
			QuillDraw.text(graphics, font, "Quill", leftBoundary, textY, QuillTheme.TEXT_PRIMARY);
			leftBoundary += QuillDraw.textWidth(font, "Quill") + 10;
		}

		// Centered VS Code Command Center search pill
		int availCenter = rightBoundary - leftBoundary - 12;
		boolean hoverCmdCenter = false;
		if (availCenter >= 120) {
			int pillW = Math.min(280, Math.max(120, availCenter - 16));
			int pillX = Math.max(leftBoundary + 4, bar.x() + (bar.width() - pillW) / 2);
			if (pillX + pillW > rightBoundary - 6) {
				pillX = rightBoundary - 6 - pillW;
			}
			commandCenterRect = new IdeLayout.Rect(pillX, bar.y() + 3, pillW, bar.height() - 6);
			hoverCmdCenter = commandCenterRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, commandCenterRect.x(), commandCenterRect.y(), commandCenterRect.width(), commandCenterRect.height(),
					hoverCmdCenter ? QuillTheme.BG_HOVER : QuillTheme.BG_INPUT);
			QuillDraw.outline(graphics, commandCenterRect.x(), commandCenterRect.y(), commandCenterRect.width(), commandCenterRect.height(),
					hoverCmdCenter ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE);

			OpenTabState active = session.activeTab();
			String quickOpenKey = QuillClientConfig.current().shortcut(QuillClientConfig.ShortcutAction.QUICK_OPEN).displayLabel();
			String pillText = settingsOpen
					? "Settings  (" + quickOpenKey + ")"
					: (active != null
							? active.fileName() + (active.isDirty() ? " *" : "") + "  (" + quickOpenKey + ")"
							: "Search files or >commands  (" + quickOpenKey + ")");
			String clipped = QuillDraw.clipToWidth(font, pillText, pillW - 12);
			int pillTextX = commandCenterRect.x() + (commandCenterRect.width() - QuillDraw.textWidth(font, clipped)) / 2;
			QuillDraw.text(graphics, font, clipped, pillTextX, textY, QuillTheme.TEXT_MUTED);
		} else {
			commandCenterRect = new IdeLayout.Rect(0, 0, 0, 0);
			OpenTabState active = session.activeTab();
			String titleText = active != null ? active.fileName() : "Quill IDE";
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, titleText, Math.max(0, rightBoundary - leftBoundary - 6)),
					leftBoundary, textY, QuillTheme.TEXT_PRIMARY);
		}

		// Geometric Maximize/Restore & Close buttons
		boolean hoverMax = maximizeBtnRect.contains(mouseX, mouseY);
		boolean hoverClose = closeBtnRect.contains(mouseX, mouseY);
		QuillDraw.fill(graphics, maximizeBtnRect.x(), maximizeBtnRect.y(), maximizeBtnRect.width(), maximizeBtnRect.height(),
				hoverMax ? QuillTheme.BG_HOVER : QuillTheme.BG_TITLE_BAR);
		QuillDraw.fill(graphics, closeBtnRect.x(), closeBtnRect.y(), closeBtnRect.width(), closeBtnRect.height(),
				hoverClose ? QuillTheme.BG_BUTTON_DANGER : QuillTheme.BG_TITLE_BAR);

		int iconColor = QuillTheme.TEXT_PRIMARY;
		int mxIcon = maximizeBtnRect.x() + (maximizeBtnRect.width() - 8) / 2;
		int myIcon = maximizeBtnRect.y() + (maximizeBtnRect.height() - 8) / 2;
		if (session.isMaximized()) {
			// Overlapping restore squares
			QuillDraw.outline(graphics, mxIcon + 2, myIcon, 6, 6, iconColor);
			QuillDraw.fill(graphics, mxIcon, myIcon + 2, 6, 6, hoverMax ? QuillTheme.BG_HOVER : QuillTheme.BG_TITLE_BAR);
			QuillDraw.outline(graphics, mxIcon, myIcon + 2, 6, 6, iconColor);
		} else {
			QuillDraw.outline(graphics, mxIcon, myIcon, 8, 8, iconColor);
			QuillDraw.hLine(graphics, mxIcon, 8, myIcon + 1, iconColor);
		}

		int closeColor = hoverClose ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY;
		QuillDraw.drawCloseX(graphics,
				closeBtnRect.x() + (closeBtnRect.width() - 8) / 2,
				closeBtnRect.y() + (closeBtnRect.height() - 8) / 2,
				8,
				closeColor);

		if (hoverMax || hoverClose || hoverRun || hoverCmdCenter) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	protected void renderActivityBar(GuiGraphicsExtractor graphics, IdeLayout.Rect bar, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, bar.x(), bar.y(), bar.width(), bar.height(), QuillTheme.BG_ACTIVITY_BAR);
		QuillDraw.vLine(graphics, bar.right() - 1, bar.y(), bar.height(), QuillTheme.BORDER_SUBTLE);

		int iconW = bar.width() - 1;
		int iconH = 28;
		explorerIconRect = new IdeLayout.Rect(bar.x(), bar.y() + 4, iconW, iconH);
		searchIconRect = new IdeLayout.Rect(bar.x(), explorerIconRect.bottom() + 2, iconW, iconH);
		inspectorIconRect = new IdeLayout.Rect(bar.x(), searchIconRect.bottom() + 2, iconW, iconH);
		presetsIconRect = new IdeLayout.Rect(bar.x(), inspectorIconRect.bottom() + 2, iconW, iconH);
		settingsIconRect = new IdeLayout.Rect(bar.x(), Math.max(presetsIconRect.bottom() + 4, bar.bottom() - iconH - 4), iconW, iconH);

		QuillClientConfig cfg = QuillClientConfig.current();
		boolean expActive = !session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER;
		boolean srcActive = !session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.SEARCH;
		boolean insActive = !session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.INSPECTOR;
		boolean preActive = !session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.PRESETS;

		boolean expHover = explorerIconRect.contains(mouseX, mouseY);
		boolean srcHover = searchIconRect.contains(mouseX, mouseY);
		boolean insHover = inspectorIconRect.contains(mouseX, mouseY);
		boolean preHover = presetsIconRect.contains(mouseX, mouseY);
		boolean setHover = settingsIconRect.contains(mouseX, mouseY);

		drawActivitySlot(graphics, explorerIconRect, expActive, expHover);
		drawExplorerIcon(graphics, explorerIconRect, expActive || expHover ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

		drawActivitySlot(graphics, searchIconRect, srcActive, srcHover);
		drawSearchIcon(graphics, searchIconRect, srcActive || srcHover ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

		drawActivitySlot(graphics, inspectorIconRect, insActive, insHover);
		drawInspectorIcon(graphics, inspectorIconRect, insActive || insHover ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

		drawActivitySlot(graphics, presetsIconRect, preActive, preHover);
		drawPresetsIcon(graphics, presetsIconRect, preActive || preHover ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

		drawActivitySlot(graphics, settingsIconRect, settingsOpen, setHover);
		drawGearIcon(graphics, settingsIconRect, settingsOpen || setHover ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

		if (expHover) {
			setActivityTooltip("Explorer (" + cfg.shortcut(QuillClientConfig.ShortcutAction.TOGGLE_SIDEBAR).displayLabel() + ")", explorerIconRect);
		} else if (srcHover) {
			setActivityTooltip("Search (" + cfg.shortcut(QuillClientConfig.ShortcutAction.SEARCH_IN_FILES).displayLabel() + ")", searchIconRect);
		} else if (insHover) {
			setActivityTooltip("Macro & Scoreboard Inspector", inspectorIconRect);
		} else if (preHover) {
			setActivityTooltip("Presets & Quick Actions", presetsIconRect);
		} else if (setHover) {
			setActivityTooltip("Settings (" + cfg.shortcut(QuillClientConfig.ShortcutAction.OPEN_SETTINGS).displayLabel() + ")", settingsIconRect);
		}

		if (expHover || srcHover || insHover || preHover || setHover) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	private void setActivityTooltip(String text, IdeLayout.Rect anchor) {
		hoveredActivityTooltip = text;
		hoveredActivityTooltipX = anchor.right() + 4;
		hoveredActivityTooltipY = anchor.y() + (anchor.height() - 16) / 2;
	}

	private static void drawActivitySlot(GuiGraphicsExtractor graphics, IdeLayout.Rect rect, boolean active, boolean hovered) {
		if (active) {
			QuillDraw.fill(graphics, rect.x(), rect.y() + 2, 2, rect.height() - 4, QuillTheme.ACCENT_BLUE);
		} else if (hovered) {
			QuillDraw.fill(graphics, rect.x() + 2, rect.y() + 2, rect.width() - 4, rect.height() - 4, QuillTheme.BG_HOVER);
		}
	}

	private static void drawExplorerIcon(GuiGraphicsExtractor graphics, IdeLayout.Rect r, int color) {
		int cx = r.x() + (r.width() - 12) / 2;
		int cy = r.y() + (r.height() - 13) / 2;
		// Back document
		QuillDraw.outline(graphics, cx + 3, cy, 9, 10, color);
		// Front document (masked interior so lines don't collide)
		QuillDraw.fill(graphics, cx, cy + 3, 9, 10, QuillTheme.BG_ACTIVITY_BAR);
		QuillDraw.outline(graphics, cx, cy + 3, 9, 10, color);
		QuillDraw.hLine(graphics, cx + 2, 5, cy + 6, color);
		QuillDraw.hLine(graphics, cx + 2, 5, cy + 9, color);
	}

	private static void drawSearchIcon(GuiGraphicsExtractor graphics, IdeLayout.Rect r, int color) {
		int cx = r.x() + (r.width() - 13) / 2;
		int cy = r.y() + (r.height() - 13) / 2;
		QuillDraw.drawSearchLoupe(graphics, cx, cy, color);
	}

	private static void drawInspectorIcon(GuiGraphicsExtractor graphics, IdeLayout.Rect r, int color) {
		int cx = r.x() + (r.width() - 12) / 2;
		int cy = r.y() + (r.height() - 12) / 2;
		// Variable tree / nodes icon
		QuillDraw.fill(graphics, cx, cy + 1, 3, 3, color);
		QuillDraw.hLine(graphics, cx + 4, 8, cy + 2, color);
		QuillDraw.vLine(graphics, cx + 1, cy + 4, 7, color);
		QuillDraw.hLine(graphics, cx + 1, 4, cy + 6, color);
		QuillDraw.fill(graphics, cx + 5, cy + 5, 7, 2, color);
		QuillDraw.hLine(graphics, cx + 1, 4, cy + 10, color);
		QuillDraw.fill(graphics, cx + 5, cy + 9, 5, 2, color);
	}

	private static void drawPresetsIcon(GuiGraphicsExtractor graphics, IdeLayout.Rect r, int color) {
		int cx = r.x() + (r.width() - 12) / 2;
		int cy = r.y() + (r.height() - 12) / 2;
		// VS Code Extensions 4-tile grid (top-right tile offset)
		QuillDraw.outline(graphics, cx, cy + 1, 5, 5, color);
		QuillDraw.outline(graphics, cx, cy + 7, 5, 5, color);
		QuillDraw.outline(graphics, cx + 6, cy + 7, 5, 5, color);
		QuillDraw.outline(graphics, cx + 7, cy, 5, 5, color);
	}

	private static void drawGearIcon(GuiGraphicsExtractor graphics, IdeLayout.Rect r, int color) {
		QuillDraw.drawGearIcon(graphics, r.x() + r.width() / 2, r.y() + r.height() / 2, color, QuillTheme.BG_ACTIVITY_BAR);
	}

	protected void renderSidebar(GuiGraphicsExtractor graphics, IdeLayout.Rect sidebar, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, sidebar.x(), sidebar.y(), sidebar.width(), sidebar.height(), QuillTheme.BG_SIDEBAR);
		QuillDraw.vLine(graphics, sidebar.right() - 1, sidebar.y(), sidebar.height(), QuillTheme.BORDER_SUBTLE);

		switch (session.sidebarView()) {
			case EXPLORER -> explorerTree.render(graphics, font, sidebar, mouseX, mouseY);
			case SEARCH -> searchSidebar.render(graphics, font, sidebar, mouseX, mouseY);
			case INSPECTOR -> inspectorSidebar.render(graphics, font, sidebar, mouseX, mouseY);
			case PRESETS -> quickActions.render(graphics, font, sidebar, mouseX, mouseY);
		}
	}

	protected void renderTabBar(GuiGraphicsExtractor graphics, IdeLayout.Rect tabBar, int mouseX, int mouseY) {
		tabBarWidget.render(graphics, font, tabBar, mouseX, mouseY);
	}

	protected void renderBreadcrumbs(GuiGraphicsExtractor graphics, IdeLayout.Rect bar, int mouseX, int mouseY) {
		breadcrumbSegments.clear();
		QuillDraw.fill(graphics, bar.x(), bar.y(), bar.width(), bar.height(), QuillTheme.BG_BREADCRUMB);
		QuillDraw.hLine(graphics, bar.x(), bar.width(), bar.bottom() - 1, QuillTheme.BORDER_SUBTLE);

		OpenTabState active = session.activeTab();
		if (active == null || bar.width() < 24) {
			return;
		}
		String[] parts = active.relativePath().split("/");
		int x = bar.x() + 8;
		int textY = bar.y() + (bar.height() - font.lineHeight) / 2;
		StringBuilder pathAcc = new StringBuilder();
		boolean anyHovered = false;

		QuillDraw.beginClip(graphics, bar);
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				String sep = " > ";
				QuillDraw.text(graphics, font, sep, x, textY, QuillTheme.TEXT_MUTED);
				x += QuillDraw.textWidth(font, sep);
				pathAcc.append('/');
			}
			pathAcc.append(parts[i]);
			boolean isFile = i == parts.length - 1;
			String label = parts[i];
			int segW = QuillDraw.textWidth(font, label) + 4;
			IdeLayout.Rect segRect = new IdeLayout.Rect(x - 2, bar.y() + 1, segW, bar.height() - 2);
			breadcrumbSegments.add(new BreadcrumbSegment(label, pathAcc.toString(), isFile, segRect));

			boolean hover = bar.contains(mouseX, mouseY) && segRect.contains(mouseX, mouseY);
			if (hover) {
				anyHovered = true;
				QuillDraw.fill(graphics, segRect.x(), segRect.y(), segRect.width(), segRect.height(), QuillTheme.BG_HOVER);
			}
			int color = isFile ? QuillTheme.TEXT_BRIGHT : (hover ? QuillTheme.TEXT_PRIMARY : QuillTheme.TEXT_MUTED);
			QuillDraw.text(graphics, font, label, x, textY, color);
			x += QuillDraw.textWidth(font, label);
			if (x > bar.right() - 8) {
				break;
			}
		}
		QuillDraw.endClip(graphics);

		if (anyHovered) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	protected void renderEditorArea(GuiGraphicsExtractor graphics, IdeLayout.Rect editor, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, editor.x(), editor.y(), editor.width(), editor.height(), QuillTheme.BG_EDITOR);
		if (settingsOpen) {
			welcomeNewPackRect = new IdeLayout.Rect(0, 0, 0, 0);
			welcomeNewFnRect = new IdeLayout.Rect(0, 0, 0, 0);
			welcomeSettingsRect = new IdeLayout.Rect(0, 0, 0, 0);
			settingsPanel.render(graphics, font, editor, mouseX, mouseY);
			return;
		}
		OpenTabState active = session.activeTab();
		if (active == null) {
			QuillDraw.beginClip(graphics, editor);
			int leftX = editor.x() + 24;
			int topY = editor.y() + Math.max(18, editor.height() / 5);

			QuillDraw.textShadow(graphics, font, "Quill Datapack IDE", leftX, topY, QuillTheme.TEXT_BRIGHT);
			QuillDraw.text(graphics, font, "Visual Studio Code workspace for Minecraft 26.3 datapacks",
					leftX, topY + 13, QuillTheme.TEXT_MUTED);

			int btnY = topY + 32;
			String dpLabel = "+ New Datapack";
			String fnLabel = "+ New .mcfunction";
			String setLabel = "Settings";
			int dpW = QuillDraw.textWidth(font, dpLabel) + 14;
			int fnW = QuillDraw.textWidth(font, fnLabel) + 14;
			int setW = QuillDraw.textWidth(font, setLabel) + 14;
			welcomeNewPackRect = new IdeLayout.Rect(leftX, btnY, dpW, 20);
			welcomeNewFnRect = new IdeLayout.Rect(leftX + dpW + 8, btnY, fnW, 20);
			welcomeSettingsRect = new IdeLayout.Rect(welcomeNewFnRect.right() + 8, btnY, setW, 20);

			boolean hoverDp = welcomeNewPackRect.contains(mouseX, mouseY);
			boolean hoverFn = welcomeNewFnRect.contains(mouseX, mouseY);
			boolean hoverSet = welcomeSettingsRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, welcomeNewPackRect.x(), welcomeNewPackRect.y(), welcomeNewPackRect.width(), welcomeNewPackRect.height(),
					hoverDp ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
			QuillDraw.fill(graphics, welcomeNewFnRect.x(), welcomeNewFnRect.y(), welcomeNewFnRect.width(), welcomeNewFnRect.height(),
					hoverFn ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);
			QuillDraw.fill(graphics, welcomeSettingsRect.x(), welcomeSettingsRect.y(), welcomeSettingsRect.width(), welcomeSettingsRect.height(),
					hoverSet ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);

			int btnTextY = btnY + (20 - font.lineHeight) / 2;
			QuillDraw.text(graphics, font, dpLabel, welcomeNewPackRect.x() + 7, btnTextY, QuillTheme.TEXT_BRIGHT);
			QuillDraw.text(graphics, font, fnLabel, welcomeNewFnRect.x() + 7, btnTextY, QuillTheme.TEXT_PRIMARY);
			QuillDraw.text(graphics, font, setLabel, welcomeSettingsRect.x() + 7, btnTextY, QuillTheme.TEXT_PRIMARY);
			if (hoverDp || hoverFn || hoverSet) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}

			// Live configured keyboard shortcuts table
			QuillClientConfig cfg = QuillClientConfig.current();
			int tableY = btnY + 34;
			QuillDraw.text(graphics, font, "Shortcuts", leftX, tableY, QuillTheme.TEXT_PRIMARY);
			tableY += 14;
			QuillClientConfig.ShortcutAction[] shown = {
					QuillClientConfig.ShortcutAction.QUICK_OPEN,
					QuillClientConfig.ShortcutAction.COMMAND_PALETTE,
					QuillClientConfig.ShortcutAction.SEARCH_IN_FILES,
					QuillClientConfig.ShortcutAction.SAVE,
					QuillClientConfig.ShortcutAction.TOGGLE_SIDEBAR,
					QuillClientConfig.ShortcutAction.TOGGLE_BOTTOM_PANEL,
					QuillClientConfig.ShortcutAction.ZOOM_IN,
					QuillClientConfig.ShortcutAction.ZOOM_OUT,
					QuillClientConfig.ShortcutAction.OPEN_SETTINGS
			};
			for (QuillClientConfig.ShortcutAction action : shown) {
				if (tableY + 14 > editor.bottom() - 8) {
					break;
				}
				String keyStr = cfg.shortcut(action).displayLabel();
				int kw = QuillDraw.textWidth(font, keyStr) + 8;
				QuillDraw.fill(graphics, leftX, tableY, kw, 12, QuillTheme.BG_SIDEBAR);
				QuillDraw.outline(graphics, leftX, tableY, kw, 12, QuillTheme.BORDER_SUBTLE);
				QuillDraw.text(graphics, font, keyStr, leftX + 4, tableY + 2, QuillTheme.TEXT_BRIGHT);
				QuillDraw.text(graphics, font, action.label(), leftX + Math.max(82, kw + 10), tableY + 2, QuillTheme.TEXT_MUTED);
				tableY += 15;
			}
			QuillDraw.endClip(graphics);
		} else {
			welcomeNewPackRect = new IdeLayout.Rect(0, 0, 0, 0);
			welcomeNewFnRect = new IdeLayout.Rect(0, 0, 0, 0);
			welcomeSettingsRect = new IdeLayout.Rect(0, 0, 0, 0);
			codeEditorWidget.render(graphics, font, editor, active, mouseX, mouseY, isCodeEditorFocused());
		}
	}

	static int statusBarBackgroundColor(boolean hasErrors, boolean statusIsError, boolean hasAnyDirtyTab) {
		if (statusIsError || hasErrors) {
			return QuillTheme.BG_STATUS_ERROR;
		}
		return QuillTheme.BG_STATUS_BAR;
	}

	protected void renderStatusBar(GuiGraphicsExtractor graphics, IdeLayout.Rect bar, int mouseX, int mouseY) {
		QuillClientConfig cfg = QuillClientConfig.current();
		ReloadReport report = session.lastReloadReport();
		boolean hasErrors = report != null && report.hasErrors();
		int errorCount = hasErrors ? report.diagnostics().size() : 0;
		int bg = statusBarBackgroundColor(hasErrors, session.statusIsError(), session.hasAnyDirtyTab());
		QuillDraw.fill(graphics, bar.x(), bar.y(), bar.width(), bar.height(), bg);

		int textY = bar.y() + (bar.height() - font.lineHeight) / 2;
		int leftX = bar.x();

		// Leftmost VS Code remote/local accent badge
		String badgeText = ">< " + session.backendLabel();
		int badgeW = QuillDraw.textWidth(font, badgeText) + 10;
		statusRemoteBadgeRect = new IdeLayout.Rect(leftX, bar.y(), badgeW, bar.height());
		boolean hoverBadge = statusRemoteBadgeRect.contains(mouseX, mouseY);
		QuillDraw.fill(graphics, statusRemoteBadgeRect.x(), statusRemoteBadgeRect.y(), statusRemoteBadgeRect.width(), statusRemoteBadgeRect.height(),
				hoverBadge ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_STATUS_REMOTE_BADGE);
		QuillDraw.text(graphics, font, badgeText, statusRemoteBadgeRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
		leftX = statusRemoteBadgeRect.right() + 4;

		dev.catgirlclaude.quill.client.config.KeyShortcut saveShortcut = cfg.shortcut(QuillClientConfig.ShortcutAction.SAVE);
		String saveLabel = saveShortcut.isUnbound() ? "Save" : "Save (" + saveShortcut.displayLabel() + ")";
		String reloadLabel = "Reload";
		String problemsLabel = "!" + errorCount;
		String outputLabel = "Output";

		int saveW = QuillDraw.textWidth(font, saveLabel) + 8;
		int reloadW = QuillDraw.textWidth(font, reloadLabel) + 8;
		int probW = QuillDraw.textWidth(font, problemsLabel) + 8;
		int outW = QuillDraw.textWidth(font, outputLabel) + 8;

		saveReloadBtnRect = new IdeLayout.Rect(leftX, bar.y() + 1, saveW, bar.height() - 2);
		reloadBtnRect = new IdeLayout.Rect(saveReloadBtnRect.right() + 3, bar.y() + 1, reloadW, bar.height() - 2);
		statusProblemsBtnRect = new IdeLayout.Rect(reloadBtnRect.right() + 3, bar.y() + 1, probW, bar.height() - 2);
		statusOutputBtnRect = new IdeLayout.Rect(statusProblemsBtnRect.right() + 3, bar.y() + 1, outW, bar.height() - 2);

		boolean hoverSave = saveReloadBtnRect.contains(mouseX, mouseY);
		boolean hoverReload = reloadBtnRect.contains(mouseX, mouseY);
		boolean hoverProb = statusProblemsBtnRect.contains(mouseX, mouseY);
		boolean hoverOut = statusOutputBtnRect.contains(mouseX, mouseY);

		QuillDraw.fill(graphics, saveReloadBtnRect.x(), saveReloadBtnRect.y(), saveReloadBtnRect.width(), saveReloadBtnRect.height(),
				hoverSave ? 0x33FFFFFF : 0x1AFFFFFF);
		QuillDraw.fill(graphics, reloadBtnRect.x(), reloadBtnRect.y(), reloadBtnRect.width(), reloadBtnRect.height(),
				hoverReload ? 0x33FFFFFF : 0x1AFFFFFF);
		boolean probActive = session.isBottomPanelOpen() && session.bottomPanelTab() == ClientEditorSession.BottomPanelTab.PROBLEMS;
		boolean outActive = session.isBottomPanelOpen() && session.bottomPanelTab() == ClientEditorSession.BottomPanelTab.OUTPUT;
		QuillDraw.fill(graphics, statusProblemsBtnRect.x(), statusProblemsBtnRect.y(), statusProblemsBtnRect.width(), statusProblemsBtnRect.height(),
				hoverProb ? 0x33FFFFFF : (probActive ? 0x26FFFFFF : 0x14FFFFFF));
		QuillDraw.fill(graphics, statusOutputBtnRect.x(), statusOutputBtnRect.y(), statusOutputBtnRect.width(), statusOutputBtnRect.height(),
				hoverOut ? 0x33FFFFFF : (outActive ? 0x26FFFFFF : 0x14FFFFFF));

		QuillDraw.text(graphics, font, saveLabel, saveReloadBtnRect.x() + 4, textY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, reloadLabel, reloadBtnRect.x() + 4, textY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, problemsLabel, statusProblemsBtnRect.x() + 4, textY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, outputLabel, statusOutputBtnRect.x() + 4, textY, QuillTheme.TEXT_BRIGHT);

		int statusX = statusOutputBtnRect.right() + 5;
		boolean hoverWarn = false;
		if (cfg.hasInstantAutosaveReloadWarning()) {
			String warnLabel = "[!] Keypress+Reload";
			int warnW = QuillDraw.textWidth(font, warnLabel) + 8;
			statusWarningPillRect = new IdeLayout.Rect(statusX, bar.y() + 1, warnW, bar.height() - 2);
			hoverWarn = statusWarningPillRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, statusWarningPillRect.x(), statusWarningPillRect.y(), statusWarningPillRect.width(), statusWarningPillRect.height(),
					hoverWarn ? QuillTheme.WARNING_BANNER_BORDER : QuillTheme.WARNING_BANNER_BG);
			QuillDraw.text(graphics, font, warnLabel, statusWarningPillRect.x() + 4, textY, QuillTheme.TEXT_WARNING);
			statusX = statusWarningPillRect.right() + 6;
		} else {
			statusWarningPillRect = new IdeLayout.Rect(0, 0, 0, 0);
		}

		// Right-side compact VS Code metadata
		OpenTabState active = settingsOpen ? null : session.activeTab();
		String rightInfo = formatStatusRightInfo(active, cfg.zoomPercent(), settingsOpen, session.isReloadPendingOnClose());
		int rightW = rightInfo.isEmpty() ? 0 : QuillDraw.textWidth(font, rightInfo);
		int rightX = bar.right() - rightW - 8;
		if (!rightInfo.isEmpty() && rightX > statusX + 8) {
			QuillDraw.text(graphics, font, rightInfo, rightX, textY, QuillTheme.TEXT_BRIGHT);
		} else if (active != null) {
			String compact = "Ln " + active.buffer().oneBasedLine() + ", Col " + active.buffer().oneBasedColumn();
			int cw = QuillDraw.textWidth(font, compact);
			rightX = bar.right() - cw - 6;
			if (rightX > statusX + 4) {
				QuillDraw.text(graphics, font, compact, rightX, textY, QuillTheme.TEXT_BRIGHT);
			} else {
				rightX = bar.right() - 4;
			}
		} else {
			rightX = bar.right() - 4;
		}

		int statusMaxW = Math.max(0, rightX - statusX - 8);
		if (statusMaxW > 18) {
			String msg = session.isReloadPendingOnClose()
					? "[Reload on close] " + session.statusMessage()
					: session.statusMessage();
			String statusClipped = QuillDraw.clipToWidth(font, msg, statusMaxW);
			QuillDraw.text(graphics, font, statusClipped, statusX, textY, QuillTheme.TEXT_BRIGHT);
		}

		if (hoverBadge || hoverSave || hoverReload || hoverProb || hoverOut || hoverWarn) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	static String formatStatusRightInfo(
			OpenTabState active,
			int zoomPercent,
			boolean settingsOpen,
			boolean reloadPendingOnClose
	) {
		List<String> parts = new ArrayList<>();
		if (zoomPercent != QuillClientConfig.DEFAULT_ZOOM_PERCENT) {
			parts.add(zoomPercent + "%");
		}
		if (active != null) {
			String selInfo = active.buffer().hasSelection()
					? " (" + active.buffer().selectedCharCount() + " sel)"
					: "";
			parts.add("Ln " + active.buffer().oneBasedLine() + ", Col " + active.buffer().oneBasedColumn() + selInfo);
			parts.add(languageLabel(active.fileName()));
		} else if (settingsOpen) {
			parts.add("Settings");
		} else if (reloadPendingOnClose) {
			parts.add("Reload pending");
		}
		return String.join("  |  ", parts);
	}

	private static String languageLabel(String fileName) {
		if (fileName.endsWith(".mcfunction")) {
			return "mcfunction";
		}
		if (fileName.endsWith(".json") || fileName.endsWith(".mcmeta")) {
			return "JSON";
		}
		if (fileName.endsWith(".md")) {
			return "Markdown";
		}
		return "Plain Text";
	}

	private static String shortcutBadge(QuillClientConfig cfg, QuillClientConfig.ShortcutAction action) {
		var shortcut = cfg.shortcut(action);
		return shortcut.isUnbound() ? "" : shortcut.displayLabel();
	}

	private List<QuickPickOverlay.PickItem> buildCommandPaletteItems() {
		QuillClientConfig cfg = QuillClientConfig.current();
		List<QuickPickOverlay.PickItem> items = new ArrayList<>();
		items.add(new QuickPickOverlay.PickItem(
				"save_config", "File: Save Active File", "Save active file (follows Reload Trigger setting)",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.SAVE),
				"File: Save Active File save", session::saveActiveTabAccordingToConfig));
		items.add(new QuickPickOverlay.PickItem(
				"save_reload", "File: Save & Reload Datapacks", "Save active file and run /reload", "",
				"File: Save & Reload Datapacks save reload", () -> session.saveActiveTab(true)));
		items.add(new QuickPickOverlay.PickItem(
				"save_only", "File: Save Without Reload", "Write active buffer to disk without reloading", "",
				"File: Save Without Reload save", () -> session.saveActiveTab(false)));
		items.add(new QuickPickOverlay.PickItem(
				"quick_open", "File: Quick Open...", "Fuzzy-search files across datapacks/",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.QUICK_OPEN),
				"File: Quick Open go to file switcher", () -> openQuickPick(QuickPickOverlay.Mode.FILES)));
		items.add(new QuickPickOverlay.PickItem(
				"open_settings", "Preferences: Open Settings", "Configure theme, font, text size, autosave, pause, and shortcuts",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.OPEN_SETTINGS),
				"Preferences: Open Settings config theme font keybinds autosave pause", this::openSettings));

		String activeFn = session.activeFunctionId();
		if (activeFn != null) {
			items.add(new QuickPickOverlay.PickItem(
					"run_function", "Datapack: Run Current Function", "/function " + activeFn,
					shortcutBadge(cfg, QuillClientConfig.ShortcutAction.RUN_FUNCTION),
					"Datapack: Run Current Function execute run " + activeFn, session::runCurrentFunction));
		}

		items.add(new QuickPickOverlay.PickItem(
				"reload", "Datapack: Reload Datapacks", "Trigger world datapack reload",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.RELOAD_DATAPACKS),
				"Datapack: Reload Datapacks reload", session::reloadDatapacks));
		items.add(new QuickPickOverlay.PickItem(
				"new_pack", "Datapack: New Datapack...", "Scaffold pack.mcmeta (MC 26.3) and data/", "",
				"Datapack: New Datapack scaffold create", quickActions::promptNewDatapack));
		items.add(new QuickPickOverlay.PickItem(
				"new_ns", "Datapack: New Namespace...", "Create data/<namespace>/function/", "",
				"Datapack: New Namespace scaffold create", quickActions::promptNewNamespace));
		items.add(new QuickPickOverlay.PickItem(
				"new_fn", "Datapack: New .mcfunction...", "Create a new function file", "",
				"Datapack: New .mcfunction function scaffold create", quickActions::promptNewFunction));
		items.add(new QuickPickOverlay.PickItem(
				"explorer_new_folder", "Explorer: New Folder", "Create a folder inline inside the File Explorer", "",
				"Explorer: New Folder directory mkdir inline", () -> {
					session.setSidebarView(ClientEditorSession.SidebarView.EXPLORER);
					explorerTree.beginInlineCreateFolder();
					syncTextInputState();
				}));
		items.add(new QuickPickOverlay.PickItem(
				"explorer_new_file", "Explorer: New File", "Create a file inline inside the File Explorer", "",
				"Explorer: New File inline", () -> {
					session.setSidebarView(ClientEditorSession.SidebarView.EXPLORER);
					explorerTree.beginInlineCreateFile();
					syncTextInputState();
				}));
		items.add(new QuickPickOverlay.PickItem(
				"explorer_rename", "Explorer: Rename Selected", "Rename selected file or folder inline", "F2",
				"Explorer: Rename Selected mv inline", () -> {
					session.setSidebarView(ClientEditorSession.SidebarView.EXPLORER);
					explorerTree.beginInlineRename();
					syncTextInputState();
				}));
		items.add(new QuickPickOverlay.PickItem(
				"refresh_tree", "Explorer: Refresh File Tree", "Rescan datapacks/ folder", "",
				"Explorer: Refresh File Tree rescan", session::refreshTree));

		if (session.activeTab() != null) {
			items.add(new QuickPickOverlay.PickItem(
					"close_tab", "Editor: Close Active Tab", session.activeTab().fileName(),
					shortcutBadge(cfg, QuillClientConfig.ShortcutAction.CLOSE_TAB),
					"Editor: Close Active Tab", () -> tabBarWidget.requestCloseTab(session.activeTabIndex())));
		}

		items.add(new QuickPickOverlay.PickItem(
				"toggle_sidebar", "View: Toggle Sidebar", "Show or hide left sidebar",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.TOGGLE_SIDEBAR),
				"View: Toggle Sidebar collapse", session::toggleSidebarCollapsed));
		items.add(new QuickPickOverlay.PickItem(
				"toggle_panel", "View: Toggle Bottom Panel", "Show or hide Problems / Output panel",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.TOGGLE_BOTTOM_PANEL),
				"View: Toggle Bottom Panel problems output terminal console", session::toggleBottomPanel));
		items.add(new QuickPickOverlay.PickItem(
				"show_problems", "View: Show Problems", "Open Problems diagnostics tab", "",
				"View: Show Problems errors diagnostics", () -> session.openBottomPanel(ClientEditorSession.BottomPanelTab.PROBLEMS)));
		items.add(new QuickPickOverlay.PickItem(
				"show_output", "View: Show Output Log", "Open live Reload & Function Output tab", "",
				"View: Show Output Log console", () -> session.openBottomPanel(ClientEditorSession.BottomPanelTab.OUTPUT)));
		items.add(new QuickPickOverlay.PickItem(
				"clear_output", "Output: Clear Log", "Clear entries in the Output panel", "",
				"Output: Clear Log", session::clearOutputLog));
		items.add(new QuickPickOverlay.PickItem(
				"view_explorer", "View: Show Explorer", "Focus File Explorer sidebar", "",
				"View: Show Explorer files", () -> session.setSidebarView(ClientEditorSession.SidebarView.EXPLORER)));
		items.add(new QuickPickOverlay.PickItem(
				"view_search", "Search: Find & Replace in Files", "Search across all datapack files",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.SEARCH_IN_FILES),
				"Search: Find & Replace in Files grep workspace", this::openSearchSidebar));
		items.add(new QuickPickOverlay.PickItem(
				"view_inspector", "View: Show Macro & Scoreboard Inspector", "Inspect scoreboard objectives, storage, and macros", "",
				"View: Show Macro & Scoreboard Inspector variables storage", () -> openInspectorSidebar(false)));
		items.add(new QuickPickOverlay.PickItem(
				"rescan_inspector", "Inspector: Rescan Datapacks", "Refresh scoreboard, storage, and macro index", "",
				"Inspector: Rescan Datapacks refresh variables", () -> openInspectorSidebar(true)));
		items.add(new QuickPickOverlay.PickItem(
				"view_presets", "View: Show Presets & Quick Actions", "Focus Presets sidebar", "",
				"View: Show Presets Quick Actions templates", () -> session.setSidebarView(ClientEditorSession.SidebarView.PRESETS)));
		items.add(new QuickPickOverlay.PickItem(
				"toggle_maximize", "View: Toggle Maximize IDE Window", "Switch floating vs full-screen mode", "",
				"View: Toggle Maximize IDE Window fullscreen", session::toggleMaximized));
		items.add(new QuickPickOverlay.PickItem(
				"reset_window", "View: Reset Window Position & Size", "Restore default centered floating window geometry", "",
				"View: Reset Window Position Size layout", () -> {
					QuillClientConfig.current().resetWindowBounds();
					QuillClientConfig.saveCurrent();
					onResetWindowBounds();
				}));
		items.add(new QuickPickOverlay.PickItem(
				"zoom_in", "View: Zoom In", "Increase overall IDE UI & font scale",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.ZOOM_IN),
				"View: Zoom In scale font size larger", () -> applyZoomChange(QuillClientConfig.current().zoomIn())));
		items.add(new QuickPickOverlay.PickItem(
				"zoom_out", "View: Zoom Out", "Decrease overall IDE UI & font scale",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.ZOOM_OUT),
				"View: Zoom Out scale font size smaller", () -> applyZoomChange(QuillClientConfig.current().zoomOut())));
		items.add(new QuickPickOverlay.PickItem(
				"zoom_reset", "View: Reset Zoom", "Reset overall IDE UI & font scale to 100%",
				shortcutBadge(cfg, QuillClientConfig.ShortcutAction.ZOOM_RESET),
				"View: Reset Zoom 100% default scale font size", () -> applyZoomChange(QuillClientConfig.current().resetZoom())));

		for (PresetDescriptor preset : session.presets()) {
			items.add(new QuickPickOverlay.PickItem(
					"preset:" + preset.id(),
					"Preset: " + preset.displayName(),
					preset.id(),
					"",
					"Preset: " + preset.displayName() + " " + preset.id() + " " + preset.description(),
					() -> quickActions.promptApplyPreset(preset)
			));
		}
		return items;
	}

	private List<QuickPickOverlay.PickItem> buildQuickOpenFileItems() {
		List<QuickPickOverlay.PickItem> items = new ArrayList<>();
		for (String relPath : session.listAllFilePaths()) {
			int slash = relPath.lastIndexOf('/');
			String fileName = slash >= 0 ? relPath.substring(slash + 1) : relPath;
			String parent = slash >= 0 ? relPath.substring(0, slash) : "";
			boolean alreadyOpen = session.findOpenTab(relPath) != null;
			items.add(new QuickPickOverlay.PickItem(
					relPath,
					fileName,
					parent,
					alreadyOpen ? "Open" : "",
					relPath,
					() -> {
						closeSettings();
						session.openFile(relPath);
					}
			));
		}
		return items;
	}

	private boolean isTitleBarInteractiveHit(double mx, double my) {
		return closeBtnRect.contains(mx, my)
				|| maximizeBtnRect.contains(mx, my)
				|| runTitleBtnRect.contains(mx, my)
				|| commandCenterRect.contains(mx, my);
	}

	private static CursorType cursorForResizeEdge(IdeLayout.ResizeEdge edge) {
		return switch (edge) {
			case E, W -> CursorTypes.RESIZE_EW;
			case N, S -> CursorTypes.RESIZE_NS;
			case NE, NW, SE, SW -> CursorTypes.RESIZE_ALL;
			case NONE -> CursorTypes.ARROW;
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent ev = unscaleMouseEvent(event);
		if (activeModal != null) {
			boolean handled = activeModal.mouseClicked(ev);
			syncTextInputState();
			return handled;
		}
		if (activeQuickPick != null) {
			boolean handled = activeQuickPick.mouseClicked(ev);
			syncTextInputState();
			return handled;
		}
		if (notificationBanner.mouseClicked(ev)) {
			return true;
		}
		double mx = ev.x();
		double my = ev.y();

		if (ev.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			// 1. Outer floating frame resize edges/corners
			if (!session.isMaximized() && !isTitleBarInteractiveHit(mx, my)) {
				IdeLayout.ResizeEdge edge = IdeLayout.hitTestResizeEdge(lastLayout.window(), mx, my, IdeLayout.RESIZE_BORDER);
				if (edge != IdeLayout.ResizeEdge.NONE) {
					dragMode = DragMode.RESIZE_WINDOW;
					activeResizeEdge = edge;
					dragStartMouseX = mx;
					dragStartMouseY = my;
					IdeLayout.Rect w = lastLayout.window();
					dragStartBounds = new IdeLayout.WindowBounds(w.x(), w.y(), w.width(), w.height());
					return true;
				}
			}
			// 2. Draggable splitters (sidebar / bottom panel)
			if (sidebarSplitterRect.contains(mx, my)) {
				dragMode = DragMode.RESIZE_SIDEBAR;
				dragStartMouseX = mx;
				dragStartSplitterSize = lastLayout.sidebar().width();
				return true;
			}
			if (bottomSplitterRect.contains(mx, my)) {
				dragMode = DragMode.RESIZE_BOTTOM_PANEL;
				dragStartMouseY = my;
				dragStartSplitterSize = lastLayout.diagnosticsBanner().height();
				return true;
			}
		}

		if (!lastSidebarRect.contains(mx, my)) {
			searchSidebar.unfocus();
			explorerTree.cancelInlineInput();
			syncTextInputState();
		}
		if (tabBarWidget.mouseClicked(ev)) {
			sidebarFocused = false;
			syncTextInputState();
			return true;
		}
		if (bottomPanelWidget.mouseClicked(ev)) {
			sidebarFocused = true;
			syncTextInputState();
			return true;
		}
		if (ev.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (closeBtnRect.contains(mx, my)) {
				onClose();
				return true;
			}
			if (maximizeBtnRect.contains(mx, my)) {
				session.toggleMaximized();
				return true;
			}
			if (runTitleBtnRect.contains(mx, my)) {
				session.runCurrentFunction();
				return true;
			}
			if (commandCenterRect.contains(mx, my)) {
				openQuickPick(QuickPickOverlay.Mode.FILES);
				return true;
			}
			if (lastLayout.titleBar().contains(mx, my)) {
				if (doubleClick) {
					session.toggleMaximized();
					return true;
				}
				if (session.isMaximized()) {
					session.toggleMaximized();
					float physScale = IdeLayout.physicalToVirtualScale(QuillClientConfig.current().uiScale());
					int vw = virtualWidth();
					int vh = virtualHeight();
					IdeLayout restored = computeCurrentLayout(vw, vh);
					int rw = restored.window().width();
					int rh = restored.window().height();
					int nx = Math.max(0, Math.min(Math.max(0, vw - rw), (int) mx - rw / 2));
					int ny = Math.max(0, Math.min(Math.max(0, vh - rh), (int) my - QuillTheme.TITLE_BAR_HEIGHT / 2));
					QuillClientConfig.current().setCustomWindowBounds(
							Math.round(nx * physScale),
							Math.round(ny * physScale),
							Math.round(rw * physScale),
							Math.round(rh * physScale)
					);
					dragStartBounds = new IdeLayout.WindowBounds(nx, ny, rw, rh);
				} else {
					IdeLayout.Rect w = lastLayout.window();
					dragStartBounds = new IdeLayout.WindowBounds(w.x(), w.y(), w.width(), w.height());
				}
				dragMode = DragMode.MOVE_WINDOW;
				dragStartMouseX = mx;
				dragStartMouseY = my;
				return true;
			}
			if (explorerIconRect.contains(mx, my)) {
				if (!session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER) {
					explorerTree.cancelInlineInput();
					session.toggleSidebarCollapsed();
					sidebarFocused = false;
				} else {
					session.setSidebarView(ClientEditorSession.SidebarView.EXPLORER);
					sidebarFocused = true;
				}
				syncTextInputState();
				return true;
			}
			if (searchIconRect.contains(mx, my)) {
				if (!session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.SEARCH) {
					session.toggleSidebarCollapsed();
					searchSidebar.unfocus();
					sidebarFocused = false;
					syncTextInputState();
				} else {
					sidebarFocused = true;
					openSearchSidebar();
				}
				return true;
			}
			if (inspectorIconRect.contains(mx, my)) {
				if (!session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.INSPECTOR) {
					session.toggleSidebarCollapsed();
					sidebarFocused = false;
					syncTextInputState();
				} else {
					sidebarFocused = true;
					openInspectorSidebar(false);
				}
				return true;
			}
			if (presetsIconRect.contains(mx, my)) {
				explorerTree.cancelInlineInput();
				searchSidebar.unfocus();
				if (!session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.PRESETS) {
					session.toggleSidebarCollapsed();
					sidebarFocused = false;
				} else {
					session.setSidebarView(ClientEditorSession.SidebarView.PRESETS);
					sidebarFocused = true;
				}
				syncTextInputState();
				return true;
			}
			if (settingsIconRect.contains(mx, my)) {
				toggleSettings();
				return true;
			}
			if (lastSidebarRect.contains(mx, my)) {
				OpenTabState prevTab = session.activeTab();
				int prevTabCount = session.openTabs().size();
				boolean handled = switch (session.sidebarView()) {
					case EXPLORER -> explorerTree.mouseClicked(ev);
					case SEARCH -> searchSidebar.mouseClicked(ev);
					case INSPECTOR -> inspectorSidebar.mouseClicked(ev);
					case PRESETS -> quickActions.mouseClicked(ev);
				};
				boolean switchedTab = session.activeTab() != prevTab || session.openTabs().size() != prevTabCount;
				sidebarFocused = !switchedTab;
				syncActiveTabSelection();
				syncTextInputState();
				if (handled) {
					return true;
				}
			}
			// Clickable breadcrumbs
			for (BreadcrumbSegment seg : breadcrumbSegments) {
				if (seg.rect().contains(mx, my)) {
					session.setSidebarView(ClientEditorSession.SidebarView.EXPLORER);
					session.expandAncestors(seg.fullPath() + "/dummy");
					if (!seg.isFile()) {
						session.expandedPaths().add(seg.fullPath());
					}
					session.setSelectedTreePath(seg.fullPath());
					sidebarFocused = true;
					return true;
				}
			}
			if (settingsOpen && settingsPanel.mouseClicked(ev)) {
				syncTextInputState();
				return true;
			}
			if (welcomeNewPackRect.contains(mx, my)) {
				sidebarFocused = false;
				quickActions.promptNewDatapack();
				return true;
			}
			if (welcomeNewFnRect.contains(mx, my)) {
				sidebarFocused = false;
				quickActions.promptNewFunction();
				return true;
			}
			if (welcomeSettingsRect.contains(mx, my)) {
				openSettings();
				return true;
			}
			OpenTabState active = settingsOpen ? null : session.activeTab();
			if (active != null && codeEditorWidget.mouseClicked(ev, font, active)) {
				sidebarFocused = false;
				syncTextInputState();
				return true;
			}
			if (statusRemoteBadgeRect.contains(mx, my)) {
				openQuickPick(QuickPickOverlay.Mode.COMMANDS);
				return true;
			}
			if (saveReloadBtnRect.contains(mx, my)) {
				session.saveActiveTabAccordingToConfig();
				return true;
			}
			if (reloadBtnRect.contains(mx, my)) {
				session.reloadDatapacks();
				return true;
			}
			if (statusProblemsBtnRect.contains(mx, my)) {
				if (session.isBottomPanelOpen() && session.bottomPanelTab() == ClientEditorSession.BottomPanelTab.PROBLEMS) {
					session.setDiagnosticsExpanded(false);
				} else {
					session.openBottomPanel(ClientEditorSession.BottomPanelTab.PROBLEMS);
				}
				return true;
			}
			if (statusOutputBtnRect.contains(mx, my)) {
				if (session.isBottomPanelOpen() && session.bottomPanelTab() == ClientEditorSession.BottomPanelTab.OUTPUT) {
					session.setDiagnosticsExpanded(false);
				} else {
					session.openBottomPanel(ClientEditorSession.BottomPanelTab.OUTPUT);
				}
				return true;
			}
			if (statusWarningPillRect.contains(mx, my)) {
				openSettings();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		MouseButtonEvent ev = unscaleMouseEvent(event);
		if (activeModal != null) {
			return activeModal.mouseDragged(ev);
		}
		if (activeQuickPick != null) {
			return activeQuickPick.mouseDragged(ev);
		}
		if (dragMode != DragMode.NONE && ev.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			int dx = (int) Math.round(ev.x() - dragStartMouseX);
			int dy = (int) Math.round(ev.y() - dragStartMouseY);
			QuillClientConfig cfg = QuillClientConfig.current();
			float physScale = IdeLayout.physicalToVirtualScale(cfg.uiScale());
			int vw = virtualWidth();
			int vh = virtualHeight();
			switch (dragMode) {
				case MOVE_WINDOW -> {
					IdeLayout.WindowBounds next = IdeLayout.applyMove(dragStartBounds, dx, dy, vw, vh);
					cfg.setCustomWindowBounds(
							Math.round(next.x() * physScale),
							Math.round(next.y() * physScale),
							Math.round(next.width() * physScale),
							Math.round(next.height() * physScale)
					);
				}
				case RESIZE_WINDOW -> {
					IdeLayout.WindowBounds next = IdeLayout.applyResize(dragStartBounds, activeResizeEdge, dx, dy, vw, vh);
					cfg.setCustomWindowBounds(
							Math.round(next.x() * physScale),
							Math.round(next.y() * physScale),
							Math.round(next.width() * physScale),
							Math.round(next.height() * physScale)
					);
				}
				case RESIZE_SIDEBAR -> cfg.setSidebarWidth(dragStartSplitterSize + dx);
				case RESIZE_BOTTOM_PANEL -> cfg.setBottomPanelHeight(dragStartSplitterSize - dy);
				case NONE -> {
				}
			}
			return true;
		}
		if (isExplorerInlineInputActive() && explorerTree.mouseDragged(ev)) {
			return true;
		}
		if (isSearchInputFocused() && searchSidebar.mouseDragged(ev)) {
			return true;
		}
		if (settingsOpen && settingsPanel.mouseDragged(ev)) {
			return true;
		}
		OpenTabState active = settingsOpen ? null : session.activeTab();
		if (active != null && codeEditorWidget.mouseDragged(ev, font, active)) {
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		MouseButtonEvent ev = unscaleMouseEvent(event);
		if (dragMode != DragMode.NONE) {
			dragMode = DragMode.NONE;
			activeResizeEdge = IdeLayout.ResizeEdge.NONE;
			QuillClientConfig.saveCurrent();
			return true;
		}
		codeEditorWidget.mouseReleased(ev);
		searchSidebar.mouseReleased(ev);
		explorerTree.mouseReleased(ev);
		settingsPanel.mouseReleased(ev);
		if (activeModal != null) {
			activeModal.mouseReleased(ev);
			return true;
		}
		if (activeQuickPick != null) {
			activeQuickPick.mouseReleased(ev);
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		float scale = uiScale();
		double vmX = mouseX / scale;
		double vmY = mouseY / scale;
		if (activeModal != null) {
			return true;
		}
		if (activeQuickPick != null) {
			return activeQuickPick.mouseScrolled(scrollY);
		}
		if (bottomPanelWidget.mouseScrolled(vmX, vmY, scrollY)) {
			return true;
		}
		if (tabBarWidget.mouseScrolled(vmX, vmY, scrollY)) {
			return true;
		}
		if (lastSidebarRect.contains(vmX, vmY)) {
			boolean handled = switch (session.sidebarView()) {
				case EXPLORER -> explorerTree.mouseScrolled(vmX, vmY, scrollY);
				case SEARCH -> searchSidebar.mouseScrolled(vmX, vmY, scrollY);
				case INSPECTOR -> inspectorSidebar.mouseScrolled(vmX, vmY, scrollY);
				case PRESETS -> quickActions.mouseScrolled(vmX, vmY, scrollY);
			};
			if (handled) {
				return true;
			}
		}
		if (settingsOpen && settingsPanel.mouseScrolled(vmX, vmY, scrollY)) {
			return true;
		}
		OpenTabState active = settingsOpen ? null : session.activeTab();
		if (active != null && codeEditorWidget.mouseScrolled(vmX, vmY, scrollX, scrollY, font, active)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void applyZoomChange(boolean changed) {
		QuillClientConfig cfg = QuillClientConfig.current();
		if (changed) {
			QuillClientConfig.saveCurrent();
		}
		session.setStatus("UI Zoom: " + cfg.zoomPercent() + "%", false);
	}

	private static boolean matchesZoomIn(KeyEvent event, QuillClientConfig cfg) {
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.ZOOM_IN).matches(event)) {
			return true;
		}
		if (!cfg.shortcut(QuillClientConfig.ShortcutAction.ZOOM_IN)
				.equals(QuillClientConfig.ShortcutAction.ZOOM_IN.defaultShortcut())) {
			return false;
		}
		return event.hasControlDownWithQuirk() && !event.hasAltDown()
				&& (event.shortcutKey() == '+'
				|| event.key() == InputConstants.KEY_ADD
				|| (event.hasShiftDown() && (event.shortcutKey() == '=' || event.key() == InputConstants.KEY_EQUALS)));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (activeModal != null) {
			boolean handled = activeModal.keyPressed(event);
			syncTextInputState();
			return handled;
		}
		if (activeQuickPick != null) {
			boolean handled = activeQuickPick.keyPressed(event);
			syncTextInputState();
			return handled;
		}
		if (settingsOpen && (settingsPanel.isRecordingKeybind() || settingsPanel.hasFocusedTextInput())) {
			boolean handled = settingsPanel.keyPressed(event);
			syncTextInputState();
			if (handled) {
				return true;
			}
		}
		if (isExplorerInlineInputActive() && explorerTree.keyPressed(event)) {
			syncTextInputState();
			return true;
		}

		// Configurable IDE keyboard shortcuts
		QuillClientConfig cfg = QuillClientConfig.current();
		if (matchesZoomIn(event, cfg)) {
			applyZoomChange(cfg.zoomIn());
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.ZOOM_OUT).matches(event)) {
			applyZoomChange(cfg.zoomOut());
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.ZOOM_RESET).matches(event)) {
			applyZoomChange(cfg.resetZoom());
			return true;
		}
		if (event.key() == InputConstants.KEY_F1 && !event.hasControlDownWithQuirk() && !event.hasAltDown()
				|| cfg.shortcut(QuillClientConfig.ShortcutAction.COMMAND_PALETTE).matches(event)) {
			openQuickPick(QuickPickOverlay.Mode.COMMANDS);
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.QUICK_OPEN).matches(event)) {
			openQuickPick(QuickPickOverlay.Mode.FILES);
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.OPEN_SETTINGS).matches(event)) {
			toggleSettings();
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.SEARCH_IN_FILES).matches(event)) {
			openSearchSidebar();
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.TOGGLE_SIDEBAR).matches(event)) {
			session.toggleSidebarCollapsed();
			syncTextInputState();
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.TOGGLE_BOTTOM_PANEL).matches(event)) {
			session.toggleBottomPanel();
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.SAVE).matches(event)) {
			session.saveActiveTabAccordingToConfig();
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.CLOSE_TAB).matches(event)) {
			if (settingsOpen) {
				closeSettings();
			} else if (session.activeTab() != null) {
				tabBarWidget.requestCloseTab(session.activeTabIndex());
			}
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.RUN_FUNCTION).matches(event)) {
			session.runCurrentFunction();
			return true;
		}
		if (cfg.shortcut(QuillClientConfig.ShortcutAction.RELOAD_DATAPACKS).matches(event)) {
			session.reloadDatapacks();
			return true;
		}

		if (isSearchInputFocused() && searchSidebar.keyPressed(event)) {
			syncTextInputState();
			return true;
		}
		if (!session.isSidebarCollapsed()
				&& session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER
				&& event.key() == InputConstants.KEY_F2
				&& explorerTree.keyPressed(event)) {
			syncTextInputState();
			return true;
		}
		if (settingsOpen) {
			if (event.isEscape()) {
				closeSettings();
			}
			return true;
		}
		if (isCodeEditorFocused()) {
			OpenTabState active = session.activeTab();
			if (active != null && codeEditorWidget.keyPressed(event, font, active)) {
				return true;
			}
		} else if (event.hasControlDownWithQuirk() && !event.hasAltDown()
				&& (event.shortcutKey() == InputConstants.KEYCODE_Z || event.key() == InputConstants.KEY_Z)) {
			if (event.hasShiftDown()) {
				session.redoFileOperation();
			} else {
				session.undoFileOperation();
			}
			syncTextInputState();
			return true;
		}
		if (event.isEscape() && shouldCloseOnEsc()) {
			return super.keyPressed(event);
		}
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (activeModal != null) {
			return activeModal.charTyped(event);
		}
		if (activeQuickPick != null) {
			return activeQuickPick.charTyped(event);
		}
		if (settingsOpen && (settingsPanel.isRecordingKeybind() || settingsPanel.hasFocusedTextInput())) {
			return settingsPanel.charTyped(event);
		}
		if (isExplorerInlineInputActive() && explorerTree.charTyped(event)) {
			return true;
		}
		if (isSearchInputFocused() && searchSidebar.charTyped(event)) {
			return true;
		}
		if (settingsOpen) {
			return false;
		}
		if (isCodeEditorFocused()) {
			OpenTabState active = session.activeTab();
			if (active != null && codeEditorWidget.charTyped(event, font, active)) {
				return true;
			}
		}
		return super.charTyped(event);
	}
}
