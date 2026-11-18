package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.editor.SingleLineTextInput;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Specialized New Datapack dialog supporting an optional "Use template" checkbox that reveals
 * a Namespace input and an expandable "More" dropdown for configuring {@code load} and {@code tick}
 * function names, each with a default-checked "Use default" toggle backed by {@link QuillClientConfig}.
 */
public final class NewDatapackModal implements IdeModal {
	public enum FieldId {
		PACK,
		DESCRIPTION,
		NAMESPACE,
		LOAD_FN,
		TICK_FN
	}

	public record Submission(
			String packName,
			String description,
			boolean useTemplate,
			String namespace,
			String loadFunctionName,
			String tickFunctionName
	) {
	}

	private final Consumer<Submission> onConfirm;
	private final Runnable onCancel;

	private final SingleLineTextInput packInput = new SingleLineTextInput("my_pack");
	private final SingleLineTextInput descInput = new SingleLineTextInput("Created with Quill");
	private final SingleLineTextInput namespaceInput;
	private final SingleLineTextInput loadFnInput;
	private final SingleLineTextInput tickFnInput;

	private boolean useTemplate = false;
	private boolean moreExpanded = false;
	private boolean useDefaultLoad = true;
	private boolean useDefaultTick = true;

	private FieldId focusedField = FieldId.PACK;
	private String validationError = null;

	private IdeLayout.Rect dialogRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect packBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect descBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect useTemplateRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect namespaceBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect moreToggleRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect loadUseDefaultRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect loadBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect tickUseDefaultRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect tickBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect confirmBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect cancelBtnRect = new IdeLayout.Rect(0, 0, 0, 0);

	public NewDatapackModal(Consumer<Submission> onConfirm, Runnable onCancel) {
		this.onConfirm = onConfirm;
		this.onCancel = onCancel;
		QuillClientConfig cfg = QuillClientConfig.current();
		this.namespaceInput = new SingleLineTextInput(cfg.defaultTemplateNamespace());
		this.loadFnInput = new SingleLineTextInput(cfg.defaultLoadFunctionName());
		this.tickFnInput = new SingleLineTextInput(cfg.defaultTickFunctionName());
	}

	public boolean useTemplate() {
		return useTemplate;
	}

	public void setUseTemplate(boolean useTemplate) {
		this.useTemplate = useTemplate;
		if (!useTemplate && (focusedField == FieldId.NAMESPACE || focusedField == FieldId.LOAD_FN || focusedField == FieldId.TICK_FN)) {
			focusedField = FieldId.PACK;
		}
		validationError = null;
	}

	public boolean moreExpanded() {
		return moreExpanded;
	}

	public void setMoreExpanded(boolean moreExpanded) {
		this.moreExpanded = moreExpanded;
		if (!moreExpanded && (focusedField == FieldId.LOAD_FN || focusedField == FieldId.TICK_FN)) {
			focusedField = useTemplate ? FieldId.NAMESPACE : FieldId.PACK;
		}
		validationError = null;
	}

	public boolean useDefaultLoad() {
		return useDefaultLoad;
	}

	public void setUseDefaultLoad(boolean useDefaultLoad) {
		this.useDefaultLoad = useDefaultLoad;
		if (useDefaultLoad) {
			loadFnInput.setText(QuillClientConfig.current().defaultLoadFunctionName());
		}
		validationError = null;
	}

	public boolean useDefaultTick() {
		return useDefaultTick;
	}

	public void setUseDefaultTick(boolean useDefaultTick) {
		this.useDefaultTick = useDefaultTick;
		if (useDefaultTick) {
			tickFnInput.setText(QuillClientConfig.current().defaultTickFunctionName());
		}
		validationError = null;
	}

	public void focusField(FieldId fieldId) {
		if (fieldId == null) {
			return;
		}
		if (fieldId == FieldId.NAMESPACE && !useTemplate) {
			return;
		}
		if ((fieldId == FieldId.LOAD_FN || fieldId == FieldId.TICK_FN) && (!useTemplate || !moreExpanded)) {
			return;
		}
		this.focusedField = fieldId;
	}

	public String validationError() {
		return validationError;
	}

	@Override
	public boolean hasTextFields() {
		return true;
	}

	@Override
	public void render(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, 0, 0, screenWidth, screenHeight, 0x88000000);

		int dialogW = Math.min(368, Math.max(240, screenWidth - 32));
		int extraH = 0;
		if (useTemplate) {
			extraH += 34; // Namespace field
			extraH += 20; // > More toggle row
			if (moreExpanded) {
				extraH += 72; // Load + Tick fields with Use default checkboxes
			}
		}
		int errorH = validationError != null ? 16 : 0;
		// Header(22) + subtitle(16) + Pack(34) + Desc(34) + UseTemplate(20) + extra + error + buttons(28)
		int dialogH = 26 + 16 + 34 + 34 + 22 + extraH + errorH + 30;
		int dx = (screenWidth - dialogW) / 2;
		int dy = Math.max(6, (screenHeight - dialogH) / 2);
		dialogRect = new IdeLayout.Rect(dx, dy, dialogW, dialogH);

		QuillDraw.fill(graphics, dx, dy, dialogW, dialogH, QuillTheme.BG_MODAL);
		QuillDraw.outline(graphics, dx, dy, dialogW, dialogH, QuillTheme.BORDER_SUBTLE);

		QuillDraw.fill(graphics, dx + 1, dy + 1, dialogW - 2, 20, QuillTheme.BG_TITLE_BAR);
		QuillDraw.textShadow(graphics, font, QuillDraw.clipToWidth(font, "New Datapack (MC 26.3)", dialogW - 16),
				dx + 8, dy + 6, QuillTheme.TEXT_BRIGHT);

		int curY = dy + 26;
		QuillDraw.text(graphics, font,
				QuillDraw.clipToWidth(font, "Creates pack.mcmeta (format 121) and data/ structure", dialogW - 16),
				dx + 8, curY, QuillTheme.TEXT_PRIMARY);
		curY += 16;

		boolean hoverInput = false;
		boolean hoverClickable = false;

		// 1. Pack Name
		QuillDraw.text(graphics, font, "Datapack Folder Name", dx + 8, curY, QuillTheme.TEXT_MUTED);
		packBoxRect = new IdeLayout.Rect(dx + 8, curY + 11, dialogW - 16, 18);
		packInput.render(graphics, font, packBoxRect, "my_pack", focusedField == FieldId.PACK);
		hoverInput |= packBoxRect.contains(mouseX, mouseY);
		curY += 34;

		// 2. Description
		QuillDraw.text(graphics, font, "Description", dx + 8, curY, QuillTheme.TEXT_MUTED);
		descBoxRect = new IdeLayout.Rect(dx + 8, curY + 11, dialogW - 16, 18);
		descInput.render(graphics, font, descBoxRect, "Created with Quill", focusedField == FieldId.DESCRIPTION);
		hoverInput |= descBoxRect.contains(mouseX, mouseY);
		curY += 34;

		// 3. Use template checkbox
		useTemplateRect = new IdeLayout.Rect(dx + 8, curY, dialogW - 16, 18);
		boolean hoverUseTpl = useTemplateRect.contains(mouseX, mouseY);
		if (hoverUseTpl) {
			QuillDraw.fill(graphics, useTemplateRect.x(), useTemplateRect.y(), useTemplateRect.width(), useTemplateRect.height(), QuillTheme.BG_HOVER);
			hoverClickable = true;
		}
		drawCheckbox(graphics, font, dx + 10, curY + 3, useTemplate);
		QuillDraw.text(graphics, font, "Use template (namespace + load & tick hooks)", dx + 27, curY + 4, QuillTheme.TEXT_BRIGHT);
		curY += 22;

		namespaceBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
		moreToggleRect = new IdeLayout.Rect(0, 0, 0, 0);
		loadUseDefaultRect = new IdeLayout.Rect(0, 0, 0, 0);
		loadBoxRect = new IdeLayout.Rect(0, 0, 0, 0);
		tickUseDefaultRect = new IdeLayout.Rect(0, 0, 0, 0);
		tickBoxRect = new IdeLayout.Rect(0, 0, 0, 0);

		if (useTemplate) {
			// 4. Namespace input
			QuillDraw.text(graphics, font, "Namespace ([a-z0-9_.-])", dx + 8, curY, QuillTheme.TEXT_MUTED);
			namespaceBoxRect = new IdeLayout.Rect(dx + 8, curY + 11, dialogW - 16, 18);
			namespaceInput.render(graphics, font, namespaceBoxRect, QuillClientConfig.current().defaultTemplateNamespace(),
					focusedField == FieldId.NAMESPACE);
			hoverInput |= namespaceBoxRect.contains(mouseX, mouseY);
			curY += 34;

			// 5. Expandable "More" dropdown toggle
			moreToggleRect = new IdeLayout.Rect(dx + 8, curY, dialogW - 16, 16);
			boolean hoverMore = moreToggleRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, moreToggleRect.x(), moreToggleRect.y(), moreToggleRect.width(), moreToggleRect.height(),
					hoverMore ? QuillTheme.BG_HOVER : QuillTheme.BG_SIDEBAR);
			QuillDraw.outline(graphics, moreToggleRect.x(), moreToggleRect.y(), moreToggleRect.width(), moreToggleRect.height(), QuillTheme.BORDER_SUBTLE);
			String moreLabel = (moreExpanded ? "v More" : "> More") + "  (load & tick function names)";
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, moreLabel, moreToggleRect.width() - 10),
					moreToggleRect.x() + 5, moreToggleRect.y() + (16 - font.lineHeight) / 2, QuillTheme.TEXT_PRIMARY);
			hoverClickable |= hoverMore;
			curY += 20;

			if (moreExpanded) {
				QuillClientConfig cfg = QuillClientConfig.current();
				int subX = dx + 14;
				int subW = dialogW - 22;

				// 6. Load function + Use default
				String defTag = "Use default (" + cfg.defaultLoadFunctionName() + ")";
				int defLoadW = QuillDraw.textWidth(font, defTag) + 18;
				loadUseDefaultRect = new IdeLayout.Rect(subX + subW - defLoadW, curY - 1, defLoadW, 13);
				boolean hoverDefLoad = loadUseDefaultRect.contains(mouseX, mouseY);
				if (hoverDefLoad) {
					QuillDraw.fill(graphics, loadUseDefaultRect.x(), loadUseDefaultRect.y(), loadUseDefaultRect.width(), loadUseDefaultRect.height(), QuillTheme.BG_HOVER);
					hoverClickable = true;
				}
				QuillDraw.text(graphics, font, "Load function", subX, curY, QuillTheme.TEXT_MUTED);
				drawSmallCheckbox(graphics, font, loadUseDefaultRect.x() + 2, curY, useDefaultLoad);
				QuillDraw.text(graphics, font, defTag, loadUseDefaultRect.x() + 15, curY, QuillTheme.TEXT_PRIMARY);

				loadBoxRect = new IdeLayout.Rect(subX, curY + 12, subW, 18);
				if (useDefaultLoad) {
					renderDefaultLockedBox(graphics, font, loadBoxRect, cfg.defaultLoadFunctionName());
				} else {
					loadFnInput.render(graphics, font, loadBoxRect, cfg.defaultLoadFunctionName(), focusedField == FieldId.LOAD_FN);
				}
				hoverInput |= loadBoxRect.contains(mouseX, mouseY);
				curY += 36;

				// 7. Tick function + Use default
				String defTickTag = "Use default (" + cfg.defaultTickFunctionName() + ")";
				int defTickW = QuillDraw.textWidth(font, defTickTag) + 18;
				tickUseDefaultRect = new IdeLayout.Rect(subX + subW - defTickW, curY - 1, defTickW, 13);
				boolean hoverDefTick = tickUseDefaultRect.contains(mouseX, mouseY);
				if (hoverDefTick) {
					QuillDraw.fill(graphics, tickUseDefaultRect.x(), tickUseDefaultRect.y(), tickUseDefaultRect.width(), tickUseDefaultRect.height(), QuillTheme.BG_HOVER);
					hoverClickable = true;
				}
				QuillDraw.text(graphics, font, "Tick function", subX, curY, QuillTheme.TEXT_MUTED);
				drawSmallCheckbox(graphics, font, tickUseDefaultRect.x() + 2, curY, useDefaultTick);
				QuillDraw.text(graphics, font, defTickTag, tickUseDefaultRect.x() + 15, curY, QuillTheme.TEXT_PRIMARY);

				tickBoxRect = new IdeLayout.Rect(subX, curY + 12, subW, 18);
				if (useDefaultTick) {
					renderDefaultLockedBox(graphics, font, tickBoxRect, cfg.defaultTickFunctionName());
				} else {
					tickFnInput.render(graphics, font, tickBoxRect, cfg.defaultTickFunctionName(), focusedField == FieldId.TICK_FN);
				}
				hoverInput |= tickBoxRect.contains(mouseX, mouseY);
				curY += 36;
			}
		}

		if (validationError != null) {
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, validationError, dialogW - 16),
					dx + 8, curY, QuillTheme.TEXT_ERROR);
			curY += 16;
		}

		// Action buttons
		int btnW = 78;
		int btnH = 18;
		int btnY = dy + dialogH - btnH - 8;
		cancelBtnRect = new IdeLayout.Rect(dx + dialogW - btnW - 8, btnY, btnW, btnH);
		confirmBtnRect = new IdeLayout.Rect(cancelBtnRect.x() - btnW - 6, btnY, btnW, btnH);

		boolean hoverConfirm = confirmBtnRect.contains(mouseX, mouseY);
		boolean hoverCancel = cancelBtnRect.contains(mouseX, mouseY);
		QuillDraw.fill(graphics, confirmBtnRect.x(), confirmBtnRect.y(), confirmBtnRect.width(), confirmBtnRect.height(),
				hoverConfirm ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
		QuillDraw.fill(graphics, cancelBtnRect.x(), cancelBtnRect.y(), cancelBtnRect.width(), cancelBtnRect.height(),
				hoverCancel ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);

		int labelY = btnY + (btnH - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, "Create",
				confirmBtnRect.x() + (btnW - QuillDraw.textWidth(font, "Create")) / 2, labelY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, "Cancel",
				cancelBtnRect.x() + (btnW - QuillDraw.textWidth(font, "Cancel")) / 2, labelY, QuillTheme.TEXT_PRIMARY);

		if (hoverInput) {
			QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
		} else if (hoverClickable || hoverConfirm || hoverCancel) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

	private static void renderDefaultLockedBox(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect box, String value) {
		QuillDraw.fill(graphics, box.x(), box.y(), box.width(), box.height(), QuillTheme.BG_SIDEBAR);
		QuillDraw.outline(graphics, box.x(), box.y(), box.width(), box.height(), QuillTheme.BORDER_SUBTLE);
		int textY = box.y() + (box.height() - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, value + "  (default)", box.width() - 8),
				box.x() + 4, textY, QuillTheme.TEXT_MUTED);
	}

	private static void drawCheckbox(GuiGraphicsExtractor graphics, Font font, int x, int y, boolean checked) {
		QuillDraw.fill(graphics, x, y, 12, 12, checked ? QuillTheme.BG_BUTTON : QuillTheme.BG_INPUT);
		QuillDraw.outline(graphics, x, y, 12, 12, checked ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE);
		if (checked) {
			QuillDraw.text(graphics, font, "x", x + 3, y + 2, QuillTheme.TEXT_BRIGHT);
		}
	}

	private static void drawSmallCheckbox(GuiGraphicsExtractor graphics, Font font, int x, int y, boolean checked) {
		QuillDraw.fill(graphics, x, y, 10, 10, checked ? QuillTheme.BG_BUTTON : QuillTheme.BG_INPUT);
		QuillDraw.outline(graphics, x, y, 10, 10, checked ? QuillTheme.ACCENT_BLUE : QuillTheme.BORDER_SUBTLE);
		if (checked) {
			QuillDraw.text(graphics, font, "x", x + 2, y + 1, QuillTheme.TEXT_BRIGHT);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		double mx = event.x();
		double my = event.y();

		if (confirmBtnRect.contains(mx, my)) {
			trySubmit();
			return true;
		}
		if (cancelBtnRect.contains(mx, my)) {
			onCancel.run();
			return true;
		}
		if (useTemplateRect.contains(mx, my)) {
			setUseTemplate(!useTemplate);
			return true;
		}
		if (useTemplate && moreToggleRect.contains(mx, my)) {
			setMoreExpanded(!moreExpanded);
			return true;
		}
		if (useTemplate && moreExpanded && loadUseDefaultRect.contains(mx, my)) {
			setUseDefaultLoad(!useDefaultLoad);
			if (!useDefaultLoad) {
				focusedField = FieldId.LOAD_FN;
				loadFnInput.selectAll();
			}
			return true;
		}
		if (useTemplate && moreExpanded && tickUseDefaultRect.contains(mx, my)) {
			setUseDefaultTick(!useDefaultTick);
			if (!useDefaultTick) {
				focusedField = FieldId.TICK_FN;
				tickFnInput.selectAll();
			}
			return true;
		}

		Font font = safeFont();
		if (packBoxRect.contains(mx, my)) {
			focusedField = FieldId.PACK;
			packInput.mouseClicked(mx, font, event.hasShiftDown());
			return true;
		}
		if (descBoxRect.contains(mx, my)) {
			focusedField = FieldId.DESCRIPTION;
			descInput.mouseClicked(mx, font, event.hasShiftDown());
			return true;
		}
		if (useTemplate && namespaceBoxRect.contains(mx, my)) {
			focusedField = FieldId.NAMESPACE;
			namespaceInput.mouseClicked(mx, font, event.hasShiftDown());
			return true;
		}
		if (useTemplate && moreExpanded && loadBoxRect.contains(mx, my)) {
			if (useDefaultLoad) {
				useDefaultLoad = false;
			}
			focusedField = FieldId.LOAD_FN;
			loadFnInput.mouseClicked(mx, font, event.hasShiftDown());
			return true;
		}
		if (useTemplate && moreExpanded && tickBoxRect.contains(mx, my)) {
			if (useDefaultTick) {
				useDefaultTick = false;
			}
			focusedField = FieldId.TICK_FN;
			tickFnInput.mouseClicked(mx, font, event.hasShiftDown());
			return true;
		}
		if (!dialogRect.contains(mx, my)) {
			onCancel.run();
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		SingleLineTextInput active = activeInput();
		if (active != null && active.isDragging()) {
			active.mouseDragged(event.x(), safeFont());
		}
		return true;
	}

	@Override
	public void mouseReleased(MouseButtonEvent event) {
		packInput.mouseReleased();
		descInput.mouseReleased();
		namespaceInput.mouseReleased();
		loadFnInput.mouseReleased();
		tickFnInput.mouseReleased();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			onCancel.run();
			return true;
		}
		if (event.isConfirmation()) {
			trySubmit();
			return true;
		}
		if (event.isCycleFocus()) {
			List<FieldId> visible = visibleFields();
			int idx = visible.indexOf(focusedField);
			int delta = event.hasShiftDown() ? -1 : 1;
			focusedField = visible.get(Math.floorMod(idx + delta, visible.size()));
			return true;
		}

		if (focusedField == FieldId.LOAD_FN && useDefaultLoad) {
			useDefaultLoad = false;
		}
		if (focusedField == FieldId.TICK_FN && useDefaultTick) {
			useDefaultTick = false;
		}
		SingleLineTextInput active = activeInput();
		if (active != null) {
			SingleLineTextInput.KeyActionResult res = active.keyPressed(event);
			if (res.textChanged()) {
				validationError = null;
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (focusedField == FieldId.LOAD_FN && useDefaultLoad) {
			useDefaultLoad = false;
		}
		if (focusedField == FieldId.TICK_FN && useDefaultTick) {
			useDefaultTick = false;
		}
		SingleLineTextInput active = activeInput();
		if (active != null && active.charTyped(event)) {
			validationError = null;
		}
		return true;
	}

	private List<FieldId> visibleFields() {
		List<FieldId> list = new ArrayList<>();
		list.add(FieldId.PACK);
		list.add(FieldId.DESCRIPTION);
		if (useTemplate) {
			list.add(FieldId.NAMESPACE);
			if (moreExpanded) {
				list.add(FieldId.LOAD_FN);
				list.add(FieldId.TICK_FN);
			}
		}
		return list;
	}

	private SingleLineTextInput activeInput() {
		return switch (focusedField) {
			case PACK -> packInput;
			case DESCRIPTION -> descInput;
			case NAMESPACE -> namespaceInput;
			case LOAD_FN -> loadFnInput;
			case TICK_FN -> tickFnInput;
		};
	}

	private void trySubmit() {
		String pack = packInput.text().trim();
		String desc = descInput.text().trim();
		try {
			DatapackScaffolder.validatePackName(pack);
		} catch (EditorSecurityException e) {
			focusedField = FieldId.PACK;
			validationError = e.getMessage();
			return;
		}

		if (!useTemplate) {
			onConfirm.accept(new Submission(pack, desc, false, "", "", ""));
			return;
		}

		QuillClientConfig cfg = QuillClientConfig.current();
		String ns = namespaceInput.text().trim().toLowerCase(Locale.ROOT);
		try {
			ns = DatapackScaffolder.validateNamespace(ns);
		} catch (EditorSecurityException e) {
			focusedField = FieldId.NAMESPACE;
			validationError = e.getMessage();
			return;
		}

		String loadFn = (useDefaultLoad ? cfg.defaultLoadFunctionName() : loadFnInput.text().trim()).toLowerCase(Locale.ROOT);
		try {
			loadFn = DatapackScaffolder.validateFunctionPath(loadFn);
		} catch (EditorSecurityException e) {
			moreExpanded = true;
			focusedField = FieldId.LOAD_FN;
			validationError = "Load function: " + e.getMessage();
			return;
		}

		String tickFn = (useDefaultTick ? cfg.defaultTickFunctionName() : tickFnInput.text().trim()).toLowerCase(Locale.ROOT);
		try {
			tickFn = DatapackScaffolder.validateFunctionPath(tickFn);
		} catch (EditorSecurityException e) {
			moreExpanded = true;
			focusedField = FieldId.TICK_FN;
			validationError = "Tick function: " + e.getMessage();
			return;
		}

		onConfirm.accept(new Submission(pack, desc, true, ns, loadFn, tickFn));
	}

	private static Font safeFont() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null ? mc.font : null;
	}
}
