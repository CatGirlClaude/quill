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
		assertEquals(2, packNode.children().size());
		assertEquals("data", packNode.children().get(0).name());
		assertEquals("pack.mcmeta", packNode.children().get(1).name());
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
}
