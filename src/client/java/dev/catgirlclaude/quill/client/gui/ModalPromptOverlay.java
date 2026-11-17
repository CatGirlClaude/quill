package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
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
 * Reusable modal dialog overlay supporting confirmation prompts and multi-field validated inputs.
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
		final StringBuilder text = new StringBuilder();
		int cursor;
		IdeLayout.Rect boxRect = new IdeLayout.Rect(0, 0, 0, 0);

		FieldState(FieldSpec spec) {
			this.spec = spec;
			if (spec.initialValue() != null) {
				this.text.append(spec.initialValue());
			}
			this.cursor = this.text.length();
		}
	}

	private final String title;
	private final String message;
	private final String confirmLabel;
	private final boolean dangerConfirm;
	private final List<FieldState> fields = new ArrayList<>();
	private final Consumer<Map<String, String>> onConfirm;
	private final Runnable onCancel;

	private int focusedFieldIndex = 0;
	private String validationError = null;
	private IdeLayout.Rect confirmBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
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
		this.title = title;
		this.message = message;
		this.confirmLabel = confirmLabel;
		this.dangerConfirm = dangerConfirm;
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
				List.of(),
				ignored -> onConfirm.run(),
				onCancel
		);
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

			QuillDraw.fill(graphics, field.boxRect.x(), field.boxRect.y(), field.boxRect.width(), field.boxRect.height(), QuillTheme.BG_INPUT);
			QuillDraw.outline(graphics, field.boxRect.x(), field.boxRect.y(), field.boxRect.width(), field.boxRect.height(),
					focused ? QuillTheme.ACCENT_FOCUS : QuillTheme.BORDER_SUBTLE);

			QuillDraw.beginClip(graphics, field.boxRect);
			String value = field.text.toString();
			int textY = boxY + (18 - font.lineHeight) / 2;
			if (value.isEmpty() && field.spec.placeholder() != null && !focused) {
				QuillDraw.text(graphics, font, field.spec.placeholder(), field.boxRect.x() + 5, textY, QuillTheme.TEXT_MUTED);
			} else {
				QuillDraw.text(graphics, font, value, field.boxRect.x() + 5, textY, QuillTheme.TEXT_BRIGHT);
			}
			if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
				int cursorX = field.boxRect.x() + 5 + font.width(value.substring(0, Math.min(field.cursor, value.length())));
				QuillDraw.fill(graphics, cursorX, boxY + 3, 1, 12, QuillTheme.CURSOR_COLOR);
			}
			QuillDraw.endClip(graphics);

			if (field.boxRect.contains(mouseX, mouseY)) {
				QuillDraw.requestCursor(graphics, CursorTypes.IBEAM);
			}
			curY += 34;
		}

		if (validationError != null) {
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, validationError, dialogW - 16), dx + 8, curY, QuillTheme.TEXT_ERROR);
			curY += 16;
		}

		int btnW = 78;
		int btnH = 18;
		int btnY = dy + dialogH - btnH - 8;
		cancelBtnRect = new IdeLayout.Rect(dx + dialogW - btnW - 8, btnY, btnW, btnH);
		confirmBtnRect = new IdeLayout.Rect(cancelBtnRect.x() - btnW - 6, btnY, btnW, btnH);

		boolean hoverConfirm = confirmBtnRect.contains(mouseX, mouseY);
		boolean hoverCancel = cancelBtnRect.contains(mouseX, mouseY);
		int confirmColor = dangerConfirm ? QuillTheme.BG_BUTTON_DANGER : (hoverConfirm ? QuillTheme.BG_BUTTON_HOVER : QuillTheme.BG_BUTTON);
		QuillDraw.fill(graphics, confirmBtnRect.x(), confirmBtnRect.y(), confirmBtnRect.width(), confirmBtnRect.height(), confirmColor);
		QuillDraw.fill(graphics, cancelBtnRect.x(), cancelBtnRect.y(), cancelBtnRect.width(), cancelBtnRect.height(),
				hoverCancel ? QuillTheme.BG_HOVER : QuillTheme.BG_BUTTON_SECONDARY);

		int labelY = btnY + (btnH - font.lineHeight) / 2;
		QuillDraw.text(graphics, font, confirmLabel,
				confirmBtnRect.x() + (btnW - font.width(confirmLabel)) / 2, labelY, QuillTheme.TEXT_BRIGHT);
		QuillDraw.text(graphics, font, "Cancel",
				cancelBtnRect.x() + (btnW - font.width("Cancel")) / 2, labelY, QuillTheme.TEXT_PRIMARY);

		if (hoverConfirm || hoverCancel) {
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
		if (cancelBtnRect.contains(mx, my)) {
			onCancel.run();
			return true;
		}
		for (int i = 0; i < fields.size(); i++) {
			FieldState field = fields.get(i);
			if (field.boxRect.contains(mx, my)) {
				focusedFieldIndex = i;
				Font font = Minecraft.getInstance().font;
				int relX = (int) mx - (field.boxRect.x() + 5);
				String sub = font.plainSubstrByWidth(field.text.toString(), Math.max(0, relX));
				field.cursor = sub.length();
				return true;
			}
		}
		if (!dialogRect.contains(mx, my)) {
			onCancel.run();
		}
		return true;
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
		if (event.isPaste()) {
			String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
			if (clip != null && !clip.isEmpty()) {
				String singleLine = clip.replace("\r", "").replace("\n", "");
				field.text.insert(field.cursor, singleLine);
				field.cursor += singleLine.length();
				validationError = null;
			}
			return true;
		}
		if (event.isCopy()) {
			Minecraft.getInstance().keyboardHandler.setClipboard(field.text.toString());
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_BACKSPACE || event.key() == InputConstants.KEY_BACKSPACE) {
			if (field.cursor > 0) {
				field.text.deleteCharAt(field.cursor - 1);
				field.cursor--;
				validationError = null;
			}
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_DELETE || event.key() == InputConstants.KEY_DELETE) {
			if (field.cursor < field.text.length()) {
				field.text.deleteCharAt(field.cursor);
				validationError = null;
			}
			return true;
		}
		if (event.isLeft()) {
			field.cursor = Math.max(0, field.cursor - 1);
			return true;
		}
		if (event.isRight()) {
			field.cursor = Math.min(field.text.length(), field.cursor + 1);
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_HOME || event.key() == InputConstants.KEY_HOME) {
			field.cursor = 0;
			return true;
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_END || event.key() == InputConstants.KEY_END) {
			field.cursor = field.text.length();
			return true;
		}
		return true;
	}

	public boolean charTyped(CharacterEvent event) {
		if (fields.isEmpty() || !event.isAllowedChatCharacter()) {
			return true;
		}
		FieldState field = fields.get(focusedFieldIndex);
		String ch = event.codepointAsString();
		field.text.insert(field.cursor, ch);
		field.cursor += ch.length();
		validationError = null;
		return true;
	}

	private void trySubmit() {
		Map<String, String> values = new LinkedHashMap<>();
		for (int i = 0; i < fields.size(); i++) {
			FieldState field = fields.get(i);
			String val = field.text.toString().trim();
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
}
