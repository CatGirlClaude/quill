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

	// Multiplayer networking & framing bounds (kept safely under MC 26.3's 32,767-byte C2S custom payload cap)
	public static final int MAX_CHUNK_BYTES = 24 * 1024;
	public static final int MAX_MESSAGE_BYTES = 1024 * 1024;
	public static final int MAX_CHUNKS_PER_MESSAGE = (MAX_MESSAGE_BYTES + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES + 1;
	public static final int MAX_CONCURRENT_TRANSFERS_PER_PLAYER = 4;
	public static final int MAX_PENDING_TRANSFER_BYTES_PER_PLAYER = 2 * 1024 * 1024;
	public static final long TRANSFER_TIMEOUT_MS = 30_000L;
	public static final long REQUEST_TIMEOUT_MS = 30_000L;

	// Server-side RPC rate limits & metadata bounds
	public static final int DEFAULT_REQUESTS_PER_MINUTE = 120;
	public static final int DEFAULT_RATE_LIMIT_BURST = 40;
	public static final int MAX_PRESET_VARIABLES = 32;
	public static final int MAX_DESCRIPTION_LENGTH = 512;
	public static final int MAX_VARIABLE_LENGTH = 256;
	public static final int MAX_DIAGNOSTICS_PER_REPORT = 1_000;
	public static final int MAX_ERROR_MESSAGE_LENGTH = 1_024;

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
