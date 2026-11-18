package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.security.EditorLimits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuillNetworkPayloadsTest {

	@Test
	void roundTripsFrameAndTreeChangedPayloads() {
		byte[] sample = new byte[]{1, 2, 3, 4, 5, 6, 7};

		ByteBuf c2sRaw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(c2sRaw);
			QuillNetworkPayloads.C2SFramePayload original = new QuillNetworkPayloads.C2SFramePayload(42L, 1, 3, sample);
			QuillNetworkPayloads.C2SFramePayload.STREAM_CODEC.encode(buf, original);
			QuillNetworkPayloads.C2SFramePayload decoded = QuillNetworkPayloads.C2SFramePayload.STREAM_CODEC.decode(buf);
			assertEquals(42L, decoded.requestId());
			assertEquals(1, decoded.chunkIndex());
			assertEquals(3, decoded.totalChunks());
			assertArrayEquals(sample, decoded.data());
		} finally {
			c2sRaw.release();
		}

		ByteBuf s2cRaw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(s2cRaw);
			QuillNetworkPayloads.S2CFramePayload original = new QuillNetworkPayloads.S2CFramePayload(99L, 0, 1, sample);
			QuillNetworkPayloads.S2CFramePayload.STREAM_CODEC.encode(buf, original);
			QuillNetworkPayloads.S2CFramePayload decoded = QuillNetworkPayloads.S2CFramePayload.STREAM_CODEC.decode(buf);
			assertEquals(99L, decoded.requestId());
			assertEquals(0, decoded.chunkIndex());
			assertEquals(1, decoded.totalChunks());
			assertArrayEquals(sample, decoded.data());
		} finally {
			s2cRaw.release();
		}

		ByteBuf treeRaw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(treeRaw);
			QuillNetworkPayloads.TreeChangedPayload original = new QuillNetworkPayloads.TreeChangedPayload(
					"Alice", "WRITE", "my_pack/data/demo/function/load.mcfunction", "deadbeef"
			);
			QuillNetworkPayloads.TreeChangedPayload.STREAM_CODEC.encode(buf, original);
			QuillNetworkPayloads.TreeChangedPayload decoded = QuillNetworkPayloads.TreeChangedPayload.STREAM_CODEC.decode(buf);
			assertEquals(original, decoded);
		} finally {
			treeRaw.release();
		}
	}

	@Test
	void rejectsOversizedOrInvalidFrameBounds() {
		assertThrows(IllegalArgumentException.class, () ->
				new QuillNetworkPayloads.C2SFramePayload(1L, 0, 1, new byte[EditorLimits.MAX_CHUNK_BYTES + 1])
		);
		assertThrows(IllegalArgumentException.class, () ->
				new QuillNetworkPayloads.C2SFramePayload(1L, 2, 2, new byte[4])
		);
		assertThrows(IllegalArgumentException.class, () ->
				new QuillNetworkPayloads.S2CFramePayload(1L, 0, 0, new byte[4])
		);
	}
}
