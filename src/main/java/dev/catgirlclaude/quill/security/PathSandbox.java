package dev.catgirlclaude.quill.security;

import dev.catgirlclaude.quill.api.EntryKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Enforces root anchoring, traversal guards, extension allowlists, symlink rejection,
 * size limits, and atomic UTF-8 file writes within {@code <world>/datapacks/}.
 */
public final class PathSandbox {
	private static final String TEMP_SUFFIX = ".quill.tmp";

	private final Path root;

	public PathSandbox(Path root) {
		Objects.requireNonNull(root, "root");
		this.root = root.toAbsolutePath().normalize();
	}

	public Path root() {
		return root;
	}

	/**
	 * Validates and normalizes a relative path using forward slashes ({@code /}).
	 */
	public String normalizeRelativePath(String rawPath) {
		if (rawPath == null || rawPath.isBlank()) {
			throw new EditorSecurityException("Path must not be empty.");
		}
		if (rawPath.length() > EditorLimits.MAX_PATH_LENGTH) {
			throw new EditorSecurityException("Path exceeds maximum length of " + EditorLimits.MAX_PATH_LENGTH + " characters.");
		}
		if (rawPath.indexOf('\0') >= 0) {
			throw new EditorSecurityException("Path contains illegal null byte.");
		}
		if (rawPath.indexOf('\\') >= 0) {
			throw new EditorSecurityException("Backslashes are not allowed in relative paths; use '/'.");
		}
		if (rawPath.startsWith("/") || rawPath.endsWith("/")) {
			throw new EditorSecurityException("Path must be relative without leading or trailing '/'.");
		}
		if (rawPath.indexOf(':') >= 0) {
			throw new EditorSecurityException("Drive letters or colons are not allowed in paths.");
		}

		String[] segments = rawPath.split("/", -1);
		if (segments.length > EditorLimits.MAX_TREE_DEPTH) {
			throw new EditorSecurityException("Path exceeds maximum depth of " + EditorLimits.MAX_TREE_DEPTH + ".");
		}

		List<String> cleanSegments = new ArrayList<>(segments.length);
		for (String segment : segments) {
			validateSegment(segment);
			cleanSegments.add(segment);
		}
		return String.join("/", cleanSegments);
	}

	public static void validateSegment(String segment) {
		if (segment == null || segment.isEmpty()) {
			throw new EditorSecurityException("Path contains an empty segment.");
		}
		if (".".equals(segment) || "..".equals(segment)) {
			throw new EditorSecurityException("Path traversal segments ('.' or '..') are forbidden.");
		}
		for (int i = 0; i < segment.length(); i++) {
			char c = segment.charAt(i);
			if (c < 0x20 || c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
				throw new EditorSecurityException("Path segment contains illegal character: '" + c + "'");
			}
		}
	}

	public static void validatePackOrFolderName(String name) {
		validateSegment(name);
		if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			throw new EditorSecurityException("Archive datapacks (.zip) are not supported; use directory datapacks.");
		}
	}

	public static boolean hasAllowedExtension(String relativePath) {
		String lower = relativePath.toLowerCase(Locale.ROOT);
		for (String ext : EditorLimits.ALLOWED_EXTENSIONS) {
			if (lower.endsWith(ext)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isTempFile(Path path) {
		String name = path.getFileName() == null ? "" : path.getFileName().toString();
		return name.endsWith(TEMP_SUFFIX);
	}

	public static boolean isTempOrHiddenArchive(Path path) {
		String name = path.getFileName() == null ? "" : path.getFileName().toString();
		return isTempFile(path) || name.toLowerCase(Locale.ROOT).endsWith(".zip");
	}

	public Path resolveExistingFile(String rawRelativePath) {
		String normalized = normalizeRelativePath(rawRelativePath);
		requireAllowedFileExtension(normalized);
		rejectArchivePackPath(normalized);

		Path target = resolveUnderRoot(normalized);
		ensureNoSymlinksOnExistingPrefix(target);

		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new EditorSecurityException("File does not exist: " + normalized);
		}
		if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new EditorSecurityException("Target is not a regular file: " + normalized);
		}
		try {
			long size = Files.size(target);
			if (size > EditorLimits.MAX_FILE_SIZE_BYTES) {
				throw new EditorSecurityException("File exceeds max allowed size of " + EditorLimits.MAX_FILE_SIZE_BYTES + " bytes.");
			}
		} catch (IOException e) {
			throw new EditorSecurityException("Failed to inspect file size: " + normalized, e);
		}
		return target;
	}

	public Path resolveWritableFile(String rawRelativePath) {
		String normalized = normalizeRelativePath(rawRelativePath);
		requireAllowedFileExtension(normalized);
		rejectArchivePackPath(normalized);

		Path target = resolveUnderRoot(normalized);
		ensureNoSymlinksOnExistingPrefix(target);

		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new EditorSecurityException("Cannot overwrite directory with a file: " + normalized);
		}
		return target;
	}

	public Path resolveEntryForCreate(String rawRelativePath, EntryKind kind) {
		Objects.requireNonNull(kind, "kind");
		if (kind == EntryKind.ROOT) {
			throw new EditorSecurityException("Cannot create root entry.");
		}
		String normalized = normalizeRelativePath(rawRelativePath);
		rejectArchivePackPath(normalized);

		if (kind == EntryKind.FILE) {
			requireAllowedFileExtension(normalized);
		} else if (kind == EntryKind.DATAPACK && normalized.contains("/")) {
			throw new EditorSecurityException("Datapack folder must be a direct child of datapacks/: " + normalized);
		}

		Path target = resolveUnderRoot(normalized);
		ensureNoSymlinksOnExistingPrefix(target);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new EditorSecurityException("Entry already exists: " + normalized);
		}
		return target;
	}

	public Path resolveExistingEntryForModify(String rawRelativePath) {
		String normalized = normalizeRelativePath(rawRelativePath);
		rejectArchivePackPath(normalized);

		Path target = resolveUnderRoot(normalized);
		ensureNoSymlinksOnExistingPrefix(target);
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new EditorSecurityException("Entry does not exist: " + normalized);
		}
		return target;
	}

	public Path resolveTargetForRename(String sourceRelativePath, String targetRelativePath, boolean sourceIsDirectory) {
		String normalizedSource = normalizeRelativePath(sourceRelativePath);
		String normalizedTarget = normalizeRelativePath(targetRelativePath);
		rejectArchivePackPath(normalizedSource);
		rejectArchivePackPath(normalizedTarget);

		if (normalizedSource.equals(normalizedTarget)) {
			throw new EditorSecurityException("Source and target paths are identical.");
		}
		if (sourceIsDirectory && normalizedTarget.startsWith(normalizedSource + "/")) {
			throw new EditorSecurityException("Cannot move a directory inside itself.");
		}
		if (!sourceIsDirectory) {
			requireAllowedFileExtension(normalizedTarget);
		}

		Path target = resolveUnderRoot(normalizedTarget);
		ensureNoSymlinksOnExistingPrefix(target);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new EditorSecurityException("Target already exists: " + normalizedTarget);
		}
		return target;
	}

	public void validateContent(String content) {
		if (content == null) {
			throw new EditorSecurityException("File content must not be null.");
		}
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > EditorLimits.MAX_FILE_SIZE_BYTES) {
			throw new EditorSecurityException("Content exceeds max file size of " + EditorLimits.MAX_FILE_SIZE_BYTES + " bytes.");
		}
		int lines = 1;
		for (int i = 0; i < content.length(); i++) {
			if (content.charAt(i) == '\n') {
				lines++;
				if (lines > EditorLimits.MAX_LINE_COUNT) {
					throw new EditorSecurityException("Content exceeds max line count of " + EditorLimits.MAX_LINE_COUNT + ".");
				}
			}
		}
	}

	public void ensureDirectoryTree(Path directory) throws IOException {
		ensureNoSymlinksOnExistingPrefix(directory);
		Files.createDirectories(directory);
		ensureNoSymlinksOnExistingPrefix(directory);
	}

	public void atomicWriteUtf8(Path target, String content) throws IOException {
		validateContent(content);
		Path parent = target.getParent();
		if (parent == null || !parent.startsWith(root)) {
			throw new EditorSecurityException("Invalid target parent path.");
		}
		ensureDirectoryTree(parent);
		ensureNoSymlinksOnExistingPrefix(target);

		Path tempFile = parent.resolve("." + target.getFileName() + TEMP_SUFFIX);
		try {
			Files.writeString(tempFile, content, StandardCharsets.UTF_8);
			try {
				Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	public void deleteRecursively(Path target) throws IOException {
		if (target.equals(root)) {
			throw new EditorSecurityException("Deleting the datapacks root directory is forbidden.");
		}
		ensureNoSymlinksOnExistingPrefix(target);
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
			List<Path> entries;
			try (var walk = Files.walk(target)) {
				entries = walk.sorted(Comparator.reverseOrder()).toList();
			}
			for (Path p : entries) {
				if (Files.isSymbolicLink(p)) {
					throw new EditorSecurityException("Refusing to traverse symbolic link during delete: " + relativize(p));
				}
			}
			for (Path p : entries) {
				Files.deleteIfExists(p);
			}
		} else {
			Files.deleteIfExists(target);
		}
	}

	public String relativize(Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		if (!normalized.startsWith(root)) {
			throw new EditorSecurityException("Path escapes sandbox root.");
		}
		return root.relativize(normalized).toString().replace('\\', '/');
	}

	public static String sha256Hex(String content) {
		return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
	}

	public static String sha256Hex(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private Path resolveUnderRoot(String normalizedRelativePath) {
		Path resolved = root.resolve(normalizedRelativePath).normalize();
		if (!resolved.startsWith(root) || resolved.equals(root)) {
			throw new EditorSecurityException("Resolved path escapes sandbox root: " + normalizedRelativePath);
		}
		return resolved;
	}

	private void requireAllowedFileExtension(String normalizedRelativePath) {
		if (!hasAllowedExtension(normalizedRelativePath)) {
			throw new EditorSecurityException("Unsupported file extension for: " + normalizedRelativePath
					+ ". Allowed: " + EditorLimits.ALLOWED_EXTENSIONS);
		}
	}

	private void rejectArchivePackPath(String normalizedRelativePath) {
		String firstSegment = normalizedRelativePath.split("/", 2)[0];
		if (firstSegment.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			throw new EditorSecurityException("Archive datapacks (.zip) are read-only and cannot be edited: " + firstSegment);
		}
	}

	private void ensureNoSymlinksOnExistingPrefix(Path target) {
		try {
			if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
				if (Files.isSymbolicLink(root)) {
					throw new EditorSecurityException("Datapacks root must not be a symbolic link.");
				}
				Path rootReal = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
				Path relative = root.relativize(target);
				Path cursor = root;
				Path deepestExisting = root;
				for (Path part : relative) {
					cursor = cursor.resolve(part);
					if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
						if (Files.isSymbolicLink(cursor)) {
							throw new EditorSecurityException("Symbolic links are forbidden inside datapacks/: " + relativize(cursor));
						}
						deepestExisting = cursor;
					} else {
						break;
					}
				}
				Path realAncestor = deepestExisting.toRealPath(LinkOption.NOFOLLOW_LINKS);
				if (!realAncestor.startsWith(rootReal)) {
					throw new EditorSecurityException("Path escapes sandbox root via link/junction.");
				}
			}
		} catch (IOException e) {
			throw new EditorSecurityException("Failed to verify path safety for: " + target, e);
		}
	}
}
