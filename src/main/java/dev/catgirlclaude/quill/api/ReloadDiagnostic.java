package dev.catgirlclaude.quill.api;

import java.util.Objects;

/**
 * Single parse or reload error surfaced during a datapack reload.
 *
 * @param file    Relative path inside {@code datapacks/} (or synthetic context label)
 * @param line    1-based line number, or {@code 0} when not tied to a specific line
 * @param message Human-readable diagnostic explanation
 */
public record ReloadDiagnostic(
		String file,
		int line,
		String message
) {
	public ReloadDiagnostic {
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(message, "message");
	}
}
