package dev.catgirlclaude.quill.security;

/**
 * Thrown when an editor path, content payload, or permission check violates sandbox rules.
 */
public class EditorSecurityException extends RuntimeException {
	public EditorSecurityException(String message) {
		super(message);
	}

	public EditorSecurityException(String message, Throwable cause) {
		super(message, cause);
	}
}
