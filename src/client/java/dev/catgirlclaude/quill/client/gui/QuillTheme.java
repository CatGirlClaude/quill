package dev.catgirlclaude.quill.client.gui;

import dev.catgirlclaude.quill.client.config.QuillColorPalette;

/**
 * Dynamic Visual Studio Code ARGB color palette and spacing constants for Quill's UI.
 * Color fields update live via {@link #applyPalette(QuillColorPalette)} when the user
 * selects a theme in Settings.
 */
public final class QuillTheme {
	private static volatile QuillColorPalette activePalette = QuillColorPalette.DARK_PLUS;

	public static int BG_BACKDROP = QuillColorPalette.DARK_PLUS.bgBackdrop();
	public static int BG_TITLE_BAR = QuillColorPalette.DARK_PLUS.bgTitleBar();
	public static int BG_ACTIVITY_BAR = QuillColorPalette.DARK_PLUS.bgActivityBar();
	public static int BG_SIDEBAR = QuillColorPalette.DARK_PLUS.bgSidebar();
	public static int BG_EDITOR = QuillColorPalette.DARK_PLUS.bgEditor();
	public static int BG_GUTTER = QuillColorPalette.DARK_PLUS.bgGutter();
	public static int BG_TAB_BAR = QuillColorPalette.DARK_PLUS.bgTabBar();
	public static int BG_TAB_ACTIVE = QuillColorPalette.DARK_PLUS.bgTabActive();
	public static int BG_TAB_INACTIVE = QuillColorPalette.DARK_PLUS.bgTabInactive();
	public static int BG_BREADCRUMB = QuillColorPalette.DARK_PLUS.bgBreadcrumb();

	public static int BG_STATUS_BAR = QuillColorPalette.DARK_PLUS.bgStatusBar();
	public static int BG_STATUS_DIRTY = QuillColorPalette.DARK_PLUS.bgStatusDirty();
	public static int BG_STATUS_ERROR = QuillColorPalette.DARK_PLUS.bgStatusError();
	public static int BG_STATUS_REMOTE_BADGE = QuillColorPalette.DARK_PLUS.bgStatusRemoteBadge();

	public static int BG_DIAGNOSTICS = QuillColorPalette.DARK_PLUS.bgDiagnostics();
	public static int BG_MODAL = QuillColorPalette.DARK_PLUS.bgModal();
	public static int BG_INPUT = QuillColorPalette.DARK_PLUS.bgInput();
	public static int BG_BUTTON = QuillColorPalette.DARK_PLUS.bgButton();
	public static int BG_BUTTON_HOVER = QuillColorPalette.DARK_PLUS.bgButtonHover();
	public static int BG_BUTTON_DANGER = QuillColorPalette.DARK_PLUS.bgButtonDanger();
	public static int BG_BUTTON_SECONDARY = QuillColorPalette.DARK_PLUS.bgButtonSecondary();
	public static int BG_HOVER = QuillColorPalette.DARK_PLUS.bgHover();

	public static int ACCENT_BLUE = QuillColorPalette.DARK_PLUS.accentBlue();
	public static int ACCENT_FOCUS = QuillColorPalette.DARK_PLUS.accentFocus();
	public static int BORDER_SUBTLE = QuillColorPalette.DARK_PLUS.borderSubtle();

	public static int TEXT_PRIMARY = QuillColorPalette.DARK_PLUS.textPrimary();
	public static int TEXT_BRIGHT = QuillColorPalette.DARK_PLUS.textBright();
	public static int TEXT_MUTED = QuillColorPalette.DARK_PLUS.textMuted();
	public static int TEXT_DIRTY = QuillColorPalette.DARK_PLUS.textDirty();
	public static int TEXT_ERROR = QuillColorPalette.DARK_PLUS.textError();
	public static int TEXT_WARNING = QuillColorPalette.DARK_PLUS.textWarning();
	public static int TEXT_SUCCESS = QuillColorPalette.DARK_PLUS.textSuccess();
	public static int TEXT_FOLDER = QuillColorPalette.DARK_PLUS.textFolder();
	public static int TEXT_FUNCTION = QuillColorPalette.DARK_PLUS.textFunction();
	public static int TEXT_JSON = QuillColorPalette.DARK_PLUS.textJson();

	public static int SYNTAX_PLAIN = QuillColorPalette.DARK_PLUS.syntaxPlain();
	public static int SYNTAX_COMMENT = QuillColorPalette.DARK_PLUS.syntaxComment();
	public static int SYNTAX_COMMAND = QuillColorPalette.DARK_PLUS.syntaxCommand();
	public static int SYNTAX_KEYWORD = QuillColorPalette.DARK_PLUS.syntaxKeyword();
	public static int SYNTAX_BOOLEAN = QuillColorPalette.DARK_PLUS.syntaxBoolean();
	public static int SYNTAX_SELECTOR = QuillColorPalette.DARK_PLUS.syntaxSelector();
	public static int SYNTAX_COORDINATE = QuillColorPalette.DARK_PLUS.syntaxCoordinate();
	public static int SYNTAX_NUMBER = QuillColorPalette.DARK_PLUS.syntaxNumber();
	public static int SYNTAX_STRING = QuillColorPalette.DARK_PLUS.syntaxString();
	public static int SYNTAX_PROPERTY = QuillColorPalette.DARK_PLUS.syntaxProperty();
	public static int SYNTAX_IDENTIFIER = QuillColorPalette.DARK_PLUS.syntaxIdentifier();
	public static int SYNTAX_MACRO = QuillColorPalette.DARK_PLUS.syntaxMacro();
	public static int SYNTAX_BRACKET = QuillColorPalette.DARK_PLUS.syntaxBracket();

	public static int BG_POPUP = QuillColorPalette.DARK_PLUS.bgPopup();
	public static int BG_POPUP_SELECTED = QuillColorPalette.DARK_PLUS.bgPopupSelected();
	public static int BG_PANEL = QuillColorPalette.DARK_PLUS.bgPanel();
	public static int BG_PANEL_HEADER = QuillColorPalette.DARK_PLUS.bgPanelHeader();
	public static int MATCH_HIGHLIGHT = QuillColorPalette.DARK_PLUS.matchHighlight();

	public static int SELECTION_BG = QuillColorPalette.DARK_PLUS.selectionBg();
	public static int ROW_SELECTED = QuillColorPalette.DARK_PLUS.rowSelected();
	public static int LINE_HIGHLIGHT = QuillColorPalette.DARK_PLUS.lineHighlight();
	public static int CURSOR_COLOR = QuillColorPalette.DARK_PLUS.cursorColor();
	public static int BRACKET_MATCH_BG = QuillColorPalette.DARK_PLUS.bracketMatchBg();
	public static int WARNING_BANNER_BG = QuillColorPalette.DARK_PLUS.warningBannerBg();
	public static int WARNING_BANNER_BORDER = QuillColorPalette.DARK_PLUS.warningBannerBorder();

	/**
	 * Centralized spacing, chrome sizing, and elevation tokens in normalized virtual pixels.
	 */
	public static final class Metrics {
		public static final int TITLE_BAR_HEIGHT = 24;
		public static final int ACTIVITY_BAR_WIDTH = 36;
		public static final int SIDEBAR_DEFAULT_WIDTH = 204;
		public static final int TAB_BAR_HEIGHT = 24;
		public static final int BREADCRUMB_HEIGHT = 18;
		public static final int STATUS_BAR_HEIGHT = 18;
		public static final int DIAGNOSTICS_HEIGHT = 96;
		public static final int EXPLORER_ROW_HEIGHT = 17;

		public static final int PAD_XS = 2;
		public static final int PAD_SM = 4;
		public static final int PAD_MD = 8;
		public static final int PAD_LG = 12;
		public static final int SCROLLBAR_SIZE = 5;

		public static final int SHADOW_OUTER = 0x33000000;
		public static final int SHADOW_INNER = 0x26000000;
		public static final int WINDOW_HIGHLIGHT = 0x16FFFFFF;

		private Metrics() {
		}
	}

	public static final int TITLE_BAR_HEIGHT = Metrics.TITLE_BAR_HEIGHT;
	public static final int ACTIVITY_BAR_WIDTH = Metrics.ACTIVITY_BAR_WIDTH;
	public static final int SIDEBAR_DEFAULT_WIDTH = Metrics.SIDEBAR_DEFAULT_WIDTH;
	public static final int TAB_BAR_HEIGHT = Metrics.TAB_BAR_HEIGHT;
	public static final int BREADCRUMB_HEIGHT = Metrics.BREADCRUMB_HEIGHT;
	public static final int STATUS_BAR_HEIGHT = Metrics.STATUS_BAR_HEIGHT;
	public static final int DIAGNOSTICS_HEIGHT = Metrics.DIAGNOSTICS_HEIGHT;

	private QuillTheme() {
	}

	public static int withAlpha(int argb, int alpha) {
		int a = Math.max(0, Math.min(255, alpha));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	public static int dividerSoft() {
		return withAlpha(BORDER_SUBTLE, 0x88);
	}

	public static QuillColorPalette palette() {
		return activePalette;
	}

	public static boolean isLightTheme() {
		return activePalette.lightTheme();
	}

	public static void applyPalette(QuillColorPalette p) {
		if (p == null) {
			p = QuillColorPalette.DARK_PLUS;
		}
		activePalette = p;
		BG_BACKDROP = p.bgBackdrop();
		BG_TITLE_BAR = p.bgTitleBar();
		BG_ACTIVITY_BAR = p.bgActivityBar();
		BG_SIDEBAR = p.bgSidebar();
		BG_EDITOR = p.bgEditor();
		BG_GUTTER = p.bgGutter();
		BG_TAB_BAR = p.bgTabBar();
		BG_TAB_ACTIVE = p.bgTabActive();
		BG_TAB_INACTIVE = p.bgTabInactive();
		BG_BREADCRUMB = p.bgBreadcrumb();
		BG_STATUS_BAR = p.bgStatusBar();
		BG_STATUS_DIRTY = p.bgStatusDirty();
		BG_STATUS_ERROR = p.bgStatusError();
		BG_STATUS_REMOTE_BADGE = p.bgStatusRemoteBadge();
		BG_DIAGNOSTICS = p.bgDiagnostics();
		BG_MODAL = p.bgModal();
		BG_INPUT = p.bgInput();
		BG_BUTTON = p.bgButton();
		BG_BUTTON_HOVER = p.bgButtonHover();
		BG_BUTTON_DANGER = p.bgButtonDanger();
		BG_BUTTON_SECONDARY = p.bgButtonSecondary();
		BG_HOVER = p.bgHover();
		ACCENT_BLUE = p.accentBlue();
		ACCENT_FOCUS = p.accentFocus();
		BORDER_SUBTLE = p.borderSubtle();
		TEXT_PRIMARY = p.textPrimary();
		TEXT_BRIGHT = p.textBright();
		TEXT_MUTED = p.textMuted();
		TEXT_DIRTY = p.textDirty();
		TEXT_ERROR = p.textError();
		TEXT_WARNING = p.textWarning();
		TEXT_SUCCESS = p.textSuccess();
		TEXT_FOLDER = p.textFolder();
		TEXT_FUNCTION = p.textFunction();
		TEXT_JSON = p.textJson();
		SYNTAX_PLAIN = p.syntaxPlain();
		SYNTAX_COMMENT = p.syntaxComment();
		SYNTAX_COMMAND = p.syntaxCommand();
		SYNTAX_KEYWORD = p.syntaxKeyword();
		SYNTAX_BOOLEAN = p.syntaxBoolean();
		SYNTAX_SELECTOR = p.syntaxSelector();
		SYNTAX_COORDINATE = p.syntaxCoordinate();
		SYNTAX_NUMBER = p.syntaxNumber();
		SYNTAX_STRING = p.syntaxString();
		SYNTAX_PROPERTY = p.syntaxProperty();
		SYNTAX_IDENTIFIER = p.syntaxIdentifier();
		SYNTAX_MACRO = p.syntaxMacro();
		SYNTAX_BRACKET = p.syntaxBracket();
		BG_POPUP = p.bgPopup();
		BG_POPUP_SELECTED = p.bgPopupSelected();
		BG_PANEL = p.bgPanel();
		BG_PANEL_HEADER = p.bgPanelHeader();
		MATCH_HIGHLIGHT = p.matchHighlight();
		SELECTION_BG = p.selectionBg();
		ROW_SELECTED = p.rowSelected();
		LINE_HIGHLIGHT = p.lineHighlight();
		CURSOR_COLOR = p.cursorColor();
		BRACKET_MATCH_BG = p.bracketMatchBg();
		WARNING_BANNER_BG = p.warningBannerBg();
		WARNING_BANNER_BORDER = p.warningBannerBorder();
	}
}
