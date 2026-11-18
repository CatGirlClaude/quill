package dev.catgirlclaude.quill.client.config;

import com.mojang.blaze3d.platform.InputConstants;
import dev.catgirlclaude.quill.client.gui.QuillTheme;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuillClientConfigTest {

	@AfterEach
	void resetDefaultConfig() {
		QuillClientConfig.setCurrent(QuillClientConfig.createDefault());
	}

	@Test
	void defaultsAndWarningConditionWorkAsExpected() {
		QuillClientConfig cfg = QuillClientConfig.createDefault();
		assertEquals(QuillColorPalette.DARK_PLUS.id(), cfg.themeId());
		assertEquals(QuillClientConfig.FontChoice.MINECRAFT_DEFAULT, cfg.fontChoice());
		assertEquals(QuillClientConfig.DEFAULT_TEXT_SIZE, cfg.textSize());
		assertFalse(cfg.pauseGameWhileOpen());
		assertEquals(QuillClientConfig.AutosaveMode.OFF, cfg.autosaveMode());
		assertEquals(QuillClientConfig.ReloadTriggerMode.ON_SAVE, cfg.reloadTriggerMode());
		assertFalse(cfg.hasInstantAutosaveReloadWarning());
		assertEquals("demo", cfg.defaultTemplateNamespace());
		assertEquals("load", cfg.defaultLoadFunctionName());
		assertEquals("tick", cfg.defaultTickFunctionName());
		assertFalse(cfg.hasCustomWindowBounds());

		cfg.setAutosaveMode(QuillClientConfig.AutosaveMode.ON_KEYPRESS);
		assertTrue(cfg.hasInstantAutosaveReloadWarning(),
				"Instant keypress autosave + reload on save must trigger warning");

		cfg.setReloadTriggerMode(QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE);
		assertFalse(cfg.hasInstantAutosaveReloadWarning());
	}

	@Test
	void jsonPersistenceRoundTripPreservesAllSettings(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("quill-client.json");
		QuillClientConfig cfg = QuillClientConfig.createDefault();
		cfg.setThemeId(QuillColorPalette.DRACULA.id());
		cfg.setFontChoice(QuillClientConfig.FontChoice.SANS_SERIF);
		cfg.setTextSize(12);
		cfg.setPauseGameWhileOpen(true);
		cfg.setAutosaveMode(QuillClientConfig.AutosaveMode.ON_GUI_CLOSE);
		cfg.setReloadTriggerMode(QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE);
		cfg.setDefaultTemplateNamespace("my_ns");
		cfg.setDefaultLoadFunctionName("init/boot");
		cfg.setDefaultTickFunctionName("loop/main");
		cfg.setCustomWindowBounds(40, 24, 620, 380);
		cfg.setSidebarWidth(240);
		cfg.setBottomPanelHeight(130);
		cfg.setShortcut(QuillClientConfig.ShortcutAction.SAVE, KeyShortcut.ofChar(true, true, false, 's'));
		cfg.setShortcut(QuillClientConfig.ShortcutAction.RUN_FUNCTION, KeyShortcut.ofFunctionKey(false, false, false, 5));

		cfg.save(file);

		QuillClientConfig loaded = QuillClientConfig.load(file);
		assertEquals(QuillColorPalette.DRACULA.id(), loaded.themeId());
		assertEquals(QuillClientConfig.FontChoice.SANS_SERIF, loaded.fontChoice());
		assertEquals(12, loaded.textSize());
		assertTrue(loaded.pauseGameWhileOpen());
		assertEquals(QuillClientConfig.AutosaveMode.ON_GUI_CLOSE, loaded.autosaveMode());
		assertEquals(QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE, loaded.reloadTriggerMode());
		assertEquals("my_ns", loaded.defaultTemplateNamespace());
		assertEquals("init/boot", loaded.defaultLoadFunctionName());
		assertEquals("loop/main", loaded.defaultTickFunctionName());
		assertTrue(loaded.hasCustomWindowBounds());
		assertEquals(40, loaded.windowX());
		assertEquals(24, loaded.windowY());
		assertEquals(620, loaded.windowWidth());
		assertEquals(380, loaded.windowHeight());
		assertEquals(240, loaded.sidebarWidth());
		assertEquals(130, loaded.bottomPanelHeight());
		assertEquals("Ctrl+Shift+S", loaded.shortcut(QuillClientConfig.ShortcutAction.SAVE).displayLabel());
		assertEquals("F5", loaded.shortcut(QuillClientConfig.ShortcutAction.RUN_FUNCTION).displayLabel());
	}

	@Test
	void themeSwitchingUpdatesDynamicQuillThemeTokens() {
		QuillClientConfig cfg = QuillClientConfig.createDefault();
		QuillClientConfig.setCurrent(cfg);
		int darkEditorBg = QuillTheme.BG_EDITOR;
		assertFalse(QuillTheme.isLightTheme());

		cfg.setThemeId(QuillColorPalette.LIGHT_PLUS.id());
		assertTrue(QuillTheme.isLightTheme());
		assertNotEquals(darkEditorBg, QuillTheme.BG_EDITOR);
		assertEquals(QuillColorPalette.LIGHT_PLUS.bgEditor(), QuillTheme.BG_EDITOR);

		assertEquals(7, QuillColorPalette.all().size());
		for (QuillColorPalette p : QuillColorPalette.all()) {
			cfg.setThemeId(p.id());
			assertEquals(p.id(), cfg.palette().id());
			assertEquals(p.accentBlue(), QuillTheme.ACCENT_BLUE);
		}
	}

	@Test
	void clampingAndTemplateDefaultValidationRejectInvalidValues() {
		QuillClientConfig cfg = QuillClientConfig.createDefault();
		cfg.setTextSize(99);
		assertEquals(QuillClientConfig.MAX_TEXT_SIZE, cfg.textSize());
		cfg.setTextSize(2);
		assertEquals(QuillClientConfig.MIN_TEXT_SIZE, cfg.textSize());

		cfg.setDefaultTemplateNamespace("../bad!");
		assertEquals("demo", cfg.defaultTemplateNamespace());
		cfg.setDefaultLoadFunctionName("INVALID SPACE");
		assertEquals("load", cfg.defaultLoadFunctionName());
		cfg.setDefaultTickFunctionName("bad\\path");
		assertEquals("tick", cfg.defaultTickFunctionName());
	}

	@Test
	void keyShortcutParseFormatMatchAndCaptureWork() {
		KeyShortcut save = KeyShortcut.parse("Ctrl+S", null);
		assertEquals("Ctrl+S", save.displayLabel());
		assertTrue(save.matches(new KeyEvent(InputConstants.KEY_S, 's', InputConstants.MOD_CONTROL)));
		assertFalse(save.matches(new KeyEvent(InputConstants.KEY_S, 's', InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT)));
		assertFalse(save.matches(new KeyEvent(InputConstants.KEY_S, 's', 0)));

		KeyShortcut f5 = KeyShortcut.parse("Shift+F5", null);
		assertEquals("Shift+F5", f5.displayLabel());
		assertTrue(f5.matches(new KeyEvent(InputConstants.KEY_F5, 0, InputConstants.MOD_SHIFT)));

		KeyShortcut space = KeyShortcut.parse("Ctrl+Space", null);
		assertEquals("Ctrl+Space", space.displayLabel());
		assertTrue(space.matches(new KeyEvent(InputConstants.KEY_SPACE, InputConstants.KEYCODE_SPACE, InputConstants.MOD_CONTROL)));

		KeyShortcut captured = KeyShortcut.fromKeyEvent(new KeyEvent(InputConstants.KEY_K, 'k', InputConstants.MOD_CONTROL | InputConstants.MOD_ALT));
		assertEquals("Ctrl+Alt+K", captured.displayLabel());

		assertNull(KeyShortcut.fromKeyEvent(new KeyEvent(InputConstants.KEY_LCONTROL, 0, InputConstants.MOD_CONTROL)));
		assertNull(KeyShortcut.fromKeyEvent(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
	}
}
