package dev.catgirlclaude.quill.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeLayoutTest {

	@Test
	void computesDockedAndMaximizedRegionsWithoutOverlap() {
		IdeLayout docked = IdeLayout.compute(854, 480, false, false, false);
		assertTrue(docked.window().x() > 0);
		assertTrue(docked.window().y() > 0);
		assertEquals(0, docked.diagnosticsBanner().height());
		assertEquals(docked.activityBar().right(), docked.sidebar().x());
		assertEquals(docked.sidebar().right(), docked.editor().x());
		assertEquals(docked.tabBar().bottom(), docked.editor().y());
		assertEquals(docked.editor().bottom(), docked.statusBar().y());

		IdeLayout maximized = IdeLayout.compute(854, 480, true, false, true);
		assertEquals(0, maximized.window().x());
		assertEquals(0, maximized.window().y());
		assertEquals(854, maximized.window().width());
		assertEquals(480, maximized.window().height());
		assertTrue(maximized.diagnosticsBanner().height() > 0);
		assertEquals(maximized.diagnosticsBanner().bottom(), maximized.statusBar().y());
	}

	@Test
	void hidesSidebarWhenCollapsedOrScreenTooNarrow() {
		IdeLayout collapsed = IdeLayout.compute(854, 480, false, true, false);
		assertEquals(0, collapsed.sidebar().width());
		assertEquals(collapsed.activityBar().right(), collapsed.editor().x());

		IdeLayout narrow = IdeLayout.compute(190, 220, true, false, false);
		assertEquals(0, narrow.sidebar().width());
		assertTrue(narrow.editor().width() > 0);
	}
}
