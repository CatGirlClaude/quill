package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.security.EditorLimits;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedTransferAssemblerTest {

	@Test
	void splitsAndReassemblesLargePayloadEvenOutOfOrder() {
		byte[] payload = new byte[200_000];
		new Random(42L).nextBytes(payload);

		List<byte[]> chunks = ChunkedTransferAssembler.split(payload, EditorLimits.MAX_CHUNK_BYTES, EditorLimits.MAX_MESSAGE_BYTES);
		assertEquals(9, chunks.size());

		ChunkedTransferAssembler<UUID> assembler = new ChunkedTransferAssembler<>();
		UUID player = UUID.randomUUID();

		// Deliver out of order: last chunk first, then 0..n-2
		assertTrue(assembler.accept(player, 7L, chunks.size() - 1, chunks.size(), chunks.getLast()).isEmpty());
		for (int i = 0; i < chunks.size() - 2; i++) {
			assertTrue(assembler.accept(player, 7L, i, chunks.size(), chunks.get(i)).isEmpty());
		}
		Optional<byte[]> completed = assembler.accept(player, 7L, chunks.size() - 2, chunks.size(), chunks.get(chunks.size() - 2));
		assertTrue(completed.isPresent());
		assertArrayEquals(payload, completed.get());
		assertEquals(0, assembler.activeTransferCount());
	}

	@Test
	void enforcesDuplicateOversizedConcurrencyAndTimeoutLimits() {
		AtomicLong now = new AtomicLong(1_000L);
		ChunkedTransferAssembler<UUID> assembler = new ChunkedTransferAssembler<>(
				100,   // maxChunkBytes
				250,   // maxMessageBytes
				4,     // maxChunksPerMessage
				2,     // maxConcurrentTransfersPerOwner
				400,   // maxPendingBytesPerOwner
				5_000L,
				now::get
		);
		UUID player = UUID.randomUUID();

		// Duplicate chunk rejected and transfer cleaned up
		assertTrue(assembler.accept(player, 1L, 0, 2, new byte[50]).isEmpty());
		assertThrows(EditorSecurityException.class, () -> assembler.accept(player, 1L, 0, 2, new byte[50]));
		assertEquals(0, assembler.activeTransferCount());

		// Message exceeding maxMessageBytes rejected
		assertTrue(assembler.accept(player, 2L, 0, 3, new byte[100]).isEmpty());
		assertTrue(assembler.accept(player, 2L, 1, 3, new byte[100]).isEmpty());
		assertThrows(EditorSecurityException.class, () -> assembler.accept(player, 2L, 2, 3, new byte[60]));
		assertEquals(0, assembler.activeTransferCount());

		// Max concurrent transfers enforced
		assertTrue(assembler.accept(player, 10L, 0, 2, new byte[40]).isEmpty());
		assertTrue(assembler.accept(player, 11L, 0, 2, new byte[40]).isEmpty());
		assertThrows(EditorSecurityException.class, () -> assembler.accept(player, 12L, 0, 2, new byte[40]));

		// Stale transfers expire after timeout
		now.addAndGet(6_000L);
		assertEquals(0, assembler.activeTransferCount());
		assertTrue(assembler.accept(player, 12L, 0, 2, new byte[40]).isEmpty());

		// Owner cleanup removes active transfers
		assembler.clearOwner(player);
		assertEquals(0, assembler.activeTransferCount());
	}
}
