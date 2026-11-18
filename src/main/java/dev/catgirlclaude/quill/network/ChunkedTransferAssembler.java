package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.security.EditorLimits;
import dev.catgirlclaude.quill.security.EditorSecurityException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Splits large binary RPC messages into fixed-size chunks (safely under Minecraft's 32 KB
 * serverbound custom payload cap) and reassembles incoming chunks with strict per-owner
 * memory, concurrency, and timeout bounds.
 *
 * @param <K> Owner key type (e.g. {@link java.util.UUID} on server or a fixed key on client)
 */
public final class ChunkedTransferAssembler<K> {
	private record TransferKey<K>(K owner, long requestId) {
	}

	private static final class InFlightTransfer {
		final int totalChunks;
		final byte[][] chunks;
		final long createdAtMillis;
		int receivedCount = 0;
		int accumulatedBytes = 0;

		InFlightTransfer(int totalChunks, long createdAtMillis) {
			this.totalChunks = totalChunks;
			this.chunks = new byte[totalChunks][];
			this.createdAtMillis = createdAtMillis;
		}
	}

	private static final class OwnerStats {
		int activeTransfers = 0;
		int pendingBytes = 0;
	}

	private final int maxChunkBytes;
	private final int maxMessageBytes;
	private final int maxChunksPerMessage;
	private final int maxConcurrentTransfersPerOwner;
	private final int maxPendingBytesPerOwner;
	private final long transferTimeoutMs;
	private final LongSupplier clockMillis;

	private final Map<TransferKey<K>, InFlightTransfer> transfers = new HashMap<>();
	private final Map<K, OwnerStats> ownerStats = new HashMap<>();

	public ChunkedTransferAssembler() {
		this(
				EditorLimits.MAX_CHUNK_BYTES,
				EditorLimits.MAX_MESSAGE_BYTES,
				EditorLimits.MAX_CHUNKS_PER_MESSAGE,
				EditorLimits.MAX_CONCURRENT_TRANSFERS_PER_PLAYER,
				EditorLimits.MAX_PENDING_TRANSFER_BYTES_PER_PLAYER,
				EditorLimits.TRANSFER_TIMEOUT_MS,
				System::currentTimeMillis
		);
	}

	public ChunkedTransferAssembler(
			int maxChunkBytes,
			int maxMessageBytes,
			int maxChunksPerMessage,
			int maxConcurrentTransfersPerOwner,
			int maxPendingBytesPerOwner,
			long transferTimeoutMs,
			LongSupplier clockMillis
	) {
		this.maxChunkBytes = maxChunkBytes;
		this.maxMessageBytes = maxMessageBytes;
		this.maxChunksPerMessage = maxChunksPerMessage;
		this.maxConcurrentTransfersPerOwner = maxConcurrentTransfersPerOwner;
		this.maxPendingBytesPerOwner = maxPendingBytesPerOwner;
		this.transferTimeoutMs = transferTimeoutMs;
		this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
	}

	/**
	 * Splits {@code message} into chunks each at most {@code maxChunkBytes} bytes long.
	 */
	public static List<byte[]> split(byte[] message, int maxChunkBytes, int maxMessageBytes) {
		Objects.requireNonNull(message, "message");
		if (maxChunkBytes <= 0) {
			throw new IllegalArgumentException("maxChunkBytes must be positive.");
		}
		if (message.length > maxMessageBytes) {
			throw new EditorSecurityException("Message exceeds maximum allowed size of " + maxMessageBytes + " bytes.");
		}
		if (message.length == 0) {
			return List.of(new byte[0]);
		}
		int totalChunks = (message.length + maxChunkBytes - 1) / maxChunkBytes;
		List<byte[]> parts = new ArrayList<>(totalChunks);
		for (int offset = 0; offset < message.length; offset += maxChunkBytes) {
			int end = Math.min(message.length, offset + maxChunkBytes);
			parts.add(Arrays.copyOfRange(message, offset, end));
		}
		return parts;
	}

	public synchronized boolean hasInFlight(K owner, long requestId) {
		evictExpired(clockMillis.getAsLong());
		return transfers.containsKey(new TransferKey<>(owner, requestId));
	}

	/**
	 * Accepts one frame for {@code (owner, requestId)} and returns the fully assembled message
	 * once all {@code totalChunks} have arrived.
	 */
	public synchronized Optional<byte[]> accept(
			K owner,
			long requestId,
			int chunkIndex,
			int totalChunks,
			byte[] data
	) {
		Objects.requireNonNull(owner, "owner");
		long now = clockMillis.getAsLong();
		evictExpired(now);

		if (totalChunks < 1 || totalChunks > maxChunksPerMessage) {
			throw new EditorSecurityException("Invalid totalChunks: " + totalChunks + " (max " + maxChunksPerMessage + ").");
		}
		if (chunkIndex < 0 || chunkIndex >= totalChunks) {
			throw new EditorSecurityException("Invalid chunkIndex " + chunkIndex + " for totalChunks " + totalChunks + ".");
		}
		if (data == null || data.length > maxChunkBytes) {
			throw new EditorSecurityException("Chunk size exceeds limit of " + maxChunkBytes + " bytes.");
		}

		TransferKey<K> key = new TransferKey<>(owner, requestId);

		// Fast path for single-chunk messages
		if (totalChunks == 1) {
			if (transfers.containsKey(key)) {
				removeTransfer(key);
				throw new EditorSecurityException("Conflicting single-chunk frame for existing multi-chunk transfer.");
			}
			if (data.length > maxMessageBytes) {
				throw new EditorSecurityException("Message exceeds maximum size of " + maxMessageBytes + " bytes.");
			}
			return Optional.of(data.clone());
		}

		InFlightTransfer transfer = transfers.get(key);
		OwnerStats stats = ownerStats.computeIfAbsent(owner, ignored -> new OwnerStats());

		if (transfer == null) {
			if (stats.activeTransfers >= maxConcurrentTransfersPerOwner) {
				throw new EditorSecurityException("Too many concurrent chunked transfers (max " + maxConcurrentTransfersPerOwner + ").");
			}
			if (stats.pendingBytes + data.length > maxPendingBytesPerOwner) {
				throw new EditorSecurityException("Pending chunked transfer memory cap exceeded.");
			}
			transfer = new InFlightTransfer(totalChunks, now);
			transfers.put(key, transfer);
			stats.activeTransfers++;
		} else {
			if (transfer.totalChunks != totalChunks) {
				removeTransfer(key);
				throw new EditorSecurityException("Inconsistent totalChunks for request " + requestId + ".");
			}
			if (transfer.chunks[chunkIndex] != null) {
				removeTransfer(key);
				throw new EditorSecurityException("Duplicate chunk index " + chunkIndex + " for request " + requestId + ".");
			}
			if (transfer.accumulatedBytes + data.length > maxMessageBytes) {
				removeTransfer(key);
				throw new EditorSecurityException("Assembled message exceeds maximum size of " + maxMessageBytes + " bytes.");
			}
			if (stats.pendingBytes + data.length > maxPendingBytesPerOwner) {
				removeTransfer(key);
				throw new EditorSecurityException("Pending chunked transfer memory cap exceeded.");
			}
		}

		byte[] copy = data.clone();
		transfer.chunks[chunkIndex] = copy;
		transfer.receivedCount++;
		transfer.accumulatedBytes += copy.length;
		stats.pendingBytes += copy.length;

		if (transfer.receivedCount < transfer.totalChunks) {
			return Optional.empty();
		}

		byte[] assembled = new byte[transfer.accumulatedBytes];
		int pos = 0;
		for (byte[] part : transfer.chunks) {
			System.arraycopy(part, 0, assembled, pos, part.length);
			pos += part.length;
		}
		removeTransfer(key);
		return Optional.of(assembled);
	}

	public synchronized void clearOwner(K owner) {
		if (owner == null) {
			return;
		}
		Iterator<Map.Entry<TransferKey<K>, InFlightTransfer>> it = transfers.entrySet().iterator();
		while (it.hasNext()) {
			if (owner.equals(it.next().getKey().owner())) {
				it.remove();
			}
		}
		ownerStats.remove(owner);
	}

	public synchronized void clearAll() {
		transfers.clear();
		ownerStats.clear();
	}

	public synchronized int activeTransferCount() {
		evictExpired(clockMillis.getAsLong());
		return transfers.size();
	}

	private void removeTransfer(TransferKey<K> key) {
		InFlightTransfer removed = transfers.remove(key);
		if (removed == null) {
			return;
		}
		OwnerStats stats = ownerStats.get(key.owner());
		if (stats != null) {
			stats.activeTransfers = Math.max(0, stats.activeTransfers - 1);
			stats.pendingBytes = Math.max(0, stats.pendingBytes - removed.accumulatedBytes);
			if (stats.activeTransfers == 0 && stats.pendingBytes == 0) {
				ownerStats.remove(key.owner());
			}
		}
	}

	private void evictExpired(long nowMillis) {
		if (transfers.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<TransferKey<K>, InFlightTransfer>> it = transfers.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<TransferKey<K>, InFlightTransfer> entry = it.next();
			InFlightTransfer transfer = entry.getValue();
			if (nowMillis - transfer.createdAtMillis > transferTimeoutMs) {
				it.remove();
				OwnerStats stats = ownerStats.get(entry.getKey().owner());
				if (stats != null) {
					stats.activeTransfers = Math.max(0, stats.activeTransfers - 1);
					stats.pendingBytes = Math.max(0, stats.pendingBytes - transfer.accumulatedBytes);
					if (stats.activeTransfers == 0 && stats.pendingBytes == 0) {
						ownerStats.remove(entry.getKey().owner());
					}
				}
			}
		}
	}
}
