package dev.catgirlclaude.quill.api;

import java.util.Objects;

/**
 * UTF-8 file document returned by {@link EditorBackend#readFile(String)} and write operations.
 */
public record FileDocument(
		String relativePath,
		String content,
		String sha256,
		long lastModifiedEpochMs
) {
	public FileDocument {
		Objects.requireNonNull(relativePath, "relativePath");
		Objects.requireNonNull(content, "content");
		Objects.requireNonNull(sha256, "sha256");
	}
}
