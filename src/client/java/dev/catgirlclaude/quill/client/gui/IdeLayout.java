package dev.catgirlclaude.quill.client.gui;

/**
 * Computes docked, floating (movable &amp; resizable), or maximized layout rectangles for {@link QuillIdeScreen}.
 */
public record IdeLayout(
		Rect window,
		Rect titleBar,
		Rect activityBar,
		Rect sidebar,
		Rect tabBar,
		Rect breadcrumbs,
		Rect editor,
		Rect diagnosticsBanner,
		Rect statusBar
) {
	public static final int MIN_WINDOW_WIDTH = 300;
	public static final int MIN_WINDOW_HEIGHT = 200;
	public static final int RESIZE_BORDER = 5;

	public record Rect(int x, int y, int width, int height) {
		public int right() {
			return x + width;
		}

		public int bottom() {
			return y + height;
		}

		public boolean contains(double px, double py) {
			return width > 0 && height > 0 && px >= x && px < right() && py >= y && py < bottom();
		}
	}

	public record WindowBounds(int x, int y, int width, int height) {
	}

	public enum ResizeEdge {
		NONE, N, S, E, W, NE, NW, SE, SW
	}

	public static IdeLayout compute(
			int screenWidth,
			int screenHeight,
			boolean maximized,
			boolean sidebarCollapsed,
			boolean showDiagnostics
	) {
		return compute(
				screenWidth,
				screenHeight,
				maximized,
				sidebarCollapsed,
				showDiagnostics,
				false,
				null,
				QuillTheme.SIDEBAR_DEFAULT_WIDTH,
				QuillTheme.DIAGNOSTICS_HEIGHT
		);
	}

	public static IdeLayout compute(
			int screenWidth,
			int screenHeight,
			boolean maximized,
			boolean sidebarCollapsed,
			boolean showDiagnostics,
			boolean showBreadcrumbs,
			WindowBounds customBounds,
			int customSidebarWidth,
			int customBottomPanelHeight
	) {
		int winX;
		int winY;
		int winW;
		int winH;

		if (maximized) {
			winX = 0;
			winY = 0;
			winW = Math.max(0, screenWidth);
			winH = Math.max(0, screenHeight);
		} else if (customBounds != null && customBounds.width() > 0 && customBounds.height() > 0) {
			int minW = Math.min(MIN_WINDOW_WIDTH, Math.max(0, screenWidth));
			int minH = Math.min(MIN_WINDOW_HEIGHT, Math.max(0, screenHeight));
			winW = Math.max(minW, Math.min(screenWidth, customBounds.width()));
			winH = Math.max(minH, Math.min(screenHeight, customBounds.height()));
			winX = Math.max(0, Math.min(Math.max(0, screenWidth - winW), customBounds.x()));
			winY = Math.max(0, Math.min(Math.max(0, screenHeight - winH), customBounds.y()));
		} else {
			int marginX = Math.max(10, Math.min(28, screenWidth / 22));
			int marginY = Math.max(10, Math.min(24, screenHeight / 20));
			winX = marginX;
			winY = marginY;
			winW = Math.max(240, screenWidth - marginX * 2);
			winH = Math.max(160, screenHeight - marginY * 2);
			if (winW > screenWidth) {
				winX = 0;
				winW = screenWidth;
			}
			if (winH > screenHeight) {
				winY = 0;
				winH = screenHeight;
			}
		}

		Rect window = new Rect(winX, winY, winW, winH);
		int titleH = QuillTheme.TITLE_BAR_HEIGHT;
		int statusH = QuillTheme.STATUS_BAR_HEIGHT;
		int maxDiagH = Math.max(60, winH - titleH - statusH - 70);
		int diagH = (showDiagnostics && winH >= 200)
				? Math.max(60, Math.min(maxDiagH, customBottomPanelHeight > 0 ? customBottomPanelHeight : QuillTheme.DIAGNOSTICS_HEIGHT))
				: 0;

		Rect titleBar = new Rect(winX, winY, winW, titleH);
		Rect statusBar = new Rect(winX, winY + winH - statusH, winW, statusH);
		Rect diagnostics = diagH > 0
				? new Rect(winX, statusBar.y() - diagH, winW, diagH)
				: new Rect(winX, statusBar.y(), winW, 0);

		int bodyY = winY + titleH;
		int bodyH = Math.max(40, diagnostics.y() - bodyY);

		int activityW = Math.min(QuillTheme.ACTIVITY_BAR_WIDTH, Math.max(24, winW / 10));
		Rect activityBar = new Rect(winX, bodyY, activityW, bodyH);

		int minEditorWidth = Math.min(160, Math.max(80, winW / 3));
		int availableForSidebarAndEditor = Math.max(0, winW - activityW);
		int desiredSidebarW = customSidebarWidth > 0 ? Math.max(110, customSidebarWidth) : QuillTheme.SIDEBAR_DEFAULT_WIDTH;
		int sidebarW = 0;
		if (!sidebarCollapsed && availableForSidebarAndEditor > minEditorWidth + 90) {
			sidebarW = Math.min(desiredSidebarW, availableForSidebarAndEditor - minEditorWidth);
		}
		Rect sidebar = new Rect(activityBar.right(), bodyY, sidebarW, bodyH);

		int editorAreaX = sidebar.right();
		int editorAreaW = Math.max(0, winX + winW - editorAreaX);
		int tabH = QuillTheme.TAB_BAR_HEIGHT;
		Rect tabBar = new Rect(editorAreaX, bodyY, editorAreaW, tabH);

		int breadH = (showBreadcrumbs && bodyH - tabH > 60) ? QuillTheme.BREADCRUMB_HEIGHT : 0;
		Rect breadcrumbs = new Rect(editorAreaX, bodyY + tabH, editorAreaW, breadH);
		Rect editor = new Rect(editorAreaX, breadcrumbs.bottom(), editorAreaW, Math.max(0, bodyH - tabH - breadH));

		return new IdeLayout(window, titleBar, activityBar, sidebar, tabBar, breadcrumbs, editor, diagnostics, statusBar);
	}

	public static ResizeEdge hitTestResizeEdge(Rect frame, double mx, double my, int borderPx) {
		if (frame == null || frame.width() <= 0 || frame.height() <= 0) {
			return ResizeEdge.NONE;
		}
		int b = Math.max(2, borderPx);
		int outer = 2;
		boolean inX = mx >= frame.x() - outer && mx <= frame.right() + outer;
		boolean inY = my >= frame.y() - outer && my <= frame.bottom() + outer;
		if (!inX || !inY) {
			return ResizeEdge.NONE;
		}
		boolean left = mx <= frame.x() + b;
		boolean right = mx >= frame.right() - b;
		boolean top = my <= frame.y() + b;
		boolean bottom = my >= frame.bottom() - b;

		if (top && left) return ResizeEdge.NW;
		if (top && right) return ResizeEdge.NE;
		if (bottom && left) return ResizeEdge.SW;
		if (bottom && right) return ResizeEdge.SE;
		if (top) return ResizeEdge.N;
		if (bottom) return ResizeEdge.S;
		if (left) return ResizeEdge.W;
		if (right) return ResizeEdge.E;
		return ResizeEdge.NONE;
	}

	public static WindowBounds applyMove(WindowBounds start, int dx, int dy, int screenWidth, int screenHeight) {
		int w = Math.min(screenWidth, Math.max(1, start.width()));
		int h = Math.min(screenHeight, Math.max(1, start.height()));
		int nx = Math.max(0, Math.min(Math.max(0, screenWidth - w), start.x() + dx));
		int ny = Math.max(0, Math.min(Math.max(0, screenHeight - h), start.y() + dy));
		return new WindowBounds(nx, ny, w, h);
	}

	public static WindowBounds applyResize(
			WindowBounds start,
			ResizeEdge edge,
			int dx,
			int dy,
			int screenWidth,
			int screenHeight
	) {
		if (edge == null || edge == ResizeEdge.NONE) {
			return start;
		}
		int minW = Math.min(MIN_WINDOW_WIDTH, Math.max(1, screenWidth));
		int minH = Math.min(MIN_WINDOW_HEIGHT, Math.max(1, screenHeight));
		int left = start.x();
		int top = start.y();
		int right = start.x() + start.width();
		int bottom = start.y() + start.height();

		boolean moveLeft = edge == ResizeEdge.W || edge == ResizeEdge.NW || edge == ResizeEdge.SW;
		boolean moveRight = edge == ResizeEdge.E || edge == ResizeEdge.NE || edge == ResizeEdge.SE;
		boolean moveTop = edge == ResizeEdge.N || edge == ResizeEdge.NW || edge == ResizeEdge.NE;
		boolean moveBottom = edge == ResizeEdge.S || edge == ResizeEdge.SW || edge == ResizeEdge.SE;

		if (moveLeft) {
			left = Math.max(0, Math.min(right - minW, left + dx));
		}
		if (moveRight) {
			right = Math.min(screenWidth, Math.max(left + minW, right + dx));
		}
		if (moveTop) {
			top = Math.max(0, Math.min(bottom - minH, top + dy));
		}
		if (moveBottom) {
			bottom = Math.min(screenHeight, Math.max(top + minH, bottom + dy));
		}
		return new WindowBounds(left, top, right - left, bottom - top);
	}
}
