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

	@Test
	void guiScaleIndependentScalingKeepsVirtualLayoutIdenticalAcrossMcScales() {
		int fbWidth = 1920;
		int fbHeight = 1080;
		IdeLayout baseline = null;
		for (int mcGuiScale = 1; mcGuiScale <= 4; mcGuiScale++) {
			int screenW = fbWidth / mcGuiScale;
			int screenH = fbHeight / mcGuiScale;
			float effective = IdeLayout.computeEffectiveScale(mcGuiScale, 1.0f);
			int vw = IdeLayout.toVirtualSize(screenW, effective);
			int vh = IdeLayout.toVirtualSize(screenH, effective);
			assertEquals(960, vw, "1080p at 100% zoom should normalize to 960 virtual width at GUI scale " + mcGuiScale);
			assertEquals(540, vh, "1080p at 100% zoom should normalize to 540 virtual height at GUI scale " + mcGuiScale);

			IdeLayout layout = IdeLayout.compute(vw, vh, false, false, true, true, null, 204, 96);
			if (baseline == null) {
				baseline = layout;
			} else {
				assertEquals(baseline, layout, "Virtual layout must match cleanly across MC GUI Scale " + mcGuiScale);
			}
		}
	}

	@Test
	void zoomRange50To200PreservesNonOverlappingLayoutOnDesktopAndSmallScreens() {
		int[][] displays = {{1920, 1080}, {1280, 720}};
		float[] zooms = {0.50f, 1.00f, 1.50f, 2.00f};
		for (int[] display : displays) {
			for (int mcScale = 1; mcScale <= 4; mcScale++) {
				int screenW = Math.max(1, display[0] / mcScale);
				int screenH = Math.max(1, display[1] / mcScale);
				for (float zoom : zooms) {
					float effective = IdeLayout.computeEffectiveScale(mcScale, zoom);
					int vw = IdeLayout.toVirtualSize(screenW, effective);
					int vh = IdeLayout.toVirtualSize(screenH, effective);
					IdeLayout layout = IdeLayout.compute(vw, vh, false, false, true, true, null, 204, 96);
					assertTrue(layout.window().width() > 0 && layout.window().width() <= vw);
					assertTrue(layout.window().height() > 0 && layout.window().height() <= vh);
					assertTrue(layout.titleBar().bottom() <= layout.statusBar().y());
					assertTrue(layout.editor().height() >= 0);
					assertTrue(layout.editor().bottom() <= layout.statusBar().y());
					assertTrue(layout.statusBar().bottom() <= layout.window().bottom());
				}
			}
		}

		// Extremely compact screen must still never overlap editor/tab/status regions
		IdeLayout tiny = IdeLayout.compute(160, 100, true, false, true, true, null, 204, 96);
		assertTrue(tiny.tabBar().bottom() <= tiny.statusBar().y());
		assertTrue(tiny.editor().bottom() <= tiny.statusBar().y());
		assertEquals(tiny.window().bottom(), tiny.statusBar().bottom());
	}

	@Test
	void themeMetricsAndAnimatorRespectDisabledAnimations() {
		assertEquals(24, QuillTheme.Metrics.TITLE_BAR_HEIGHT);
		assertEquals(24, QuillTheme.Metrics.TAB_BAR_HEIGHT);
		assertEquals(18, QuillTheme.Metrics.BREADCRUMB_HEIGHT);
		assertEquals(18, QuillTheme.Metrics.STATUS_BAR_HEIGHT);
		assertEquals(17, QuillTheme.Metrics.EXPLORER_ROW_HEIGHT);
		assertEquals(5, QuillTheme.Metrics.SCROLLBAR_SIZE);
		assertEquals(2, QuillTheme.Metrics.PAD_XS);
		assertEquals(4, QuillTheme.Metrics.PAD_SM);
		assertEquals(8, QuillTheme.Metrics.PAD_MD);
		assertEquals(12, QuillTheme.Metrics.PAD_LG);

		assertEquals(1.0f, QuillAnimator.stepTowards(0.0f, 1.0f, 0.016f, 10.0f, false), 0.0001f);
		float mid = QuillAnimator.stepTowards(0.0f, 1.0f, 0.016f, 10.0f, true);
		assertTrue(mid > 0.0f && mid < 1.0f);
	}
}
