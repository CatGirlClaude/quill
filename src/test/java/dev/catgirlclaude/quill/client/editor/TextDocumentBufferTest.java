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
}
