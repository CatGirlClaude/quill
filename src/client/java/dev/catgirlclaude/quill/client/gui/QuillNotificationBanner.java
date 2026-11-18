package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;

/**
 * Persistent VS Code-style top notification toast (no auto-hide, dismissed explicitly via the X button).
 */
public final class QuillNotificationBanner {
	private final ClientEditorSession session;
	private IdeLayout.Rect bannerRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect closeRect = new IdeLayout.Rect(0, 0, 0, 0);
	private ClientEditorSession.NotificationBanner lastBanner = null;
	private float revealProgress = 1.0f;
	private long lastFrameNs = 0L;

	public QuillNotificationBanner(ClientEditorSession session) {
		this.session = session;
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect windowRect, int topOffset, int mouseX, int mouseY) {
		ClientEditorSession.NotificationBanner banner = session.activeNotification();
		if (banner == null || windowRect.width() < 120) {
			bannerRect = new IdeLayout.Rect(0, 0, 0, 0);
			closeRect = new IdeLayout.Rect(0, 0, 0, 0);
			lastBanner = null;
			return;
		}

		long now = System.nanoTime();
		float dt = lastFrameNs == 0L ? 0.016f : Math.min(0.1f, Math.max(0.0f, (now - lastFrameNs) / 1_000_000_000.0f));
		lastFrameNs = now;

		if (banner != lastBanner) {
			lastBanner = banner;
			revealProgress = QuillClientConfig.current().animationsEnabled() ? 0.0f : 1.0f;
		}
		revealProgress = QuillAnimator.stepTowards(
				revealProgress, 1.0f, dt, 16.0f, QuillClientConfig.current().animationsEnabled());

		int maxW = Math.min(350, Math.max(160, windowRect.width() - 24));
		int textMaxW = Math.max(80, maxW - 36);
		List<String> lines = QuillDraw.wrapLines(font, banner.message(), textMaxW);
		int lineStep = Math.max(10, font.lineHeight + 2);
		int targetH = Math.max(24, lines.size() * lineStep + 12);

		int bx = windowRect.right() - maxW - 10;
		if (bx < windowRect.x() + 10) {
			bx = windowRect.x() + (windowRect.width() - maxW) / 2;
		}
		int finalY = topOffset + 4;
		int by = finalY - Math.round((1.0f - revealProgress) * 10.0f);

		bannerRect = new IdeLayout.Rect(bx, by, maxW, targetH);
		closeRect = new IdeLayout.Rect(bannerRect.right() - 20, bannerRect.y() + 4, 16, 16);

		int accent = switch (banner.level()) {
			case INFO -> QuillTheme.ACCENT_BLUE;
			case WARNING -> QuillTheme.TEXT_WARNING;
			case ERROR -> QuillTheme.TEXT_ERROR;
		};

		// Drop shadow + toast surface
		QuillDraw.fill(graphics, bx - 1, by + 1, maxW + 3, targetH + 3, QuillTheme.Metrics.SHADOW_OUTER);
		QuillDraw.fill(graphics, bx, by, maxW, targetH, QuillTheme.BG_POPUP);
		QuillDraw.outline(graphics, bx, by, maxW, targetH, QuillTheme.BORDER_SUBTLE);
		QuillDraw.fill(graphics, bx, by, 3, targetH, accent);

		int textY = by + 6;
		for (int i = 0; i < lines.size(); i++) {
			QuillDraw.text(graphics, font, lines.get(i), bx + 10, textY + i * lineStep, QuillTheme.TEXT_BRIGHT);
		}

		boolean hoverClose = closeRect.contains(mouseX, mouseY);
		if (hoverClose) {
			QuillDraw.fill(graphics, closeRect.x(), closeRect.y(), closeRect.width(), closeRect.height(), QuillTheme.BG_HOVER);
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
		QuillDraw.drawCloseX(graphics, closeRect.x() + 4, closeRect.y() + 4, 8,
				hoverClose ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY);
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (session.activeNotification() == null || !bannerRect.contains(event.x(), event.y())) {
			return false;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && closeRect.contains(event.x(), event.y())) {
			session.dismissNotification();
		}
		return true;
	}
}
