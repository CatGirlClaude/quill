package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextMenuOverlayTest {

	@Test
	void boundsStayClampedInsideViewportNearBottomRightEdge() {
		List<ContextMenuOverlay.MenuEntry> entries = List.of(
				new ContextMenuOverlay.ActionItem("Open to Side", "", true, () -> {}),
				new ContextMenuOverlay.ActionItem("Run Function", "", true, () -> {}),
				new ContextMenuOverlay.Separator(),
				new ContextMenuOverlay.ActionItem("Copy Path", "Ctrl+Alt+C", true, () -> {}),
				new ContextMenuOverlay.ActionItem("Rename", "F2", true, () -> {}),
				new ContextMenuOverlay.ActionItem("Delete", "Del", true, () -> {})
		);

		IdeLayout.Rect bounds = ContextMenuOverlay.computeBounds(
				790,
				470,
				entries,
				s -> s.length() * 6,
				800,
				480
		);

		assertTrue(bounds.x() >= 4, "Menu X must stay inside left bound: " + bounds.x());
		assertTrue(bounds.y() >= 4, "Menu Y must stay inside top bound: " + bounds.y());
		assertTrue(bounds.right() <= 796, "Menu right edge must stay inside viewport: " + bounds.right());
		assertTrue(bounds.bottom() <= 476, "Menu bottom edge must stay inside viewport: " + bounds.bottom());
		assertTrue(bounds.width() >= 100);
	}

	@Test
	void clickingEnabledEntryRunsActionAndDismissesWhileDisabledIsNoOpAndEscapeDismisses() {
		AtomicInteger ranFirst = new AtomicInteger();
		AtomicInteger ranDisabled = new AtomicInteger();
		AtomicBoolean dismissed = new AtomicBoolean(false);

		List<ContextMenuOverlay.MenuEntry> entries = List.of(
				new ContextMenuOverlay.ActionItem("Rename", "F2", true, ranFirst::incrementAndGet),
				new ContextMenuOverlay.ActionItem("Reveal in File Explorer", "", false, ranDisabled::incrementAndGet),
				new ContextMenuOverlay.Separator(),
				new ContextMenuOverlay.ActionItem("Delete", "Del", true, () -> {})
		);

		ContextMenuOverlay menu = new ContextMenuOverlay(40, 40, entries, () -> dismissed.set(true));
		IdeLayout.Rect bounds = menu.computeLayoutBounds(s -> s.length() * 6, 800, 480);

		// Click disabled second row (y = bounds.y() + 4 + 17 + 4)
		int disabledY = bounds.y() + ContextMenuOverlay.PADDING_Y + ContextMenuOverlay.ROW_HEIGHT + 4;
		boolean handledDisabled = menu.mouseClicked(new MouseButtonEvent(bounds.x() + 12, disabledY, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		assertTrue(handledDisabled);
		assertEquals(0, ranDisabled.get());
		assertFalse(dismissed.get(), "Clicking disabled entry should keep menu open.");

		// Click enabled first row
		int firstY = bounds.y() + ContextMenuOverlay.PADDING_Y + 4;
		boolean handledFirst = menu.mouseClicked(new MouseButtonEvent(bounds.x() + 12, firstY, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		assertTrue(handledFirst);
		assertEquals(1, ranFirst.get());
		assertTrue(dismissed.get(), "Clicking enabled item must run action and dismiss menu.");

		// Escape dismisses
		dismissed.set(false);
		assertTrue(menu.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
		assertTrue(dismissed.get());
	}
}
