package dev.catgirlclaude.quill.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous backend contract mediating all IDE file, scaffolding, preset, and reload actions.
 * UI code in {@code src/client} must always interact with datapacks through this interface.
 */
public interface EditorBackend {
	CompletableFuture<FileTreeNode> listTree();

	CompletableFuture<FileDocument> readFile(String relativePath);

	default CompletableFuture<SaveResult> writeFile(String relativePath, String content, boolean reloadAfterSave) {
		return writeFile(relativePath, content, null, reloadAfterSave);
	}

	/**
	 * Writes UTF-8 content to {@code relativePath}. When {@code expectedBaseSha256} is non-null
	 * and non-empty, the backend enforces optimistic concurrency and fails with
	 * {@link RevisionConflictException} if the current server revision differs.
	 */
	CompletableFuture<SaveResult> writeFile(
			String relativePath,
			String content,
			String expectedBaseSha256,
			boolean reloadAfterSave
	);

	CompletableFuture<Void> createEntry(String relativePath, EntryKind kind);

	CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath);

	CompletableFuture<Void> deleteEntry(String relativePath);

	CompletableFuture<String> createDatapack(String packName, String description);

	CompletableFuture<String> createNamespace(String packName, String namespace);

	CompletableFuture<String> createFunction(String packName, String namespace, String functionPath);

	CompletableFuture<List<PresetDescriptor>> listPresets();

	CompletableFuture<FileDocument> applyPreset(String packName, String presetId, Map<String, String> variables);

	CompletableFuture<ReloadReport> reloadDatapacks();
}
