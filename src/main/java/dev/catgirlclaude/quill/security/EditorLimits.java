package dev.catgirlclaude.quill.security;

import java.util.Set;

/**
 * Hard safety and resource limits enforced across all backend operations.
 */
public final class EditorLimits {
	public static final int MAX_FILE_SIZE_BYTES = 512 * 1024;
	public static final int MAX_LINE_COUNT = 10_000;
	public static final int MAX_TREE_DEPTH = 16;
	public static final int MAX_TREE_ENTRIES = 5_000;
	public static final int MAX_PATH_LENGTH = 255;
	public static final long RELOAD_COOLDOWN_MS = 500L;

	public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
			".mcfunction",
			".json",
			".mcmeta",
			".txt",
			".md"
	);

	private EditorLimits() {
	}
}
