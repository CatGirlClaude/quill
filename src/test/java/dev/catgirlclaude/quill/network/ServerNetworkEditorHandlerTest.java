package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.backend.ServerEditorBackend;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import dev.catgirlclaude.quill.preset.PresetRegistry;
import dev.catgirlclaude.quill.security.EditorLimits;
import dev.catgirlclaude.quill.security.PathSandbox;
import dev.catgirlclaude.quill.security.QuillAccessControl;
import dev.catgirlclaude.quill.security.ServerRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerNetworkEditorHandlerTest {

	@TempDir
	Path datapacksRoot;

	private static final class LocalSandboxBackend implements EditorBackend {
		private final PathSandbox sandbox;
		private final PresetRegistry presetRegistry = PresetRegistry.loadDefault();

		LocalSandboxBackend(PathSandbox sandbox) {
			this.sandbox = sandbox;
		}

		@Override
		public CompletableFuture<FileTreeNode> listTree() {
			return CompletableFuture.completedFuture(ServerEditorBackend.scanTree(sandbox));
		}

		@Override
		public CompletableFuture<FileDocument> readFile(String relativePath) {
			return CompletableFuture.completedFuture(ServerEditorBackend.readDocumentSync(sandbox, relativePath));
		}

		@Override
		public CompletableFuture<SaveResult> writeFile(String relativePath, String content, String expectedBaseSha256, boolean reloadAfterSave) {
			FileDocument doc = ServerEditorBackend.writeDocumentSync(sandbox, relativePath, content, expectedBaseSha256);
			ReloadReport report = reloadAfterSave ? new ReloadReport(true, 5L, 1, List.of()) : null;
			return CompletableFuture.completedFuture(new SaveResult(doc, report));
		}

		@Override
		public CompletableFuture<Void> createEntry(String relativePath, EntryKind kind) {
			ServerEditorBackend.createEntrySync(sandbox, relativePath, kind);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath) {
			ServerEditorBackend.renameEntrySync(sandbox, sourceRelativePath, targetRelativePath);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<Void> deleteEntry(String relativePath) {
			ServerEditorBackend.deleteEntrySync(sandbox, relativePath);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<String> createDatapack(String packName, String description) {
			return CompletableFuture.completedFuture(DatapackScaffolder.createDatapack(sandbox, packName, description));
		}

		@Override
		public CompletableFuture<String> createNamespace(String packName, String namespace) {
			return CompletableFuture.completedFuture(DatapackScaffolder.createNamespace(sandbox, packName, namespace));
		}

		@Override
		public CompletableFuture<String> createFunction(String packName, String namespace, String functionPath) {
			return CompletableFuture.completedFuture(DatapackScaffolder.createFunction(sandbox, packName, namespace, functionPath));
		}

		@Override
		public CompletableFuture<List<PresetDescriptor>> listPresets() {
			return CompletableFuture.completedFuture(presetRegistry.listPresets());
		}

		@Override
		public CompletableFuture<FileDocument> applyPreset(String packName, String presetId, Map<String, String> variables) {
			return CompletableFuture.completedFuture(presetRegistry.applyPreset(sandbox, packName, presetId, variables));
		}

		@Override
		public CompletableFuture<ReloadReport> reloadDatapacks() {
			return CompletableFuture.completedFuture(new ReloadReport(true, 8L, 1, List.of()));
		}
	}

	private static final class RecordingPeer implements ServerNetworkEditorHandler.PeerContext {
		final UUID id;
		final String name;
		final boolean authorized;
		final EditorBackend backend;
		final List<QuillNetworkPayloads.S2CFramePayload> frames = new ArrayList<>();
		final List<QuillNetworkPayloads.TreeChangedPayload> broadcasts = new ArrayList<>();

		RecordingPeer(UUID id, String name, boolean authorized, EditorBackend backend) {
			this.id = id;
			this.name = name;
			this.authorized = authorized;
			this.backend = backend;
		}

		@Override
		public UUID playerId() {
			return id;
		}

		@Override
		public String playerName() {
			return name;
		}

		@Override
		public boolean isAuthorized() {
			return authorized;
		}

		@Override
		public EditorBackend backend() {
			return backend;
		}

		@Override
		public void sendFrame(QuillNetworkPayloads.S2CFramePayload frame) {
			frames.add(frame);
		}

		@Override
		public void broadcastTreeChanged(QuillNetworkPayloads.TreeChangedPayload notification) {
			broadcasts.add(notification);
		}

		QuillRpcResponse decodeSingleResponse(long requestId) {
			ChunkedTransferAssembler<String> clientAssembler = new ChunkedTransferAssembler<>();
			byte[] assembled = null;
			for (QuillNetworkPayloads.S2CFramePayload frame : frames) {
				if (frame.requestId() != requestId) {
					continue;
				}
				var maybe = clientAssembler.accept("server", frame.requestId(), frame.chunkIndex(), frame.totalChunks(), frame.data());
				if (maybe.isPresent()) {
					assembled = maybe.get();
				}
			}
			assertTrue(assembled != null, "Expected assembled response for requestId=" + requestId);
			return QuillProtocolCodec.decodeResponse(assembled);
		}
	}

	private static void sendRequest(ServerNetworkEditorHandler handler, RecordingPeer peer, long requestId, QuillRpcRequest request) {
		byte[] encoded = QuillProtocolCodec.encodeRequest(request);
		List<byte[]> chunks = ChunkedTransferAssembler.split(encoded, EditorLimits.MAX_CHUNK_BYTES, EditorLimits.MAX_MESSAGE_BYTES);
		for (int i = 0; i < chunks.size(); i++) {
			handler.handleFrame(peer, new QuillNetworkPayloads.C2SFramePayload(requestId, i, chunks.size(), chunks.get(i)));
		}
	}

	@Test
	void rejectsUnauthorizedPlayersAndPathTraversalAttempts() {
		PathSandbox sandbox = new PathSandbox(datapacksRoot);
		LocalSandboxBackend backend = new LocalSandboxBackend(sandbox);
		QuillAccessControl acl = QuillAccessControl.inMemory(QuillAccessControl.AclPolicy.defaults());
		ServerNetworkEditorHandler handler = new ServerNetworkEditorHandler(
				acl,
				new ServerRateLimiter(),
				new ChunkedTransferAssembler<>()
		);

		RecordingPeer unauthorized = new RecordingPeer(UUID.randomUUID(), "Guest", false, backend);
		sendRequest(handler, unauthorized, 1L, new QuillRpcRequest.ListTree());
		QuillRpcResponse.ErrorResult denied = assertInstanceOf(
				QuillRpcResponse.ErrorResult.class,
				unauthorized.decodeSingleResponse(1L)
		);
		assertEquals(QuillRpcResponse.ErrorCode.PERMISSION_DENIED, denied.code());

		RecordingPeer op = new RecordingPeer(UUID.randomUUID(), "Admin", true, backend);
		sendRequest(handler, op, 2L, new QuillRpcRequest.ReadFile("../secret.mcfunction"));
		QuillRpcResponse.ErrorResult traversal = assertInstanceOf(
				QuillRpcResponse.ErrorResult.class,
				op.decodeSingleResponse(2L)
		);
		assertEquals(QuillRpcResponse.ErrorCode.SECURITY_VIOLATION, traversal.code());
	}

	@Test
	void enforcesRateLimitsReloadCooldownChunkedFilesOptimisticConcurrencyAndBroadcasts() {
		AtomicLong now = new AtomicLong(10_000L);
		PathSandbox sandbox = new PathSandbox(datapacksRoot);
		LocalSandboxBackend backend = new LocalSandboxBackend(sandbox);
		QuillAccessControl acl = QuillAccessControl.inMemory(new QuillAccessControl.AclPolicy(
				true, Set.of(), Set.of(), Set.of(), Set.of(), 60, 500L
		));
		ServerNetworkEditorHandler handler = new ServerNetworkEditorHandler(
				acl,
				new ServerRateLimiter(now::get, 5),
				new ChunkedTransferAssembler<>()
		);

		RecordingPeer alice = new RecordingPeer(UUID.randomUUID(), "Alice", true, backend);
		// 1. Create datapack -> succeeds & emits broadcast
		sendRequest(handler, alice, 1L, new QuillRpcRequest.CreateDatapack("mp_pack", "Multiplayer pack"));
		QuillRpcResponse.StringResult created = assertInstanceOf(QuillRpcResponse.StringResult.class, alice.decodeSingleResponse(1L));
		assertEquals("mp_pack", created.value());
		assertEquals(1, alice.broadcasts.size());
		assertEquals("CREATE_DATAPACK", alice.broadcasts.getFirst().changeKind());

		// 2. Chunked large file upload (~80 KB, 4 chunks) with reload
		String largeContent = "# line\nsay hello from chunked upload!\n".repeat(2_000);
		String relPath = "mp_pack/data/demo/function/large.mcfunction";
		sendRequest(handler, alice, 2L, new QuillRpcRequest.WriteFile(relPath, largeContent, "", true));
		QuillRpcResponse.SaveOpResult saveRes = assertInstanceOf(QuillRpcResponse.SaveOpResult.class, alice.decodeSingleResponse(2L));
		assertEquals(largeContent, saveRes.result().document().content());
		assertEquals(PathSandbox.sha256Hex(largeContent), saveRes.result().document().sha256());

		// 3. Immediate second reload within 500ms cooldown is rate-limited
		now.addAndGet(100L);
		sendRequest(handler, alice, 3L, new QuillRpcRequest.ReloadDatapacks());
		QuillRpcResponse.ErrorResult cooldownErr = assertInstanceOf(QuillRpcResponse.ErrorResult.class, alice.decodeSingleResponse(3L));
		assertEquals(QuillRpcResponse.ErrorCode.RATE_LIMITED, cooldownErr.code());

		// 4. Stale sha256 save returns REVISION_CONFLICT with server's actual sha256
		sendRequest(handler, alice, 4L, new QuillRpcRequest.WriteFile(relPath, "say stale\n", "00000000", false));
		QuillRpcResponse.ErrorResult conflictErr = assertInstanceOf(QuillRpcResponse.ErrorResult.class, alice.decodeSingleResponse(4L));
		assertEquals(QuillRpcResponse.ErrorCode.REVISION_CONFLICT, conflictErr.code());
		assertEquals(relPath, conflictErr.conflictPath());
		assertEquals("00000000", conflictErr.expectedSha256());
		assertEquals(saveRes.result().document().sha256(), conflictErr.actualSha256());

		// 5. Large chunked download returns exact content across multiple S2C chunks
		sendRequest(handler, alice, 5L, new QuillRpcRequest.ReadFile(relPath));
		long s2cChunksForReq5 = alice.frames.stream().filter(f -> f.requestId() == 5L).count();
		assertTrue(s2cChunksForReq5 >= 3, "Expected multi-chunk S2C download, got " + s2cChunksForReq5);
		QuillRpcResponse.DocumentResult readRes = assertInstanceOf(QuillRpcResponse.DocumentResult.class, alice.decodeSingleResponse(5L));
		assertEquals(largeContent, readRes.document().content());

		// 6. Burst of 5 exhausted -> 6th immediate request is rate-limited
		sendRequest(handler, alice, 6L, new QuillRpcRequest.ListTree());
		QuillRpcResponse.ErrorResult rateErr = assertInstanceOf(QuillRpcResponse.ErrorResult.class, alice.decodeSingleResponse(6L));
		assertEquals(QuillRpcResponse.ErrorCode.RATE_LIMITED, rateErr.code());
	}
}
