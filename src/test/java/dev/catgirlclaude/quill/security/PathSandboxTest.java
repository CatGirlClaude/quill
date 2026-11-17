package dev.catgirlclaude.quill.security;

import dev.catgirlclaude.quill.api.EntryKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSandboxTest {

	@TempDir
	Path tempDir;

	@Test
	void rejectsTraversalAndMalformedPaths() {
		PathSandbox sandbox = new PathSandbox(tempDir);

		assertThrows(EditorSecurityException.class, () -> sandbox.normalizeRelativePath("../secret.txt"));
		assertThrows(EditorSecurityException.class, () -> sandbox.normalizeRelativePath("pack/../secret.txt"));
		assertThrows(EditorSecurityException.class, () -> sandbox.normalizeRelativePath("/etc/passwd"));
		assertThrows(EditorSecurityException.class, () -> sandbox.normalizeRelativePath("pack\\file.mcfunction"));
		assertThrows(EditorSecurityException.class, () -> sandbox.normalizeRelativePath("C:/windows/win.ini"));
		assertThrows(EditorSecurityException.class, () -> sandbox.normalizeRelativePath("pack/\0bad.txt"));
		assertThrows(EditorSecurityException.class, () -> sandbox.normalizeRelativePath(""));
		assertThrows(EditorSecurityException.class, () -> sandbox.normalizeRelativePath("pack/"));
	}

	@Test
	void enforcesExtensionAllowlistAndRejectsZipArchives() {
		PathSandbox sandbox = new PathSandbox(tempDir);

		assertThrows(EditorSecurityException.class, () -> sandbox.resolveWritableFile("pack/exploit.jar"));
		assertThrows(EditorSecurityException.class, () -> sandbox.resolveWritableFile("pack/run.sh"));
		assertThrows(EditorSecurityException.class, () -> sandbox.resolveWritableFile("archive.zip/pack.mcmeta"));
		assertThrows(EditorSecurityException.class, () -> sandbox.resolveEntryForCreate("archive.zip", EntryKind.DATAPACK));

		Path valid = sandbox.resolveWritableFile("my_pack/data/demo/function/hello.mcfunction");
		assertEquals(tempDir.resolve("my_pack/data/demo/function/hello.mcfunction").normalize(), valid);
	}

	@Test
	void enforcesSizeAndLineLimits() {
		PathSandbox sandbox = new PathSandbox(tempDir);

		String oversized = "a".repeat(EditorLimits.MAX_FILE_SIZE_BYTES + 1);
		assertThrows(EditorSecurityException.class, () -> sandbox.validateContent(oversized));

		String tooManyLines = "say hi\n".repeat(EditorLimits.MAX_LINE_COUNT + 1);
		assertThrows(EditorSecurityException.class, () -> sandbox.validateContent(tooManyLines));
	}

	@Test
	void rejectsSymlinkEscapes() throws IOException {
		Path datapacks = Files.createDirectory(tempDir.resolve("datapacks"));
		Path outside = Files.createDirectory(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("secret.txt"), "top secret");

		Path link = datapacks.resolve("escape_pack");
		try {
			Files.createSymbolicLink(link, outside);
		} catch (UnsupportedOperationException | IOException e) {
			// Skip if environment forbids creating symlinks
			return;
		}

		PathSandbox sandbox = new PathSandbox(datapacks);
		assertThrows(EditorSecurityException.class, () -> sandbox.resolveExistingFile("escape_pack/secret.txt"));
	}

	@Test
	void atomicWriteProducesExpectedFileWithoutLeavingTempFile() throws IOException {
		PathSandbox sandbox = new PathSandbox(tempDir);
		Path target = sandbox.resolveWritableFile("demo/data/demo/function/tick.mcfunction");

		sandbox.atomicWriteUtf8(target, "say hello\n");

		assertTrue(Files.isRegularFile(target));
		assertEquals("say hello\n", Files.readString(target));
		assertFalse(Files.exists(target.getParent().resolve(".tick.mcfunction.quill.tmp")));
	}
}
