package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.ReloadDiagnostic;
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

	private record DiagnosticRowBounds(ReloadDiagnostic diagnostic, IdeLayout.Rect rect) {
	}

	private final ClientEditorSession session;
	private final TextInputBridge textInputBridge;
	private final FileExplorerTreeWidget explorerTree;
	private final QuickActionsAndPresetsWidget quickActions;
	private final EditorTabBarWidget tabBarWidget;
	private final CodeEditorWidget codeEditorWidget;
	private final List<DiagnosticRowBounds> diagnosticRows = new ArrayList<>();

	private ModalPromptOverlay activeModal = null;
	private boolean textInputActive = false;

	private IdeLayout.Rect maximizeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect closeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect explorerIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect presetsIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect saveReloadBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect reloadBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect statusToggleDiagRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect diagnosticsCloseRect = new IdeLayout.Rect(0, 0, 0, 0);
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
	}

	public ClientEditorSession session() {
		return session;
	}

	QuickActionsAndPresetsWidget quickActions() {
		return quickActions;
	}

	protected void openModal(ModalPromptOverlay modal) {
		this.activeModal = modal;
		syncTextInputState();
	}

	protected void closeModal() {
		this.activeModal = null;
		syncTextInputState();
	}

	private boolean wantsTextInput() {
		if (activeModal != null) {
			return activeModal.hasTextFields();
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

	@Override
	protected void init() {
		super.init();
		syncTextInputState();
	}

	@Override
	public void tick() {
		super.tick();
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
		syncTextInputState();
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		ReloadReport report = session.lastReloadReport();
		boolean showDiag = report != null && report.hasErrors() && session.isDiagnosticsExpanded();
		IdeLayout layout = IdeLayout.compute(width, height, session.isMaximized(), session.isSidebarCollapsed(), showDiag);
		lastSidebarRect = layout.sidebar();

		int uiMouseX = activeModal != null ? -1 : mouseX;
		int uiMouseY = activeModal != null ? -1 : mouseY;

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
			renderDiagnosticsBanner(graphics, layout.diagnosticsBanner(), report, uiMouseX, uiMouseY);
		} else {
			diagnosticRows.clear();
			diagnosticsCloseRect = new IdeLayout.Rect(0, 0, 0, 0);
		}
		renderStatusBar(graphics, layout.statusBar(), uiMouseX, uiMouseY);

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

		boolean hoverMax = maximizeBtnRect.contains(mouseX, mouseY);
		boolean hoverClose = closeBtnRect.contains(mouseX, mouseY);
		QuillDraw.fill(graphics, maximizeBtnRect.x(), maximizeBtnRect.y(), maximizeBtnRect.width(), maximizeBtnRect.height(),
				hoverMax ? QuillTheme.BG_HOVER : QuillTheme.BG_TITLE_BAR);
		QuillDraw.fill(graphics, closeBtnRect.x(), closeBtnRect.y(), closeBtnRect.width(), closeBtnRect.height(),
				hoverClose ? QuillTheme.BG_BUTTON_DANGER : QuillTheme.BG_TITLE_BAR);

		String maxGlyph = session.isMaximized() ? "[-]" : "[+]";
		int textY = bar.y() + (bar.height() - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, maxGlyph, maximizeBtnRect.x() + (maximizeBtnRect.width() - font.width(maxGlyph)) / 2, textY, QuillTheme.TEXT_PRIMARY);
		QuillDraw.text(graphics, font, "X", closeBtnRect.x() + (closeBtnRect.width() - font.width("X")) / 2, textY,
				hoverClose ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY);

		if (hoverMax || hoverClose) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}

		OpenTabState active = session.activeTab();
		String titleText = active != null
				? "Quill  -  datapacks/" + active.relativePath() + (active.isDirty() ? " *" : "")
				: "Quill  -  Datapack IDE (MC 26.3)";
		int availableWidth = Math.max(0, maximizeBtnRect.x() - (bar.x() + 8) - 6);
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

			QuillDraw.text(graphics, font, "Shortcuts: Ctrl+S Save & Reload  |  Ctrl+B Sidebar  |  Esc Close",
					leftX, btnY + 30, QuillTheme.TEXT_MUTED);
			QuillDraw.endClip(graphics);
		} else {
			welcomeNewPackRect = new IdeLayout.Rect(0, 0, 0, 0);
			welcomeNewFnRect = new IdeLayout.Rect(0, 0, 0, 0);
			codeEditorWidget.render(graphics, font, editor, active, mouseX, mouseY);
		}
	}

	protected void renderDiagnosticsBanner(GuiGraphicsExtractor graphics, IdeLayout.Rect banner, ReloadReport report, int mouseX, int mouseY) {
		diagnosticRows.clear();
		QuillDraw.fill(graphics, banner.x(), banner.y(), banner.width(), banner.height(), QuillTheme.BG_DIAGNOSTICS);
		QuillDraw.hLine(graphics, banner.x(), banner.width(), banner.y(), QuillTheme.BG_STATUS_ERROR);

		diagnosticsCloseRect = new IdeLayout.Rect(banner.right() - 18, banner.y() + 3, 14, 14);
		boolean hoverClose = diagnosticsCloseRect.contains(mouseX, mouseY);
		if (hoverClose) {
			QuillDraw.fill(graphics, diagnosticsCloseRect.x(), diagnosticsCloseRect.y(), diagnosticsCloseRect.width(), diagnosticsCloseRect.height(), QuillTheme.BG_HOVER);
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
		QuillDraw.text(graphics, font, "x",
				diagnosticsCloseRect.x() + (diagnosticsCloseRect.width() - font.width("x")) / 2,
				diagnosticsCloseRect.y() + (diagnosticsCloseRect.height() - font.lineHeight) / 2,
				hoverClose ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

		if (report == null || report.diagnostics().isEmpty()) {
			return;
		}

		QuillDraw.beginClip(graphics, new IdeLayout.Rect(banner.x(), banner.y() + 1, Math.max(0, diagnosticsCloseRect.x() - banner.x() - 4), banner.height() - 2));
		List<ReloadDiagnostic> diagnostics = report.diagnostics();
		int rowHeight = 13;
		int maxVisible = Math.min(3, Math.max(1, (banner.height() - 6) / rowHeight));
		int y = banner.y() + 4;
		int rowWidth = Math.max(20, diagnosticsCloseRect.x() - banner.x() - 12);

		for (int i = 0; i < Math.min(maxVisible, diagnostics.size()); i++) {
			ReloadDiagnostic diag = diagnostics.get(i);
			IdeLayout.Rect rowRect = new IdeLayout.Rect(banner.x() + 6, y, rowWidth, rowHeight);
			diagnosticRows.add(new DiagnosticRowBounds(diag, rowRect));

			boolean hovered = rowRect.contains(mouseX, mouseY);
			if (hovered) {
				QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.BG_HOVER);
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}

			String suffix = (i == maxVisible - 1 && diagnostics.size() > maxVisible)
					? "  (+" + (diagnostics.size() - maxVisible) + " more)"
					: "";
			String loc = diag.line() > 0 ? " (Line " + diag.line() + ")" : "";
			String msg = "[!] " + diag.file() + loc + ": " + diag.message() + suffix;
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, msg, rowWidth - 6),
					rowRect.x() + 3, rowRect.y() + (rowHeight - font.lineHeight) / 2, QuillTheme.TEXT_ERROR);
			y += rowHeight;
		}
		QuillDraw.endClip(graphics);
	}

	protected void renderStatusBar(GuiGraphicsExtractor graphics, IdeLayout.Rect bar, int mouseX, int mouseY) {
		ReloadReport report = session.lastReloadReport();
		boolean hasErrors = report != null && report.hasErrors();
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
		int saveW = font.width(saveLabel) + 10;
		int reloadW = font.width(reloadLabel) + 10;

		saveReloadBtnRect = new IdeLayout.Rect(bar.x() + 4, bar.y() + 1, saveW, bar.height() - 2);
		reloadBtnRect = new IdeLayout.Rect(saveReloadBtnRect.right() + 4, bar.y() + 1, reloadW, bar.height() - 2);

		boolean hoverSave = saveReloadBtnRect.contains(mouseX, mouseY);
		boolean hoverReload = reloadBtnRect.contains(mouseX, mouseY);
		QuillDraw.fill(graphics, saveReloadBtnRect.x(), saveReloadBtnRect.y(), saveReloadBtnRect.width(), saveReloadBtnRect.height(),
				hoverSave ? 0x33FFFFFF : 0x1AFFFFFF);
		QuillDraw.fill(graphics, reloadBtnRect.x(), reloadBtnRect.y(), reloadBtnRect.width(), reloadBtnRect.height(),
				hoverReload ? 0x33FFFFFF : 0x1AFFFFFF);

		QuillDraw.text(graphics, font, saveLabel, saveReloadBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, reloadLabel, reloadBtnRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);

		OpenTabState active = session.activeTab();
		String rightInfo = active != null
				? "Ln " + active.buffer().oneBasedLine() + ", Col " + active.buffer().oneBasedColumn() + " | UTF-8"
				: "UTF-8";
		int rightW = font.width(rightInfo);
		int rightX = bar.right() - rightW - 8;
		QuillDraw.text(graphics, font, rightInfo, rightX, textY, QuillTheme.TEXT_BRIGHT);

		int statusX = reloadBtnRect.right() + 10;
		int statusMaxW = Math.max(0, rightX - statusX - 10);
		statusToggleDiagRect = hasErrors && statusMaxW > 0
				? new IdeLayout.Rect(statusX - 2, bar.y() + 1, statusMaxW + 4, bar.height() - 2)
				: new IdeLayout.Rect(0, 0, 0, 0);
		String statusClipped = QuillDraw.clipToWidth(font, session.statusMessage(), statusMaxW);
		QuillDraw.text(graphics, font, statusClipped, statusX, textY, QuillTheme.TEXT_BRIGHT);

		if (hoverSave || hoverReload || (hasErrors && statusToggleDiagRect.contains(mouseX, mouseY))) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (activeModal != null) {
			return activeModal.mouseClicked(event);
		}
		if (tabBarWidget.mouseClicked(event)) {
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
			if (diagnosticsCloseRect.contains(mx, my)) {
				session.setDiagnosticsExpanded(false);
				return true;
			}
			for (DiagnosticRowBounds row : diagnosticRows) {
				if (row.rect().contains(mx, my)) {
					session.openFileAndJump(row.diagnostic().file(), row.diagnostic().line());
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
			if (statusToggleDiagRect.contains(mx, my)) {
				session.setDiagnosticsExpanded(!session.isDiagnosticsExpanded());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (activeModal != null) {
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
		if (activeModal != null) {
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (activeModal != null) {
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
			return activeModal.keyPressed(event);
		}
		if (event.hasControlDownWithQuirk() && !event.hasAltDown() && event.shortcutKey() == InputConstants.KEYCODE_B) {
			session.toggleSidebarCollapsed();
			return true;
		}
		if (event.hasControlDownWithQuirk() && !event.hasAltDown() && event.shortcutKey() == 's') {
			session.saveActiveTab(true);
			return true;
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
		OpenTabState active = session.activeTab();
		if (active != null && codeEditorWidget.charTyped(event, font, active)) {
			return true;
		}
		return super.charTyped(event);
	}
}
