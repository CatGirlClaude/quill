package dev.catgirlclaude.quill.backend;

import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerEditorBackendSyncTest {

	@TempDir
	Path datapacksRoot;

	@Test
	void scansTreeIgnoringZipArchivesAndTempFiles() throws IOException {
		PathSandbox sandbox = new PathSandbox(datapacksRoot);
		Files.writeString(datapacksRoot.resolve("legacy.zip"), "zip");
		Path pack = Files.createDirectories(datapacksRoot.resolve("my_pack/data/demo/function"));
		Files.writeString(datapacksRoot.resolve("my_pack/pack.mcmeta"), "{}");
		Files.writeString(pack.resolve("hello.mcfunction"), "say hi\n");
		Files.writeString(pack.resolve(".hello.mcfunction.quill.tmp"), "tmp");
		Files.writeString(datapacksRoot.resolve("my_pack/pack.png"), "png");

		FileTreeNode root = ServerEditorBackend.scanTree(sandbox);
		assertEquals(EntryKind.ROOT, root.kind());
		assertEquals(1, root.children().size());
		FileTreeNode packNode = root.children().getFirst();
		assertEquals("my_pack", packNode.name());
		assertEquals(EntryKind.DATAPACK, packNode.kind());
		assertEquals(3, packNode.children().size());
		assertEquals("data", packNode.children().get(0).name());
		assertEquals("pack.mcmeta", packNode.children().get(1).name());
		assertEquals("pack.png", packNode.children().get(2).name());
		assertThrows(EditorSecurityException.class, () -> ServerEditorBackend.readDocumentSync(sandbox, "my_pack/pack.png"));
	}

	@Test
	void supportsCreateReadWriteRenameAndDelete() {
		PathSandbox sandbox = new PathSandbox(datapacksRoot);

		ServerEditorBackend.createEntrySync(sandbox, "demo_pack", EntryKind.DATAPACK);
		ServerEditorBackend.createEntrySync(sandbox, "demo_pack/data/test/function", EntryKind.DIRECTORY);
		ServerEditorBackend.createEntrySync(sandbox, "demo_pack/data/test/function/a.mcfunction", EntryKind.FILE);

		FileDocument written = ServerEditorBackend.writeDocumentSync(
				sandbox,
				"demo_pack/data/test/function/a.mcfunction",
				"say first\n"
		);
		assertEquals(PathSandbox.sha256Hex("say first\n"), written.sha256());

		FileDocument read = ServerEditorBackend.readDocumentSync(sandbox, "demo_pack/data/test/function/a.mcfunction");
		assertEquals("say first\n", read.content());

		ServerEditorBackend.renameEntrySync(
				sandbox,
				"demo_pack/data/test/function/a.mcfunction",
				"demo_pack/data/test/function/b.mcfunction"
		);
		assertFalse(Files.exists(datapacksRoot.resolve("demo_pack/data/test/function/a.mcfunction")));
		assertTrue(Files.exists(datapacksRoot.resolve("demo_pack/data/test/function/b.mcfunction")));

		assertThrows(EditorSecurityException.class, () ->
				ServerEditorBackend.renameEntrySync(sandbox, "demo_pack/data", "demo_pack/data/sub")
		);

		ServerEditorBackend.deleteEntrySync(sandbox, "demo_pack");
		assertFalse(Files.exists(datapacksRoot.resolve("demo_pack")));
	}

	@Test
	void enforcesOptimisticConcurrencyViaExpectedBaseSha256() {
		PathSandbox sandbox = new PathSandbox(datapacksRoot);
		String rel = "pack/data/demo/function/test.mcfunction";
		FileDocument v1 = ServerEditorBackend.writeDocumentSync(sandbox, rel, "say v1\n");

		// Matching revision succeeds
		FileDocument v2 = ServerEditorBackend.writeDocumentSync(sandbox, rel, "say v2\n", v1.sha256());
		assertEquals(PathSandbox.sha256Hex("say v2\n"), v2.sha256());

		// Stale revision throws RevisionConflictException with both hashes
		dev.catgirlclaude.quill.api.RevisionConflictException conflict = assertThrows(
				dev.catgirlclaude.quill.api.RevisionConflictException.class,
				() -> ServerEditorBackend.writeDocumentSync(sandbox, rel, "say stale\n", v1.sha256())
		);
		assertEquals(rel, conflict.relativePath());
		assertEquals(v1.sha256(), conflict.expectedSha256());
		assertEquals(v2.sha256(), conflict.actualSha256());
		assertEquals("say v2\n", ServerEditorBackend.readDocumentSync(sandbox, rel).content());

		// Null expected revision force-overwrites
		FileDocument v3 = ServerEditorBackend.writeDocumentSync(sandbox, rel, "say forced\n", null);
		assertEquals("say forced\n", v3.content());

		// Externally deleted file with non-empty expected revision throws conflict with empty actualSha256
		ServerEditorBackend.deleteEntrySync(sandbox, rel);
		dev.catgirlclaude.quill.api.RevisionConflictException deletedConflict = assertThrows(
				dev.catgirlclaude.quill.api.RevisionConflictException.class,
				() -> ServerEditorBackend.writeDocumentSync(sandbox, rel, "say recreate\n", v3.sha256())
		);
		assertEquals("", deletedConflict.actualSha256());
	}
}
