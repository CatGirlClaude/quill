package dev.catgirlclaude.quill.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.catgirlclaude.quill.Quill;
import dev.catgirlclaude.quill.client.gui.QuillTheme;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Global client-side configuration persisted to {@code config/quill-client.json}.
 * Controls VS Code color theme, font family, text size, pause-on-open, autosave/reload triggers,
 * datapack template defaults, window geometry, and customizable IDE keyboard shortcuts.
 */
public final class QuillClientConfig {
	public static final int MIN_TEXT_SIZE = 7;
	public static final int MAX_TEXT_SIZE = 16;
	public static final int DEFAULT_TEXT_SIZE = 9;
	public static final int DEFAULT_SIDEBAR_WIDTH = 204;
	public static final int DEFAULT_BOTTOM_PANEL_HEIGHT = 96;

	public enum FontChoice {
		MINECRAFT_DEFAULT("minecraft_default", "Minecraft Default", "minecraft:default"),
		MINECRAFT_UNIFORM("minecraft_uniform", "Minecraft Uniform", "minecraft:uniform"),
		SANS_SERIF("sans_serif", "Sans-Serif (Roboto)", "quill:sans_serif"),
		ARIAL("arial", "Arial Style (DejaVu Sans)", "quill:arial"),
		MONOSPACE("monospace", "Monospace (DejaVu Mono)", "quill:monospace");

		private final String id;
		private final String displayName;
		private final String resourceId;

		FontChoice(String id, String displayName, String resourceId) {
			this.id = id;
			this.displayName = displayName;
			this.resourceId = resourceId;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public String resourceId() {
			return resourceId;
		}

		public static FontChoice byId(String id) {
			if (id == null) {
				return SANS_SERIF;
			}
			for (FontChoice fc : values()) {
				if (fc.id.equalsIgnoreCase(id)) {
					return fc;
				}
			}
			return SANS_SERIF;
		}
	}

	public enum AutosaveMode {
		OFF("off", "Off (Manual Ctrl+S)"),
		ON_GUI_CLOSE("on_gui_close", "On GUI Close"),
		ON_KEYPRESS("on_keypress", "On Every Keypress");

		private final String id;
		private final String displayName;

		AutosaveMode(String id, String displayName) {
			this.id = id;
			this.displayName = displayName;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public static AutosaveMode byId(String id) {
			if (id == null) {
				return OFF;
			}
			for (AutosaveMode m : values()) {
				if (m.id.equalsIgnoreCase(id)) {
					return m;
				}
			}
			return OFF;
		}
	}

	public enum ReloadTriggerMode {
		ON_SAVE("on_save", "On Save"),
		ON_GUI_CLOSE("on_gui_close", "On GUI Close");

		private final String id;
		private final String displayName;

		ReloadTriggerMode(String id, String displayName) {
			this.id = id;
			this.displayName = displayName;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public static ReloadTriggerMode byId(String id) {
			if (id == null) {
				return ON_SAVE;
			}
			for (ReloadTriggerMode m : values()) {
				if (m.id.equalsIgnoreCase(id)) {
					return m;
				}
			}
			return ON_SAVE;
		}
	}

	public enum ShortcutAction {
		SAVE("save", "Save Active File", KeyShortcut.ofChar(true, false, false, 's')),
		QUICK_OPEN("quick_open", "Quick Open File", KeyShortcut.ofChar(true, false, false, 'p')),
		COMMAND_PALETTE("command_palette", "Command Palette", KeyShortcut.ofChar(true, true, false, 'p')),
		SEARCH_IN_FILES("search_in_files", "Search Across Files", KeyShortcut.ofChar(true, true, false, 'f')),
		TOGGLE_SIDEBAR("toggle_sidebar", "Toggle Sidebar", KeyShortcut.ofChar(true, false, false, 'b')),
		TOGGLE_BOTTOM_PANEL("toggle_bottom_panel", "Toggle Bottom Panel", KeyShortcut.ofChar(true, false, false, 'j')),
		CLOSE_TAB("close_tab", "Close Active Tab", KeyShortcut.ofChar(true, false, false, 'w')),
		OPEN_SETTINGS("open_settings", "Open Settings", KeyShortcut.ofChar(true, false, false, ',')),
		TRIGGER_AUTOCOMPLETE("trigger_autocomplete", "Trigger Autocomplete", KeyShortcut.ofSpace(true, false, false)),
		RUN_FUNCTION("run_function", "Run Current Function", KeyShortcut.ofChar(true, true, false, 'r')),
		RELOAD_DATAPACKS("reload_datapacks", "Reload Datapacks", KeyShortcut.ofChar(true, false, false, 'r')),
		ZOOM_IN("zoom_in", "Zoom In UI", KeyShortcut.ofChar(true, false, false, '=')),
		ZOOM_OUT("zoom_out", "Zoom Out UI", KeyShortcut.ofChar(true, false, false, '-')),
		ZOOM_RESET("zoom_reset", "Reset UI Zoom", KeyShortcut.ofChar(true, false, false, '0'));

		private final String id;
		private final String label;
		private final KeyShortcut defaultShortcut;

		ShortcutAction(String id, String label, KeyShortcut defaultShortcut) {
			this.id = id;
			this.label = label;
			this.defaultShortcut = defaultShortcut;
		}

		public String id() {
			return id;
		}

		public String label() {
			return label;
		}

		public KeyShortcut defaultShortcut() {
			return defaultShortcut;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static volatile QuillClientConfig current = createDefault();
	private static volatile Path boundPath = null;

	private String themeId = QuillColorPalette.DARK_PLUS.id();
	private FontChoice fontChoice = FontChoice.SANS_SERIF;
	private int textSize = DEFAULT_TEXT_SIZE;
	private boolean pauseGameWhileOpen = false;
	private AutosaveMode autosaveMode = AutosaveMode.OFF;
	private ReloadTriggerMode reloadTriggerMode = ReloadTriggerMode.ON_SAVE;
	private String defaultTemplateNamespace = "demo";
	private String defaultLoadFunctionName = "load";
	private String defaultTickFunctionName = "tick";

	// Custom floating window bounds (-1 means default auto-centered docked size)
	private int windowX = -1;
	private int windowY = -1;
	private int windowWidth = -1;
	private int windowHeight = -1;
	private int sidebarWidth = DEFAULT_SIDEBAR_WIDTH;
	private int bottomPanelHeight = DEFAULT_BOTTOM_PANEL_HEIGHT;

	private final EnumMap<ShortcutAction, KeyShortcut> shortcuts = new EnumMap<>(ShortcutAction.class);

	public QuillClientConfig() {
		for (ShortcutAction action : ShortcutAction.values()) {
			shortcuts.put(action, action.defaultShortcut());
		}
	}

	public static QuillClientConfig createDefault() {
		return new QuillClientConfig();
	}

	public static QuillClientConfig current() {
		return current;
	}

	public static void setCurrent(QuillClientConfig config) {
		current = Objects.requireNonNull(config, "config");
		QuillTheme.applyPalette(current.palette());
	}

	public static void loadFromDefaultPath() {
		try {
			Path configPath = FabricLoader.getInstance().getConfigDir().resolve("quill-client.json");
			boundPath = configPath;
			QuillClientConfig loaded = load(configPath);
			setCurrent(loaded);
		} catch (Throwable t) {
			Quill.LOGGER.warn("Could not load quill-client.json; using defaults: {}", t.getMessage());
			setCurrent(createDefault());
		}
	}

	public static void saveCurrent() {
		Path path = boundPath;
		if (path == null) {
			try {
				path = FabricLoader.getInstance().getConfigDir().resolve("quill-client.json");
				boundPath = path;
			} catch (Throwable ignored) {
				return;
			}
		}
		try {
			current.save(path);
		} catch (IOException e) {
			Quill.LOGGER.warn("Failed to save quill-client.json: {}", e.getMessage());
		}
	}

	public static QuillClientConfig load(Path path) {
		QuillClientConfig cfg = createDefault();
		if (path == null || !Files.isRegularFile(path)) {
			return cfg;
		}
		try {
			String raw = Files.readString(path, StandardCharsets.UTF_8);
			JsonElement rootEl = JsonParser.parseString(raw);
			if (!rootEl.isJsonObject()) {
				return cfg;
			}
			JsonObject obj = rootEl.getAsJsonObject();
			if (obj.has("themeId") && obj.get("themeId").isJsonPrimitive()) {
				cfg.setThemeId(obj.get("themeId").getAsString());
			}
			if (obj.has("fontId") && obj.get("fontId").isJsonPrimitive()) {
				cfg.setFontChoice(FontChoice.byId(obj.get("fontId").getAsString()));
			}
			if (obj.has("textSize") && obj.get("textSize").isJsonPrimitive()) {
				cfg.setTextSize(obj.get("textSize").getAsInt());
			}
			if (obj.has("pauseGameWhileOpen") && obj.get("pauseGameWhileOpen").isJsonPrimitive()) {
				cfg.setPauseGameWhileOpen(obj.get("pauseGameWhileOpen").getAsBoolean());
			}
			if (obj.has("autosaveMode") && obj.get("autosaveMode").isJsonPrimitive()) {
				cfg.setAutosaveMode(AutosaveMode.byId(obj.get("autosaveMode").getAsString()));
			}
			if (obj.has("reloadTriggerMode") && obj.get("reloadTriggerMode").isJsonPrimitive()) {
				cfg.setReloadTriggerMode(ReloadTriggerMode.byId(obj.get("reloadTriggerMode").getAsString()));
			}
			if (obj.has("defaultTemplateNamespace") && obj.get("defaultTemplateNamespace").isJsonPrimitive()) {
				cfg.setDefaultTemplateNamespace(obj.get("defaultTemplateNamespace").getAsString());
			}
			if (obj.has("defaultLoadFunctionName") && obj.get("defaultLoadFunctionName").isJsonPrimitive()) {
				cfg.setDefaultLoadFunctionName(obj.get("defaultLoadFunctionName").getAsString());
			}
			if (obj.has("defaultTickFunctionName") && obj.get("defaultTickFunctionName").isJsonPrimitive()) {
				cfg.setDefaultTickFunctionName(obj.get("defaultTickFunctionName").getAsString());
			}
			if (obj.has("windowX") && obj.get("windowX").isJsonPrimitive()) {
				cfg.windowX = obj.get("windowX").getAsInt();
			}
			if (obj.has("windowY") && obj.get("windowY").isJsonPrimitive()) {
				cfg.windowY = obj.get("windowY").getAsInt();
			}
			if (obj.has("windowWidth") && obj.get("windowWidth").isJsonPrimitive()) {
				cfg.windowWidth = obj.get("windowWidth").getAsInt();
			}
			if (obj.has("windowHeight") && obj.get("windowHeight").isJsonPrimitive()) {
				cfg.windowHeight = obj.get("windowHeight").getAsInt();
			}
			if (obj.has("sidebarWidth") && obj.get("sidebarWidth").isJsonPrimitive()) {
				cfg.setSidebarWidth(obj.get("sidebarWidth").getAsInt());
			}
			if (obj.has("bottomPanelHeight") && obj.get("bottomPanelHeight").isJsonPrimitive()) {
				cfg.setBottomPanelHeight(obj.get("bottomPanelHeight").getAsInt());
			}
			if (obj.has("shortcuts") && obj.get("shortcuts").isJsonObject()) {
				JsonObject scObj = obj.getAsJsonObject("shortcuts");
				for (ShortcutAction action : ShortcutAction.values()) {
					if (scObj.has(action.id()) && scObj.get(action.id()).isJsonPrimitive()) {
						KeyShortcut parsed = KeyShortcut.parse(scObj.get(action.id()).getAsString(), action.defaultShortcut());
						cfg.shortcuts.put(action, parsed);
					}
				}
			}
		} catch (Exception e) {
			Quill.LOGGER.warn("Malformed quill-client.json ({}); falling back to defaults.", e.getMessage());
		}
		return cfg;
	}

	public void save(Path path) throws IOException {
		Objects.requireNonNull(path, "path");
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("themeId", themeId);
		obj.addProperty("fontId", fontChoice.id());
		obj.addProperty("textSize", textSize);
		obj.addProperty("pauseGameWhileOpen", pauseGameWhileOpen);
		obj.addProperty("autosaveMode", autosaveMode.id());
		obj.addProperty("reloadTriggerMode", reloadTriggerMode.id());
		obj.addProperty("defaultTemplateNamespace", defaultTemplateNamespace);
		obj.addProperty("defaultLoadFunctionName", defaultLoadFunctionName);
		obj.addProperty("defaultTickFunctionName", defaultTickFunctionName);
		obj.addProperty("windowX", windowX);
		obj.addProperty("windowY", windowY);
		obj.addProperty("windowWidth", windowWidth);
		obj.addProperty("windowHeight", windowHeight);
		obj.addProperty("sidebarWidth", sidebarWidth);
		obj.addProperty("bottomPanelHeight", bottomPanelHeight);

		JsonObject scObj = new JsonObject();
		for (ShortcutAction action : ShortcutAction.values()) {
			scObj.addProperty(action.id(), shortcut(action).format());
		}
		obj.add("shortcuts", scObj);

		Files.writeString(path, GSON.toJson(obj) + "\n", StandardCharsets.UTF_8);
	}

	public void resetAllToDefaults() {
		QuillClientConfig fresh = createDefault();
		this.themeId = fresh.themeId;
		this.fontChoice = fresh.fontChoice;
		this.textSize = fresh.textSize;
		this.pauseGameWhileOpen = fresh.pauseGameWhileOpen;
		this.autosaveMode = fresh.autosaveMode;
		this.reloadTriggerMode = fresh.reloadTriggerMode;
		this.defaultTemplateNamespace = fresh.defaultTemplateNamespace;
		this.defaultLoadFunctionName = fresh.defaultLoadFunctionName;
		this.defaultTickFunctionName = fresh.defaultTickFunctionName;
		this.windowX = fresh.windowX;
		this.windowY = fresh.windowY;
		this.windowWidth = fresh.windowWidth;
		this.windowHeight = fresh.windowHeight;
		this.sidebarWidth = fresh.sidebarWidth;
		this.bottomPanelHeight = fresh.bottomPanelHeight;
		this.shortcuts.clear();
		this.shortcuts.putAll(fresh.shortcuts);
		QuillTheme.applyPalette(palette());
	}

	public String themeId() {
		return themeId;
	}

	public QuillColorPalette palette() {
		return QuillColorPalette.byId(themeId);
	}

	public void setThemeId(String themeId) {
		QuillColorPalette resolved = QuillColorPalette.byId(themeId);
		this.themeId = resolved.id();
		if (this == current) {
			QuillTheme.applyPalette(resolved);
		}
	}

	public FontChoice fontChoice() {
		return fontChoice;
	}

	public void setFontChoice(FontChoice fontChoice) {
		this.fontChoice = fontChoice == null ? FontChoice.SANS_SERIF : fontChoice;
	}

	public int textSize() {
		return textSize;
	}

	public void setTextSize(int textSize) {
		this.textSize = Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, textSize));
	}

	public float uiScale() {
		return textSize / (float) DEFAULT_TEXT_SIZE;
	}

	public float editorTextScale() {
		return uiScale();
	}

	public boolean zoomIn() {
		int prev = textSize;
		setTextSize(textSize + 1);
		return textSize != prev;
	}

	public boolean zoomOut() {
		int prev = textSize;
		setTextSize(textSize - 1);
		return textSize != prev;
	}

	public boolean resetZoom() {
		int prev = textSize;
		setTextSize(DEFAULT_TEXT_SIZE);
		return textSize != prev;
	}

	public boolean pauseGameWhileOpen() {
		return pauseGameWhileOpen;
	}

	public void setPauseGameWhileOpen(boolean pauseGameWhileOpen) {
		this.pauseGameWhileOpen = pauseGameWhileOpen;
	}

	public AutosaveMode autosaveMode() {
		return autosaveMode;
	}

	public void setAutosaveMode(AutosaveMode autosaveMode) {
		this.autosaveMode = autosaveMode == null ? AutosaveMode.OFF : autosaveMode;
	}

	public ReloadTriggerMode reloadTriggerMode() {
		return reloadTriggerMode;
	}

	public void setReloadTriggerMode(ReloadTriggerMode reloadTriggerMode) {
		this.reloadTriggerMode = reloadTriggerMode == null ? ReloadTriggerMode.ON_SAVE : reloadTriggerMode;
	}

	/**
	 * Returns {@code true} when instant keypress autosave and reload-on-save are simultaneously enabled,
	 * which would trigger a datapack reload on every keystroke.
	 */
	public boolean hasInstantAutosaveReloadWarning() {
		return autosaveMode == AutosaveMode.ON_KEYPRESS && reloadTriggerMode == ReloadTriggerMode.ON_SAVE;
	}

	public boolean shouldReloadOnSave() {
		return reloadTriggerMode == ReloadTriggerMode.ON_SAVE;
	}

	public String defaultTemplateNamespace() {
		return defaultTemplateNamespace;
	}

	public void setDefaultTemplateNamespace(String ns) {
		if (ns == null || ns.isBlank()) {
			return;
		}
		try {
			this.defaultTemplateNamespace = DatapackScaffolder.validateNamespace(ns.trim().toLowerCase(Locale.ROOT));
		} catch (RuntimeException ignored) {
			// Preserve previous valid value
		}
	}

	public String defaultLoadFunctionName() {
		return defaultLoadFunctionName;
	}

	public void setDefaultLoadFunctionName(String name) {
		if (name == null || name.isBlank()) {
			return;
		}
		try {
			this.defaultLoadFunctionName = DatapackScaffolder.validateFunctionPath(name.trim().toLowerCase(Locale.ROOT));
		} catch (RuntimeException ignored) {
			// Preserve previous valid value
		}
	}

	public String defaultTickFunctionName() {
		return defaultTickFunctionName;
	}

	public void setDefaultTickFunctionName(String name) {
		if (name == null || name.isBlank()) {
			return;
		}
		try {
			this.defaultTickFunctionName = DatapackScaffolder.validateFunctionPath(name.trim().toLowerCase(Locale.ROOT));
		} catch (RuntimeException ignored) {
			// Preserve previous valid value
		}
	}

	public boolean hasCustomWindowBounds() {
		return windowWidth > 0 && windowHeight > 0 && windowX >= 0 && windowY >= 0;
	}

	public int windowX() {
		return windowX;
	}

	public int windowY() {
		return windowY;
	}

	public int windowWidth() {
		return windowWidth;
	}

	public int windowHeight() {
		return windowHeight;
	}

	public void setCustomWindowBounds(int x, int y, int width, int height) {
		this.windowX = Math.max(0, x);
		this.windowY = Math.max(0, y);
		this.windowWidth = Math.max(240, width);
		this.windowHeight = Math.max(160, height);
	}

	public void resetWindowBounds() {
		this.windowX = -1;
		this.windowY = -1;
		this.windowWidth = -1;
		this.windowHeight = -1;
		this.sidebarWidth = DEFAULT_SIDEBAR_WIDTH;
		this.bottomPanelHeight = DEFAULT_BOTTOM_PANEL_HEIGHT;
	}

	public int sidebarWidth() {
		return sidebarWidth;
	}

	public void setSidebarWidth(int sidebarWidth) {
		this.sidebarWidth = Math.max(120, Math.min(400, sidebarWidth));
	}

	public int bottomPanelHeight() {
		return bottomPanelHeight;
	}

	public void setBottomPanelHeight(int bottomPanelHeight) {
		this.bottomPanelHeight = Math.max(60, Math.min(280, bottomPanelHeight));
	}

	public KeyShortcut shortcut(ShortcutAction action) {
		return shortcuts.getOrDefault(action, action.defaultShortcut());
	}

	public void setShortcut(ShortcutAction action, KeyShortcut shortcut) {
		if (action != null && shortcut != null) {
			shortcuts.put(action, shortcut);
		}
	}

	public void resetShortcut(ShortcutAction action) {
		if (action != null) {
			shortcuts.put(action, action.defaultShortcut());
		}
	}

	public Map<ShortcutAction, KeyShortcut> allShortcuts() {
		return Map.copyOf(shortcuts);
	}

	public static List<FontChoice> allFontChoices() {
		return List.of(FontChoice.values());
	}
}
