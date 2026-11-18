package dev.catgirlclaude.quill.client.gui;

import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.client.session.OpenTabState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickPickOverlayTest {

	@Test
	void commandCenterPillOmitsRedundantQuickOpenShortcut() {
		String emptyLabel = QuillIdeScreen.formatCommandCenterText(null, false);
		assertEquals("Search files or >commands", emptyLabel);
		assertFalse(emptyLabel.toUpperCase().contains("CTRL"), "Command center must not repeat Ctrl+P shortcut.");

		OpenTabState dirtyTab = new OpenTabState(new FileDocument("pack/data/demo/function/tick.mcfunction", "say hi", "sha", 0L));
		dirtyTab.buffer().insertText("!");
		String tabLabel = QuillIdeScreen.formatCommandCenterText(dirtyTab, false);
		assertEquals("tick.mcfunction *", tabLabel);
		assertFalse(tabLabel.toUpperCase().contains("CTRL"));

		String settingsLabel = QuillIdeScreen.formatCommandCenterText(dirtyTab, true);
		assertEquals("Settings", settingsLabel);
		assertFalse(settingsLabel.toUpperCase().contains("CTRL"));
	}

	@Test
	void quickPickAnchorsFlushBelowTitleBarAndSupportsRevealAnimation() {
		IdeLayout.Rect window = new IdeLayout.Rect(100, 40, 760, 460);
		IdeLayout.Rect titleBar = new IdeLayout.Rect(100, 40, 760, 24);
		IdeLayout.Rect commandCenter = new IdeLayout.Rect(340, 43, 280, 18);

		IdeLayout.Rect full = QuickPickOverlay.computeDropdownBounds(window, titleBar, commandCenter, 6, 540, 1.0f);
		assertTrue(full.y() >= titleBar.bottom() - 2 && full.y() <= titleBar.bottom() + 2,
				"QuickPick must drop down directly from the top Command Center bar, not float detached.");
		assertTrue(full.width() >= commandCenter.width() && full.width() <= 480);
		int windowCenter = window.x() + window.width() / 2;
		int dropdownCenter = full.x() + full.width() / 2;
		assertTrue(Math.abs(windowCenter - dropdownCenter) <= 60, "Dropdown must stay centered near Command Center.");

		IdeLayout.Rect animatingHalf = QuickPickOverlay.computeDropdownBounds(window, titleBar, commandCenter, 6, 540, 0.5f);
		assertTrue(animatingHalf.height() < full.height() && animatingHalf.height() >= 36,
				"Half-revealed animated dropdown should interpolate height smoothly from header.");
	}
}
