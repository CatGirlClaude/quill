package dev.catgirlclaude.quill.client.network;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.RevisionConflictException;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.network.ChunkedTransferAssembler;
import dev.catgirlclaude.quill.network.QuillNetworkPayloads;
import dev.catgirlclaude.quill.network.QuillProtocolCodec;
import dev.catgirlclaude.quill.network.QuillRpcRequest;
import dev.catgirlclaude.quill.network.QuillRpcResponse;
import dev.catgirlclaude.quill.security.EditorLimits;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client-side {@link EditorBackend} implementation communicating with a remote Quill server
 * over chunked {@link QuillNetworkPayloads} C2S/S2C frames.
 */
public final class RemoteEditorBackend implements EditorBackend {
	private static final String SERVER_PEER_KEY = "server";

	private record PendingCall(
			CompletableFuture<?> future,
			Consumer<QuillRpcResponse> responseHandler
	) {
	}

	private final Consumer<QuillNetworkPayloads.C2SFramePayload> frameSender;
	private final long requestTimeoutMs;
	private final AtomicLong nextRequestId = new AtomicLong(1L);
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final ConcurrentHashMap<Long, PendingCall> pendingCalls = new ConcurrentHashMap<>();
	private final ChunkedTransferAssembler<String> responseAssembler;

	private volatile String closedReason = "Remote editor session closed.";
	private volatile Consumer<QuillNetworkPayloads.TreeChangedPayload> treeChangedListener;

	public RemoteEditorBackend() {
		this(ClientPlayNetworking::send, EditorLimits.REQUEST_TIMEOUT_MS);
	}

	public RemoteEditorBackend(
			Consumer<QuillNetworkPayloads.C2SFramePayload> frameSender,
			long requestTimeoutMs
	) {
		this.frameSender = Objects.requireNonNull(frameSender, "frameSender");
		this.requestTimeoutMs = Math.max(100L, requestTimeoutMs);
		this.responseAssembler = new ChunkedTransferAssembler<>(
				EditorLimits.MAX_CHUNK_BYTES,
				EditorLimits.MAX_MESSAGE_BYTES,
				EditorLimits.MAX_CHUNKS_PER_MESSAGE,
				16,
				4 * 1024 * 1024,
				this.requestTimeoutMs,
				System::currentTimeMillis
		);
	}

	public void setTreeChangedListener(Consumer<QuillNetworkPayloads.TreeChangedPayload> listener) {
		this.treeChangedListener = listener;
	}

	@Override
	public CompletableFuture<FileTreeNode> listTree() {
		return sendRpc(new QuillRpcRequest.ListTree(), resp -> expect(resp, QuillRpcResponse.TreeResult.class).root());
	}

	@Override
	public CompletableFuture<FileDocument> readFile(String relativePath) {
		return sendRpc(new QuillRpcRequest.ReadFile(relativePath),
				resp -> expect(resp, QuillRpcResponse.DocumentResult.class).document());
	}

	@Override
	public CompletableFuture<SaveResult> writeFile(
			String relativePath,
			String content,
			String expectedBaseSha256,
			boolean reloadAfterSave
	) {
		return sendRpc(
				new QuillRpcRequest.WriteFile(relativePath, content, expectedBaseSha256, reloadAfterSave),
				resp -> expect(resp, QuillRpcResponse.SaveOpResult.class).result()
		);
	}

	@Override
	public CompletableFuture<Void> createEntry(String relativePath, EntryKind kind) {
		return sendRpc(new QuillRpcRequest.CreateEntry(relativePath, kind), resp -> {
			expect(resp, QuillRpcResponse.VoidResult.class);
			return null;
		});
	}

	@Override
	public CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath) {
		return sendRpc(new QuillRpcRequest.RenameEntry(sourceRelativePath, targetRelativePath), resp -> {
			expect(resp, QuillRpcResponse.VoidResult.class);
			return null;
		});
	}

	@Override
	public CompletableFuture<Void> deleteEntry(String relativePath) {
		return sendRpc(new QuillRpcRequest.DeleteEntry(relativePath), resp -> {
			expect(resp, QuillRpcResponse.VoidResult.class);
			return null;
		});
	}

	@Override
	public CompletableFuture<String> createDatapack(String packName, String description) {
		return sendRpc(new QuillRpcRequest.CreateDatapack(packName, description),
				resp -> expect(resp, QuillRpcResponse.StringResult.class).value());
	}

	@Override
	public CompletableFuture<String> createNamespace(String packName, String namespace) {
		return sendRpc(new QuillRpcRequest.CreateNamespace(packName, namespace),
				resp -> expect(resp, QuillRpcResponse.StringResult.class).value());
	}

	@Override
	public CompletableFuture<String> createFunction(String packName, String namespace, String functionPath) {
		return sendRpc(new QuillRpcRequest.CreateFunction(packName, namespace, functionPath),
				resp -> expect(resp, QuillRpcResponse.StringResult.class).value());
	}

	@Override
	public CompletableFuture<List<PresetDescriptor>> listPresets() {
		return sendRpc(new QuillRpcRequest.ListPresets(),
				resp -> expect(resp, QuillRpcResponse.PresetsResult.class).presets());
	}

	@Override
	public CompletableFuture<FileDocument> applyPreset(String packName, String presetId, Map<String, String> variables) {
		return sendRpc(new QuillRpcRequest.ApplyPreset(packName, presetId, variables),
				resp -> expect(resp, QuillRpcResponse.DocumentResult.class).document());
	}

	@Override
	public CompletableFuture<ReloadReport> reloadDatapacks() {
		return sendRpc(new QuillRpcRequest.ReloadDatapacks(),
				resp -> expect(resp, QuillRpcResponse.ReloadOpResult.class).report());
	}

	/**
	 * Feeds an incoming S2C chunk frame into the reassembler and completes the correlated request future.
	 */
	public void handleServerFrame(QuillNetworkPayloads.S2CFramePayload frame) {
		Objects.requireNonNull(frame, "frame");
		if (closed.get()) {
			return;
		}
		Optional<byte[]> assembled;
		try {
			assembled = responseAssembler.accept(
					SERVER_PEER_KEY,
					frame.requestId(),
					frame.chunkIndex(),
					frame.totalChunks(),
					frame.data()
			);
		} catch (EditorSecurityException e) {
			PendingCall pending = pendingCalls.remove(frame.requestId());
			if (pending != null) {
				pending.future().completeExceptionally(e);
			}
			return;
		}
		if (assembled.isEmpty()) {
			return;
		}

		PendingCall pending = pendingCalls.remove(frame.requestId());
		if (pending == null) {
			return;
		}
		try {
			QuillRpcResponse response = QuillProtocolCodec.decodeResponse(assembled.get());
			pending.responseHandler().accept(response);
		} catch (Throwable t) {
			pending.future().completeExceptionally(t);
		}
	}

	/**
	 * Forwards a server-initiated datapack tree change broadcast to the active session listener.
	 */
	public void handleTreeChanged(QuillNetworkPayloads.TreeChangedPayload notification) {
		if (closed.get() || notification == null) {
			return;
		}
		Consumer<QuillNetworkPayloads.TreeChangedPayload> listener = this.treeChangedListener;
		if (listener != null) {
			listener.accept(notification);
		}
	}

	public void close(String reason) {
		String msg = (reason == null || reason.isBlank()) ? "Disconnected from server." : reason;
		this.closedReason = msg;
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		IllegalStateException disconnectError = new IllegalStateException(msg);
		for (var entry : pendingCalls.entrySet()) {
			if (pendingCalls.remove(entry.getKey(), entry.getValue())) {
				entry.getValue().future().completeExceptionally(disconnectError);
			}
		}
		responseAssembler.clearAll();
	}

	public boolean isClosed() {
		return closed.get();
	}

	public int pendingRequestCount() {
		return pendingCalls.size();
	}

	private <T> CompletableFuture<T> sendRpc(QuillRpcRequest request, Function<QuillRpcResponse, T> mapper) {
		if (closed.get()) {
			return CompletableFuture.failedFuture(new IllegalStateException(closedReason));
		}

		byte[] encoded;
		List<byte[]> chunks;
		try {
			encoded = QuillProtocolCodec.encodeRequest(request);
			chunks = ChunkedTransferAssembler.split(encoded, EditorLimits.MAX_CHUNK_BYTES, EditorLimits.MAX_MESSAGE_BYTES);
		} catch (Throwable t) {
			return CompletableFuture.failedFuture(t);
		}

		long requestId = nextRequestId.getAndIncrement();
		CompletableFuture<T> future = new CompletableFuture<>();
		PendingCall pending = new PendingCall(future, response -> completeFromResponse(future, response, mapper));
		pendingCalls.put(requestId, pending);

		future.orTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS);
		future.whenComplete((ignored, err) -> pendingCalls.remove(requestId, pending));

		try {
			int total = chunks.size();
			for (int i = 0; i < total; i++) {
				frameSender.accept(new QuillNetworkPayloads.C2SFramePayload(requestId, i, total, chunks.get(i)));
			}
		} catch (Throwable t) {
			pendingCalls.remove(requestId, pending);
			future.completeExceptionally(t);
		}
		return future;
	}

	private static <T> void completeFromResponse(
			CompletableFuture<T> future,
			QuillRpcResponse response,
			Function<QuillRpcResponse, T> mapper
	) {
		if (response instanceof QuillRpcResponse.ErrorResult error) {
			future.completeExceptionally(toClientException(error));
			return;
		}
		try {
			future.complete(mapper.apply(response));
		} catch (Throwable t) {
			future.completeExceptionally(t);
		}
	}

	private static Throwable toClientException(QuillRpcResponse.ErrorResult error) {
		return switch (error.code()) {
			case REVISION_CONFLICT -> new RevisionConflictException(
					error.conflictPath(),
					error.expectedSha256(),
					error.actualSha256()
			);
			case PERMISSION_DENIED, SECURITY_VIOLATION, RATE_LIMITED -> new EditorSecurityException(error.message());
			case INTERNAL_ERROR -> new IllegalStateException(
					error.message().isBlank() ? "Server failed to complete editor request." : error.message()
			);
		};
	}

	private static <R extends QuillRpcResponse> R expect(QuillRpcResponse response, Class<R> expectedType) {
		if (!expectedType.isInstance(response)) {
			throw new IllegalStateException("Unexpected server response type: " + response.kind());
		}
		return expectedType.cast(response);
	}
}
