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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Horizontal VS Code-style scrollable tab strip with colored file-type badges,
 * dirty dot-to-close hover transition, active top accent bar, and integrated Settings tab.
 */
public final class EditorTabBarWidget {
	private record TabBounds(int index, IdeLayout.Rect tabRect, IdeLayout.Rect closeRect) {
	}

	private final ClientEditorSession session;
	private final Consumer<ModalPromptOverlay> modalOpener;
	private final Runnable modalCloser;
	private final List<TabBounds> renderedTabs = new ArrayList<>();
	private IdeLayout.Rect lastBarRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect settingsTabRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect settingsCloseRect = new IdeLayout.Rect(0, 0, 0, 0);
	private int scrollX = 0;
	private int lastActiveTabIndex = -1;

	private BooleanSupplier settingsOpenSupplier = () -> false;
	private Runnable onSelectSettings = () -> {
	};
	private Runnable onCloseSettings = () -> {
	};
	private Runnable onSelectFileTab = () -> {
	};

	public EditorTabBarWidget(
			ClientEditorSession session,
			Consumer<ModalPromptOverlay> modalOpener,
			Runnable modalCloser
	) {
		this.session = session;
		this.modalOpener = modalOpener;
		this.modalCloser = modalCloser;
	}

	public void setSettingsHooks(
			BooleanSupplier settingsOpenSupplier,
			Runnable onSelectSettings,
			Runnable onCloseSettings,
			Runnable onSelectFileTab
	) {
		this.settingsOpenSupplier = settingsOpenSupplier != null ? settingsOpenSupplier : () -> false;
		this.onSelectSettings = onSelectSettings != null ? onSelectSettings : () -> {
		};
		this.onCloseSettings = onCloseSettings != null ? onCloseSettings : () -> {
		};
		this.onSelectFileTab = onSelectFileTab != null ? onSelectFileTab : () -> {
		};
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect bar, int mouseX, int mouseY) {
		lastBarRect = bar;
		renderedTabs.clear();
		settingsTabRect = new IdeLayout.Rect(0, 0, 0, 0);
		settingsCloseRect = new IdeLayout.Rect(0, 0, 0, 0);

		QuillDraw.fill(graphics, bar.x(), bar.y(), bar.width(), bar.height(), QuillTheme.BG_TAB_BAR);
		QuillDraw.hLine(graphics, bar.x(), bar.width(), bar.bottom() - 1, QuillTheme.BORDER_SUBTLE);

		boolean settingsOpen = settingsOpenSupplier.getAsBoolean();
		List<OpenTabState> tabs = session.openTabs();
		if (tabs.isEmpty() && !settingsOpen) {
			lastActiveTabIndex = -1;
			QuillDraw.text(graphics, font, "Welcome", bar.x() + 10,
					bar.y() + (bar.height() - font.lineHeight) / 2, QuillTheme.TEXT_MUTED);
			return;
		}

		int settingsW = settingsOpen ? computeSettingsTabWidth(font) : 0;
		int totalWidth = settingsW;
		int activeStart = 0;
		int activeEnd = 0;
		int activeIdx = settingsOpen ? -1 : session.activeTabIndex();
		for (int i = 0; i < tabs.size(); i++) {
			int w = computeTabWidth(font, tabs.get(i));
			if (i == activeIdx) {
				activeStart = totalWidth - settingsW;
				activeEnd = activeStart + w;
			}
			totalWidth += w;
		}
		if (settingsOpen) {
			activeStart = totalWidth - settingsW;
			activeEnd = totalWidth;
		}
		if (activeIdx != lastActiveTabIndex && bar.width() > 0) {
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
			IdeLayout.Rect closeRect = new IdeLayout.Rect(tabRect.right() - 17, bar.y() + 4, 13, bar.height() - 8);
			renderedTabs.add(new TabBounds(i, tabRect, closeRect));

			boolean active = !settingsOpen && i == session.activeTabIndex();
			boolean hovered = bar.contains(mouseX, mouseY) && tabRect.contains(mouseX, mouseY);
			boolean closeHovered = bar.contains(mouseX, mouseY) && closeRect.contains(mouseX, mouseY);

			int bg = active ? QuillTheme.BG_TAB_ACTIVE : (hovered ? QuillTheme.BG_HOVER : QuillTheme.BG_TAB_INACTIVE);
			QuillDraw.fill(graphics, tabRect.x(), tabRect.y(), tabRect.width(), tabRect.height(), bg);
			QuillDraw.vLine(graphics, tabRect.right() - 1, tabRect.y(), tabRect.height(), QuillTheme.BORDER_SUBTLE);
			if (active) {
				QuillDraw.fill(graphics, tabRect.x(), tabRect.y(), tabRect.width(), 2, QuillTheme.ACCENT_BLUE);
			}

			int iconW = QuillDraw.drawFileTypeIcon(graphics, font, tab.fileName(), tabRect.x() + 7, bar.y(), bar.height() - 1);
			int titleX = tabRect.x() + 7 + iconW + 4;
			String title = tab.fileName();
			int titleColor = active ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY;
			QuillDraw.text(graphics, font, title, titleX, textY, titleColor);

			// Neutral '*' dirty indicator -> geometric close X on hover
			if (tab.isDirty() && !hovered && !closeHovered) {
				String glyph = dirtyIndicatorGlyph();
				int glyphX = closeRect.x() + (closeRect.width() - QuillDraw.textWidth(font, glyph)) / 2;
				QuillDraw.text(graphics, font, glyph, glyphX, textY, active ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY);
			} else if (active || hovered || closeHovered) {
				if (closeHovered) {
					QuillDraw.fill(graphics, closeRect.x(), closeRect.y(), closeRect.width(), closeRect.height(), QuillTheme.BG_BUTTON_SECONDARY);
				}
				QuillDraw.drawCloseX(graphics, closeRect.x() + 3, closeRect.y() + (closeRect.height() - 7) / 2, 7,
						closeHovered ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);
			}

			if (hovered || closeHovered) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}
			x += tabW;
		}

		if (settingsOpen) {
			IdeLayout.Rect tabRect = new IdeLayout.Rect(x, bar.y(), settingsW, bar.height() - 1);
			IdeLayout.Rect closeRect = new IdeLayout.Rect(tabRect.right() - 17, bar.y() + 4, 13, bar.height() - 8);
			settingsTabRect = tabRect;
			settingsCloseRect = closeRect;

			boolean hovered = bar.contains(mouseX, mouseY) && tabRect.contains(mouseX, mouseY);
			boolean closeHovered = bar.contains(mouseX, mouseY) && closeRect.contains(mouseX, mouseY);

			QuillDraw.fill(graphics, tabRect.x(), tabRect.y(), tabRect.width(), tabRect.height(), QuillTheme.BG_TAB_ACTIVE);
			QuillDraw.vLine(graphics, tabRect.right() - 1, tabRect.y(), tabRect.height(), QuillTheme.BORDER_SUBTLE);
			QuillDraw.fill(graphics, tabRect.x(), tabRect.y(), tabRect.width(), 2, QuillTheme.ACCENT_BLUE);

			QuillDraw.drawGearIcon(graphics, tabRect.x() + 12, bar.y() + (bar.height() - 1) / 2, QuillTheme.ACCENT_BLUE, QuillTheme.BG_TAB_ACTIVE);
			int labelX = tabRect.x() + 21;
			QuillDraw.text(graphics, font, "Settings", labelX, textY, QuillTheme.TEXT_BRIGHT);

			if (closeHovered) {
				QuillDraw.fill(graphics, closeRect.x(), closeRect.y(), closeRect.width(), closeRect.height(), QuillTheme.BG_BUTTON_SECONDARY);
			}
			QuillDraw.drawCloseX(graphics, closeRect.x() + 3, closeRect.y() + (closeRect.height() - 7) / 2, 7,
					closeHovered ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);

			if (hovered || closeHovered) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}
		}

		QuillDraw.endClip(graphics);
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (!lastBarRect.contains(event.x(), event.y())) {
			return false;
		}
		if (settingsTabRect.contains(event.x(), event.y())) {
			if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE
					|| (event.button() == InputConstants.MOUSE_BUTTON_LEFT && settingsCloseRect.contains(event.x(), event.y()))) {
				onCloseSettings.run();
				return true;
			}
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				onSelectSettings.run();
				return true;
			}
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
						onSelectFileTab.run();
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
		int badgeW = QuillDraw.fileTypeIconWidth(font, tab.fileName()) + 4;
		return Math.max(72, badgeW + QuillDraw.textWidth(font, tab.fileName()) + 30);
	}

	private static int computeSettingsTabWidth(Font font) {
		return Math.max(82, 16 + QuillDraw.textWidth(font, "Settings") + 28);
	}

	static String dirtyIndicatorGlyph() {
		return "*";
	}
}
