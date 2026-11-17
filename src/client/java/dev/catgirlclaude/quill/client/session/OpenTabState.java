package dev.catgirlclaude.quill.client.session;

import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.client.editor.TextDocumentBuffer;

import java.util.Objects;

/**
 * Holds client-side state for an open editor tab.
 */
public final class OpenTabState {
	private final String relativePath;
	private final TextDocumentBuffer buffer;
	private String sha256;
	private int scrollLine = 0;
	private int scrollX = 0;
	private boolean pendingScrollToCaret = false;

	public OpenTabState(FileDocument document) {
		Objects.requireNonNull(document, "document");
		this.relativePath = document.relativePath();
		this.buffer = new TextDocumentBuffer(document.content());
		this.sha256 = document.sha256();
	}

	public String relativePath() {
		return relativePath;
	}

	public String fileName() {
		int slash = relativePath.lastIndexOf('/');
		return slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
	}

	public TextDocumentBuffer buffer() {
		return buffer;
	}

	public String sha256() {
		return sha256;
	}

	public boolean isDirty() {
		return buffer.isDirty();
	}

	public void applySavedDocument(FileDocument document) {
		this.sha256 = document.sha256();
		this.buffer.markSaved(document.content());
	}

	public int scrollLine() {
		return scrollLine;
	}

	public void setScrollLine(int scrollLine) {
		this.scrollLine = Math.max(0, scrollLine);
	}

	public int scrollX() {
		return scrollX;
	}

	public void setScrollX(int scrollX) {
		this.scrollX = Math.max(0, scrollX);
	}

	public void requestScrollToCaret() {
		this.pendingScrollToCaret = true;
	}

	public boolean consumeScrollToCaret() {
		if (!pendingScrollToCaret) {
			return false;
		}
		pendingScrollToCaret = false;
		return true;
	}
}
