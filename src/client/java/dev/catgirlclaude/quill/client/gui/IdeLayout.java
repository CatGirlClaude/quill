package dev.catgirlclaude.quill.client.gui;

/**
 * Computes docked or maximized layout rectangles for {@link QuillIdeScreen}.
 */
public record IdeLayout(
		Rect window,
		Rect titleBar,
		Rect activityBar,
		Rect sidebar,
		Rect tabBar,
		Rect editor,
		Rect diagnosticsBanner,
		Rect statusBar
) {
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

	public static IdeLayout compute(
			int screenWidth,
			int screenHeight,
			boolean maximized,
			boolean sidebarCollapsed,
			boolean showDiagnostics
	) {
		int marginX = maximized ? 0 : Math.max(10, Math.min(28, screenWidth / 22));
		int marginY = maximized ? 0 : Math.max(10, Math.min(24, screenHeight / 20));

		int winX = marginX;
		int winY = marginY;
		int winW = Math.max(240, screenWidth - marginX * 2);
		int winH = Math.max(160, screenHeight - marginY * 2);
		if (winW > screenWidth) {
			winX = 0;
			winW = screenWidth;
		}
		if (winH > screenHeight) {
			winY = 0;
			winH = screenHeight;
		}

		Rect window = new Rect(winX, winY, winW, winH);
		int titleH = QuillTheme.TITLE_BAR_HEIGHT;
		int statusH = QuillTheme.STATUS_BAR_HEIGHT;
		int diagH = (showDiagnostics && winH >= 200) ? QuillTheme.DIAGNOSTICS_HEIGHT : 0;

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
		int sidebarW = 0;
		if (!sidebarCollapsed && availableForSidebarAndEditor > minEditorWidth + 90) {
			sidebarW = Math.min(QuillTheme.SIDEBAR_DEFAULT_WIDTH, availableForSidebarAndEditor - minEditorWidth);
		}
		Rect sidebar = new Rect(activityBar.right(), bodyY, sidebarW, bodyH);

		int editorAreaX = sidebar.right();
		int editorAreaW = Math.max(0, winX + winW - editorAreaX);
		int tabH = QuillTheme.TAB_BAR_HEIGHT;
		Rect tabBar = new Rect(editorAreaX, bodyY, editorAreaW, tabH);
		Rect editor = new Rect(editorAreaX, bodyY + tabH, editorAreaW, Math.max(0, bodyH - tabH));

		return new IdeLayout(window, titleBar, activityBar, sidebar, tabBar, editor, diagnostics, statusBar);
	}
}
