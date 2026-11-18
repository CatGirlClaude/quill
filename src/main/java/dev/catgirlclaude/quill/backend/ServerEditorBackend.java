package dev.catgirlclaude.quill.backend;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.RevisionConflictException;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import dev.catgirlclaude.quill.preset.PresetRegistry;
import dev.catgirlclaude.quill.reload.DatapackReloadCoordinator;
import dev.catgirlclaude.quill.security.EditorLimits;
import dev.catgirlclaude.quill.security.EditorPermissionChecker;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import dev.catgirlclaude.quill.security.QuillAccessControl;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link EditorBackend} implementation backed by a {@link MinecraftServer}'s {@code datapacks/} directory.
 */
public class ServerEditorBackend implements EditorBackend {
	private static final Executor IO_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
	private static final ConcurrentHashMap<Path, Object> WRITE_LOCKS = new ConcurrentHashMap<>();

	private final MinecraftServer server;
	private final ServerPlayer actingPlayer;
	private final QuillAccessControl accessControl;
	private final PathSandbox sandbox;
	private final PresetRegistry presetRegistry;

	public ServerEditorBackend(MinecraftServer server) {
		this(server, null, null);
	}

	public ServerEditorBackend(MinecraftServer server, ServerPlayer actingPlayer) {
		this(server, actingPlayer, null);
	}

	public ServerEditorBackend(MinecraftServer server, ServerPlayer actingPlayer, QuillAccessControl accessControl) {
		this.server = Objects.requireNonNull(server, "server");
		this.actingPlayer = actingPlayer;
		this.accessControl = accessControl;
		Path datapacksRoot = server.getWorldPath(LevelResource.DATAPACK_DIR);
		this.sandbox = new PathSandbox(datapacksRoot);
		this.presetRegistry = PresetRegistry.loadDefault();
	}

	protected PathSandbox sandbox() {
		return sandbox;
	}

	protected MinecraftServer server() {
		return server;
	}

	@Override
	public CompletableFuture<FileTreeNode> listTree() {
		return CompletableFuture.supplyAsync(() -> {
			requireAuthorized();
			return scanTree(sandbox);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<FileDocument> readFile(String relativePath) {
		return CompletableFuture.supplyAsync(() -> {
			requireAuthorized();
			return readDocumentSync(sandbox, relativePath);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<SaveResult> writeFile(
			String relativePath,
			String content,
			String expectedBaseSha256,
			boolean reloadAfterSave
	) {
		return CompletableFuture.supplyAsync(() -> {
			requireAuthorized();
			return writeDocumentSync(sandbox, relativePath, content, expectedBaseSha256);
		}, IO_EXECUTOR).thenCompose(document -> {
			if (!reloadAfterSave) {
				return CompletableFuture.completedFuture(new SaveResult(document, null));
			}
			return reloadDatapacks().thenApply(report -> new SaveResult(document, report));
		});
	}

	@Override
	public CompletableFuture<Void> createEntry(String relativePath, EntryKind kind) {
		return CompletableFuture.runAsync(() -> {
			requireAuthorized();
			createEntrySync(sandbox, relativePath, kind);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath) {
		return CompletableFuture.runAsync(() -> {
			requireAuthorized();
			renameEntrySync(sandbox, sourceRelativePath, targetRelativePath);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<Void> deleteEntry(String relativePath) {
		return CompletableFuture.runAsync(() -> {
			requireAuthorized();
			deleteEntrySync(sandbox, relativePath);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<String> createDatapack(String packName, String description) {
		return CompletableFuture.supplyAsync(() -> {
			requireAuthorized();
			return DatapackScaffolder.createDatapack(sandbox, packName, description);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<String> createNamespace(String packName, String namespace) {
		return CompletableFuture.supplyAsync(() -> {
			requireAuthorized();
			return DatapackScaffolder.createNamespace(sandbox, packName, namespace);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<String> createFunction(String packName, String namespace, String functionPath) {
		return CompletableFuture.supplyAsync(() -> {
			requireAuthorized();
			return DatapackScaffolder.createFunction(sandbox, packName, namespace, functionPath);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<List<PresetDescriptor>> listPresets() {
		return CompletableFuture.supplyAsync(() -> {
			requireAuthorized();
			return presetRegistry.listPresets();
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<FileDocument> applyPreset(String packName, String presetId, Map<String, String> variables) {
		return CompletableFuture.supplyAsync(() -> {
			requireAuthorized();
			return presetRegistry.applyPreset(sandbox, packName, presetId, variables);
		}, IO_EXECUTOR);
	}

	@Override
	public CompletableFuture<ReloadReport> reloadDatapacks() {
		try {
			requireAuthorized();
		} catch (Throwable t) {
			return CompletableFuture.failedFuture(t);
		}
		return DatapackReloadCoordinator.reload(server, sandbox);
	}

	protected void requireAuthorized() {
		if (accessControl != null) {
			EditorPermissionChecker.requireCanEdit(server, actingPlayer, accessControl);
		} else {
			EditorPermissionChecker.requireCanEdit(server, actingPlayer);
		}
	}

	public static FileTreeNode scanTree(PathSandbox sandbox) {
		Path root = sandbox.root();
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of());
		}
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new EditorSecurityException("Datapacks root is not a regular directory.");
		}
		AtomicInteger entryCount = new AtomicInteger(0);
		List<FileTreeNode> children = scanChildren(sandbox, root, 1, entryCount, true);
		return new FileTreeNode("datapacks", "", EntryKind.ROOT, children);
	}

	private static List<FileTreeNode> scanChildren(
			PathSandbox sandbox,
			Path dir,
			int depth,
			AtomicInteger entryCount,
			boolean directChildOfRoot
	) {
		if (depth > EditorLimits.MAX_TREE_DEPTH) {
			return List.of();
		}

		List<Path> rawChildren;
		try (var stream = Files.list(dir)) {
			rawChildren = stream.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to list directory: " + dir, e);
		}

		List<FileTreeNode> nodes = new ArrayList<>();
		for (Path child : rawChildren) {
			if (entryCount.get() >= EditorLimits.MAX_TREE_ENTRIES) {
				break;
			}
			if (Files.isSymbolicLink(child) || PathSandbox.isTempFile(child)) {
				continue;
			}
			if (directChildOfRoot && PathSandbox.isTempOrHiddenArchive(child)) {
				continue;
			}
			boolean isDir = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
			String name = child.getFileName().toString();
			String relativePath = sandbox.relativize(child);

			if (isDir) {
				entryCount.incrementAndGet();
				EntryKind kind = directChildOfRoot ? EntryKind.DATAPACK : EntryKind.DIRECTORY;
				List<FileTreeNode> subChildren = scanChildren(sandbox, child, depth + 1, entryCount, false);
				nodes.add(new FileTreeNode(name, relativePath, kind, subChildren));
			} else if (!directChildOfRoot && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
				entryCount.incrementAndGet();
				nodes.add(new FileTreeNode(name, relativePath, EntryKind.FILE, List.of()));
			}
		}

		nodes.sort(Comparator
				.comparing((FileTreeNode n) -> n.kind() == EntryKind.FILE ? 1 : 0)
				.thenComparing(n -> n.name().toLowerCase()));
		return nodes;
	}

	public static FileDocument readDocumentSync(PathSandbox sandbox, String relativePath) {
		Path target = sandbox.resolveExistingFile(relativePath);
		String normalized = sandbox.relativize(target);
		try {
			byte[] bytes = Files.readAllBytes(target);
			String content = new String(bytes, StandardCharsets.UTF_8);
			long lastModified = Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toMillis();
			return new FileDocument(normalized, content, PathSandbox.sha256Hex(bytes), lastModified);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read file: " + normalized, e);
		}
	}

	public static FileDocument writeDocumentSync(PathSandbox sandbox, String relativePath, String content) {
		return writeDocumentSync(sandbox, relativePath, content, null);
	}

	public static FileDocument writeDocumentSync(
			PathSandbox sandbox,
			String relativePath,
			String content,
			String expectedBaseSha256
	) {
		Object lock = WRITE_LOCKS.computeIfAbsent(sandbox.root(), ignored -> new Object());
		synchronized (lock) {
			Path target = sandbox.resolveWritableFile(relativePath);
			String normalized = sandbox.relativize(target);
			try {
				if (expectedBaseSha256 != null && !expectedBaseSha256.isBlank()) {
					if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
						throw new RevisionConflictException(normalized, expectedBaseSha256, "");
					}
					byte[] existingBytes = Files.readAllBytes(target);
					String actualSha256 = PathSandbox.sha256Hex(existingBytes);
					if (!actualSha256.equals(expectedBaseSha256)) {
						throw new RevisionConflictException(normalized, expectedBaseSha256, actualSha256);
					}
				}
				sandbox.atomicWriteUtf8(target, content);
				byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
				long lastModified = Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toMillis();
				return new FileDocument(normalized, content, PathSandbox.sha256Hex(bytes), lastModified);
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to write file: " + normalized, e);
			}
		}
	}

	public static void createEntrySync(PathSandbox sandbox, String relativePath, EntryKind kind) {
		Path target = sandbox.resolveEntryForCreate(relativePath, kind);
		try {
			if (kind == EntryKind.FILE) {
				sandbox.atomicWriteUtf8(target, "");
			} else {
				sandbox.ensureDirectoryTree(target);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create entry: " + relativePath, e);
		}
	}

	public static void renameEntrySync(PathSandbox sandbox, String sourceRelativePath, String targetRelativePath) {
		Path source = sandbox.resolveExistingEntryForModify(sourceRelativePath);
		boolean isDir = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
		Path target = sandbox.resolveTargetForRename(sourceRelativePath, targetRelativePath, isDir);
		try {
			if (target.getParent() != null) {
				sandbox.ensureDirectoryTree(target.getParent());
			}
			Files.move(source, target);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to rename entry from " + sourceRelativePath + " to " + targetRelativePath, e);
		}
	}

	public static void deleteEntrySync(PathSandbox sandbox, String relativePath) {
		Path target = sandbox.resolveExistingEntryForModify(relativePath);
		try {
			sandbox.deleteRecursively(target);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to delete entry: " + relativePath, e);
		}
	}
}
