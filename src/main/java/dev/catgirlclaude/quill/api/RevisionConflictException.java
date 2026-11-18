package dev.catgirlclaude.quill.api;

import java.util.Objects;

/**
 * Thrown when saving a file fails an optimistic concurrency check because its on-disk
 * SHA-256 revision no longer matches the client's expected base revision.
 */
public class RevisionConflictException extends RuntimeException {
	private final String relativePath;
	private final String expectedSha256;
	private final String actualSha256;

	public RevisionConflictException(String relativePath, String expectedSha256, String actualSha256) {
		super(buildMessage(relativePath, expectedSha256, actualSha256));
		this.relativePath = Objects.requireNonNull(relativePath, "relativePath");
		this.expectedSha256 = expectedSha256 == null ? "" : expectedSha256;
		this.actualSha256 = actualSha256 == null ? "" : actualSha256;
	}

	public String relativePath() {
		return relativePath;
	}

	public String expectedSha256() {
		return expectedSha256;
	}

	public String actualSha256() {
		return actualSha256;
	}

	private static String buildMessage(String relativePath, String expectedSha256, String actualSha256) {
		String expectedShort = abbreviate(expectedSha256);
		if (actualSha256 == null || actualSha256.isEmpty()) {
			return "Revision conflict for '" + relativePath + "': file was deleted on the server (expected " + expectedShort + ").";
		}
		return "Revision conflict for '" + relativePath + "': file was modified externally (expected "
				+ expectedShort + ", server has " + abbreviate(actualSha256) + ").";
	}

	private static String abbreviate(String sha256) {
		if (sha256 == null || sha256.isEmpty()) {
			return "<none>";
		}
		return sha256.length() <= 8 ? sha256 : sha256.substring(0, 8);
	}
}
