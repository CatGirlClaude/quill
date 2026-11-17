package dev.catgirlclaude.quill.api;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a datapack discovery and reload operation.
 */
public record ReloadReport(
		boolean success,
		long durationMs,
		int enabledPackCount,
		List<ReloadDiagnostic> diagnostics
) {
	public ReloadReport {
		diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
	}

	public boolean hasErrors() {
		return !success || !diagnostics.isEmpty();
	}
}
