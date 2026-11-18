package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
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
 * Non-pausing VS Code Dark+ IDE screen rendered directly over the live Minecraft world.
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

	private final ClientEditorSession session;
	private final TextInputBridge textInputBridge;
	private final FileExplorerTreeWidget explorerTree;
	private final QuickActionsAndPresetsWidget quickActions;
	private final EditorTabBarWidget tabBarWidget;
	private final CodeEditorWidget codeEditorWidget;
	private final BottomPanelWidget bottomPanelWidget;

	private ModalPromptOverlay activeModal = null;
	private QuickPickOverlay activeQuickPick = null;
	private boolean textInputActive = false;

	private IdeLayout.Rect runTitleBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect maximizeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect closeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect explorerIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect presetsIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect saveReloadBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect reloadBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect statusProblemsBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect statusOutputBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect welcomeNewPackRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect welcomeNewFnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect lastSidebarRect = new IdeLayout.Rect(0, 0, 0, 0);

	public QuillIdeScreen(ClientEditorSession session) {
		this(Minecraft.getInstance(), Minecraft.getInstance().font, session, TextInputBridge.forMinecraft(Minecraft.getInstance()));
	}

	QuillIdeScreen(Minecraft minecraft, Font font, ClientEditorSession session, TextInputBridge textInputBridge) {
		super(minecraft, font, Component.literal("Quill IDE"));
		this.session = Objects.requireNonNull(session, "session");
		this.textInputBridge = Objects.requireNonNull(textInputBridge, "textInputBridge");
		this.explorerTree = new FileExplorerTreeWidget(session, this::openModal, this::closeModal);
		this.quickActions = new QuickActionsAndPresetsWidget(session, this::openModal, this::closeModal);
		this.tabBarWidget = new EditorTabBarWidget(session, this::openModal, this::closeModal);
		this.codeEditorWidget = new CodeEditorWidget();
		this.bottomPanelWidget = new BottomPanelWidget(session);
	}

	public ClientEditorSession session() {
		return session;
	}

	QuickActionsAndPresetsWidget quickActions() {
		return quickActions;
	}

	protected void openModal(ModalPromptOverlay modal) {
		this.codeEditorWidget.dismissAutocomplete();
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

	private boolean wantsTextInput() {
		if (activeModal != null) {
			return activeModal.hasTextFields();
		}
		if (activeQuickPick != null) {
			return true;
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
		pollPendingConflictModal();
		syncTextInputState();
	}

	@Override
	public void removed() {
		textInputActive = false;
		textInputBridge.stopTextInput(this);
		super.removed();
	}

	@Override
	public boolean isInputCaptured() {
		return wantsTextInput() || super.isInputCaptured();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (!session.isMaximized()) {
			QuillDraw.fill(graphics, 0, 0, width, height, QuillTheme.BG_BACKDROP);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		pollPendingConflictModal();
		syncTextInputState();
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		boolean showBottomPanel = session.isBottomPanelOpen();
		IdeLayout layout = IdeLayout.compute(width, height, session.isMaximized(), session.isSidebarCollapsed(), showBottomPanel);
		lastSidebarRect = layout.sidebar();

		boolean overlayOpen = activeModal != null || activeQuickPick != null;
		int uiMouseX = overlayOpen ? -1 : mouseX;
		int uiMouseY = overlayOpen ? -1 : mouseY;

		// Outer frame
		QuillDraw.fill(graphics, layout.window().x(), layout.window().y(), layout.window().width(), layout.window().height(), QuillTheme.BG_EDITOR);
		QuillDraw.outline(graphics, layout.window().x(), layout.window().y(), layout.window().width(), layout.window().height(), QuillTheme.BORDER_SUBTLE);

		renderTitleBar(graphics, layout.titleBar(), uiMouseX, uiMouseY);
		renderActivityBar(graphics, layout.activityBar(), uiMouseX, uiMouseY);
		if (layout.sidebar().width() > 0) {
			renderSidebar(graphics, layout.sidebar(), uiMouseX, uiMouseY);
		}
		renderTabBar(graphics, layout.tabBar(), uiMouseX, uiMouseY);
		renderEditorArea(graphics, layout.editor(), uiMouseX, uiMouseY);
		if (layout.diagnosticsBanner().height() > 0) {
			bottomPanelWidget.render(graphics, font, layout.diagnosticsBanner(), uiMouseX, uiMouseY);
		} else {
			bottomPanelWidget.clearBounds();
		}
		renderStatusBar(graphics, layout.statusBar(), uiMouseX, uiMouseY);

		if (activeQuickPick != null) {
			activeQuickPick.render(graphics, font, width, height, mouseX, mouseY);
		}
		if (activeModal != null) {
			activeModal.render(graphics, font, width, height, mouseX, mouseY);
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

		String fnId = session.activeFunctionId();
		boolean hoverRun = false;
		if (fnId != null && bar.width() > 220) {
			String runLabel = "> Run";
			int rw = font.width(runLabel) + 10;
			runTitleBtnRect = new IdeLayout.Rect(maximizeBtnRect.x() - rw - 5, bar.y() + 2, rw, btnSize);
			hoverRun = runTitleBtnRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, runTitleBtnRect.x(), runTitleBtnRect.y(), runTitleBtnRect.width(), runTitleBtnRect.height(),
					hoverRun ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
			QuillDraw.text(graphics, font, runLabel, runTitleBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
			rightBoundary = runTitleBtnRect.x();
		} else {
			runTitleBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
		}

		boolean hoverMax = maximizeBtnRect.contains(mouseX, mouseY);
		boolean hoverClose = closeBtnRect.contains(mouseX, mouseY);
		QuillDraw.fill(graphics, maximizeBtnRect.x(), maximizeBtnRect.y(), maximizeBtnRect.width(), maximizeBtnRect.height(),
				hoverMax ? QuillTheme.BG_HOVER : QuillTheme.BG_TITLE_BAR);
		QuillDraw.fill(graphics, closeBtnRect.x(), closeBtnRect.y(), closeBtnRect.width(), closeBtnRect.height(),
				hoverClose ? QuillTheme.BG_BUTTON_DANGER : QuillTheme.BG_TITLE_BAR);

		String maxGlyph = session.isMaximized() ? "[-]" : "[+]";
		QuillDraw.text(graphics, font, maxGlyph, maximizeBtnRect.x() + (maximizeBtnRect.width() - font.width(maxGlyph)) / 2, textY, QuillTheme.TEXT_PRIMARY);
		QuillDraw.text(graphics, font, "X", closeBtnRect.x() + (closeBtnRect.width() - font.width("X")) / 2, textY,
				hoverClose ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY);

		if (hoverMax || hoverClose || hoverRun) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}

		OpenTabState active = session.activeTab();
		String titleText = active != null
				? "Quill  -  datapacks/" + active.relativePath() + (active.isDirty() ? " *" : "")
				: "Quill  -  Datapack IDE (MC 26.3)";
		int availableWidth = Math.max(0, rightBoundary - (bar.x() + 8) - 6);
		String clipped = QuillDraw.clipToWidth(font, titleText, availableWidth);
		QuillDraw.text(graphics, font, clipped, bar.x() + 8, textY, QuillTheme.TEXT_PRIMARY);
	}

	protected void renderActivityBar(GuiGraphicsExtractor graphics, IdeLayout.Rect bar, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, bar.x(), bar.y(), bar.width(), bar.height(), QuillTheme.BG_ACTIVITY_BAR);
		QuillDraw.vLine(graphics, bar.right() - 1, bar.y(), bar.height(), QuillTheme.BORDER_SUBTLE);

		int iconSize = bar.width() - 2;
		explorerIconRect = new IdeLayout.Rect(bar.x(), bar.y() + 4, iconSize, 26);
		presetsIconRect = new IdeLayout.Rect(bar.x(), explorerIconRect.bottom() + 4, iconSize, 26);

		drawActivityButton(graphics, explorerIconRect, "E",
				!session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER,
				explorerIconRect.contains(mouseX, mouseY));
		drawActivityButton(graphics, presetsIconRect, "P",
				!session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.PRESETS,
				presetsIconRect.contains(mouseX, mouseY));

		if (explorerIconRect.contains(mouseX, mouseY) || presetsIconRect.contains(mouseX, mouseY)) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	private void drawActivityButton(GuiGraphicsExtractor graphics, IdeLayout.Rect rect, String label, boolean active, boolean hovered) {
		if (active) {
			QuillDraw.fill(graphics, rect.x(), rect.y(), 2, rect.height(), QuillTheme.ACCENT_BLUE);
		} else if (hovered) {
			QuillDraw.fill(graphics, rect.x(), rect.y(), rect.width(), rect.height(), QuillTheme.BG_HOVER);
		}
		int tx = rect.x() + (rect.width() - font.width(label)) / 2;
		int ty = rect.y() + (rect.height() - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, label, tx, ty, active ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);
	}

	protected void renderSidebar(GuiGraphicsExtractor graphics, IdeLayout.Rect sidebar, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, sidebar.x(), sidebar.y(), sidebar.width(), sidebar.height(), QuillTheme.BG_SIDEBAR);
		QuillDraw.vLine(graphics, sidebar.right() - 1, sidebar.y(), sidebar.height(), QuillTheme.BORDER_SUBTLE);

		if (session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER) {
			explorerTree.render(graphics, font, sidebar, mouseX, mouseY);
		} else {
			quickActions.render(graphics, font, sidebar, mouseX, mouseY);
		}
	}

	protected void renderTabBar(GuiGraphicsExtractor graphics, IdeLayout.Rect tabBar, int mouseX, int mouseY) {
		tabBarWidget.render(graphics, font, tabBar, mouseX, mouseY);
	}

	protected void renderEditorArea(GuiGraphicsExtractor graphics, IdeLayout.Rect editor, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, editor.x(), editor.y(), editor.width(), editor.height(), QuillTheme.BG_EDITOR);
		OpenTabState active = session.activeTab();
		if (active == null) {
			QuillDraw.beginClip(graphics, editor);
			int centerY = editor.y() + Math.max(16, editor.height() / 3);
			int leftX = editor.x() + 20;
			QuillDraw.textShadow(graphics, font, "Quill Datapack IDE", leftX, centerY, QuillTheme.TEXT_BRIGHT);
			QuillDraw.text(graphics, font, "Click a file in Explorer or scaffold with Quick Actions:",
					leftX, centerY + 16, QuillTheme.TEXT_MUTED);

			int btnY = centerY + 34;
			String dpLabel = "+ New Datapack";
			String fnLabel = "+ New .mcfunction";
			int dpW = font.width(dpLabel) + 14;
			int fnW = font.width(fnLabel) + 14;
			welcomeNewPackRect = new IdeLayout.Rect(leftX, btnY, dpW, 20);
			welcomeNewFnRect = new IdeLayout.Rect(leftX + dpW + 8, btnY, fnW, 20);

			boolean hoverDp = welcomeNewPackRect.contains(mouseX, mouseY);
			boolean hoverFn = welcomeNewFnRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, welcomeNewPackRect.x(), welcomeNewPackRect.y(), welcomeNewPackRect.width(), welcomeNewPackRect.height(),
					hoverDp ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
			QuillDraw.fill(graphics, welcomeNewFnRect.x(), welcomeNewFnRect.y(), welcomeNewFnRect.width(), welcomeNewFnRect.height(),
					hoverFn ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);

			int btnTextY = btnY + (20 - font.lineHeight) / 2;
			QuillDraw.text(graphics, font, dpLabel, welcomeNewPackRect.x() + 7, btnTextY, QuillTheme.TEXT_BRIGHT);
			QuillDraw.text(graphics, font, fnLabel, welcomeNewFnRect.x() + 7, btnTextY, QuillTheme.TEXT_PRIMARY);
			if (hoverDp || hoverFn) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}

			QuillDraw.text(graphics, font, "Ctrl+P Quick Open  |  Ctrl+Shift+P Commands  |  Ctrl+S Save  |  Ctrl+J Panel",
					leftX, btnY + 30, QuillTheme.TEXT_MUTED);
			QuillDraw.endClip(graphics);
		} else {
			welcomeNewPackRect = new IdeLayout.Rect(0, 0, 0, 0);
			welcomeNewFnRect = new IdeLayout.Rect(0, 0, 0, 0);
			codeEditorWidget.render(graphics, font, editor, active, mouseX, mouseY);
		}
	}

	protected void renderStatusBar(GuiGraphicsExtractor graphics, IdeLayout.Rect bar, int mouseX, int mouseY) {
		ReloadReport report = session.lastReloadReport();
		boolean hasErrors = report != null && report.hasErrors();
		int errorCount = hasErrors ? report.diagnostics().size() : 0;
		int bg = QuillTheme.BG_STATUS_BAR;
		if (session.statusIsError() || hasErrors) {
			bg = QuillTheme.BG_STATUS_ERROR;
		} else if (session.hasAnyDirtyTab()) {
			bg = QuillTheme.BG_STATUS_DIRTY;
		}
		QuillDraw.fill(graphics, bar.x(), bar.y(), bar.width(), bar.height(), bg);

		int textY = bar.y() + (bar.height() - font.lineHeight) / 2;
		String saveLabel = "Save & Reload (Ctrl+S)";
		String reloadLabel = "Reload";
		String problemsLabel = "!" + errorCount;
		String outputLabel = "Output";

		int saveW = font.width(saveLabel) + 10;
		int reloadW = font.width(reloadLabel) + 10;
		int probW = font.width(problemsLabel) + 10;
		int outW = font.width(outputLabel) + 10;

		saveReloadBtnRect = new IdeLayout.Rect(bar.x() + 4, bar.y() + 1, saveW, bar.height() - 2);
		reloadBtnRect = new IdeLayout.Rect(saveReloadBtnRect.right() + 4, bar.y() + 1, reloadW, bar.height() - 2);
		statusProblemsBtnRect = new IdeLayout.Rect(reloadBtnRect.right() + 4, bar.y() + 1, probW, bar.height() - 2);
		statusOutputBtnRect = new IdeLayout.Rect(statusProblemsBtnRect.right() + 4, bar.y() + 1, outW, bar.height() - 2);

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

		QuillDraw.text(graphics, font, saveLabel, saveReloadBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, reloadLabel, reloadBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, problemsLabel, statusProblemsBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, outputLabel, statusOutputBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);

		OpenTabState active = session.activeTab();
		String rightInfo = active != null
				? session.backendLabel() + " | Ln " + active.buffer().oneBasedLine() + ", Col " + active.buffer().oneBasedColumn() + " | UTF-8"
				: session.backendLabel() + " | UTF-8";
		int rightW = font.width(rightInfo);
		int rightX = bar.right() - rightW - 8;
		QuillDraw.text(graphics, font, rightInfo, rightX, textY, QuillTheme.TEXT_BRIGHT);

		int statusX = statusOutputBtnRect.right() + 10;
		int statusMaxW = Math.max(0, rightX - statusX - 10);
		if (statusMaxW > 12) {
			String statusClipped = QuillDraw.clipToWidth(font, session.statusMessage(), statusMaxW);
			QuillDraw.text(graphics, font, statusClipped, statusX, textY, QuillTheme.TEXT_BRIGHT);
		}

		if (hoverSave || hoverReload || hoverProb || hoverOut) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	private List<QuickPickOverlay.PickItem> buildCommandPaletteItems() {
		List<QuickPickOverlay.PickItem> items = new ArrayList<>();
		items.add(new QuickPickOverlay.PickItem(
				"save_reload", "File: Save & Reload Datapacks", "Save active file and run /reload", "Ctrl+S",
				"File: Save & Reload Datapacks save reload", () -> session.saveActiveTab(true)));
		items.add(new QuickPickOverlay.PickItem(
				"save_only", "File: Save Without Reload", "Write active buffer to disk without reloading", "",
				"File: Save Without Reload save", () -> session.saveActiveTab(false)));
		items.add(new QuickPickOverlay.PickItem(
				"quick_open", "File: Quick Open...", "Fuzzy-search files across datapacks/", "Ctrl+P",
				"File: Quick Open go to file switcher", () -> openQuickPick(QuickPickOverlay.Mode.FILES)));

		String activeFn = session.activeFunctionId();
		if (activeFn != null) {
			items.add(new QuickPickOverlay.PickItem(
					"run_function", "Datapack: Run Current Function", "/function " + activeFn, "",
					"Datapack: Run Current Function execute run " + activeFn, session::runCurrentFunction));
		}

		items.add(new QuickPickOverlay.PickItem(
				"reload", "Datapack: Reload Datapacks", "Trigger world datapack reload", "",
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
				"refresh_tree", "Explorer: Refresh File Tree", "Rescan datapacks/ folder", "",
				"Explorer: Refresh File Tree rescan", session::refreshTree));

		if (session.activeTab() != null) {
			items.add(new QuickPickOverlay.PickItem(
					"close_tab", "Editor: Close Active Tab", session.activeTab().fileName(), "Ctrl+W",
					"Editor: Close Active Tab", () -> tabBarWidget.requestCloseTab(session.activeTabIndex())));
		}

		items.add(new QuickPickOverlay.PickItem(
				"toggle_sidebar", "View: Toggle Sidebar", "Show or hide left sidebar", "Ctrl+B",
				"View: Toggle Sidebar collapse", session::toggleSidebarCollapsed));
		items.add(new QuickPickOverlay.PickItem(
				"toggle_panel", "View: Toggle Bottom Panel", "Show or hide Problems / Output panel", "Ctrl+J",
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
				"view_presets", "View: Show Presets & Quick Actions", "Focus Presets sidebar", "",
				"View: Show Presets Quick Actions templates", () -> session.setSidebarView(ClientEditorSession.SidebarView.PRESETS)));
		items.add(new QuickPickOverlay.PickItem(
				"toggle_maximize", "View: Toggle Maximize IDE Window", "Switch docked vs full-screen mode", "",
				"View: Toggle Maximize IDE Window fullscreen", session::toggleMaximized));

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
					() -> session.openFile(relPath)
			));
		}
		return items;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (activeModal != null) {
			boolean handled = activeModal.mouseClicked(event);
			syncTextInputState();
			return handled;
		}
		if (activeQuickPick != null) {
			boolean handled = activeQuickPick.mouseClicked(event);
			syncTextInputState();
			return handled;
		}
		if (tabBarWidget.mouseClicked(event)) {
			return true;
		}
		if (bottomPanelWidget.mouseClicked(event)) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			double mx = event.x();
			double my = event.y();
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
			if (explorerIconRect.contains(mx, my)) {
				if (!session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER) {
					session.toggleSidebarCollapsed();
				} else {
					session.setSidebarView(ClientEditorSession.SidebarView.EXPLORER);
				}
				return true;
			}
			if (presetsIconRect.contains(mx, my)) {
				if (!session.isSidebarCollapsed() && session.sidebarView() == ClientEditorSession.SidebarView.PRESETS) {
					session.toggleSidebarCollapsed();
				} else {
					session.setSidebarView(ClientEditorSession.SidebarView.PRESETS);
				}
				return true;
			}
			if (lastSidebarRect.contains(mx, my)) {
				boolean handled = session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER
						? explorerTree.mouseClicked(event)
						: quickActions.mouseClicked(event);
				if (handled) {
					return true;
				}
			}
			if (welcomeNewPackRect.contains(mx, my)) {
				quickActions.promptNewDatapack();
				return true;
			}
			if (welcomeNewFnRect.contains(mx, my)) {
				quickActions.promptNewFunction();
				return true;
			}
			OpenTabState active = session.activeTab();
			if (active != null && codeEditorWidget.mouseClicked(event, font, active)) {
				return true;
			}
			if (saveReloadBtnRect.contains(mx, my)) {
				session.saveActiveTab(true);
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
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (activeModal != null || activeQuickPick != null) {
			return true;
		}
		OpenTabState active = session.activeTab();
		if (active != null && codeEditorWidget.mouseDragged(event, font, active)) {
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		codeEditorWidget.mouseReleased(event);
		if (activeModal != null || activeQuickPick != null) {
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (activeModal != null) {
			return true;
		}
		if (activeQuickPick != null) {
			return activeQuickPick.mouseScrolled(scrollY);
		}
		if (bottomPanelWidget.mouseScrolled(mouseX, mouseY, scrollY)) {
			return true;
		}
		if (tabBarWidget.mouseScrolled(mouseX, mouseY, scrollY)) {
			return true;
		}
		if (lastSidebarRect.contains(mouseX, mouseY)) {
			boolean handled = session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER
					? explorerTree.mouseScrolled(mouseX, mouseY, scrollY)
					: quickActions.mouseScrolled(mouseX, mouseY, scrollY);
			if (handled) {
				return true;
			}
		}
		OpenTabState active = session.activeTab();
		if (active != null && codeEditorWidget.mouseScrolled(mouseX, mouseY, scrollX, scrollY, font, active)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
		if (event.key() == InputConstants.KEY_F1 && !event.hasControlDownWithQuirk() && !event.hasAltDown()) {
			openQuickPick(QuickPickOverlay.Mode.COMMANDS);
			return true;
		}
		if (event.hasControlDownWithQuirk() && !event.hasAltDown()) {
			int sc = event.shortcutKey();
			if (sc == 'p') {
				openQuickPick(event.hasShiftDown() ? QuickPickOverlay.Mode.COMMANDS : QuickPickOverlay.Mode.FILES);
				return true;
			}
			if (!event.hasShiftDown() && sc == InputConstants.KEYCODE_B) {
				session.toggleSidebarCollapsed();
				return true;
			}
			if (!event.hasShiftDown() && sc == 'j') {
				session.toggleBottomPanel();
				return true;
			}
			if (!event.hasShiftDown() && sc == 's') {
				session.saveActiveTab(true);
				return true;
			}
			if (!event.hasShiftDown() && sc == InputConstants.KEYCODE_W) {
				if (session.activeTab() != null) {
					tabBarWidget.requestCloseTab(session.activeTabIndex());
				}
				return true;
			}
		}
		OpenTabState active = session.activeTab();
		if (active != null && codeEditorWidget.keyPressed(event, font, active)) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (activeModal != null) {
			return activeModal.charTyped(event);
		}
		if (activeQuickPick != null) {
			return activeQuickPick.charTyped(event);
		}
		OpenTabState active = session.activeTab();
		if (active != null && codeEditorWidget.charTyped(event, font, active)) {
			return true;
		}
		return super.charTyped(event);
	}
}
