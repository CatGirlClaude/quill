package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.Quill;
import dev.catgirlclaude.quill.security.EditorLimits;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minecraft 26.3 {@link CustomPacketPayload} definitions and Fabric {@link PayloadTypeRegistry}
 * registration for Quill's C2S/S2C chunked RPC protocol and tree-change notifications.
 */
public final class QuillNetworkPayloads {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
	private static final int MAX_ACTOR_NAME_LENGTH = 64;
	private static final int MAX_CHANGE_KIND_LENGTH = 32;
	private static final int MAX_SHA256_LENGTH = 64;

	private QuillNetworkPayloads() {
	}

	/**
	 * Client-to-server chunked RPC frame ({@code quill:c2s_frame}).
	 */
	public record C2SFramePayload(
			long requestId,
			int chunkIndex,
			int totalChunks,
			byte[] data
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<C2SFramePayload> TYPE =
				new CustomPacketPayload.Type<>(Quill.id("c2s_frame"));

		public static final StreamCodec<FriendlyByteBuf, C2SFramePayload> STREAM_CODEC =
				CustomPacketPayload.codec(C2SFramePayload::write, C2SFramePayload::read);

		public C2SFramePayload {
			validateFrameBounds(chunkIndex, totalChunks, data);
			data = data.clone();
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeLong(requestId);
			buf.writeVarInt(chunkIndex);
			buf.writeVarInt(totalChunks);
			buf.writeByteArray(data);
		}

		private static C2SFramePayload read(FriendlyByteBuf buf) {
			long requestId = buf.readLong();
			int chunkIndex = buf.readVarInt();
			int totalChunks = buf.readVarInt();
			byte[] data = buf.readByteArray(EditorLimits.MAX_CHUNK_BYTES);
			return new C2SFramePayload(requestId, chunkIndex, totalChunks, data);
		}

		@Override
		public CustomPacketPayload.Type<C2SFramePayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server-to-client chunked RPC frame ({@code quill:s2c_frame}).
	 */
	public record S2CFramePayload(
			long requestId,
			int chunkIndex,
			int totalChunks,
			byte[] data
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<S2CFramePayload> TYPE =
				new CustomPacketPayload.Type<>(Quill.id("s2c_frame"));

		public static final StreamCodec<FriendlyByteBuf, S2CFramePayload> STREAM_CODEC =
				CustomPacketPayload.codec(S2CFramePayload::write, S2CFramePayload::read);

		public S2CFramePayload {
			validateFrameBounds(chunkIndex, totalChunks, data);
			data = data.clone();
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeLong(requestId);
			buf.writeVarInt(chunkIndex);
			buf.writeVarInt(totalChunks);
			buf.writeByteArray(data);
		}

		private static S2CFramePayload read(FriendlyByteBuf buf) {
			long requestId = buf.readLong();
			int chunkIndex = buf.readVarInt();
			int totalChunks = buf.readVarInt();
			byte[] data = buf.readByteArray(EditorLimits.MAX_CHUNK_BYTES);
			return new S2CFramePayload(requestId, chunkIndex, totalChunks, data);
		}

		@Override
		public CustomPacketPayload.Type<S2CFramePayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server-to-client broadcast notifying authorized editors that server datapacks changed ({@code quill:s2c_tree_changed}).
	 */
	public record TreeChangedPayload(
			String actorName,
			String changeKind,
			String affectedPath,
			String newSha256
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<TreeChangedPayload> TYPE =
				new CustomPacketPayload.Type<>(Quill.id("s2c_tree_changed"));

		public static final StreamCodec<FriendlyByteBuf, TreeChangedPayload> STREAM_CODEC =
				CustomPacketPayload.codec(TreeChangedPayload::write, TreeChangedPayload::read);

		public TreeChangedPayload {
			actorName = clamp(actorName, MAX_ACTOR_NAME_LENGTH);
			changeKind = clamp(changeKind, MAX_CHANGE_KIND_LENGTH);
			affectedPath = clamp(affectedPath, EditorLimits.MAX_PATH_LENGTH);
			newSha256 = clamp(newSha256, MAX_SHA256_LENGTH);
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(actorName, MAX_ACTOR_NAME_LENGTH);
			buf.writeUtf(changeKind, MAX_CHANGE_KIND_LENGTH);
			buf.writeUtf(affectedPath, EditorLimits.MAX_PATH_LENGTH);
			buf.writeUtf(newSha256, MAX_SHA256_LENGTH);
		}

		private static TreeChangedPayload read(FriendlyByteBuf buf) {
			String actorName = buf.readUtf(MAX_ACTOR_NAME_LENGTH);
			String changeKind = buf.readUtf(MAX_CHANGE_KIND_LENGTH);
			String affectedPath = buf.readUtf(EditorLimits.MAX_PATH_LENGTH);
			String newSha256 = buf.readUtf(MAX_SHA256_LENGTH);
			return new TreeChangedPayload(actorName, changeKind, affectedPath, newSha256);
		}

		@Override
		public CustomPacketPayload.Type<TreeChangedPayload> type() {
			return TYPE;
		}
	}

	public static void registerPayloads() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}
		PayloadTypeRegistry.serverboundPlay().register(C2SFramePayload.TYPE, C2SFramePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CFramePayload.TYPE, S2CFramePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TreeChangedPayload.TYPE, TreeChangedPayload.STREAM_CODEC);
	}

	private static void validateFrameBounds(int chunkIndex, int totalChunks, byte[] data) {
		Objects.requireNonNull(data, "data");
		if (totalChunks < 1 || totalChunks > EditorLimits.MAX_CHUNKS_PER_MESSAGE) {
			throw new IllegalArgumentException("totalChunks out of range: " + totalChunks);
		}
		if (chunkIndex < 0 || chunkIndex >= totalChunks) {
			throw new IllegalArgumentException("chunkIndex out of range: " + chunkIndex + " / " + totalChunks);
		}
		if (data.length > EditorLimits.MAX_CHUNK_BYTES) {
			throw new IllegalArgumentException("Chunk payload exceeds max chunk bytes: " + data.length);
		}
	}

	private static String clamp(String value, int maxLen) {
		if (value == null) {
			return "";
		}
		return value.length() <= maxLen ? value : value.substring(0, maxLen);
	}
}
