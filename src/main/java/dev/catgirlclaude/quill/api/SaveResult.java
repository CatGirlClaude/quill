package dev.catgirlclaude.quill.api;

import java.util.Objects;

/**
 * Result of saving a file through {@link EditorBackend#writeFile(String, String, boolean)}.
 *
 * @param document     Updated document metadata after disk write
 * @param reloadReport Optional reload report if {@code reloadAfterSave} was requested; may be {@code null}
 */
public record SaveResult(
		FileDocument document,
		ReloadReport reloadReport
) {
	public SaveResult {
		Objects.requireNonNull(document, "document");
	}
}
