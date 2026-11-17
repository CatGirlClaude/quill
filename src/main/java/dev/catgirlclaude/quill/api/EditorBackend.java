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

	CompletableFuture<SaveResult> writeFile(String relativePath, String content, boolean reloadAfterSave);

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
