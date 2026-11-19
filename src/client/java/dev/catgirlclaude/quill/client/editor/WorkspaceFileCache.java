package dev.catgirlclaude.quill.client.editor;

import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.OpenTabState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Per-revision client-side cache of closed workspace file contents backed by {@code EditorBackend.readFile},
 * always overlaying live unsaved {@link OpenTabState} buffers on top.
 */
public final class WorkspaceFileCache {
	public record WorkspaceFileSnapshot(
			String relativePath,
			String content,
			String sha256,
			boolean fromOpenTab
	) {
	}

	private final Map<String, FileDocument> cachedDocuments = new ConcurrentHashMap<>();
	private volatile long cachedRevision = -1L;

	public void invalidate() {
		cachedDocuments.clear();
		cachedRevision = -1L;
	}

	public void putDocument(FileDocument document, long workspaceRevision) {
		if (document == null) {
			return;
		}
		ensureRevision(workspaceRevision);
		cachedDocuments.put(document.relativePath(), document);
	}

	private synchronized void ensureRevision(long currentRevision) {
		if (cachedRevision != currentRevision) {
			cachedDocuments.clear();
			cachedRevision = currentRevision;
		}
	}

	/**
	 * Loads a single file snapshot from open tabs, cache, or {@code EditorBackend.readFile}.
	 */
	public CompletableFuture<WorkspaceFileSnapshot> loadFile(
			ClientEditorSession session,
			String relativePath
	) {
		if (session == null || relativePath == null || relativePath.isBlank()) {
			return CompletableFuture.completedFuture(null);
		}
		long rev = session.workspaceRevision();
		ensureRevision(rev);

		OpenTabState openTab = session.findOpenTab(relativePath);
		if (openTab != null) {
			return CompletableFuture.completedFuture(
					new WorkspaceFileSnapshot(relativePath, openTab.buffer().getText(), openTab.sha256(), true)
			);
		}
		FileDocument cached = cachedDocuments.get(relativePath);
		if (cached != null) {
			return CompletableFuture.completedFuture(
					new WorkspaceFileSnapshot(relativePath, cached.content(), cached.sha256(), false)
			);
		}
		return session.backend().readFile(relativePath)
				.handle((doc, error) -> {
					if (error != null || doc == null) {
						return null;
					}
					if (cachedRevision == rev) {
						cachedDocuments.put(relativePath, doc);
					}
					return new WorkspaceFileSnapshot(relativePath, doc.content(), doc.sha256(), false);
				});
	}

	/**
	 * Loads text snapshots for all workspace files matching {@code pathFilter}, combining live open tabs
	 * with cached or freshly fetched backend documents.
	 */
	public CompletableFuture<List<WorkspaceFileSnapshot>> loadFiles(
			ClientEditorSession session,
			Predicate<String> pathFilter
	) {
		long rev = session.workspaceRevision();
		ensureRevision(rev);

		List<String> paths = new ArrayList<>();
		for (String path : session.listAllFilePaths()) {
			if (pathFilter.test(path)) {
				paths.add(path);
			}
		}

		Map<String, CompletableFuture<WorkspaceFileSnapshot>> futures = new LinkedHashMap<>();
		for (String path : paths) {
			OpenTabState openTab = session.findOpenTab(path);
			if (openTab != null) {
				futures.put(path, CompletableFuture.completedFuture(
						new WorkspaceFileSnapshot(path, openTab.buffer().getText(), openTab.sha256(), true)
				));
				continue;
			}
			FileDocument cached = cachedDocuments.get(path);
			if (cached != null) {
				futures.put(path, CompletableFuture.completedFuture(
						new WorkspaceFileSnapshot(path, cached.content(), cached.sha256(), false)
				));
				continue;
			}
			CompletableFuture<WorkspaceFileSnapshot> readFuture = session.backend().readFile(path)
					.handle((doc, error) -> {
						if (error != null || doc == null) {
							return null;
						}
						if (cachedRevision == rev) {
							cachedDocuments.put(path, doc);
						}
						return new WorkspaceFileSnapshot(path, doc.content(), doc.sha256(), false);
					});
			futures.put(path, readFuture);
		}

		CompletableFuture<?>[] all = futures.values().toArray(CompletableFuture[]::new);
		return CompletableFuture.allOf(all).thenApply(ignored -> {
			List<WorkspaceFileSnapshot> snapshots = new ArrayList<>(futures.size());
			for (CompletableFuture<WorkspaceFileSnapshot> future : futures.values()) {
				WorkspaceFileSnapshot snap = future.join();
				if (snap != null) {
					snapshots.add(snap);
				}
			}
			return snapshots;
		});
	}
}
