package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Reusable VS Code-style floating context menu overlay supporting labeled action items,
 * optional shortcut hints, disabled states, separator rows, keyboard navigation, and viewport clamping.
 */
public final class ContextMenuOverlay {
	public static final int ROW_HEIGHT = 17;
	public static final int SEPARATOR_HEIGHT = 7;
	public static final int PADDING_Y = 4;
	private static final int MIN_WIDTH = 148;
	private static final int MAX_WIDTH = 280;

	public sealed interface MenuEntry permits ActionItem, Separator {
	}

	public record ActionItem(String label, String shortcutHint, boolean enabled, Runnable action) implements MenuEntry {
	}

	public record Separator() implements MenuEntry {
	}

	private record RowHit(int entryIndex, ActionItem item, IdeLayout.Rect rect) {
	}

	private final int anchorX;
	private final int anchorY;
	private final List<MenuEntry> entries;
	private final Runnable onDismiss;

	private IdeLayout.Rect panelRect = new IdeLayout.Rect(0, 0, 0, 0);
	private final List<RowHit> rowHits = new ArrayList<>();
	private int keyboardSelectedIndex = -1;
	private float revealProgress;
	private long lastFrameNs = 0L;

	public ContextMenuOverlay(int anchorX, int anchorY, List<MenuEntry> entries, Runnable onDismiss) {
		this.anchorX = anchorX;
		this.anchorY = anchorY;
		this.entries = entries == null ? List.of() : List.copyOf(entries);
		this.onDismiss = Objects.requireNonNull(onDismiss, "onDismiss");
		this.revealProgress = QuillClientConfig.current().animationsEnabled() ? 0.0f : 1.0f;
	}

	public IdeLayout.Rect panelRect() {
		return panelRect;
	}

	public static IdeLayout.Rect computeBounds(
			int anchorX,
			int anchorY,
			List<MenuEntry> entries,
			ToIntFunction<String> textWidth,
			int viewportWidth,
			int viewportHeight
	) {
		int maxLabelW = 72;
		int maxShortcutW = 0;
		boolean anyShortcut = false;
		int totalH = PADDING_Y * 2;
		for (MenuEntry entry : entries) {
			switch (entry) {
				case ActionItem item -> {
					maxLabelW = Math.max(maxLabelW, textWidth.applyAsInt(item.label()));
					if (item.shortcutHint() != null && !item.shortcutHint().isEmpty()) {
						anyShortcut = true;
						maxShortcutW = Math.max(maxShortcutW, textWidth.applyAsInt(item.shortcutHint()));
					}
					totalH += ROW_HEIGHT;
				}
				case Separator ignored -> totalH += SEPARATOR_HEIGHT;
			}
		}

		int rawW = 18 + maxLabelW + (anyShortcut ? 18 + maxShortcutW : 12);
		int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, rawW));
		width = Math.min(width, Math.max(80, viewportWidth - 8));
		int height = Math.max(ROW_HEIGHT + PADDING_Y * 2, Math.min(totalH, Math.max(40, viewportHeight - 8)));

		int x = anchorX;
		if (x + width > viewportWidth - 4) {
			x = anchorX - width;
		}
		x = Math.max(4, Math.min(Math.max(4, viewportWidth - width - 4), x));

		int y = anchorY;
		if (y + height > viewportHeight - 4) {
			y = anchorY - height;
		}
		y = Math.max(4, Math.min(Math.max(4, viewportHeight - height - 4), y));

		return new IdeLayout.Rect(x, y, width, height);
	}

	public IdeLayout.Rect computeLayoutBounds(ToIntFunction<String> textWidth, int viewportWidth, int viewportHeight) {
		panelRect = computeBounds(anchorX, anchorY, entries, textWidth, viewportWidth, viewportHeight);
		rebuildRowHits();
		return panelRect;
	}

	private void rebuildRowHits() {
		rowHits.clear();
		int y = panelRect.y() + PADDING_Y;
		for (int i = 0; i < entries.size(); i++) {
			MenuEntry entry = entries.get(i);
			if (entry instanceof ActionItem item) {
				if (y + ROW_HEIGHT <= panelRect.bottom()) {
					rowHits.add(new RowHit(i, item, new IdeLayout.Rect(panelRect.x() + 2, y, panelRect.width() - 4, ROW_HEIGHT)));
				}
				y += ROW_HEIGHT;
			} else {
				y += SEPARATOR_HEIGHT;
			}
		}
	}

	public void render(GuiGraphicsExtractor graphics, Font font, int viewportWidth, int viewportHeight, int mouseX, int mouseY) {
		long now = System.nanoTime();
		float dt = lastFrameNs == 0L ? 0.016f : Math.min(0.1f, Math.max(0.0f, (now - lastFrameNs) / 1_000_000_000.0f));
		lastFrameNs = now;
		revealProgress = QuillAnimator.stepTowards(
				revealProgress, 1.0f, dt, 22.0f, QuillClientConfig.current().animationsEnabled());

		IdeLayout.Rect target = computeLayoutBounds(s -> QuillDraw.textWidth(font, s), viewportWidth, viewportHeight);
		int currentH = revealProgress >= 0.999f
				? target.height()
				: Math.max(ROW_HEIGHT + PADDING_Y * 2, Math.round(target.height() * revealProgress));

		// Soft desktop shadow + popup container
		QuillDraw.fill(graphics, target.x() - 2, target.y() + 1, target.width() + 5, currentH + 4, QuillTheme.Metrics.SHADOW_OUTER);
		QuillDraw.fill(graphics, target.x() + 1, target.y() + 2, target.width() + 1, currentH + 2, QuillTheme.Metrics.SHADOW_INNER);
		QuillDraw.fill(graphics, target.x(), target.y(), target.width(), currentH, QuillTheme.BG_POPUP);
		QuillDraw.outline(graphics, target.x(), target.y(), target.width(), currentH, QuillTheme.BORDER_SUBTLE);

		int y = target.y() + PADDING_Y;
		int rowIdx = 0;
		for (int i = 0; i < entries.size(); i++) {
			MenuEntry entry = entries.get(i);
			switch (entry) {
				case Separator ignored -> {
					if (y + SEPARATOR_HEIGHT <= target.y() + currentH) {
						QuillDraw.hLine(graphics, target.x() + 6, target.width() - 12, y + SEPARATOR_HEIGHT / 2, QuillTheme.dividerSoft());
					}
					y += SEPARATOR_HEIGHT;
				}
				case ActionItem item -> {
					if (y + ROW_HEIGHT <= target.y() + currentH - 1 && rowIdx < rowHits.size()) {
						IdeLayout.Rect rowRect = rowHits.get(rowIdx).rect();
						boolean hovered = rowRect.contains(mouseX, mouseY);
						boolean selected = (hovered || keyboardSelectedIndex == i) && item.enabled();
						if (hovered && item.enabled()) {
							keyboardSelectedIndex = i;
						}
						if (selected) {
							QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.BG_POPUP_SELECTED);
						}
						if (hovered) {
							QuillDraw.requestCursor(graphics, item.enabled() ? CursorTypes.POINTING_HAND : CursorTypes.ARROW);
						}

						int ty = rowRect.y() + (ROW_HEIGHT - font.lineHeight) / 2;
						int textColor = item.enabled()
								? (selected ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY)
								: QuillTheme.TEXT_MUTED;
						String shortcut = item.shortcutHint() == null ? "" : item.shortcutHint();
						int rightLimit = rowRect.right() - 8;
						if (!shortcut.isEmpty()) {
							int sw = QuillDraw.textWidth(font, shortcut);
							QuillDraw.text(graphics, font, shortcut, rightLimit - sw, ty, QuillTheme.TEXT_MUTED);
							rightLimit -= sw + 10;
						}
						int labelMaxW = Math.max(20, rightLimit - (rowRect.x() + 8));
						QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, item.label(), labelMaxW),
								rowRect.x() + 8, ty, textColor);
					}
					rowIdx++;
					y += ROW_HEIGHT;
				}
			}
		}
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (!panelRect.contains(event.x(), event.y())) {
			onDismiss.run();
			return false;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		for (RowHit hit : rowHits) {
			if (hit.rect().contains(event.x(), event.y())) {
				if (hit.item().enabled()) {
					onDismiss.run();
					if (hit.item().action() != null) {
						hit.item().action().run();
					}
				}
				return true;
			}
		}
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			onDismiss.run();
			return true;
		}
		if (event.isUp() || event.isDown()) {
			moveSelection(event.isDown() ? 1 : -1);
			return true;
		}
		if (event.isConfirmation()) {
			if (keyboardSelectedIndex >= 0 && keyboardSelectedIndex < entries.size()
					&& entries.get(keyboardSelectedIndex) instanceof ActionItem item
					&& item.enabled()) {
				onDismiss.run();
				if (item.action() != null) {
					item.action().run();
				}
			}
			return true;
		}
		return true;
	}

	private void moveSelection(int delta) {
		if (entries.isEmpty()) {
			return;
		}
		int n = entries.size();
		int idx = keyboardSelectedIndex < 0 ? (delta > 0 ? -1 : 0) : keyboardSelectedIndex;
		for (int step = 0; step < n; step++) {
			idx = (idx + delta + n) % n;
			if (entries.get(idx) instanceof ActionItem item && item.enabled()) {
				keyboardSelectedIndex = idx;
				return;
			}
		}
	}
}
