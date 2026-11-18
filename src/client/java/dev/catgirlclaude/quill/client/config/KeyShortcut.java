package dev.catgirlclaude.quill.client.config;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

import java.util.Locale;

/**
 * Pure-Java key combination descriptor supporting Ctrl/Shift/Alt modifiers, ASCII keycodes
 * (e.g., {@code 's'}, {@code ','}, {@code Space}), and function keys ({@code F1}–{@code F12}).
 */
public record KeyShortcut(boolean ctrl, boolean shift, boolean alt, int keyCode, int scanCode) {
	public static final KeyShortcut UNBOUND = new KeyShortcut(false, false, false, 0, 0);
	private static final int KEY_KP_SUBTRACT = 86;

	public boolean isUnbound() {
		return keyCode <= 0 && scanCode <= 0;
	}

	public static KeyShortcut ofChar(boolean ctrl, boolean shift, boolean alt, char ch) {
		return new KeyShortcut(ctrl, shift, alt, Character.toLowerCase(ch), 0);
	}

	public static KeyShortcut ofFunctionKey(boolean ctrl, boolean shift, boolean alt, int fNumber) {
		int sc = switch (fNumber) {
			case 1 -> InputConstants.KEY_F1;
			case 2 -> InputConstants.KEY_F2;
			case 3 -> InputConstants.KEY_F3;
			case 4 -> InputConstants.KEY_F4;
			case 5 -> InputConstants.KEY_F5;
			case 6 -> InputConstants.KEY_F6;
			case 7 -> InputConstants.KEY_F7;
			case 8 -> InputConstants.KEY_F8;
			case 9 -> InputConstants.KEY_F9;
			case 10 -> InputConstants.KEY_F10;
			case 11 -> InputConstants.KEY_F11;
			case 12 -> InputConstants.KEY_F12;
			default -> InputConstants.KEY_F1;
		};
		return new KeyShortcut(ctrl, shift, alt, 0, sc);
	}

	public static KeyShortcut ofSpace(boolean ctrl, boolean shift, boolean alt) {
		return new KeyShortcut(ctrl, shift, alt, InputConstants.KEYCODE_SPACE, InputConstants.KEY_SPACE);
	}

	public boolean matches(KeyEvent event) {
		if (event == null || isUnbound()) {
			return false;
		}
		if (event.hasControlDownWithQuirk() != ctrl
				|| event.hasShiftDown() != shift
				|| event.hasAltDown() != alt) {
			return false;
		}
		if (keyCode == InputConstants.KEYCODE_SPACE || scanCode == InputConstants.KEY_SPACE) {
			return event.shortcutKey() == InputConstants.KEYCODE_SPACE || event.key() == InputConstants.KEY_SPACE;
		}
		if (keyCode > 0) {
			int evCode = event.shortcutKey();
			if (evCode == keyCode) {
				return true;
			}
			if (evCode >= 'A' && evCode <= 'Z' && Character.toLowerCase((char) evCode) == keyCode) {
				return true;
			}
			if (keyCode >= 'a' && keyCode <= 'z' && event.key() >= 'A' && event.key() <= 'Z'
					&& Character.toLowerCase((char) event.key()) == keyCode) {
				return true;
			}
			if (keyCode == ',' && event.key() == InputConstants.KEY_COMMA) {
				return true;
			}
			if (keyCode == '-' && (event.key() == InputConstants.KEY_MINUS || event.key() == KEY_KP_SUBTRACT)) {
				return true;
			}
			if (keyCode == '=' && (evCode == '+'
					|| event.key() == InputConstants.KEY_EQUALS
					|| event.key() == InputConstants.KEY_ADD
					|| event.key() == InputConstants.KEY_NUMPADEQUALS)) {
				return true;
			}
			if (keyCode == '0' && (event.key() == InputConstants.KEY_0 || event.key() == InputConstants.KEY_NUMPAD0)) {
				return true;
			}
		}
		if (scanCode > 0 && event.key() == scanCode) {
			return true;
		}
		return false;
	}

	public static boolean isModifierOnly(KeyEvent event) {
		int k = event.key();
		return k == InputConstants.KEY_LSHIFT || k == InputConstants.KEY_RSHIFT
				|| k == InputConstants.KEY_LCONTROL || k == InputConstants.KEY_RCONTROL
				|| k == InputConstants.KEY_LALT || k == InputConstants.KEY_RALT
				|| k == InputConstants.KEY_LGUI || k == InputConstants.KEY_RGUI
				|| k == InputConstants.KEY_CAPSLOCK;
	}

	/**
	 * Captures a {@link KeyShortcut} from a live {@link KeyEvent}, returning {@code null} for
	 * bare modifier presses or Escape.
	 */
	public static KeyShortcut fromKeyEvent(KeyEvent event) {
		if (event == null || event.isEscape() || isModifierOnly(event)) {
			return null;
		}
		boolean c = event.hasControlDownWithQuirk();
		boolean s = event.hasShiftDown();
		boolean a = event.hasAltDown();

		int fn = functionKeyNumber(event.key());
		if (fn > 0) {
			return ofFunctionKey(c, s, a, fn);
		}
		if (event.shortcutKey() == InputConstants.KEYCODE_SPACE || event.key() == InputConstants.KEY_SPACE) {
			return ofSpace(c, s, a);
		}
		int sc = event.shortcutKey();
		if (sc >= 33 && sc <= 126) {
			return ofChar(c, s, a, (char) sc);
		}
		if (event.key() == InputConstants.KEY_COMMA) {
			return ofChar(c, s, a, ',');
		}
		if (event.key() == InputConstants.KEY_MINUS || event.key() == KEY_KP_SUBTRACT) {
			return ofChar(c, s, a, '-');
		}
		if (event.key() == InputConstants.KEY_EQUALS
				|| event.key() == InputConstants.KEY_ADD
				|| event.key() == InputConstants.KEY_NUMPADEQUALS) {
			return ofChar(c, s, a, '=');
		}
		if (event.key() == InputConstants.KEY_0 || event.key() == InputConstants.KEY_NUMPAD0) {
			return ofChar(c, s, a, '0');
		}
		return null;
	}

	public String displayLabel() {
		if (isUnbound()) {
			return "Not Bound";
		}
		StringBuilder sb = new StringBuilder();
		if (ctrl) {
			sb.append("Ctrl+");
		}
		if (shift) {
			sb.append("Shift+");
		}
		if (alt) {
			sb.append("Alt+");
		}
		sb.append(keyName());
		return sb.toString();
	}

	public String format() {
		return isUnbound() ? "UNBOUND" : displayLabel();
	}

	public static KeyShortcut parse(String raw, KeyShortcut fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		String trimmed = raw.trim();
		String trimmedUpper = trimmed.toUpperCase(Locale.ROOT);
		if ("UNBOUND".equals(trimmedUpper) || "NONE".equals(trimmedUpper) || "NOT BOUND".equals(trimmedUpper)) {
			return UNBOUND;
		}
		boolean trailingPlus = trimmed.equals("+") || trimmed.endsWith("++");
		String[] parts = trimmed.split("\\+");
		boolean c = false;
		boolean s = false;
		boolean a = false;
		String keyToken = null;
		for (String part : parts) {
			String token = part.trim();
			if (token.isEmpty()) {
				continue;
			}
			String upper = token.toUpperCase(Locale.ROOT);
			switch (upper) {
				case "CTRL", "CONTROL", "CMD" -> c = true;
				case "SHIFT" -> s = true;
				case "ALT", "OPT" -> a = true;
				default -> keyToken = token;
			}
		}
		if (trailingPlus && keyToken == null) {
			keyToken = "+";
		}
		if (keyToken == null || keyToken.isEmpty()) {
			return fallback;
		}
		String upper = keyToken.toUpperCase(Locale.ROOT);
		if ("SPACE".equals(upper)) {
			return ofSpace(c, s, a);
		}
		if (upper.startsWith("F") && upper.length() <= 3) {
			try {
				int fn = Integer.parseInt(upper.substring(1));
				if (fn >= 1 && fn <= 12) {
					return ofFunctionKey(c, s, a, fn);
				}
			} catch (NumberFormatException ignored) {
				// Fall through to single-char handling
			}
		}
		if ("COMMA".equals(upper)) {
			return ofChar(c, s, a, ',');
		}
		if ("MINUS".equals(upper)) {
			return ofChar(c, s, a, '-');
		}
		if ("EQUALS".equals(upper) || "EQUAL".equals(upper)) {
			return ofChar(c, s, a, '=');
		}
		if ("PLUS".equals(upper)) {
			return ofChar(c, s, a, '+');
		}
		if (keyToken.length() == 1) {
			char ch = keyToken.charAt(0);
			if (ch >= 33 && ch <= 126) {
				return ofChar(c, s, a, ch);
			}
		}
		return fallback;
	}

	private String keyName() {
		int fn = functionKeyNumber(scanCode);
		if (fn > 0) {
			return "F" + fn;
		}
		if (keyCode == InputConstants.KEYCODE_SPACE || scanCode == InputConstants.KEY_SPACE) {
			return "Space";
		}
		if (keyCode >= 'a' && keyCode <= 'z') {
			return String.valueOf(Character.toUpperCase((char) keyCode));
		}
		if (keyCode >= 33 && keyCode <= 126) {
			return String.valueOf((char) keyCode);
		}
		return "?";
	}

	private static int functionKeyNumber(int scanCode) {
		if (scanCode == InputConstants.KEY_F1) return 1;
		if (scanCode == InputConstants.KEY_F2) return 2;
		if (scanCode == InputConstants.KEY_F3) return 3;
		if (scanCode == InputConstants.KEY_F4) return 4;
		if (scanCode == InputConstants.KEY_F5) return 5;
		if (scanCode == InputConstants.KEY_F6) return 6;
		if (scanCode == InputConstants.KEY_F7) return 7;
		if (scanCode == InputConstants.KEY_F8) return 8;
		if (scanCode == InputConstants.KEY_F9) return 9;
		if (scanCode == InputConstants.KEY_F10) return 10;
		if (scanCode == InputConstants.KEY_F11) return 11;
		if (scanCode == InputConstants.KEY_F12) return 12;
		return 0;
	}
}
