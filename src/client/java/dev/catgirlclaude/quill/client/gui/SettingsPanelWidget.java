package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.config.KeyShortcut;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.config.QuillColorPalette;
import dev.catgirlclaude.quill.client.editor.SingleLineTextInput;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scrollable VS Code-style global client settings panel rendered inside the IDE editor area.
 * All changes apply live and persist immediately to {@code config/quill-client.json}.
 */
public final class SettingsPanelWidget {
	static final String INSTANT_RELOAD_WARNING_BODY =
			"Triggers /reload on every keystroke and may cause lag or partial-edit errors.";

	private record ClickTarget(IdeLayout.Rect rect, Runnable action) {
	}

	private final Runnable onCloseSettings;
	private final Runnable onResetWindow;

	private final SingleLineTextInput namespaceInput = new SingleLineTextInput("demo");
	private final SingleLineTextInput loadFnInput = new SingleLineTextInput("load");
	private final SingleLineTextInput tickFnInput = new SingleLineTextInput("tick");
	private final String[] templateErrors = new String[3];
	private final IdeLayout.Rect[] templateBoxRects = {
			new IdeLayout.Rect(0, 0, 0, 0),
			new IdeLayout.Rect(0, 0, 0, 0),
			new IdeLayout.Rect(0, 0, 0, 0)
	};

	private final List<ClickTarget> clickTargets = new ArrayList<>();
	private IdeLayout.Rect lastArea = new IdeLayout.Rect(0, 0, 0, 0);
	private int scrollPixels = 0;
	private int contentHeight = 0;

	private int focusedTemplateField = -1; // -1 none, 0 namespace, 1 load, 2 tick
	private QuillClientConfig.ShortcutAction recordingAction = null;

	public SettingsPanelWidget(Runnable onCloseSettings, Runnable onResetWindow) {
		this.onCloseSettings = onCloseSettings;
		this.onResetWindow = onResetWindow;
		syncFromConfig();
	}

	public void syncFromConfig() {
		QuillClientConfig cfg = QuillClientConfig.current();
		namespaceInput.setText(cfg.defaultTemplateNamespace());
		loadFnInput.setText(cfg.defaultLoadFunctionName());
		tickFnInput.setText(cfg.defaultTickFunctionName());
		templateErrors[0] = null;
		templateErrors[1] = null;
		templateErrors[2] = null;
	}

	public boolean hasFocusedTextInput() {
		return focusedTemplateField >= 0;
	}

	public boolean isRecordingKeybind() {
		return recordingAction != null;
	}

	public QuillClientConfig.ShortcutAction recordingAction() {
		return recordingAction;
	}

	public void beginRecordingKeybind(QuillClientConfig.ShortcutAction action) {
		this.focusedTemplateField = -1;
		this.recordingAction = action;
	}

	public void focusTemplateField(int index) {
		this.recordingAction = null;
		if (index >= 0 && index <= 2) {
			this.focusedTemplateField = index;
			templateInput(index).selectAll();
		} else {
			this.focusedTemplateField = -1;
		}
	}

	public void unfocus() {
		focusedTemplateField = -1;
		recordingAction = null;
		namespaceInput.mouseReleased();
		loadFnInput.mouseReleased();
		tickFnInput.mouseReleased();
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect area, int mouseX, int mouseY) {
		lastArea = area;
		clickTargets.clear();
		for (int i = 0; i < 3; i++) {
			templateBoxRects[i] = new IdeLayout.Rect(0, 0, 0, 0);
		}

		QuillDraw.fill(graphics, area.x(), area.y(), area.width(), area.height(), QuillTheme.BG_EDITOR);
		if (area.width() < 60 || area.height() < 40) {
			return;
		}

		QuillClientConfig cfg = QuillClientConfig.current();
		int maxScroll = Math.max(0, contentHeight - area.height() + 16);
		scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels));

		int padX = 16;
		int contentW = Math.max(180, area.width() - padX * 2 - 8);
		int x = area.x() + padX;
		int y = area.y() + 12 - scrollPixels;
		int startY = y;
		boolean hoveredClickable = false;
		boolean hoveredInput = false;

		QuillDraw.beginClip(graphics, area);

		// Header row
		String resetAllLabel = "Reset All Defaults";
		int resetAllW = QuillDraw.textWidth(font, resetAllLabel) + 12;
		String doneLabel = "Close";
		int doneW = QuillDraw.textWidth(font, doneLabel) + 12;
		IdeLayout.Rect doneRect = new IdeLayout.Rect(x + contentW - doneW, y + 2, doneW, 18);
		IdeLayout.Rect resetAllRect = new IdeLayout.Rect(doneRect.x() - resetAllW - 6, y + 2, resetAllW, 18);

		QuillDraw.textShadow(graphics, font, "Settings", x, y, QuillTheme.TEXT_BRIGHT);
		String sub = "Global client configuration (config/quill-client.json)";
		int subMaxW = Math.max(80, resetAllRect.x() - x - 8);
		List<String> subLines = QuillDraw.wrapLines(font, sub, subMaxW);
		int lineStep = Math.max(10, font.lineHeight + 2);
		for (int i = 0; i < subLines.size(); i++) {
			QuillDraw.text(graphics, font, subLines.get(i), x, y + 12 + i * lineStep, QuillTheme.TEXT_MUTED);
		}

		hoveredClickable |= renderButton(graphics, font, resetAllRect, resetAllLabel, false, false, mouseX, mouseY, area, () -> {
			cfg.resetAllToDefaults();
			QuillClientConfig.saveCurrent();
			syncFromConfig();
			if (onResetWindow != null) {
				onResetWindow.run();
			}
		});
		hoveredClickable |= renderButton(graphics, font, doneRect, doneLabel, true, false, mouseX, mouseY, area, () -> {
			unfocus();
			if (onCloseSettings != null) {
				onCloseSettings.run();
			}
		});

		y += Math.max(28, 12 + subLines.size() * lineStep + 6);
		QuillDraw.hLine(graphics, x, contentW, y, QuillTheme.BORDER_SUBTLE);
		y += 10;

		// ========== SECTION 1: APPEARANCE ==========
		y = renderSectionTitle(graphics, font, x, y, "Appearance & VS Code Themes");

		// Theme cards grid
		List<QuillColorPalette> palettes = QuillColorPalette.all();
		int cols = contentW >= 460 ? 3 : (contentW >= 290 ? 2 : 1);
		int gap = 6;
		int cardW = (contentW - gap * (cols - 1)) / cols;
		int cardH = 34;
		for (int i = 0; i < palettes.size(); i++) {
			QuillColorPalette p = palettes.get(i);
			int col = i % cols;
			int row = i / cols;
			int cx = x + col * (cardW + gap);
			int cy = y + row * (cardH + gap);
			IdeLayout.Rect cardRect = new IdeLayout.Rect(cx, cy, cardW, cardH);
			boolean selected = p.id().equals(cfg.themeId());
			boolean hover = area.contains(mouseX, mouseY) && cardRect.contains(mouseX, mouseY);
			if (hover) {
				hoveredClickable = true;
			}
			clickTargets.add(new ClickTarget(cardRect, () -> {
				cfg.setThemeId(p.id());
				QuillClientConfig.saveCurrent();
			}));

			QuillDraw.fill(graphics, cx, cy, cardW, cardH, selected ? QuillTheme.ROW_SELECTED : (hover ? QuillTheme.BG_HOVER : QuillTheme.BG_SIDEBAR));
			QuillDraw.outline(graphics, cx, cy, cardW, cardH, selected ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE);

			// Swatch strip showing palette colors
			int swatchY = cy + 5;
			int sw = Math.max(8, (cardW - 14) / 6);
			int[] swatches = {p.bgActivityBar(), p.bgSidebar(), p.bgEditor(), p.accentBlue(), p.syntaxCommand(), p.syntaxString()};
			for (int s = 0; s < swatches.length; s++) {
				QuillDraw.fill(graphics, cx + 6 + s * sw, swatchY, sw - 1, 7, swatches[s]);
			}
			QuillDraw.outline(graphics, cx + 6, swatchY, sw * swatches.length - 1, 7, QuillTheme.BORDER_SUBTLE);

			String nameLabel = (selected ? "* " : "") + p.displayName();
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, nameLabel, cardW - 12),
					cx + 6, cy + 17, selected ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY);
		}
		int themeRows = (palettes.size() + cols - 1) / cols;
		y += themeRows * (cardH + gap) + 8;

		// Font Family selector
		QuillDraw.text(graphics, font, "Font Family", x, y, QuillTheme.TEXT_PRIMARY);
		y += 13;
		QuillClientConfig.FontChoice[] fonts = QuillClientConfig.FontChoice.values();
		int fontCols = contentW >= 420 ? 2 : 1;
		int fontCardW = (contentW - gap * (fontCols - 1)) / fontCols;
		int fontCardH = 31;
		for (int i = 0; i < fonts.length; i++) {
			QuillClientConfig.FontChoice fc = fonts[i];
			int col = i % fontCols;
			int row = i / fontCols;
			int fx = x + col * (fontCardW + gap);
			int fy = y + row * (fontCardH + gap);
			IdeLayout.Rect fcRect = new IdeLayout.Rect(fx, fy, fontCardW, fontCardH);
			boolean selected = fc == cfg.fontChoice();
			boolean hover = area.contains(mouseX, mouseY) && fcRect.contains(mouseX, mouseY);
			if (hover) {
				hoveredClickable = true;
			}
			clickTargets.add(new ClickTarget(fcRect, () -> {
				cfg.setFontChoice(fc);
				QuillClientConfig.saveCurrent();
			}));

			QuillDraw.fill(graphics, fx, fy, fontCardW, fontCardH, selected ? QuillTheme.ROW_SELECTED : (hover ? QuillTheme.BG_HOVER : QuillTheme.BG_SIDEBAR));
			QuillDraw.outline(graphics, fx, fy, fontCardW, fontCardH, selected ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE);
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, (selected ? "* " : "") + fc.displayName(), fontCardW - 10),
					fx + 6, fy + 4, selected ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY);
			QuillDraw.textWithFont(graphics, font, "execute as @a run say Hi", fc,
					fx + 6, fy + 17, QuillTheme.TEXT_MUTED, 1.0f);
		}
		int fontRows = (fonts.length + fontCols - 1) / fontCols;
		y += fontRows * (fontCardH + gap) + 8;

		// Whole-GUI & font scale stepper
		QuillDraw.text(graphics, font, "UI & Font Scale (Zoom)", x, y + 4, QuillTheme.TEXT_PRIMARY);
		int ctrlX = x + Math.min(160, contentW / 2);
		IdeLayout.Rect minusRect = new IdeLayout.Rect(ctrlX, y, 22, 18);
		String sizeText = Math.round(cfg.uiScale() * 100) + "% (" + cfg.textSize() + " px)";
		int sizeLabelW = Math.max(76, QuillDraw.textWidth(font, sizeText) + 12);
		IdeLayout.Rect plusRect = new IdeLayout.Rect(minusRect.right() + sizeLabelW + 4, y, 22, 18);
		IdeLayout.Rect resetSizeRect = new IdeLayout.Rect(plusRect.right() + 8, y, QuillDraw.textWidth(font, "Reset") + 10, 18);

		hoveredClickable |= renderButton(graphics, font, minusRect, "-", false, false, mouseX, mouseY, area, () -> {
			cfg.zoomOut();
			QuillClientConfig.saveCurrent();
		});
		QuillDraw.text(graphics, font, sizeText,
				minusRect.right() + (sizeLabelW - QuillDraw.textWidth(font, sizeText)) / 2 + 2,
				y + (18 - font.lineHeight) / 2, QuillTheme.TEXT_BRIGHT);
		hoveredClickable |= renderButton(graphics, font, plusRect, "+", false, false, mouseX, mouseY, area, () -> {
			cfg.zoomIn();
			QuillClientConfig.saveCurrent();
		});
		hoveredClickable |= renderButton(graphics, font, resetSizeRect, "Reset", false, false, mouseX, mouseY, area, () -> {
			cfg.resetZoom();
			QuillClientConfig.saveCurrent();
		});
		String zoomHint = cfg.shortcut(QuillClientConfig.ShortcutAction.ZOOM_OUT).displayLabel()
				+ " / " + cfg.shortcut(QuillClientConfig.ShortcutAction.ZOOM_IN).displayLabel()
				+ " / " + cfg.shortcut(QuillClientConfig.ShortcutAction.ZOOM_RESET).displayLabel();
		int hintX = resetSizeRect.right() + 10;
		if (hintX + 40 < x + contentW) {
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, zoomHint, x + contentW - hintX),
					hintX, y + 4, QuillTheme.TEXT_MUTED);
		}
		y += 28;

		// ========== SECTION 2: BEHAVIOR, PAUSE, AUTOSAVE & RELOAD ==========
		QuillDraw.hLine(graphics, x, contentW, y, QuillTheme.BORDER_SUBTLE);
		y += 10;
		y = renderSectionTitle(graphics, font, x, y, "Gameplay, Autosave & Datapack Reload");

		// Pause game toggle
		IdeLayout.Rect pauseRowRect = new IdeLayout.Rect(x, y, contentW, 20);
		boolean hoverPause = area.contains(mouseX, mouseY) && pauseRowRect.contains(mouseX, mouseY);
		if (hoverPause) {
			hoveredClickable = true;
			QuillDraw.fill(graphics, x, y, contentW, 20, QuillTheme.BG_HOVER);
		}
		clickTargets.add(new ClickTarget(pauseRowRect, () -> {
			cfg.setPauseGameWhileOpen(!cfg.pauseGameWhileOpen());
			QuillClientConfig.saveCurrent();
		}));
		drawCheckbox(graphics, font, x + 2, y + 4, cfg.pauseGameWhileOpen());
		QuillDraw.text(graphics, font, "Pause game while Quill IDE is open (Singleplayer)",
				x + 20, y + 5, QuillTheme.TEXT_PRIMARY);
		y += 24;

		// Autosave mode selector
		QuillDraw.text(graphics, font, "Autosave Mode", x, y, QuillTheme.TEXT_PRIMARY);
		y += 12;
		QuillClientConfig.AutosaveMode[] autoModes = QuillClientConfig.AutosaveMode.values();
		int autoBtnX = x;
		for (QuillClientConfig.AutosaveMode mode : autoModes) {
			int bw = QuillDraw.textWidth(font, mode.displayName()) + 14;
			if (autoBtnX + bw > x + contentW && autoBtnX > x) {
				autoBtnX = x;
				y += 22;
			}
			IdeLayout.Rect r = new IdeLayout.Rect(autoBtnX, y, bw, 18);
			boolean active = cfg.autosaveMode() == mode;
			hoveredClickable |= renderTogglePill(graphics, font, r, mode.displayName(), active, mouseX, mouseY, area, () -> {
				cfg.setAutosaveMode(mode);
				QuillClientConfig.saveCurrent();
			});
			autoBtnX += bw + 6;
		}
		y += 24;

		// Reload trigger mode selector
		QuillDraw.text(graphics, font, "Reload Datapacks Trigger", x, y, QuillTheme.TEXT_PRIMARY);
		y += 12;
		QuillClientConfig.ReloadTriggerMode[] reloadModes = QuillClientConfig.ReloadTriggerMode.values();
		int reloadBtnX = x;
		for (QuillClientConfig.ReloadTriggerMode mode : reloadModes) {
			int bw = QuillDraw.textWidth(font, mode.displayName()) + 14;
			IdeLayout.Rect r = new IdeLayout.Rect(reloadBtnX, y, bw, 18);
			boolean active = cfg.reloadTriggerMode() == mode;
			hoveredClickable |= renderTogglePill(graphics, font, r, mode.displayName(), active, mouseX, mouseY, area, () -> {
				cfg.setReloadTriggerMode(mode);
				QuillClientConfig.saveCurrent();
			});
			reloadBtnX += bw + 6;
		}
		y += 24;

		// Warning banner when ON_KEYPRESS + ON_SAVE
		if (cfg.hasInstantAutosaveReloadWarning()) {
			int warnTextW = Math.max(60, contentW - 18);
			List<String> warnTitleLines = QuillDraw.wrapLines(font, "[!] Warning: Instant Autosave + Reload On Save", warnTextW);
			List<String> warnBodyLines = QuillDraw.wrapLines(font, INSTANT_RELOAD_WARNING_BODY, warnTextW);
			int textTop = y + 5;
			int bodyTop = textTop + warnTitleLines.size() * lineStep + 1;
			int btnTop = bodyTop + warnBodyLines.size() * lineStep + 3;
			int warnH = (btnTop - y) + 15 + 5;

			QuillDraw.fill(graphics, x, y, contentW, warnH, QuillTheme.WARNING_BANNER_BG);
			QuillDraw.outline(graphics, x, y, contentW, warnH, QuillTheme.WARNING_BANNER_BORDER);
			QuillDraw.fill(graphics, x, y, 3, warnH, QuillTheme.TEXT_WARNING);
			for (int i = 0; i < warnTitleLines.size(); i++) {
				QuillDraw.textShadow(graphics, font, warnTitleLines.get(i),
						x + 9, textTop + i * lineStep, QuillTheme.TEXT_WARNING);
			}
			for (int i = 0; i < warnBodyLines.size(); i++) {
				QuillDraw.text(graphics, font, warnBodyLines.get(i),
						x + 9, bodyTop + i * lineStep, QuillTheme.TEXT_BRIGHT);
			}

			String fixLabel = "Switch Reload to On GUI Close";
			int fixW = QuillDraw.textWidth(font, fixLabel) + 12;
			IdeLayout.Rect fixRect = new IdeLayout.Rect(x + 9, btnTop, Math.min(contentW - 18, fixW), 15);
			hoveredClickable |= renderButton(graphics, font, fixRect, fixLabel, true, false, mouseX, mouseY, area, () -> {
				cfg.setReloadTriggerMode(QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE);
				QuillClientConfig.saveCurrent();
			});
			y += warnH + 8;
		}

		// ========== SECTION 3: DATAPACK TEMPLATE DEFAULTS ==========
		QuillDraw.hLine(graphics, x, contentW, y, QuillTheme.BORDER_SUBTLE);
		y += 10;
		y = renderSectionTitle(graphics, font, x, y, "New Datapack Template Defaults");
		for (String line : QuillDraw.wrapLines(font, "Used when 'Use template' is checked in New Datapack.", contentW)) {
			QuillDraw.text(graphics, font, line, x, y, QuillTheme.TEXT_MUTED);
			y += lineStep;
		}
		y += 2;

		String[] tplLabels = {"Default Namespace", "Default Load Function", "Default Tick Function"};
		String[] tplPlaceholders = {"demo", "load", "tick"};
		int inputW = Math.min(220, Math.max(110, contentW - 140));
		int inputX = x + Math.min(140, contentW - inputW);
		for (int i = 0; i < 3; i++) {
			QuillDraw.text(graphics, font, tplLabels[i], x, y + 4, QuillTheme.TEXT_PRIMARY);
			IdeLayout.Rect box = new IdeLayout.Rect(inputX, y, inputW, 17);
			templateBoxRects[i] = box;
			boolean focused = focusedTemplateField == i;
			templateInput(i).render(graphics, font, box, tplPlaceholders[i], focused);
			if (templateErrors[i] != null) {
				QuillDraw.outline(graphics, box.x(), box.y(), box.width(), box.height(), QuillTheme.BG_STATUS_ERROR);
			}
			if (area.contains(mouseX, mouseY) && box.contains(mouseX, mouseY)) {
				hoveredInput = true;
			}
			y += 20;
			if (templateErrors[i] != null) {
				for (String errLine : QuillDraw.wrapLines(font, templateErrors[i], contentW)) {
					QuillDraw.text(graphics, font, errLine, x, y - 1, QuillTheme.TEXT_ERROR);
					y += lineStep;
				}
			}
		}
		y += 6;

		// ========== SECTION 4: WINDOW & LAYOUT ==========
		QuillDraw.hLine(graphics, x, contentW, y, QuillTheme.BORDER_SUBTLE);
		y += 10;
		y = renderSectionTitle(graphics, font, x, y, "Window Position & Scale");
		String winInfo = cfg.hasCustomWindowBounds()
				? "Custom window: (" + cfg.windowX() + ", " + cfg.windowY() + ") " + cfg.windowWidth() + "x" + cfg.windowHeight()
				: "Default docked window (drag Title Bar to move, drag edges/corners to resize)";
		for (String line : QuillDraw.wrapLines(font, winInfo, contentW)) {
			QuillDraw.text(graphics, font, line, x, y, QuillTheme.TEXT_MUTED);
			y += lineStep;
		}
		y += 1;
		String splitInfo = "Sidebar: " + cfg.sidebarWidth() + "px  |  Bottom Panel: " + cfg.bottomPanelHeight() + "px";
		for (String line : QuillDraw.wrapLines(font, splitInfo, contentW)) {
			QuillDraw.text(graphics, font, line, x, y, QuillTheme.TEXT_MUTED);
			y += lineStep;
		}
		y += 3;

		String resetWinLabel = "Reset Window Position & Size";
		IdeLayout.Rect resetWinRect = new IdeLayout.Rect(x, y, QuillDraw.textWidth(font, resetWinLabel) + 14, 18);
		hoveredClickable |= renderButton(graphics, font, resetWinRect, resetWinLabel, false, false, mouseX, mouseY, area, () -> {
			cfg.resetWindowBounds();
			QuillClientConfig.saveCurrent();
			if (onResetWindow != null) {
				onResetWindow.run();
			}
		});
		y += 28;

		// ========== SECTION 5: KEYBOARD SHORTCUTS ==========
		QuillDraw.hLine(graphics, x, contentW, y, QuillTheme.BORDER_SUBTLE);
		y += 10;
		y = renderSectionTitle(graphics, font, x, y, "Keyboard Shortcuts");
		for (String line : QuillDraw.wrapLines(font, "Click a binding to record a new key combination (Esc sets Not Bound).", contentW)) {
			QuillDraw.text(graphics, font, line, x, y, QuillTheme.TEXT_MUTED);
			y += lineStep;
		}
		y += 2;

		for (QuillClientConfig.ShortcutAction action : QuillClientConfig.ShortcutAction.values()) {
			boolean recording = recordingAction == action;
			KeyShortcut current = cfg.shortcut(action);
			boolean isDefault = current.equals(action.defaultShortcut());

			int rowH = 19;
			IdeLayout.Rect rowRect = new IdeLayout.Rect(x, y, contentW, rowH);
			if (recording) {
				QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.ROW_SELECTED);
			} else if (area.contains(mouseX, mouseY) && rowRect.contains(mouseX, mouseY)) {
				QuillDraw.fill(graphics, rowRect.x(), rowRect.y(), rowRect.width(), rowRect.height(), QuillTheme.BG_HOVER);
			}

			int resetW = QuillDraw.textWidth(font, "Reset") + 8;
			IdeLayout.Rect resetRowRect = new IdeLayout.Rect(x + contentW - resetW, y + 1, resetW, rowH - 2);
			String bindLabel = recording ? "Press keys..." : current.displayLabel();
			int bindW = Math.max(84, QuillDraw.textWidth(font, bindLabel) + 14);
			IdeLayout.Rect bindRect = new IdeLayout.Rect(resetRowRect.x() - bindW - 6, y + 1, bindW, rowH - 2);

			int labelMaxW = Math.max(40, bindRect.x() - x - 8);
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, action.label(), labelMaxW),
					x + 4, y + (rowH - font.lineHeight) / 2, QuillTheme.TEXT_PRIMARY);

			hoveredClickable |= renderTogglePill(graphics, font, bindRect, bindLabel, recording, mouseX, mouseY, area, () -> {
				focusedTemplateField = -1;
				recordingAction = (recordingAction == action) ? null : action;
			});

			if (!isDefault) {
				hoveredClickable |= renderButton(graphics, font, resetRowRect, "Reset", false, false, mouseX, mouseY, area, () -> {
					cfg.resetShortcut(action);
					QuillClientConfig.saveCurrent();
					if (recordingAction == action) {
						recordingAction = null;
					}
				});
			}
			y += rowH + 2;
		}

		y += 8;
		String resetKeysLabel = "Reset All Shortcuts";
		IdeLayout.Rect resetKeysRect = new IdeLayout.Rect(x, y, QuillDraw.textWidth(font, resetKeysLabel) + 14, 18);
		hoveredClickable |= renderButton(graphics, font, resetKeysRect, resetKeysLabel, false, false, mouseX, mouseY, area, () -> {
			for (QuillClientConfig.ShortcutAction a : QuillClientConfig.ShortcutAction.values()) {
				cfg.resetShortcut(a);
			}
			recordingAction = null;
			QuillClientConfig.saveCurrent();
		});
		y += 28;

		contentHeight = Math.max(0, y - startY + 12);
		QuillDraw.endClip(graphics);

		// Scrollbar
		int updatedMaxScroll = Math.max(0, contentHeight - area.height() + 16);
		if (updatedMaxScroll > 0 && area.height() > 16) {
			int trackX = area.right() - 6;
			int thumbH = Math.max(16, (int) ((long) area.height() * area.height() / Math.max(1, contentHeight)));
			int thumbY = area.y() + (int) ((long) scrollPixels * (area.height() - thumbH) / updatedMaxScroll);
			QuillDraw.fill(graphics, trackX, thumbY, 4, thumbH, QuillTheme.TEXT_MUTED);
		}

		if (hoveredInput) {
			QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
		} else if (hoveredClickable) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (!lastArea.contains(event.x(), event.y())) {
			return false;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		for (int i = 0; i < 3; i++) {
			if (templateBoxRects[i].contains(mx, my)) {
				recordingAction = null;
				focusedTemplateField = i;
				templateInput(i).mouseClicked(mx, safeFont(), event.hasShiftDown());
				return true;
			}
		}

		focusedTemplateField = -1;
		for (int i = clickTargets.size() - 1; i >= 0; i--) {
			ClickTarget target = clickTargets.get(i);
			if (target.rect().contains(mx, my)) {
				target.action().run();
				return true;
			}
		}
		recordingAction = null;
		return true;
	}

	public boolean mouseDragged(MouseButtonEvent event) {
		if (focusedTemplateField >= 0 && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			SingleLineTextInput input = templateInput(focusedTemplateField);
			if (input.isDragging()) {
				return input.mouseDragged(event.x(), safeFont());
			}
		}
		return false;
	}

	public void mouseReleased(MouseButtonEvent event) {
		namespaceInput.mouseReleased();
		loadFnInput.mouseReleased();
		tickFnInput.mouseReleased();
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!lastArea.contains(mouseX, mouseY)) {
			return false;
		}
		int maxScroll = Math.max(0, contentHeight - lastArea.height() + 16);
		scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels - (int) (scrollY * 26)));
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		if (recordingAction != null) {
			if (event.isEscape()) {
				QuillClientConfig.current().setShortcut(recordingAction, KeyShortcut.UNBOUND);
				QuillClientConfig.saveCurrent();
				recordingAction = null;
				return true;
			}
			if (KeyShortcut.isModifierOnly(event)) {
				return true;
			}
			KeyShortcut captured = KeyShortcut.fromKeyEvent(event);
			if (captured != null) {
				QuillClientConfig.current().setShortcut(recordingAction, captured);
				QuillClientConfig.saveCurrent();
				recordingAction = null;
			}
			return true;
		}
		if (focusedTemplateField >= 0) {
			if (event.isEscape()) {
				focusedTemplateField = -1;
				return true;
			}
			if (event.key() == InputConstants.KEY_TAB) {
				int delta = event.hasShiftDown() ? -1 : 1;
				focusedTemplateField = (focusedTemplateField + delta + 3) % 3;
				templateInput(focusedTemplateField).selectAll();
				return true;
			}
			if (event.isConfirmation()) {
				commitTemplateField(focusedTemplateField);
				focusedTemplateField = -1;
				return true;
			}
			SingleLineTextInput.KeyActionResult res = templateInput(focusedTemplateField).keyPressed(event);
			if (res.textChanged()) {
				commitTemplateField(focusedTemplateField);
			}
			return true;
		}
		return false;
	}

	public boolean charTyped(CharacterEvent event) {
		if (recordingAction != null) {
			return true;
		}
		if (focusedTemplateField >= 0) {
			if (templateInput(focusedTemplateField).charTyped(event)) {
				commitTemplateField(focusedTemplateField);
			}
			return true;
		}
		return false;
	}

	private void commitTemplateField(int idx) {
		QuillClientConfig cfg = QuillClientConfig.current();
		String raw = templateInput(idx).text().trim().toLowerCase(Locale.ROOT);
		if (raw.isEmpty()) {
			templateErrors[idx] = "Value cannot be empty.";
			return;
		}
		try {
			switch (idx) {
				case 0 -> {
					DatapackScaffolder.validateNamespace(raw);
					cfg.setDefaultTemplateNamespace(raw);
				}
				case 1 -> {
					DatapackScaffolder.validateFunctionPath(raw);
					cfg.setDefaultLoadFunctionName(raw);
				}
				case 2 -> {
					DatapackScaffolder.validateFunctionPath(raw);
					cfg.setDefaultTickFunctionName(raw);
				}
				default -> {
					return;
				}
			}
			templateErrors[idx] = null;
			QuillClientConfig.saveCurrent();
		} catch (RuntimeException e) {
			templateErrors[idx] = e.getMessage();
		}
	}

	private SingleLineTextInput templateInput(int idx) {
		return switch (idx) {
			case 0 -> namespaceInput;
			case 1 -> loadFnInput;
			default -> tickFnInput;
		};
	}

	private int renderSectionTitle(GuiGraphicsExtractor graphics, Font font, int x, int y, String title) {
		QuillDraw.textShadow(graphics, font, title, x, y, QuillTheme.TEXT_BRIGHT);
		return y + 14;
	}

	private boolean renderButton(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect rect,
			String label,
			boolean primary,
			boolean danger,
			int mouseX,
			int mouseY,
			IdeLayout.Rect clipArea,
			Runnable onClick
	) {
		clickTargets.add(new ClickTarget(rect, onClick));
		boolean hover = clipArea.contains(mouseX, mouseY) && rect.contains(mouseX, mouseY);
		int bg = primary
				? (hover ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON)
				: (danger ? QuillTheme.BG_BUTTON_DANGER : (hover ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY));
		QuillDraw.fill(graphics, rect.x(), rect.y(), rect.width(), rect.height(), bg);
		QuillDraw.outline(graphics, rect.x(), rect.y(), rect.width(), rect.height(),
				primary ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE);
		int tx = rect.x() + (rect.width() - QuillDraw.textWidth(font, label)) / 2;
		int ty = rect.y() + (rect.height() - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, label, tx, ty, primary ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY);
		return hover;
	}

	private boolean renderTogglePill(
			GuiGraphicsExtractor graphics,
			Font font,
			IdeLayout.Rect rect,
			String label,
			boolean active,
			int mouseX,
			int mouseY,
			IdeLayout.Rect clipArea,
			Runnable onClick
	) {
		clickTargets.add(new ClickTarget(rect, onClick));
		boolean hover = clipArea.contains(mouseX, mouseY) && rect.contains(mouseX, mouseY);
		int bg = active ? QuillTheme.BG_BUTTON : (hover ? QuillTheme.BG_HOVER : QuillTheme.BG_SIDEBAR);
		int border = active ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE;
		QuillDraw.fill(graphics, rect.x(), rect.y(), rect.width(), rect.height(), bg);
		QuillDraw.outline(graphics, rect.x(), rect.y(), rect.width(), rect.height(), border);
		int tx = rect.x() + (rect.width() - QuillDraw.textWidth(font, label)) / 2;
		int ty = rect.y() + (rect.height() - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, label, tx, ty, active ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_PRIMARY);
		return hover;
	}

	private static void drawCheckbox(GuiGraphicsExtractor graphics, Font font, int x, int y, boolean checked) {
		QuillDraw.fill(graphics, x, y, 12, 12, checked ? QuillTheme.BG_BUTTON : QuillTheme.BG_INPUT);
		QuillDraw.outline(graphics, x, y, 12, 12, checked ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE);
		if (checked) {
			QuillDraw.text(graphics, font, "x", x + 3, y + 2, QuillTheme.TEXT_BRIGHT);
		}
	}

	private static Font safeFont() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null ? mc.font : null;
	}
}
