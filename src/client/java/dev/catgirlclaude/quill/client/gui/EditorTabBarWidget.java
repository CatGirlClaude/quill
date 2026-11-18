package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.OpenTabState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Horizontal scrollable tab strip supporting active tab highlight, dirty markers, and close buttons.
 */
public final class EditorTabBarWidget {
	private record TabBounds(int index, IdeLayout.Rect tabRect, IdeLayout.Rect closeRect) {
	}

	private final ClientEditorSession session;
	private final Consumer<ModalPromptOverlay> modalOpener;
	private final Runnable modalCloser;
	private final List<TabBounds> renderedTabs = new ArrayList<>();
	private IdeLayout.Rect lastBarRect = new IdeLayout.Rect(0, 0, 0, 0);
	private int scrollX = 0;
	private int lastActiveTabIndex = -1;

	public EditorTabBarWidget(
			ClientEditorSession session,
			Consumer<ModalPromptOverlay> modalOpener,
			Runnable modalCloser
	) {
		this.session = session;
		this.modalOpener = modalOpener;
		this.modalCloser = modalCloser;
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect bar, int mouseX, int mouseY) {
		lastBarRect = bar;
		renderedTabs.clear();

		QuillDraw.fill(graphics, bar.x(), bar.y(), bar.width(), bar.height(), QuillTheme.BG_TAB_BAR);
		QuillDraw.hLine(graphics, bar.x(), bar.width(), bar.bottom() - 1, QuillTheme.BORDER_SUBTLE);

		List<OpenTabState> tabs = session.openTabs();
		if (tabs.isEmpty()) {
			lastActiveTabIndex = -1;
			QuillDraw.text(graphics, font, "No open files", bar.x() + 10,
					bar.y() + (bar.height() - font.lineHeight) / 2, QuillTheme.TEXT_MUTED);
			return;
		}

		int totalWidth = 0;
		int activeStart = 0;
		int activeEnd = 0;
		int activeIdx = session.activeTabIndex();
		for (int i = 0; i < tabs.size(); i++) {
			int w = computeTabWidth(font, tabs.get(i));
			if (i == activeIdx) {
				activeStart = totalWidth;
				activeEnd = totalWidth + w;
			}
			totalWidth += w;
		}
		if (activeIdx != lastActiveTabIndex && activeIdx >= 0 && bar.width() > 0) {
			if (activeStart < scrollX) {
				scrollX = activeStart;
			} else if (activeEnd > scrollX + bar.width()) {
				scrollX = activeEnd - bar.width();
			}
			lastActiveTabIndex = activeIdx;
		}
		int maxScroll = Math.max(0, totalWidth - bar.width());
		scrollX = Math.max(0, Math.min(maxScroll, scrollX));

		QuillDraw.beginClip(graphics, bar);
		int x = bar.x() - scrollX;
		int textY = bar.y() + (bar.height() - font.lineHeight) / 2;

		for (int i = 0; i < tabs.size(); i++) {
			OpenTabState tab = tabs.get(i);
			int tabW = computeTabWidth(font, tab);
			IdeLayout.Rect tabRect = new IdeLayout.Rect(x, bar.y(), tabW, bar.height() - 1);
			IdeLayout.Rect closeRect = new IdeLayout.Rect(tabRect.right() - 16, bar.y() + 3, 13, bar.height() - 7);
			renderedTabs.add(new TabBounds(i, tabRect, closeRect));

			boolean active = i == session.activeTabIndex();
			boolean hovered = bar.contains(mouseX, mouseY) && tabRect.contains(mouseX, mouseY);
			boolean closeHovered = bar.contains(mouseX, mouseY) && closeRect.contains(mouseX, mouseY);

			int bg = active ? QuillTheme.BG_TAB_ACTIVE : (hovered ? QuillTheme.BG_HOVER : QuillTheme.BG_TAB_INACTIVE);
			QuillDraw.fill(graphics, tabRect.x(), tabRect.y(), tabRect.width(), tabRect.height(), bg);
			QuillDraw.vLine(graphics, tabRect.right() - 1, tabRect.y(), tabRect.height(), QuillTheme.BORDER_SUBTLE);
			if (active) {
				QuillDraw.fill(graphics, tabRect.x(), tabRect.y(), tabRect.width(), 2, QuillTheme.ACCENT_BLUE);
			}

			String title = tab.fileName();
			int titleColor = active ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY;
			QuillDraw.text(graphics, font, title, tabRect.x() + 8, textY, titleColor);

			if (tab.isDirty()) {
				int dotX = tabRect.x() + 8 + font.width(title) + 4;
				QuillDraw.text(graphics, font, "*", dotX, textY, QuillTheme.TEXT_DIRTY);
			}

			if (closeHovered) {
				QuillDraw.fill(graphics, closeRect.x(), closeRect.y(), closeRect.width(), closeRect.height(), QuillTheme.BG_BUTTON_SECONDARY);
			}
			int xTextX = closeRect.x() + (closeRect.width() - font.width("x")) / 2;
			QuillDraw.text(graphics, font, "x", xTextX, textY,
					closeHovered ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

			if (hovered || closeHovered) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}
			x += tabW;
		}
		QuillDraw.endClip(graphics);
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (!lastBarRect.contains(event.x(), event.y())) {
			return false;
		}
		for (TabBounds tb : renderedTabs) {
			if (tb.tabRect().contains(event.x(), event.y())) {
				if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
					requestCloseTab(tb.index());
					return true;
				}
				if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
					if (tb.closeRect().contains(event.x(), event.y())) {
						requestCloseTab(tb.index());
					} else {
						session.setActiveTab(tb.index());
					}
					return true;
				}
			}
		}
		return false;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!lastBarRect.contains(mouseX, mouseY)) {
			return false;
		}
		scrollX = Math.max(0, scrollX - (int) (scrollY * 28));
		return true;
	}

	public void requestCloseTab(int index) {
		if (index < 0 || index >= session.openTabs().size()) {
			return;
		}
		OpenTabState tab = session.openTabs().get(index);
		if (!tab.isDirty()) {
			session.closeTab(index);
			return;
		}
		modalOpener.accept(ModalPromptOverlay.confirm(
				"Unsaved Changes",
				"Discard unsaved changes in '" + tab.fileName() + "'?",
				"Discard",
				true,
				() -> {
					modalCloser.run();
					session.closeTab(index);
				},
				modalCloser
		));
	}

	private static int computeTabWidth(Font font, OpenTabState tab) {
		int dirtyW = tab.isDirty() ? 10 : 0;
		return Math.max(64, font.width(tab.fileName()) + dirtyW + 30);
	}
}
