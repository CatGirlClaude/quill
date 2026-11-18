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
		assertEquals(0, docked.breadcrumbs().height());
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

	@Test
	void supportsCustomFloatingBoundsBreadcrumbsAndSplitterSizes() {
		IdeLayout.WindowBounds custom = new IdeLayout.WindowBounds(60, 40, 620, 360);
		IdeLayout layout = IdeLayout.compute(960, 540, false, false, true, true, custom, 260, 120);

		assertEquals(60, layout.window().x());
		assertEquals(40, layout.window().y());
		assertEquals(620, layout.window().width());
		assertEquals(360, layout.window().height());
		assertEquals(260, layout.sidebar().width());
		assertEquals(120, layout.diagnosticsBanner().height());
		assertEquals(QuillTheme.BREADCRUMB_HEIGHT, layout.breadcrumbs().height());
		assertEquals(layout.tabBar().bottom(), layout.breadcrumbs().y());
		assertEquals(layout.breadcrumbs().bottom(), layout.editor().y());
	}

	@Test
	void clampsFloatingBoundsToScreenAndMinimumSize() {
		IdeLayout.WindowBounds offscreen = new IdeLayout.WindowBounds(900, 500, 120, 80);
		IdeLayout layout = IdeLayout.compute(800, 450, false, false, false, false, offscreen, 200, 96);

		assertEquals(IdeLayout.MIN_WINDOW_WIDTH, layout.window().width());
		assertEquals(IdeLayout.MIN_WINDOW_HEIGHT, layout.window().height());
		assertEquals(800 - IdeLayout.MIN_WINDOW_WIDTH, layout.window().x());
		assertEquals(450 - IdeLayout.MIN_WINDOW_HEIGHT, layout.window().y());
	}

	@Test
	void detectsAllEightResizeEdgesAndClampsMoveAndResize() {
		IdeLayout.Rect frame = new IdeLayout.Rect(100, 80, 500, 300);
		assertEquals(IdeLayout.ResizeEdge.NW, IdeLayout.hitTestResizeEdge(frame, 101, 81, 5));
		assertEquals(IdeLayout.ResizeEdge.NE, IdeLayout.hitTestResizeEdge(frame, 599, 81, 5));
		assertEquals(IdeLayout.ResizeEdge.SW, IdeLayout.hitTestResizeEdge(frame, 101, 379, 5));
		assertEquals(IdeLayout.ResizeEdge.SE, IdeLayout.hitTestResizeEdge(frame, 599, 379, 5));
		assertEquals(IdeLayout.ResizeEdge.N, IdeLayout.hitTestResizeEdge(frame, 350, 81, 5));
		assertEquals(IdeLayout.ResizeEdge.S, IdeLayout.hitTestResizeEdge(frame, 350, 379, 5));
		assertEquals(IdeLayout.ResizeEdge.W, IdeLayout.hitTestResizeEdge(frame, 101, 200, 5));
		assertEquals(IdeLayout.ResizeEdge.E, IdeLayout.hitTestResizeEdge(frame, 599, 200, 5));
		assertEquals(IdeLayout.ResizeEdge.NONE, IdeLayout.hitTestResizeEdge(frame, 350, 200, 5));
		assertEquals(IdeLayout.ResizeEdge.NONE, IdeLayout.hitTestResizeEdge(frame, 10, 10, 5));

		IdeLayout.WindowBounds start = new IdeLayout.WindowBounds(100, 80, 500, 300);
		IdeLayout.WindowBounds movedLeft = IdeLayout.applyMove(start, -250, -200, 800, 480);
		assertEquals(0, movedLeft.x());
		assertEquals(0, movedLeft.y());
		assertEquals(500, movedLeft.width());

		IdeLayout.WindowBounds movedRight = IdeLayout.applyMove(start, 600, 400, 800, 480);
		assertEquals(300, movedRight.x());
		assertEquals(180, movedRight.y());

		IdeLayout.WindowBounds shrunkNW = IdeLayout.applyResize(start, IdeLayout.ResizeEdge.NW, 400, 250, 800, 480);
		assertEquals(IdeLayout.MIN_WINDOW_WIDTH, shrunkNW.width());
		assertEquals(IdeLayout.MIN_WINDOW_HEIGHT, shrunkNW.height());
		assertEquals(600 - IdeLayout.MIN_WINDOW_WIDTH, shrunkNW.x());
		assertEquals(380 - IdeLayout.MIN_WINDOW_HEIGHT, shrunkNW.y());

		IdeLayout.WindowBounds grownSE = IdeLayout.applyResize(start, IdeLayout.ResizeEdge.SE, 500, 500, 800, 480);
		assertEquals(100, grownSE.x());
		assertEquals(80, grownSE.y());
		assertEquals(700, grownSE.width());
		assertEquals(400, grownSE.height());
	}
}
