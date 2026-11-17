package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.OpenTabState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Non-pausing VS Code Dark+ IDE screen rendered directly over the live Minecraft world.
 */
public class QuillIdeScreen extends Screen {
	private final ClientEditorSession session;

	private IdeLayout.Rect maximizeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect closeBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect explorerIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect presetsIconRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect saveReloadBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect reloadBtnRect = new IdeLayout.Rect(0, 0, 0, 0);

	public QuillIdeScreen(ClientEditorSession session) {
		super(Component.literal("Quill IDE"));
		this.session = session;
	}

	public ClientEditorSession session() {
		return session;
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
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		ReloadReport report = session.lastReloadReport();
		boolean showDiag = report != null && report.hasErrors() && session.isDiagnosticsExpanded();
		IdeLayout layout = IdeLayout.compute(width, height, session.isMaximized(), session.isSidebarCollapsed(), showDiag);

		// Outer frame
		QuillDraw.fill(graphics, layout.window().x(), layout.window().y(), layout.window().width(), layout.window().height(), QuillTheme.BG_EDITOR);
		QuillDraw.outline(graphics, layout.window().x(), layout.window().y(), layout.window().width(), layout.window().height(), QuillTheme.BORDER_SUBTLE);

		renderTitleBar(graphics, layout.titleBar(), mouseX, mouseY);
		renderActivityBar(graphics, layout.activityBar(), mouseX, mouseY);
		if (layout.sidebar().width() > 0) {
			renderSidebar(graphics, layout.sidebar(), mouseX, mouseY);
		}
		renderTabBar(graphics, layout.tabBar(), mouseX, mouseY);
		renderEditorArea(graphics, layout.editor(), mouseX, mouseY);
		if (layout.diagnosticsBanner().height() > 0) {
			renderDiagnosticsBanner(graphics, layout.diagnosticsBanner(), report, mouseX, mouseY);
		}
		renderStatusBar(graphics, layout.statusBar(), mouseX, mouseY);
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

		QuillDraw.beginClip(graphics, sidebar);
		String heading = session.sidebarView() == ClientEditorSession.SidebarView.EXPLORER
				? "EXPLORER"
				: "QUICK ACTIONS & PRESETS";
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, heading, sidebar.width() - 16),
				sidebar.x() + 8, sidebar.y() + 6, QuillTheme.TEXT_MUTED);
		QuillDraw.hLine(graphics, sidebar.x(), sidebar.width() - 1, sidebar.y() + 19, QuillTheme.BORDER_SUBTLE);

		int packCount = session.rootTree().children().size();
		QuillDraw.text(graphics, font, packCount + " datapack(s) found", sidebar.x() + 8, sidebar.y() + 26, QuillTheme.TEXT_PRIMARY);
		QuillDraw.text(graphics, font, "Press Ctrl+B to toggle sidebar", sidebar.x() + 8, sidebar.y() + 40, QuillTheme.TEXT_MUTED);
		QuillDraw.endClip(graphics);
	}

	protected void renderTabBar(GuiGraphicsExtractor graphics, IdeLayout.Rect tabBar, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, tabBar.x(), tabBar.y(), tabBar.width(), tabBar.height(), QuillTheme.BG_TAB_BAR);
		QuillDraw.hLine(graphics, tabBar.x(), tabBar.width(), tabBar.bottom() - 1, QuillTheme.BORDER_SUBTLE);

		if (session.openTabs().isEmpty()) {
			QuillDraw.text(graphics, font, "No open files", tabBar.x() + 10,
					tabBar.y() + (tabBar.height() - font.lineHeight) / 2, QuillTheme.TEXT_MUTED);
		}
	}

	protected void renderEditorArea(GuiGraphicsExtractor graphics, IdeLayout.Rect editor, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, editor.x(), editor.y(), editor.width(), editor.height(), QuillTheme.BG_EDITOR);
		QuillDraw.beginClip(graphics, editor);
		if (session.openTabs().isEmpty()) {
			int centerY = editor.y() + Math.max(16, editor.height() / 3);
			int leftX = editor.x() + 20;
			QuillDraw.textShadow(graphics, font, "Quill Datapack IDE", leftX, centerY, QuillTheme.TEXT_BRIGHT);
			QuillDraw.text(graphics, font, "Shortcuts: Ctrl+S Save & Reload  |  Ctrl+B Toggle Sidebar  |  Esc Close",
					leftX, centerY + 16, QuillTheme.TEXT_MUTED);
			QuillDraw.text(graphics, font, "World stays running in background (non-pausing screen).",
					leftX, centerY + 30, QuillTheme.TEXT_MUTED);
		}
		QuillDraw.endClip(graphics);
	}

	protected void renderDiagnosticsBanner(GuiGraphicsExtractor graphics, IdeLayout.Rect banner, ReloadReport report, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, banner.x(), banner.y(), banner.width(), banner.height(), QuillTheme.BG_DIAGNOSTICS);
		QuillDraw.hLine(graphics, banner.x(), banner.width(), banner.y(), QuillTheme.BG_STATUS_ERROR);
		if (report != null && !report.diagnostics().isEmpty()) {
			var first = report.diagnostics().getFirst();
			String msg = "[!] " + first.file() + (first.line() > 0 ? " (Line " + first.line() + ")" : "") + ": " + first.message();
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, msg, banner.width() - 16),
					banner.x() + 8, banner.y() + 6, QuillTheme.TEXT_ERROR);
		}
	}

	protected void renderStatusBar(GuiGraphicsExtractor graphics, IdeLayout.Rect bar, int mouseX, int mouseY) {
		ReloadReport report = session.lastReloadReport();
		int bg = QuillTheme.BG_STATUS_BAR;
		if (session.statusIsError() || (report != null && report.hasErrors())) {
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
		if (hoverSave || hoverReload) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}

		OpenTabState active = session.activeTab();
		String rightInfo = active != null
				? "Ln " + active.buffer().oneBasedLine() + ", Col " + active.buffer().oneBasedColumn() + " | UTF-8"
				: "UTF-8";
		int rightW = font.width(rightInfo);
		int rightX = bar.right() - rightW - 8;
		QuillDraw.text(graphics, font, rightInfo, rightX, textY, QuillTheme.TEXT_BRIGHT);

		int statusX = reloadBtnRect.right() + 10;
		int statusMaxW = Math.max(0, rightX - statusX - 10);
		String statusClipped = QuillDraw.clipToWidth(font, session.statusMessage(), statusMaxW);
		QuillDraw.text(graphics, font, statusClipped, statusX, textY, QuillTheme.TEXT_BRIGHT);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
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
			if (saveReloadBtnRect.contains(mx, my)) {
				session.saveActiveTab(true);
				return true;
			}
			if (reloadBtnRect.contains(mx, my)) {
				session.reloadDatapacks();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.hasControlDownWithQuirk() && !event.hasAltDown() && event.shortcutKey() == InputConstants.KEYCODE_B) {
			session.toggleSidebarCollapsed();
			return true;
		}
		if (event.hasControlDownWithQuirk() && !event.hasAltDown() && event.shortcutKey() == 's') {
			session.saveActiveTab(true);
			return true;
		}
		return super.keyPressed(event);
	}
}
