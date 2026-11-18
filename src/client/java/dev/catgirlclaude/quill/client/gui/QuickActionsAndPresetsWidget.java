package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sidebar panel rendering Quick Actions (New Datapack, New Namespace, New Function)
 * and resource-driven presets ({@code minecraft:load}, {@code minecraft:tick}).
 */
public final class QuickActionsAndPresetsWidget {
	private record ClickableItem(String label, String subtitle, boolean isPreset, Runnable action, IdeLayout.Rect rect) {
	}

	private final ClientEditorSession session;
	private final Consumer<IdeModal> modalOpener;
	private final Runnable modalCloser;
	private final List<ClickableItem> items = new ArrayList<>();
	private int scrollPixels = 0;
	private IdeLayout.Rect viewportRect = new IdeLayout.Rect(0, 0, 0, 0);

	public QuickActionsAndPresetsWidget(
			ClientEditorSession session,
			Consumer<IdeModal> modalOpener,
			Runnable modalCloser
	) {
		this.session = session;
		this.modalOpener = modalOpener;
		this.modalCloser = modalCloser;
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect area, int mouseX, int mouseY) {
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, "QUICK ACTIONS & PRESETS", area.width() - 12),
				area.x() + 6, area.y() + 5, QuillTheme.TEXT_MUTED);
		QuillDraw.hLine(graphics, area.x(), area.width() - 1, area.y() + 18, QuillTheme.BORDER_SUBTLE);

		viewportRect = new IdeLayout.Rect(area.x(), area.y() + 19, area.width() - 1, Math.max(0, area.bottom() - (area.y() + 19)));
		items.clear();

		QuillDraw.beginClip(graphics, viewportRect);
		int y = viewportRect.y() + 6 - scrollPixels;
		int btnX = viewportRect.x() + 8;
		int btnW = Math.max(40, viewportRect.width() - 16);

		QuillDraw.text(graphics, font, "QUICK ACTIONS", btnX, y, QuillTheme.TEXT_MUTED);
		y += 13;

		y = addActionCard(graphics, font, btnX, y, btnW, 20, "+ New Datapack", null, false,
				this::promptNewDatapack, mouseX, mouseY);
		y = addActionCard(graphics, font, btnX, y, btnW, 20, "+ New Namespace", null, false,
				this::promptNewNamespace, mouseX, mouseY);
		y = addActionCard(graphics, font, btnX, y, btnW, 20, "+ New .mcfunction", null, false,
				this::promptNewFunction, mouseX, mouseY);

		y += 8;
		QuillDraw.text(graphics, font, "FUNCTION TAGS (data/minecraft)", btnX, y, QuillTheme.TEXT_MUTED);
		y += 13;

		List<PresetDescriptor> presets = session.presets();
		if (presets.isEmpty()) {
			QuillDraw.text(graphics, font, "Loading presets...", btnX, y, QuillTheme.TEXT_MUTED);
		} else {
			List<PresetDescriptor> tagPresets = presets.stream().filter(p -> !p.id().startsWith("registry:")).toList();
			List<PresetDescriptor> registryPresets = presets.stream().filter(p -> p.id().startsWith("registry:")).toList();
			for (PresetDescriptor preset : tagPresets) {
				y = addActionCard(graphics, font, btnX, y, btnW, 28, "* " + preset.id(), preset.displayName(), true,
						() -> promptApplyPreset(preset), mouseX, mouseY);
			}
			if (!registryPresets.isEmpty()) {
				y += 8;
				QuillDraw.text(graphics, font, "REGISTRY TEMPLATES (JSON)", btnX, y, QuillTheme.TEXT_MUTED);
				y += 13;
				for (PresetDescriptor preset : registryPresets) {
					y = addActionCard(graphics, font, btnX, y, btnW, 28, "{} " + preset.displayName(), preset.targetRelativePathPattern(), true,
							() -> promptApplyPreset(preset), mouseX, mouseY);
				}
			}
		}
		QuillDraw.endClip(graphics);
	}

	private int addActionCard(
			GuiGraphicsExtractor graphics,
			Font font,
			int x,
			int y,
			int w,
			int h,
			String title,
			String subtitle,
			boolean isPreset,
			Runnable action,
			int mouseX,
			int mouseY
	) {
		IdeLayout.Rect rect = new IdeLayout.Rect(x, y, w, h);
		items.add(new ClickableItem(title, subtitle, isPreset, action, rect));

		boolean hover = viewportRect.contains(mouseX, mouseY) && rect.contains(mouseX, mouseY);
		int bg = hover ? QuillTheme.BG_BUTTON : QuillTheme.BG_BUTTON_SECONDARY;
		QuillDraw.fill(graphics, rect.x(), rect.y(), rect.width(), rect.height(), bg);
		QuillDraw.outline(graphics, rect.x(), rect.y(), rect.width(), rect.height(), QuillTheme.BORDER_SUBTLE);

		if (subtitle == null) {
			int ty = rect.y() + (rect.height() - font.lineHeight) / 2;
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, title, rect.width() - 10),
					rect.x() + 5, ty, QuillTheme.TEXT_BRIGHT);
		} else {
			int titleColor = title.startsWith("{}") ? QuillTheme.TEXT_JSON : QuillTheme.TEXT_FUNCTION;
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, title, rect.width() - 10),
					rect.x() + 5, rect.y() + 4, titleColor);
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, subtitle, rect.width() - 10),
					rect.x() + 5, rect.y() + 15, QuillTheme.TEXT_MUTED);
		}
		if (hover) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
		return y + h + 4;
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (!viewportRect.contains(event.x(), event.y())) {
			return false;
		}
		for (ClickableItem item : items) {
			if (item.rect().contains(event.x(), event.y())) {
				item.action().run();
				return true;
			}
		}
		return false;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!viewportRect.contains(mouseX, mouseY)) {
			return false;
		}
		scrollPixels = Math.max(0, scrollPixels - (int) (scrollY * 14));
		return true;
	}

	public void promptNewDatapack() {
		modalOpener.accept(new NewDatapackModal(
				submission -> {
					modalCloser.run();
					session.createDatapackWithTemplate(
							submission.packName(),
							submission.description(),
							submission.useTemplate(),
							submission.namespace(),
							submission.loadFunctionName(),
							submission.tickFunctionName()
					);
				},
				modalCloser
		));
	}

	public void promptNewNamespace() {
		String defaultPack = inferSelectedPack();
		modalOpener.accept(new ModalPromptOverlay(
				"New Namespace",
				"Creates data/<namespace>/function/",
				"Create",
				false,
				List.of(
						new ModalPromptOverlay.FieldSpec("pack", "Target Datapack", defaultPack, "my_pack", val -> {
							try {
								DatapackScaffolder.validatePackName(val);
								return null;
							} catch (EditorSecurityException e) {
								return e.getMessage();
							}
						}),
						new ModalPromptOverlay.FieldSpec("ns", "Namespace ([a-z0-9_.-])", inferSelectedNamespace(), "demo", val -> {
							try {
								DatapackScaffolder.validateNamespace(val);
								return null;
							} catch (EditorSecurityException e) {
								return e.getMessage();
							}
						})
				),
				values -> {
					modalCloser.run();
					session.createNamespace(values.get("pack"), values.get("ns"));
				},
				modalCloser
		));
	}

	public void promptNewFunction() {
		String defaultPack = inferSelectedPack();
		String defaultNs = inferSelectedNamespace();
		modalOpener.accept(new ModalPromptOverlay(
				"New .mcfunction",
				"Creates data/<namespace>/function/<name>.mcfunction",
				"Create",
				false,
				List.of(
						new ModalPromptOverlay.FieldSpec("pack", "Target Datapack", defaultPack, "my_pack", val -> {
							try {
								DatapackScaffolder.validatePackName(val);
								return null;
							} catch (EditorSecurityException e) {
								return e.getMessage();
							}
						}),
						new ModalPromptOverlay.FieldSpec("ns", "Namespace ([a-z0-9_.-])", defaultNs, "demo", val -> {
							try {
								DatapackScaffolder.validateNamespace(val);
								return null;
							} catch (EditorSecurityException e) {
								return e.getMessage();
							}
						}),
						new ModalPromptOverlay.FieldSpec("fn", "Function Path ([a-z0-9/._-])", "hello", "sub/hello", val -> {
							try {
								DatapackScaffolder.validateFunctionPath(val);
								return null;
							} catch (EditorSecurityException e) {
								return e.getMessage();
							}
						})
				),
				values -> {
					modalCloser.run();
					session.createFunction(values.get("pack"), values.get("ns"), values.get("fn"));
				},
				modalCloser
		));
	}

	public void promptApplyPreset(PresetDescriptor preset) {
		String defaultPack = inferSelectedPack();
		String defaultNs = inferSelectedNamespace();
		if (defaultNs.isEmpty()) {
			defaultNs = preset.defaultVariables().getOrDefault("namespace", "demo");
		}
		List<ModalPromptOverlay.FieldSpec> fields = new ArrayList<>();
		fields.add(new ModalPromptOverlay.FieldSpec("pack", "Target Datapack", defaultPack, "my_pack", val -> {
			try {
				DatapackScaffolder.validatePackName(val);
				return null;
			} catch (EditorSecurityException e) {
				return e.getMessage();
			}
		}));

		for (Map.Entry<String, String> var : preset.defaultVariables().entrySet()) {
			String key = var.getKey();
			if ("namespace".equals(key)) {
				fields.add(new ModalPromptOverlay.FieldSpec("namespace", "Target Namespace ([a-z0-9_.-])", defaultNs, "demo", val -> {
					try {
						DatapackScaffolder.validateNamespace(val);
						return null;
					} catch (EditorSecurityException e) {
						return e.getMessage();
					}
				}));
			} else if ("name".equals(key)) {
				fields.add(new ModalPromptOverlay.FieldSpec("name", "Resource Path ([a-z0-9/._-])", var.getValue(), var.getValue(), val -> {
					try {
						DatapackScaffolder.validateResourcePath(val);
						return null;
					} catch (EditorSecurityException e) {
						return e.getMessage();
					}
				}));
			} else if ("functionName".equals(key)) {
				fields.add(new ModalPromptOverlay.FieldSpec("functionName", "Function Name ([a-z0-9/._-])", var.getValue(), var.getValue(), val -> {
					try {
						DatapackScaffolder.validateFunctionPath(val);
						return null;
					} catch (EditorSecurityException e) {
						return e.getMessage();
					}
				}));
			} else {
				fields.add(new ModalPromptOverlay.FieldSpec(key, key, var.getValue(), var.getValue(), null));
			}
		}

		modalOpener.accept(new ModalPromptOverlay(
				"Apply Preset: " + preset.displayName(),
				preset.description(),
				"Apply",
				false,
				fields,
				values -> {
					modalCloser.run();
					java.util.Map<String, String> vars = new java.util.LinkedHashMap<>();
					for (String varKey : preset.defaultVariables().keySet()) {
						if (values.containsKey(varKey)) {
							vars.put(varKey, values.get(varKey));
						}
					}
					session.applyPreset(values.get("pack"), preset.id(), vars);
				},
				modalCloser
		));
	}

	private String inferSelectedPack() {
		String selected = session.selectedTreePath();
		if (selected != null && !selected.isEmpty()) {
			int slash = selected.indexOf('/');
			return slash >= 0 ? selected.substring(0, slash) : selected;
		}
		List<FileTreeNode> packs = session.rootTree().children();
		return packs.isEmpty() ? "my_pack" : packs.getFirst().name();
	}

	private String inferSelectedNamespace() {
		String selected = session.selectedTreePath();
		if (selected != null && !selected.isEmpty()) {
			String[] parts = selected.split("/");
			if (parts.length >= 3 && "data".equals(parts[1]) && !"minecraft".equals(parts[2])) {
				return parts[2];
			}
		}
		return "demo";
	}
}
