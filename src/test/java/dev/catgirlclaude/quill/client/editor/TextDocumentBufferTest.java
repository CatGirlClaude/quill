package dev.catgirlclaude.quill.client.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDocumentBufferTest {

	@Test
	void normalizesCrlfAndTracksDirtyState() {
		TextDocumentBuffer buffer = new TextDocumentBuffer("say one\r\nsay two\rsay three");
		assertEquals("say one\nsay two\nsay three", buffer.getText());
		assertEquals(3, buffer.lineCount());
		assertFalse(buffer.isDirty());

		buffer.insertText("# ");
		assertTrue(buffer.isDirty());
		assertEquals("# say one", buffer.line(0));

		buffer.markSaved(buffer.getText());
		assertFalse(buffer.isDirty());
	}

	@Test
	void supportsMultiLineInsertSelectionAndDelete() {
		TextDocumentBuffer buffer = new TextDocumentBuffer("alpha\nbeta\ngamma");
		buffer.setCursor(0, 2, false);
		buffer.setCursor(2, 3, true);

		assertTrue(buffer.hasSelection());
		assertEquals("pha\nbeta\ngam", buffer.getSelectedText());

		buffer.insertText("X\nY");
		assertFalse(buffer.hasSelection());
		assertEquals("alX\nYma", buffer.getText());
		assertEquals(new TextDocumentBuffer.Position(1, 1), buffer.cursor());
	}

	@Test
	void supportsSelectAllBackspaceDeleteForwardAndTabIndent() {
		TextDocumentBuffer buffer = new TextDocumentBuffer("say hi\nsay bye");
		buffer.setCursor(1, 0, false);
		buffer.backspace();
		assertEquals("say hisay bye", buffer.getText());
		assertEquals(new TextDocumentBuffer.Position(0, 6), buffer.cursor());

		buffer.deleteForward();
		assertEquals("say hiay bye", buffer.getText());

		buffer.setCursor(0, 0, false);
		buffer.insertTab();
		assertEquals("    say hiay bye", buffer.getText());

		buffer.selectAll();
		assertEquals("    say hiay bye", buffer.getSelectedText());
		buffer.deleteSelection();
		assertEquals("", buffer.getText());
		assertEquals(1, buffer.lineCount());
	}

	@Test
	void supportsWordJumpHomeEndAndJumpToLine() {
		TextDocumentBuffer buffer = new TextDocumentBuffer("execute as @a run say hi\nsecond line\nthird line");
		buffer.moveEnd(false, false);
		assertEquals(new TextDocumentBuffer.Position(0, 24), buffer.cursor());

		buffer.moveLeft(false, true);
		assertEquals(new TextDocumentBuffer.Position(0, 22), buffer.cursor());
		buffer.moveLeft(false, true);
		assertEquals(new TextDocumentBuffer.Position(0, 18), buffer.cursor());

		buffer.moveHome(false, true);
		assertEquals(new TextDocumentBuffer.Position(0, 0), buffer.cursor());
		buffer.moveEnd(false, true);
		assertEquals(new TextDocumentBuffer.Position(2, 10), buffer.cursor());

		buffer.jumpToLine(2);
		assertEquals(2, buffer.oneBasedLine());
		assertEquals(1, buffer.oneBasedColumn());

		buffer.jumpToLine(999);
		assertEquals(3, buffer.oneBasedLine());
	}

	@Test
	void supportsUndoRedoAndRestoresDirtyFlag() {
		TextDocumentBuffer buffer = new TextDocumentBuffer("say init");
		assertFalse(buffer.isDirty());

		buffer.insertText("a");
		buffer.insertText("b");
		buffer.insertText("c");
		assertTrue(buffer.isDirty());
		assertEquals("abcsay init", buffer.getText());

		// Coalesced single-char inserts undo together
		assertTrue(buffer.undo());
		assertEquals("say init", buffer.getText());
		assertFalse(buffer.isDirty());

		assertTrue(buffer.redo());
		assertEquals("abcsay init", buffer.getText());
		assertTrue(buffer.isDirty());
	}

	@Test
	void supportsCtrlShiftWordSelectionWordDeleteAndPreferredColumn() {
		TextDocumentBuffer buffer = new TextDocumentBuffer("execute as @a run foo.bar\nshort\nlong third line here");
		buffer.setCursor(0, 0, false);

		// Ctrl+Shift+Right selects word by word cleanly
		buffer.moveRight(true, true);
		assertEquals("execute", buffer.getSelectedText());
		buffer.moveRight(true, true);
		assertEquals("execute as", buffer.getSelectedText());

		// Word selection & word drag extension
		buffer.selectWordAt(0, 20);
		assertEquals("foo", buffer.getSelectedText());
		int[] wb = buffer.wordBoundsAt(0, 20);
		buffer.extendWordSelection(0, wb[0], wb[1], 0, 23);
		assertEquals("foo.bar", buffer.getSelectedText());

		// Preferred column preserved across short middle line
		buffer.setCursor(0, 18, false);
		buffer.moveVertical(1, false);
		assertEquals(new TextDocumentBuffer.Position(1, 5), buffer.cursor());
		buffer.moveVertical(1, false);
		assertEquals(new TextDocumentBuffer.Position(2, 18), buffer.cursor());

		// Ctrl+Backspace and Ctrl+Delete
		buffer.setCursor(2, 10, false);
		buffer.deleteWordBackward();
		assertEquals("long  line here", buffer.line(2));
		buffer.deleteWordForward();
		assertEquals("long  here", buffer.line(2));
	}

	@Test
	void supportsLineSelectionMultiLineIndentUnindentAndBracketMatching() {
		TextDocumentBuffer buffer = new TextDocumentBuffer("{\n  \"pack\": [1, 2]\n}");
		buffer.selectLine(1);
		assertEquals("  \"pack\": [1, 2]\n", buffer.getSelectedText());

		buffer.extendLineSelection(0, 1);
		assertEquals("{\n  \"pack\": [1, 2]\n", buffer.getSelectedText());

		// Indent and unindent multi-line selection
		buffer.indentSelection();
		assertEquals("    {", buffer.line(0));
		assertEquals("      \"pack\": [1, 2]", buffer.line(1));
		assertEquals("}", buffer.line(2));

		buffer.unindentSelection();
		assertEquals("{", buffer.line(0));
		assertEquals("  \"pack\": [1, 2]", buffer.line(1));

		// Bracket matching
		buffer.setCursor(0, 0, false);
		TextDocumentBuffer.BracketMatch outer = buffer.findMatchingBracket();
		assertEquals(new TextDocumentBuffer.Position(0, 0), outer.open());
		assertEquals(new TextDocumentBuffer.Position(2, 0), outer.close());

		buffer.setCursor(1, 16, false);
		TextDocumentBuffer.BracketMatch inner = buffer.findMatchingBracket();
		assertEquals(new TextDocumentBuffer.Position(1, 10), inner.open());
		assertEquals(new TextDocumentBuffer.Position(1, 15), inner.close());
	}
}
