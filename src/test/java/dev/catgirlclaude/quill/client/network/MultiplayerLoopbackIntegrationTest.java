package dev.catgirlclaude.quill.client.network;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.RevisionConflictException;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.backend.ServerEditorBackend;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.network.ChunkedTransferAssembler;
import dev.catgirlclaude.quill.network.QuillNetworkPayloads;
import dev.catgirlclaude.quill.network.ServerNetworkEditorHandler;
import dev.catgirlclaude.quill.preset.DatapackScaffolder;
import dev.catgirlclaude.quill.preset.PresetRegistry;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import dev.catgirlclaude.quill.security.QuillAccessControl;
import dev.catgirlclaude.quill.security.ServerRateLimiter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end multiplayer loopback test wiring multiple {@link RemoteEditorBackend} and
 * {@link ClientEditorSession} instances through {@link QuillNetworkPayloads} binary codecs
 * to a live {@link ServerNetworkEditorHandler} backed by a real {@link PathSandbox}.
 */
class MultiplayerLoopbackIntegrationTest {

	@TempDir
	Path datapacksRoot;

	private static final class SandboxTestBackend implements EditorBackend {
		private final PathSandbox sandbox;
		private final PresetRegistry presetRegistry = PresetRegistry.loadDefault();

		SandboxTestBackend(PathSandbox sandbox) {
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
			ReloadReport report = reloadAfterSave ? new ReloadReport(true, 12L, 1, List.of()) : null;
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
			return CompletableFuture.completedFuture(new ReloadReport(true, 9L, 1, List.of()));
		}
	}

	private static final class SimulatedClient {
		final UUID id;
		final String name;
		final boolean isOp;
		final RemoteEditorBackend remoteBackend;
		final ClientEditorSession session;
		final List<QuillNetworkPayloads.TreeChangedPayload> receivedBroadcasts = new ArrayList<>();
		final AtomicInteger sentC2sFrames = new AtomicInteger();
		final AtomicInteger receivedS2cFrames = new AtomicInteger();

		SimulatedClient(
				UUID id,
				String name,
				boolean isOp,
				ServerNetworkEditorHandler serverHandler,
				QuillAccessControl acl,
				EditorBackend serverBackend,
				List<SimulatedClient> allClients
		) {
			this.id = id;
			this.name = name;
			this.isOp = isOp;

			ServerNetworkEditorHandler.PeerContext peer = new ServerNetworkEditorHandler.PeerContext() {
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
					return acl.isAuthorized(id, name, isOp);
				}

				@Override
				public EditorBackend backend() {
					return serverBackend;
				}

				@Override
				public void sendFrame(QuillNetworkPayloads.S2CFramePayload frame) {
					QuillNetworkPayloads.S2CFramePayload wireCopy = wireRoundTripS2C(frame);
					receivedS2cFrames.incrementAndGet();
					remoteBackend.handleServerFrame(wireCopy);
				}

				@Override
				public void broadcastTreeChanged(QuillNetworkPayloads.TreeChangedPayload notification) {
					QuillNetworkPayloads.TreeChangedPayload wireCopy = wireRoundTripTreeChanged(notification);
					for (SimulatedClient other : allClients) {
						if (other.id.equals(id)) {
							continue;
						}
						if (!acl.isAuthorized(other.id, other.name, other.isOp)) {
							continue;
						}
						other.receivedBroadcasts.add(wireCopy);
						other.remoteBackend.handleTreeChanged(wireCopy);
					}
				}
			};

			this.remoteBackend = new RemoteEditorBackend(frame -> {
				sentC2sFrames.incrementAndGet();
				QuillNetworkPayloads.C2SFramePayload wireCopy = wireRoundTripC2S(frame);
				serverHandler.handleFrame(peer, wireCopy);
			}, 5_000L);

			this.session = new ClientEditorSession(this.remoteBackend, Runnable::run, "Remote");
			this.remoteBackend.setTreeChangedListener(n ->
					this.session.onRemoteTreeChanged(n.actorName(), n.changeKind(), n.affectedPath(), n.newSha256())
			);
		}
	}

	@Test
	void endToEndMultiplayerEditingChunkedTransferConflictResolutionAndLiveSync() throws Exception {
		AtomicLong now = new AtomicLong(1_000L);
		UUID aliceId = UUID.randomUUID();
		UUID bobId = UUID.randomUUID();
		UUID charlieId = UUID.randomUUID();

		// Bob is non-OP but explicitly allowed via Quill ACL; Charlie is neither OP nor allowed
		QuillAccessControl acl = QuillAccessControl.inMemory(new QuillAccessControl.AclPolicy(
				true,
				Set.of(bobId),
				Set.of(),
				Set.of(),
				Set.of(),
				200,
				500L
		));
		ServerNetworkEditorHandler serverHandler = new ServerNetworkEditorHandler(
				acl,
				new ServerRateLimiter(now::get, 50),
				new ChunkedTransferAssembler<>()
		);
		SandboxTestBackend serverBackend = new SandboxTestBackend(new PathSandbox(datapacksRoot));
		List<SimulatedClient> clients = new ArrayList<>();

		SimulatedClient alice = new SimulatedClient(aliceId, "Alice", true, serverHandler, acl, serverBackend, clients);
		SimulatedClient bob = new SimulatedClient(bobId, "Bob", false, serverHandler, acl, serverBackend, clients);
		SimulatedClient charlie = new SimulatedClient(charlieId, "Charlie", false, serverHandler, acl, serverBackend, clients);
		clients.add(alice);
		clients.add(bob);
		clients.add(charlie);

		// 1. Unauthorized Charlie is denied and receives no broadcasts
		CompletionException charlieErr = assertThrows(CompletionException.class, () -> charlie.remoteBackend.listTree().join());
		assertInstanceOf(EditorSecurityException.class, charlieErr.getCause());

		// 2. Alice scaffolds datapack, namespace, and minecraft:load preset over the wire
		alice.session.createDatapack("shared_pack", "Multiplayer test").join();
		alice.session.createNamespace("shared_pack", "arena").join();
		alice.session.applyPreset("shared_pack", "minecraft:load", Map.of("namespace", "arena")).join();

		String loadPath = "shared_pack/data/arena/function/load.mcfunction";
		assertFalse(bob.receivedBroadcasts.isEmpty(), "Authorized Bob should receive broadcasts");
		assertTrue(charlie.receivedBroadcasts.isEmpty(), "Unauthorized Charlie must not receive broadcasts");

		// 3. Both Alice and Bob open load.mcfunction
		alice.session.openFile(loadPath);
		bob.session.openFile(loadPath);
		assertEquals(alice.session.activeTab().sha256(), bob.session.activeTab().sha256());

		// 4. Alice writes a large 150 KB chunked file (7+ C2S chunks); Bob's clean tab auto-syncs!
		String hugeContent = "# big function\nsay chunked multiplayer sync!\n".repeat(3_500);
		int beforeC2s = alice.sentC2sFrames.get();
		int beforeBobS2c = bob.receivedS2cFrames.get();

		alice.session.activeTab().buffer().resetFromDisk(hugeContent);
		alice.session.saveActiveTab(true);
		assertTrue(alice.sentC2sFrames.get() - beforeC2s >= 6, "Expected multi-chunk C2S upload");
		assertFalse(alice.session.activeTab().isDirty());
		assertTrue(bob.receivedS2cFrames.get() - beforeBobS2c >= 6, "Expected multi-chunk S2C sync download on Bob");
		assertEquals(hugeContent, bob.session.activeTab().buffer().getText(), "Clean open tab on Bob should auto-sync");
		assertFalse(bob.session.activeTab().isDirty());

		// 5. Optimistic concurrency: both edit concurrently; Alice saves first, Bob hits RevisionConflict!
		now.addAndGet(1_000L);
		bob.session.activeTab().buffer().insertText("# bob local edit\n");
		assertTrue(bob.session.activeTab().isDirty());

		alice.session.activeTab().buffer().resetFromDisk("say alice won the race\n");
		alice.session.saveActiveTab(false);

		// Bob's dirty tab must NOT be overwritten by broadcast
		assertTrue(bob.session.activeTab().isDirty());
		assertTrue(bob.session.activeTab().buffer().getText().startsWith("# bob local edit\n"));
		assertTrue(bob.session.statusIsError(), "Bob should see remote edit warning for dirty tab");

		// Bob attempts normal save -> server rejects with RevisionConflictException -> pendingConflict populated
		bob.session.saveActiveTab(false);
		ClientEditorSession.PendingConflict conflict = bob.session.consumePendingConflict();
		assertNotNull(conflict, "Expected PendingConflict on stale sha256 save");
		assertEquals(loadPath, conflict.relativePath());
		assertEquals(alice.session.activeTab().sha256(), conflict.actualSha256());

		// Direct RPC check confirms RevisionConflictException type on RemoteEditorBackend
		CompletionException directConflict = assertThrows(CompletionException.class, () ->
				bob.remoteBackend.writeFile(loadPath, "say stale\n", conflict.expectedSha256(), false).join()
		);
		assertInstanceOf(RevisionConflictException.class, directConflict.getCause());

		// Bob chooses force overwrite -> succeeds and updates server + Alice's clean tab!
		bob.session.forceSaveActiveTab(false);
		assertFalse(bob.session.activeTab().isDirty());
		assertTrue(alice.session.activeTab().buffer().getText().startsWith("# bob local edit\n"));

		// 6. Rename & Delete propagation
		alice.session.createEntry("shared_pack/data/arena/function/temp.mcfunction", EntryKind.FILE).join();
		bob.session.openFile("shared_pack/data/arena/function/temp.mcfunction");
		assertEquals(2, bob.session.openTabs().size());
		alice.session.deleteEntry("shared_pack/data/arena/function/temp.mcfunction").join();
		assertEquals(1, bob.session.openTabs().size(), "Deleted clean tab on Bob should close automatically");
	}

	@Test
	void remoteBackendHandlesTimeoutsAndDisconnectCleanupCleanly() {
		// Drop all frames (blackhole server) to verify timeout and disconnect behavior
		RemoteEditorBackend timeoutBackend = new RemoteEditorBackend(frame -> {
		}, 120L);

		CompletableFuture<FileTreeNode> timedOut = timeoutBackend.listTree();
		CompletionException err = assertThrows(CompletionException.class, () -> timedOut.orTimeout(2, TimeUnit.SECONDS).join());
		assertInstanceOf(TimeoutException.class, err.getCause());
		assertEquals(0, timeoutBackend.pendingRequestCount());

		RemoteEditorBackend closingBackend = new RemoteEditorBackend(frame -> {
		}, 10_000L);
		CompletableFuture<FileTreeNode> inFlight = closingBackend.listTree();
		assertEquals(1, closingBackend.pendingRequestCount());

		closingBackend.close("Server shut down.");
		CompletionException closedErr = assertThrows(CompletionException.class, inFlight::join);
		assertInstanceOf(IllegalStateException.class, closedErr.getCause());
		assertEquals(0, closingBackend.pendingRequestCount());

		// New requests after close fail immediately
		CompletionException afterClose = assertThrows(CompletionException.class, () -> closingBackend.reloadDatapacks().join());
		assertInstanceOf(IllegalStateException.class, afterClose.getCause());
	}

	private static QuillNetworkPayloads.C2SFramePayload wireRoundTripC2S(QuillNetworkPayloads.C2SFramePayload frame) {
		ByteBuf raw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(raw);
			QuillNetworkPayloads.C2SFramePayload.STREAM_CODEC.encode(buf, frame);
			return QuillNetworkPayloads.C2SFramePayload.STREAM_CODEC.decode(buf);
		} finally {
			raw.release();
		}
	}

	private static QuillNetworkPayloads.S2CFramePayload wireRoundTripS2C(QuillNetworkPayloads.S2CFramePayload frame) {
		ByteBuf raw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(raw);
			QuillNetworkPayloads.S2CFramePayload.STREAM_CODEC.encode(buf, frame);
			return QuillNetworkPayloads.S2CFramePayload.STREAM_CODEC.decode(buf);
		} finally {
			raw.release();
		}
	}

	private static QuillNetworkPayloads.TreeChangedPayload wireRoundTripTreeChanged(QuillNetworkPayloads.TreeChangedPayload payload) {
		ByteBuf raw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(raw);
			QuillNetworkPayloads.TreeChangedPayload.STREAM_CODEC.encode(buf, payload);
			return QuillNetworkPayloads.TreeChangedPayload.STREAM_CODEC.decode(buf);
		} finally {
			raw.release();
		}
	}
}
