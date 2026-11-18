package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.Quill;
import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.RevisionConflictException;
import dev.catgirlclaude.quill.backend.ServerEditorBackend;
import dev.catgirlclaude.quill.security.EditorLimits;
import dev.catgirlclaude.quill.security.EditorPermissionChecker;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.QuillAccessControl;
import dev.catgirlclaude.quill.security.ServerRateLimiter;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side multiplayer RPC handler mediating chunked {@link QuillNetworkPayloads.C2SFramePayload}
 * requests with ACL authorization, per-player rate limiting, reload cooldowns, sandboxed execution,
 * and live {@link QuillNetworkPayloads.TreeChangedPayload} broadcasts to other authorized editors.
 */
public final class ServerNetworkEditorHandler {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
	private static ServerNetworkEditorHandler defaultInstance;

	/**
	 * Minimal peer abstraction enabling both live Fabric server networking and headless JUnit loopback tests.
	 */
	public interface PeerContext {
		UUID playerId();

		String playerName();

		boolean isAuthorized();

		EditorBackend backend();

		void sendFrame(QuillNetworkPayloads.S2CFramePayload frame);

		void broadcastTreeChanged(QuillNetworkPayloads.TreeChangedPayload notification);
	}

	private record ExecutionOutcome(
			QuillRpcResponse response,
			QuillNetworkPayloads.TreeChangedPayload broadcast
	) {
	}

	private final QuillAccessControl accessControl;
	private final ServerRateLimiter rateLimiter;
	private final ChunkedTransferAssembler<UUID> assembler;

	public ServerNetworkEditorHandler(
			QuillAccessControl accessControl,
			ServerRateLimiter rateLimiter,
			ChunkedTransferAssembler<UUID> assembler
	) {
		this.accessControl = Objects.requireNonNull(accessControl, "accessControl");
		this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
		this.assembler = Objects.requireNonNull(assembler, "assembler");
	}

	/**
	 * Registers payload types, global C2S receivers, and server/player lifecycle cleanup hooks.
	 */
	public static void register() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}
		QuillNetworkPayloads.registerPayloads();

		QuillAccessControl acl = EditorPermissionChecker.defaultAccessControl();
		ServerNetworkEditorHandler handler = new ServerNetworkEditorHandler(
				acl,
				new ServerRateLimiter(),
				new ChunkedTransferAssembler<>()
		);
		defaultInstance = handler;

		ServerPlayNetworking.registerGlobalReceiver(
				QuillNetworkPayloads.C2SFramePayload.TYPE,
				(payload, context) -> handler.handleFrame(new FabricPeerContext(context, acl), payload)
		);

		ServerPlayConnectionEvents.DISCONNECT.register((packetListener, server) ->
				handler.onPlayerDisconnect(packetListener.getPlayer().getUUID())
		);

		ServerLifecycleEvents.SERVER_STARTING.register(server -> acl.ensureDefaultFileExists());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> handler.resetState());
	}

	public static ServerNetworkEditorHandler defaultInstance() {
		return defaultInstance;
	}

	public void handleFrame(PeerContext peer, QuillNetworkPayloads.C2SFramePayload frame) {
		Objects.requireNonNull(peer, "peer");
		Objects.requireNonNull(frame, "frame");

		UUID playerId = peer.playerId();
		boolean alreadyInFlight = assembler.hasInFlight(playerId, frame.requestId());

		if (!alreadyInFlight) {
			try {
				rateLimiter.checkRequest(playerId, accessControl.rateLimitRequestsPerMinute());
			} catch (ServerRateLimiter.RateLimitException e) {
				if (frame.chunkIndex() == 0) {
					sendResponse(peer, frame.requestId(),
							QuillRpcResponse.ErrorResult.simple(QuillRpcResponse.ErrorCode.RATE_LIMITED, e.getMessage()));
				}
				return;
			}
		}

		if (!peer.isAuthorized()) {
			assembler.clearOwner(playerId);
			if (frame.chunkIndex() == 0) {
				sendResponse(peer, frame.requestId(), QuillRpcResponse.ErrorResult.simple(
						QuillRpcResponse.ErrorCode.PERMISSION_DENIED,
						"You do not have permission to edit or reload server datapacks."
				));
			}
			return;
		}

		Optional<byte[]> assembled;
		try {
			assembled = assembler.accept(
					playerId,
					frame.requestId(),
					frame.chunkIndex(),
					frame.totalChunks(),
					frame.data()
			);
		} catch (EditorSecurityException e) {
			Quill.LOGGER.warn("Rejected malformed/oversized chunked frame from {} ({}): {}",
					peer.playerName(), playerId, e.getMessage());
			sendResponse(peer, frame.requestId(),
					QuillRpcResponse.ErrorResult.simple(QuillRpcResponse.ErrorCode.SECURITY_VIOLATION, e.getMessage()));
			return;
		}

		if (assembled.isEmpty()) {
			return;
		}

		QuillRpcRequest request;
		try {
			request = QuillProtocolCodec.decodeRequest(assembled.get());
		} catch (EditorSecurityException e) {
			Quill.LOGGER.warn("Rejected malformed Quill RPC request from {} ({}): {}",
					peer.playerName(), playerId, e.getMessage());
			sendResponse(peer, frame.requestId(),
					QuillRpcResponse.ErrorResult.simple(QuillRpcResponse.ErrorCode.SECURITY_VIOLATION, e.getMessage()));
			return;
		}

		if (request.requiresReloadCooldown()) {
			try {
				rateLimiter.checkReload(playerId, accessControl.reloadCooldownMs());
			} catch (ServerRateLimiter.RateLimitException e) {
				sendResponse(peer, frame.requestId(),
						QuillRpcResponse.ErrorResult.simple(QuillRpcResponse.ErrorCode.RATE_LIMITED, e.getMessage()));
				return;
			}
		}

		executeRequest(peer, request).whenComplete((outcome, error) -> {
			if (error != null) {
				QuillRpcResponse.ErrorResult errorResult = mapException(peer, error);
				sendResponse(peer, frame.requestId(), errorResult);
				return;
			}
			sendResponse(peer, frame.requestId(), outcome.response());
			if (outcome.broadcast() != null) {
				peer.broadcastTreeChanged(outcome.broadcast());
			}
		});
	}

	public void onPlayerDisconnect(UUID playerId) {
		assembler.clearOwner(playerId);
		rateLimiter.removePlayer(playerId);
	}

	public void resetState() {
		assembler.clearAll();
		rateLimiter.clear();
	}

	private CompletableFuture<ExecutionOutcome> executeRequest(PeerContext peer, QuillRpcRequest request) {
		EditorBackend backend = peer.backend();
		String actor = peer.playerName();
		try {
			return switch (request) {
				case QuillRpcRequest.ListTree ignored -> backend.listTree()
						.thenApply(tree -> new ExecutionOutcome(new QuillRpcResponse.TreeResult(tree), null));

				case QuillRpcRequest.ReadFile r -> backend.readFile(r.relativePath())
						.thenApply(doc -> new ExecutionOutcome(new QuillRpcResponse.DocumentResult(doc), null));

				case QuillRpcRequest.WriteFile w -> {
					String expected = w.expectedBaseSha256().isBlank() ? null : w.expectedBaseSha256();
					yield backend.writeFile(w.relativePath(), w.content(), expected, w.reloadAfterSave())
							.thenApply(result -> new ExecutionOutcome(
									new QuillRpcResponse.SaveOpResult(result),
									new QuillNetworkPayloads.TreeChangedPayload(
											actor,
											"WRITE",
											result.document().relativePath(),
											result.document().sha256()
									)
							));
				}

				case QuillRpcRequest.CreateEntry c -> backend.createEntry(c.relativePath(), c.kind())
						.thenApply(ignored -> new ExecutionOutcome(
								new QuillRpcResponse.VoidResult(),
								new QuillNetworkPayloads.TreeChangedPayload(actor, "CREATE", c.relativePath(), "")
						));

				case QuillRpcRequest.RenameEntry r -> backend.renameEntry(r.sourceRelativePath(), r.targetRelativePath())
						.thenApply(ignored -> new ExecutionOutcome(
								new QuillRpcResponse.VoidResult(),
								new QuillNetworkPayloads.TreeChangedPayload(actor, "RENAME", r.sourceRelativePath(), "")
						));

				case QuillRpcRequest.DeleteEntry d -> backend.deleteEntry(d.relativePath())
						.thenApply(ignored -> new ExecutionOutcome(
								new QuillRpcResponse.VoidResult(),
								new QuillNetworkPayloads.TreeChangedPayload(actor, "DELETE", d.relativePath(), "")
						));

				case QuillRpcRequest.CreateDatapack c -> backend.createDatapack(c.packName(), c.description())
						.thenApply(created -> new ExecutionOutcome(
								new QuillRpcResponse.StringResult(created),
								new QuillNetworkPayloads.TreeChangedPayload(actor, "CREATE_DATAPACK", created, "")
						));

				case QuillRpcRequest.CreateNamespace c -> backend.createNamespace(c.packName(), c.namespace())
						.thenApply(created -> new ExecutionOutcome(
								new QuillRpcResponse.StringResult(created),
								new QuillNetworkPayloads.TreeChangedPayload(actor, "CREATE_NAMESPACE", created, "")
						));

				case QuillRpcRequest.CreateFunction c -> backend.createFunction(c.packName(), c.namespace(), c.functionPath())
						.thenApply(created -> new ExecutionOutcome(
								new QuillRpcResponse.StringResult(created),
								new QuillNetworkPayloads.TreeChangedPayload(actor, "CREATE_FUNCTION", created, "")
						));

				case QuillRpcRequest.ListPresets ignored -> backend.listPresets()
						.thenApply(presets -> new ExecutionOutcome(new QuillRpcResponse.PresetsResult(presets), null));

				case QuillRpcRequest.ApplyPreset a -> backend.applyPreset(a.packName(), a.presetId(), a.variables())
						.thenApply(doc -> new ExecutionOutcome(
								new QuillRpcResponse.DocumentResult(doc),
								new QuillNetworkPayloads.TreeChangedPayload(actor, "APPLY_PRESET", doc.relativePath(), doc.sha256())
						));

				case QuillRpcRequest.ReloadDatapacks ignored -> backend.reloadDatapacks()
						.thenApply(report -> new ExecutionOutcome(
								new QuillRpcResponse.ReloadOpResult(report),
								new QuillNetworkPayloads.TreeChangedPayload(actor, "RELOAD", "", "")
						));
			};
		} catch (Throwable t) {
			return CompletableFuture.failedFuture(t);
		}
	}

	private void sendResponse(PeerContext peer, long requestId, QuillRpcResponse response) {
		byte[] encoded;
		try {
			encoded = QuillProtocolCodec.encodeResponse(response);
		} catch (EditorSecurityException e) {
			Quill.LOGGER.warn("Failed to encode Quill RPC response for {} (requestId={}): {}",
					peer.playerName(), requestId, e.getMessage());
			encoded = QuillProtocolCodec.encodeResponse(QuillRpcResponse.ErrorResult.simple(
					QuillRpcResponse.ErrorCode.INTERNAL_ERROR,
					"Server response exceeds maximum transfer size."
			));
		}

		List<byte[]> chunks = ChunkedTransferAssembler.split(
				encoded,
				EditorLimits.MAX_CHUNK_BYTES,
				EditorLimits.MAX_MESSAGE_BYTES
		);
		int total = chunks.size();
		for (int i = 0; i < total; i++) {
			peer.sendFrame(new QuillNetworkPayloads.S2CFramePayload(requestId, i, total, chunks.get(i)));
		}
	}

	private static QuillRpcResponse.ErrorResult mapException(PeerContext peer, Throwable throwable) {
		Throwable cause = unwrap(throwable);
		if (cause instanceof RevisionConflictException rce) {
			return QuillRpcResponse.ErrorResult.conflict(
					rce.relativePath(),
					rce.expectedSha256(),
					rce.actualSha256(),
					rce.getMessage()
			);
		}
		if (cause instanceof ServerRateLimiter.RateLimitException rle) {
			return QuillRpcResponse.ErrorResult.simple(QuillRpcResponse.ErrorCode.RATE_LIMITED, rle.getMessage());
		}
		if (cause instanceof EditorSecurityException ese) {
			String msg = ese.getMessage() == null ? "Operation denied." : ese.getMessage();
			QuillRpcResponse.ErrorCode code = msg.toLowerCase(Locale.ROOT).contains("permission")
					? QuillRpcResponse.ErrorCode.PERMISSION_DENIED
					: QuillRpcResponse.ErrorCode.SECURITY_VIOLATION;
			return QuillRpcResponse.ErrorResult.simple(code, msg);
		}
		Quill.LOGGER.error("Unhandled Quill RPC error for player {} ({})", peer.playerName(), peer.playerId(), cause);
		return QuillRpcResponse.ErrorResult.simple(
				QuillRpcResponse.ErrorCode.INTERNAL_ERROR,
				"Server failed to complete the editor operation."
		);
	}

	private static Throwable unwrap(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null && (current instanceof CompletionException || current instanceof ExecutionException)) {
			current = current.getCause();
		}
		return current;
	}

	private static final class FabricPeerContext implements PeerContext {
		private final MinecraftServer server;
		private final ServerPlayer player;
		private final QuillAccessControl accessControl;

		FabricPeerContext(ServerPlayNetworking.Context context, QuillAccessControl accessControl) {
			this.server = context.server();
			this.player = context.player();
			this.accessControl = accessControl;
		}

		@Override
		public UUID playerId() {
			return player.getUUID();
		}

		@Override
		public String playerName() {
			return player.nameAndId().name();
		}

		@Override
		public boolean isAuthorized() {
			return EditorPermissionChecker.canEdit(server, player, accessControl);
		}

		@Override
		public EditorBackend backend() {
			return new ServerEditorBackend(server, player, accessControl);
		}

		@Override
		public void sendFrame(QuillNetworkPayloads.S2CFramePayload frame) {
			server.execute(() -> {
				if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, QuillNetworkPayloads.S2CFramePayload.TYPE)) {
					ServerPlayNetworking.send(player, frame);
				}
			});
		}

		@Override
		public void broadcastTreeChanged(QuillNetworkPayloads.TreeChangedPayload notification) {
			server.execute(() -> {
				UUID actorId = player.getUUID();
				for (ServerPlayer other : server.getPlayerList().getPlayers()) {
					if (other.getUUID().equals(actorId) || other.hasDisconnected()) {
						continue;
					}
					if (!ServerPlayNetworking.canSend(other, QuillNetworkPayloads.TreeChangedPayload.TYPE)) {
						continue;
					}
					if (!EditorPermissionChecker.canEdit(server, other, accessControl)) {
						continue;
					}
					ServerPlayNetworking.send(other, notification);
				}
			});
		}
	}
}
