package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.editor.SingleLineTextInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reusable modal dialog overlay supporting confirmation prompts and multi-field validated inputs
 * with full text selection via {@link SingleLineTextInput}.
 */
public final class ModalPromptOverlay {
	public record FieldSpec(
			String key,
			String label,
			String initialValue,
			String placeholder,
			Function<String, String> validator
	) {
	}

	private static final class FieldState {
		final FieldSpec spec;
		final SingleLineTextInput input;
		IdeLayout.Rect boxRect = new IdeLayout.Rect(0, 0, 0, 0);

		FieldState(FieldSpec spec) {
			this.spec = spec;
			this.input = new SingleLineTextInput(spec.initialValue());
		}
	}

	private final String title;
	private final String message;
	private final String confirmLabel;
	private final boolean dangerConfirm;
	private final String secondaryLabel;
	private final Runnable onSecondary;
	private final List<FieldState> fields = new ArrayList<>();
	private final Consumer<Map<String, String>> onConfirm;
	private final Runnable onCancel;

	private int focusedFieldIndex = 0;
	private String validationError = null;
	private IdeLayout.Rect confirmBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect secondaryBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect cancelBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect dialogRect = new IdeLayout.Rect(0, 0, 0, 0);

	public ModalPromptOverlay(
			String title,
			String message,
			String confirmLabel,
			boolean dangerConfirm,
			List<FieldSpec> fieldSpecs,
			Consumer<Map<String, String>> onConfirm,
			Runnable onCancel
	) {
		this(title, message, confirmLabel, dangerConfirm, null, null, fieldSpecs, onConfirm, onCancel);
	}

	private ModalPromptOverlay(
			String title,
			String message,
			String confirmLabel,
			boolean dangerConfirm,
			String secondaryLabel,
			Runnable onSecondary,
			List<FieldSpec> fieldSpecs,
			Consumer<Map<String, String>> onConfirm,
			Runnable onCancel
	) {
		this.title = title;
		this.message = message;
		this.confirmLabel = confirmLabel;
		this.dangerConfirm = dangerConfirm;
		this.secondaryLabel = secondaryLabel;
		this.onSecondary = onSecondary;
		if (fieldSpecs != null) {
			for (FieldSpec spec : fieldSpecs) {
				this.fields.add(new FieldState(spec));
			}
		}
		this.onConfirm = onConfirm;
		this.onCancel = onCancel;
	}

	public static ModalPromptOverlay confirm(
			String title,
			String message,
			String confirmLabel,
			boolean danger,
			Runnable onConfirm,
			Runnable onCancel
	) {
		return new ModalPromptOverlay(
				title,
				message,
				confirmLabel,
				danger,
				null,
				null,
				List.of(),
				ignored -> onConfirm.run(),
				onCancel
		);
	}

	public static ModalPromptOverlay confirmWithSecondary(
			String title,
			String message,
			String confirmLabel,
			boolean danger,
			Runnable onConfirm,
			String secondaryLabel,
			Runnable onSecondary,
			Runnable onCancel
	) {
		return new ModalPromptOverlay(
				title,
				message,
				confirmLabel,
				danger,
				secondaryLabel,
				onSecondary,
				List.of(),
				ignored -> onConfirm.run(),
				onCancel
		);
	}

	public boolean hasTextFields() {
		return !fields.isEmpty();
	}

	public void render(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
		QuillDraw.fill(graphics, 0, 0, screenWidth, screenHeight, 0x88000000);

		int dialogW = Math.min(340, Math.max(220, screenWidth - 32));
		int fieldsHeight = fields.size() * 34;
		int msgHeight = (message != null && !message.isBlank()) ? 16 : 0;
		int errorHeight = validationError != null ? 16 : 0;
		int dialogH = 26 + msgHeight + fieldsHeight + errorHeight + 30;
		int dx = (screenWidth - dialogW) / 2;
		int dy = (screenHeight - dialogH) / 2;
		dialogRect = new IdeLayout.Rect(dx, dy, dialogW, dialogH);

		QuillDraw.fill(graphics, dx, dy, dialogW, dialogH, QuillTheme.BG_MODAL);
		QuillDraw.outline(graphics, dx, dy, dialogW, dialogH, QuillTheme.BORDER_SUBTLE);

		QuillDraw.fill(graphics, dx + 1, dy + 1, dialogW - 2, 20, QuillTheme.BG_TITLE_BAR);
		QuillDraw.textShadow(graphics, font, QuillDraw.clipToWidth(font, title, dialogW - 16), dx + 8, dy + 6, QuillTheme.TEXT_BRIGHT);

		int curY = dy + 26;
		if (msgHeight > 0) {
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, message, dialogW - 16), dx + 8, curY, QuillTheme.TEXT_PRIMARY);
			curY += msgHeight;
		}

		for (int i = 0; i < fields.size(); i++) {
			FieldState field = fields.get(i);
			boolean focused = i == focusedFieldIndex;
			QuillDraw.text(graphics, font, field.spec.label(), dx + 8, curY, QuillTheme.TEXT_MUTED);
			int boxY = curY + 11;
			field.boxRect = new IdeLayout.Rect(dx + 8, boxY, dialogW - 16, 18);
			field.input.render(graphics, font, field.boxRect, field.spec.placeholder(), focused);

			if (field.boxRect.contains(mouseX, mouseY)) {
				QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
			}
			curY += 34;
		}

		if (validationError != null) {
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, validationError, dialogW - 16), dx + 8, curY, QuillTheme.TEXT_ERROR);
			curY += 16;
		}

		int btnW = secondaryLabel != null ? 74 : 78;
		int btnH = 18;
		int btnY = dy + dialogH - btnH - 8;
		cancelBtnRect = new IdeLayout.Rect(dx + dialogW - btnW - 8, btnY, btnW, btnH);
		if (secondaryLabel != null && onSecondary != null) {
			secondaryBtnRect = new IdeLayout.Rect(cancelBtnRect.x() - btnW - 6, btnY, btnW, btnH);
			confirmBtnRect = new IdeLayout.Rect(secondaryBtnRect.x() - btnW - 6, btnY, btnW, btnH);
		} else {
			secondaryBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
			confirmBtnRect = new IdeLayout.Rect(cancelBtnRect.x() - btnW - 6, btnY, btnW, btnH);
		}

		boolean hoverConfirm = confirmBtnRect.contains(mouseX, mouseY);
		boolean hoverSecondary = secondaryBtnRect.width() > 0 && secondaryBtnRect.contains(mouseX, mouseY);
		boolean hoverCancel = cancelBtnRect.contains(mouseX, mouseY);
		int confirmColor = dangerConfirm ? QuillTheme.BG_BUTTON_DANGER : (hoverConfirm ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
		QuillDraw.fill(graphics, confirmBtnRect.x(), confirmBtnRect.y(), confirmBtnRect.width(), confirmBtnRect.height(), confirmColor);
		if (secondaryBtnRect.width() > 0) {
			QuillDraw.fill(graphics, secondaryBtnRect.x(), secondaryBtnRect.y(), secondaryBtnRect.width(), secondaryBtnRect.height(),
					hoverSecondary ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
		}
		QuillDraw.fill(graphics, cancelBtnRect.x(), cancelBtnRect.y(), cancelBtnRect.width(), cancelBtnRect.height(),
				hoverCancel ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);

		int labelY = btnY + (btnH - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, confirmLabel,
				confirmBtnRect.x() + (btnW - QuillDraw.textWidth(font, confirmLabel)) / 2, labelY, QuillTheme.TEXT_BRIGHT);
		if (secondaryBtnRect.width() > 0) {
			QuillDraw.text(graphics, font, secondaryLabel,
					secondaryBtnRect.x() + (btnW - QuillDraw.textWidth(font, secondaryLabel)) / 2, labelY, QuillTheme.TEXT_BRIGHT);
		}
		QuillDraw.text(graphics, font, "Cancel",
				cancelBtnRect.x() + (btnW - QuillDraw.textWidth(font, "Cancel")) / 2, labelY, QuillTheme.TEXT_PRIMARY);

		if (hoverConfirm || hoverSecondary || hoverCancel) {
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
	}

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
		if (secondaryBtnRect.width() > 0 && onSecondary != null && secondaryBtnRect.contains(mx, my)) {
			onSecondary.run();
			return true;
		}
		if (cancelBtnRect.contains(mx, my)) {
			onCancel.run();
			return true;
		}
		Font font = safeFont();
		for (int i = 0; i < fields.size(); i++) {
			FieldState field = fields.get(i);
			if (field.boxRect.contains(mx, my)) {
				focusedFieldIndex = i;
				field.input.mouseClicked(mx, font, event.hasShiftDown());
				return true;
			}
		}
		if (!dialogRect.contains(mx, my)) {
			onCancel.run();
		}
		return true;
	}

	public boolean mouseDragged(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || fields.isEmpty()) {
			return true;
		}
		FieldState field = fields.get(focusedFieldIndex);
		if (field.input.isDragging()) {
			field.input.mouseDragged(event.x(), safeFont());
		}
		return true;
	}

	public void mouseReleased(MouseButtonEvent event) {
		for (FieldState field : fields) {
			field.input.mouseReleased();
		}
	}

	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			onCancel.run();
			return true;
		}
		if (event.isConfirmation()) {
			trySubmit();
			return true;
		}
		if (fields.isEmpty()) {
			return true;
		}
		if (event.isCycleFocus()) {
			int delta = event.hasShiftDown() ? -1 : 1;
			focusedFieldIndex = Math.floorMod(focusedFieldIndex + delta, fields.size());
			return true;
		}

		FieldState field = fields.get(focusedFieldIndex);
		SingleLineTextInput.KeyActionResult res = field.input.keyPressed(event);
		if (res.textChanged()) {
			validationError = null;
		}
		return true;
	}

	public boolean charTyped(CharacterEvent event) {
		if (fields.isEmpty()) {
			return true;
		}
		FieldState field = fields.get(focusedFieldIndex);
		if (field.input.charTyped(event)) {
			validationError = null;
		}
		return true;
	}

	private void trySubmit() {
		Map<String, String> values = new LinkedHashMap<>();
		for (int i = 0; i < fields.size(); i++) {
			FieldState field = fields.get(i);
			String val = field.input.text().trim();
			if (field.spec.validator() != null) {
				String error = field.spec.validator().apply(val);
				if (error != null && !error.isBlank()) {
					focusedFieldIndex = i;
					validationError = error;
					return;
				}
			}
			values.put(field.spec.key(), val);
		}
		onConfirm.accept(values);
	}

	private static Font safeFont() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null ? mc.font : null;
	}
}
