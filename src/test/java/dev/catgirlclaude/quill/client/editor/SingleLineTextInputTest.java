package dev.catgirlclaude.quill.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleLineTextInputTest {

	@Test
	void supportsShiftArrowCtrlShiftArrowAndSelectAllReplacement() {
		SingleLineTextInput input = new SingleLineTextInput("hello_world foo.bar");
		assertEquals(19, input.cursor());
		assertFalse(input.hasSelection());

		// Ctrl+Shift+Left selects "bar"
		input.keyPressed(new KeyEvent(InputConstants.KEY_LEFT, InputConstants.KEYCODE_LEFT,
				InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT));
		assertTrue(input.hasSelection());
		assertEquals("bar", input.selectedText());

		// Ctrl+Shift+Left again extends across "."
		input.keyPressed(new KeyEvent(InputConstants.KEY_LEFT, InputConstants.KEYCODE_LEFT,
				InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT));
		assertEquals(".bar", input.selectedText());

		// Shift+Home selects to beginning of input
		input.keyPressed(new KeyEvent(InputConstants.KEY_HOME, InputConstants.KEYCODE_HOME, InputConstants.MOD_SHIFT));
		assertEquals("hello_world foo.bar", input.selectedText());

		// Typing replaces active selection
		assertTrue(input.charTyped(new CharacterEvent('x')));
		assertEquals("x", input.text());
		assertFalse(input.hasSelection());
		assertEquals(1, input.cursor());
	}

	@Test
	void supportsWordSelectionCtrlBackspaceAndCtrlDelete() {
		SingleLineTextInput input = new SingleLineTextInput("say hello world");
		input.selectWordAt(5);
		assertEquals("hello", input.selectedText());

		// Collapsing right without shift places caret at selection end
		input.moveRight(false, false);
		assertFalse(input.hasSelection());
		assertEquals(9, input.cursor());

		// Ctrl+Backspace deletes "hello"
		assertTrue(input.backspace(true));
		assertEquals("say  world", input.text());

		// Ctrl+Delete deletes " world"
		assertTrue(input.deleteForward(true));
		assertEquals("say ", input.text());
	}

	@Test
	void supportsSelectAllAndShiftEnd() {
		SingleLineTextInput input = new SingleLineTextInput("alpha beta");
		input.moveHome(false);
		assertEquals(0, input.cursor());

		input.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, InputConstants.MOD_SHIFT));
		input.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, InputConstants.MOD_SHIFT));
		assertEquals("al", input.selectedText());

		input.keyPressed(new KeyEvent(InputConstants.KEY_END, InputConstants.KEYCODE_END, InputConstants.MOD_SHIFT));
		assertEquals("alpha beta", input.selectedText());

		input.moveHome(false);
		input.keyPressed(new KeyEvent(InputConstants.KEY_A, 'a', InputConstants.MOD_CONTROL));
		assertEquals("alpha beta", input.selectedText());
	}
}
