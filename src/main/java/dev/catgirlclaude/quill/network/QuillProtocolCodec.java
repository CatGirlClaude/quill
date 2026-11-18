package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadDiagnostic;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.security.EditorLimits;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, length-bounded binary codec for {@link QuillRpcRequest} and {@link QuillRpcResponse}.
 * Produces raw {@code byte[]} messages transported inside chunked {@link QuillNetworkPayloads} frames.
 */
public final class QuillProtocolCodec {
	public static final byte PROTOCOL_VERSION = 1;
	private static final int MAX_SHA256_LENGTH = 64;
	private static final int MAX_PRESETS_COUNT = 128;

	private QuillProtocolCodec() {
	}

	public static byte[] encodeRequest(QuillRpcRequest request) {
		Objects.requireNonNull(request, "request");
		ByteBuf raw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(raw);
			buf.writeByte(PROTOCOL_VERSION);
			buf.writeByte(request.opCode().id());

			switch (request) {
				case QuillRpcRequest.ListTree ignored -> {
				}
				case QuillRpcRequest.ReadFile r -> writeBoundedString(buf, r.relativePath(), EditorLimits.MAX_PATH_LENGTH);
				case QuillRpcRequest.WriteFile w -> {
					writeBoundedString(buf, w.relativePath(), EditorLimits.MAX_PATH_LENGTH);
					writeFileContentUtf8(buf, w.content());
					writeBoundedString(buf, w.expectedBaseSha256(), MAX_SHA256_LENGTH);
					buf.writeBoolean(w.reloadAfterSave());
				}
				case QuillRpcRequest.CreateEntry c -> {
					writeBoundedString(buf, c.relativePath(), EditorLimits.MAX_PATH_LENGTH);
					buf.writeVarInt(c.kind().ordinal());
				}
				case QuillRpcRequest.RenameEntry r -> {
					writeBoundedString(buf, r.sourceRelativePath(), EditorLimits.MAX_PATH_LENGTH);
					writeBoundedString(buf, r.targetRelativePath(), EditorLimits.MAX_PATH_LENGTH);
				}
				case QuillRpcRequest.DeleteEntry d -> writeBoundedString(buf, d.relativePath(), EditorLimits.MAX_PATH_LENGTH);
				case QuillRpcRequest.CreateDatapack c -> {
					writeBoundedString(buf, c.packName(), EditorLimits.MAX_PATH_LENGTH);
					writeBoundedString(buf, c.description(), EditorLimits.MAX_DESCRIPTION_LENGTH);
				}
				case QuillRpcRequest.CreateNamespace c -> {
					writeBoundedString(buf, c.packName(), EditorLimits.MAX_PATH_LENGTH);
					writeBoundedString(buf, c.namespace(), EditorLimits.MAX_PATH_LENGTH);
				}
				case QuillRpcRequest.CreateFunction c -> {
					writeBoundedString(buf, c.packName(), EditorLimits.MAX_PATH_LENGTH);
					writeBoundedString(buf, c.namespace(), EditorLimits.MAX_PATH_LENGTH);
					writeBoundedString(buf, c.functionPath(), EditorLimits.MAX_PATH_LENGTH);
				}
				case QuillRpcRequest.ListPresets ignored -> {
				}
				case QuillRpcRequest.ApplyPreset a -> {
					writeBoundedString(buf, a.packName(), EditorLimits.MAX_PATH_LENGTH);
					writeBoundedString(buf, a.presetId(), EditorLimits.MAX_PATH_LENGTH);
					writeStringMap(buf, a.variables(), EditorLimits.MAX_PRESET_VARIABLES, EditorLimits.MAX_VARIABLE_LENGTH);
				}
				case QuillRpcRequest.ReloadDatapacks ignored -> {
				}
			}

			return toBoundedBytes(raw);
		} finally {
			raw.release();
		}
	}

	public static QuillRpcRequest decodeRequest(byte[] payload) {
		validateMessageSize(payload);
		ByteBuf raw = Unpooled.wrappedBuffer(payload);
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(raw);
			requireVersion(buf);
			QuillRpcRequest.OpCode opCode = QuillRpcRequest.OpCode.fromId(buf.readByte());
			QuillRpcRequest decoded = switch (opCode) {
				case LIST_TREE -> new QuillRpcRequest.ListTree();
				case READ_FILE -> new QuillRpcRequest.ReadFile(readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH));
				case WRITE_FILE -> new QuillRpcRequest.WriteFile(
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readFileContentUtf8(buf),
						readBoundedString(buf, MAX_SHA256_LENGTH),
						buf.readBoolean()
				);
				case CREATE_ENTRY -> new QuillRpcRequest.CreateEntry(
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readEntryKind(buf)
				);
				case RENAME_ENTRY -> new QuillRpcRequest.RenameEntry(
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH)
				);
				case DELETE_ENTRY -> new QuillRpcRequest.DeleteEntry(readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH));
				case CREATE_DATAPACK -> new QuillRpcRequest.CreateDatapack(
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readBoundedString(buf, EditorLimits.MAX_DESCRIPTION_LENGTH)
				);
				case CREATE_NAMESPACE -> new QuillRpcRequest.CreateNamespace(
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH)
				);
				case CREATE_FUNCTION -> new QuillRpcRequest.CreateFunction(
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH)
				);
				case LIST_PRESETS -> new QuillRpcRequest.ListPresets();
				case APPLY_PRESET -> new QuillRpcRequest.ApplyPreset(
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readStringMap(buf, EditorLimits.MAX_PRESET_VARIABLES, EditorLimits.MAX_VARIABLE_LENGTH)
				);
				case RELOAD_DATAPACKS -> new QuillRpcRequest.ReloadDatapacks();
			};
			requireFullyConsumed(buf);
			return decoded;
		} catch (EditorSecurityException e) {
			throw e;
		} catch (Exception e) {
			throw new EditorSecurityException("Malformed Quill RPC request: " + e.getMessage(), e);
		} finally {
			raw.release();
		}
	}

	public static byte[] encodeResponse(QuillRpcResponse response) {
		Objects.requireNonNull(response, "response");
		ByteBuf raw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(raw);
			buf.writeByte(PROTOCOL_VERSION);
			buf.writeByte(response.kind().id());

			switch (response) {
				case QuillRpcResponse.TreeResult r -> writeTreeNode(buf, r.root(), 0, new AtomicInteger(0));
				case QuillRpcResponse.DocumentResult r -> writeFileDocument(buf, r.document());
				case QuillRpcResponse.SaveOpResult r -> {
					writeFileDocument(buf, r.result().document());
					boolean hasReport = r.result().reloadReport() != null;
					buf.writeBoolean(hasReport);
					if (hasReport) {
						writeReloadReport(buf, r.result().reloadReport());
					}
				}
				case QuillRpcResponse.VoidResult ignored -> {
				}
				case QuillRpcResponse.StringResult r -> writeBoundedString(buf, r.value(), EditorLimits.MAX_PATH_LENGTH);
				case QuillRpcResponse.PresetsResult r -> {
					List<PresetDescriptor> presets = r.presets();
					if (presets.size() > MAX_PRESETS_COUNT) {
						throw new EditorSecurityException("Preset list exceeds max size: " + presets.size());
					}
					buf.writeVarInt(presets.size());
					for (PresetDescriptor preset : presets) {
						writePresetDescriptor(buf, preset);
					}
				}
				case QuillRpcResponse.ReloadOpResult r -> writeReloadReport(buf, r.report());
				case QuillRpcResponse.ErrorResult e -> {
					buf.writeByte(e.code().id());
					writeBoundedString(buf, truncate(e.message(), EditorLimits.MAX_ERROR_MESSAGE_LENGTH), EditorLimits.MAX_ERROR_MESSAGE_LENGTH);
					writeBoundedString(buf, truncate(e.conflictPath(), EditorLimits.MAX_PATH_LENGTH), EditorLimits.MAX_PATH_LENGTH);
					writeBoundedString(buf, truncate(e.expectedSha256(), MAX_SHA256_LENGTH), MAX_SHA256_LENGTH);
					writeBoundedString(buf, truncate(e.actualSha256(), MAX_SHA256_LENGTH), MAX_SHA256_LENGTH);
				}
			}

			return toBoundedBytes(raw);
		} finally {
			raw.release();
		}
	}

	public static QuillRpcResponse decodeResponse(byte[] payload) {
		validateMessageSize(payload);
		ByteBuf raw = Unpooled.wrappedBuffer(payload);
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(raw);
			requireVersion(buf);
			QuillRpcResponse.Kind kind = QuillRpcResponse.Kind.fromId(buf.readByte());
			QuillRpcResponse decoded = switch (kind) {
				case TREE -> new QuillRpcResponse.TreeResult(readTreeNode(buf, 0, new AtomicInteger(0)));
				case DOCUMENT -> new QuillRpcResponse.DocumentResult(readFileDocument(buf));
				case SAVE -> {
					FileDocument doc = readFileDocument(buf);
					boolean hasReport = buf.readBoolean();
					ReloadReport report = hasReport ? readReloadReport(buf) : null;
					yield new QuillRpcResponse.SaveOpResult(new SaveResult(doc, report));
				}
				case VOID -> new QuillRpcResponse.VoidResult();
				case STRING -> new QuillRpcResponse.StringResult(readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH));
				case PRESETS -> {
					int count = readBoundedCount(buf, MAX_PRESETS_COUNT, "presets");
					List<PresetDescriptor> list = new ArrayList<>(count);
					for (int i = 0; i < count; i++) {
						list.add(readPresetDescriptor(buf));
					}
					yield new QuillRpcResponse.PresetsResult(list);
				}
				case RELOAD -> new QuillRpcResponse.ReloadOpResult(readReloadReport(buf));
				case ERROR -> new QuillRpcResponse.ErrorResult(
						QuillRpcResponse.ErrorCode.fromId(buf.readByte()),
						readBoundedString(buf, EditorLimits.MAX_ERROR_MESSAGE_LENGTH),
						readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH),
						readBoundedString(buf, MAX_SHA256_LENGTH),
						readBoundedString(buf, MAX_SHA256_LENGTH)
				);
			};
			requireFullyConsumed(buf);
			return decoded;
		} catch (EditorSecurityException e) {
			throw e;
		} catch (Exception e) {
			throw new EditorSecurityException("Malformed Quill RPC response: " + e.getMessage(), e);
		} finally {
			raw.release();
		}
	}

	private static void writeFileDocument(FriendlyByteBuf buf, FileDocument doc) {
		writeBoundedString(buf, doc.relativePath(), EditorLimits.MAX_PATH_LENGTH);
		writeFileContentUtf8(buf, doc.content());
		writeBoundedString(buf, doc.sha256(), MAX_SHA256_LENGTH);
		buf.writeLong(doc.lastModifiedEpochMs());
	}

	private static FileDocument readFileDocument(FriendlyByteBuf buf) {
		String path = readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH);
		String content = readFileContentUtf8(buf);
		String sha256 = readBoundedString(buf, MAX_SHA256_LENGTH);
		long lastModified = buf.readLong();
		return new FileDocument(path, content, sha256, lastModified);
	}

	private static void writeTreeNode(FriendlyByteBuf buf, FileTreeNode node, int depth, AtomicInteger nodeCounter) {
		if (depth > EditorLimits.MAX_TREE_DEPTH + 1) {
			throw new EditorSecurityException("File tree exceeds max depth.");
		}
		if (nodeCounter.incrementAndGet() > EditorLimits.MAX_TREE_ENTRIES + 1) {
			throw new EditorSecurityException("File tree exceeds max node count.");
		}
		writeBoundedString(buf, node.name(), EditorLimits.MAX_PATH_LENGTH);
		writeBoundedString(buf, node.relativePath(), EditorLimits.MAX_PATH_LENGTH);
		buf.writeVarInt(node.kind().ordinal());
		List<FileTreeNode> children = node.children();
		buf.writeVarInt(children.size());
		for (FileTreeNode child : children) {
			writeTreeNode(buf, child, depth + 1, nodeCounter);
		}
	}

	private static FileTreeNode readTreeNode(FriendlyByteBuf buf, int depth, AtomicInteger nodeCounter) {
		if (depth > EditorLimits.MAX_TREE_DEPTH + 1) {
			throw new EditorSecurityException("File tree exceeds max depth.");
		}
		if (nodeCounter.incrementAndGet() > EditorLimits.MAX_TREE_ENTRIES + 1) {
			throw new EditorSecurityException("File tree exceeds max node count.");
		}
		String name = readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH);
		String relativePath = readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH);
		EntryKind kind = readEntryKind(buf);
		int childCount = readBoundedCount(buf, EditorLimits.MAX_TREE_ENTRIES, "tree children");
		List<FileTreeNode> children = new ArrayList<>(childCount);
		for (int i = 0; i < childCount; i++) {
			children.add(readTreeNode(buf, depth + 1, nodeCounter));
		}
		return new FileTreeNode(name, relativePath, kind, children);
	}

	private static void writePresetDescriptor(FriendlyByteBuf buf, PresetDescriptor preset) {
		writeBoundedString(buf, preset.id(), EditorLimits.MAX_PATH_LENGTH);
		writeBoundedString(buf, preset.displayName(), EditorLimits.MAX_DESCRIPTION_LENGTH);
		writeBoundedString(buf, preset.description(), EditorLimits.MAX_DESCRIPTION_LENGTH);
		writeBoundedString(buf, preset.targetRelativePathPattern(), EditorLimits.MAX_PATH_LENGTH);
		writeStringMap(buf, preset.defaultVariables(), EditorLimits.MAX_PRESET_VARIABLES, EditorLimits.MAX_VARIABLE_LENGTH);
	}

	private static PresetDescriptor readPresetDescriptor(FriendlyByteBuf buf) {
		String id = readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH);
		String displayName = readBoundedString(buf, EditorLimits.MAX_DESCRIPTION_LENGTH);
		String description = readBoundedString(buf, EditorLimits.MAX_DESCRIPTION_LENGTH);
		String targetPattern = readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH);
		Map<String, String> defaults = readStringMap(buf, EditorLimits.MAX_PRESET_VARIABLES, EditorLimits.MAX_VARIABLE_LENGTH);
		return new PresetDescriptor(id, displayName, description, targetPattern, defaults);
	}

	private static void writeReloadReport(FriendlyByteBuf buf, ReloadReport report) {
		buf.writeBoolean(report.success());
		buf.writeVarLong(Math.max(0L, report.durationMs()));
		buf.writeVarInt(Math.max(0, report.enabledPackCount()));
		List<ReloadDiagnostic> diagnostics = report.diagnostics();
		int count = Math.min(diagnostics.size(), EditorLimits.MAX_DIAGNOSTICS_PER_REPORT);
		buf.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			ReloadDiagnostic diag = diagnostics.get(i);
			writeBoundedString(buf, truncate(diag.file(), EditorLimits.MAX_PATH_LENGTH), EditorLimits.MAX_PATH_LENGTH);
			buf.writeVarInt(Math.max(0, diag.line()));
			writeBoundedString(buf, truncate(diag.message(), EditorLimits.MAX_ERROR_MESSAGE_LENGTH), EditorLimits.MAX_ERROR_MESSAGE_LENGTH);
		}
	}

	private static ReloadReport readReloadReport(FriendlyByteBuf buf) {
		boolean success = buf.readBoolean();
		long durationMs = buf.readVarLong();
		int enabledPackCount = buf.readVarInt();
		int count = readBoundedCount(buf, EditorLimits.MAX_DIAGNOSTICS_PER_REPORT, "reload diagnostics");
		List<ReloadDiagnostic> diagnostics = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String file = readBoundedString(buf, EditorLimits.MAX_PATH_LENGTH);
			int line = buf.readVarInt();
			String message = readBoundedString(buf, EditorLimits.MAX_ERROR_MESSAGE_LENGTH);
			diagnostics.add(new ReloadDiagnostic(file, line, message));
		}
		return new ReloadReport(success, durationMs, enabledPackCount, diagnostics);
	}

	private static void writeStringMap(FriendlyByteBuf buf, Map<String, String> map, int maxEntries, int maxValueLen) {
		if (map.size() > maxEntries) {
			throw new EditorSecurityException("Map exceeds max allowed entries (" + maxEntries + ").");
		}
		buf.writeVarInt(map.size());
		for (var entry : map.entrySet()) {
			writeBoundedString(buf, entry.getKey() == null ? "" : entry.getKey(), maxValueLen);
			writeBoundedString(buf, entry.getValue() == null ? "" : entry.getValue(), maxValueLen);
		}
	}

	private static Map<String, String> readStringMap(FriendlyByteBuf buf, int maxEntries, int maxValueLen) {
		int size = readBoundedCount(buf, maxEntries, "map entries");
		Map<String, String> out = new LinkedHashMap<>(size);
		for (int i = 0; i < size; i++) {
			String key = readBoundedString(buf, maxValueLen);
			String value = readBoundedString(buf, maxValueLen);
			out.put(key, value);
		}
		return out;
	}

	private static void writeFileContentUtf8(FriendlyByteBuf buf, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > EditorLimits.MAX_FILE_SIZE_BYTES) {
			throw new EditorSecurityException("Content exceeds max file size of " + EditorLimits.MAX_FILE_SIZE_BYTES + " bytes.");
		}
		buf.writeByteArray(bytes);
	}

	private static String readFileContentUtf8(FriendlyByteBuf buf) {
		byte[] bytes = buf.readByteArray(EditorLimits.MAX_FILE_SIZE_BYTES);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void writeBoundedString(FriendlyByteBuf buf, String value, int maxChars) {
		if (value.length() > maxChars) {
			throw new EditorSecurityException("String field exceeds max length of " + maxChars + " characters.");
		}
		buf.writeUtf(value, maxChars);
	}

	private static String readBoundedString(FriendlyByteBuf buf, int maxChars) {
		return buf.readUtf(maxChars);
	}

	private static EntryKind readEntryKind(FriendlyByteBuf buf) {
		int ordinal = buf.readVarInt();
		EntryKind[] values = EntryKind.values();
		if (ordinal < 0 || ordinal >= values.length) {
			throw new EditorSecurityException("Invalid EntryKind ordinal: " + ordinal);
		}
		return values[ordinal];
	}

	private static int readBoundedCount(FriendlyByteBuf buf, int maxCount, String label) {
		int count = buf.readVarInt();
		if (count < 0 || count > maxCount) {
			throw new EditorSecurityException("Count out of bounds for " + label + ": " + count + " (max " + maxCount + ")");
		}
		return count;
	}

	private static void requireVersion(FriendlyByteBuf buf) {
		byte version = buf.readByte();
		if (version != PROTOCOL_VERSION) {
			throw new EditorSecurityException("Unsupported Quill protocol version: " + version + " (expected " + PROTOCOL_VERSION + ")");
		}
	}

	private static void requireFullyConsumed(FriendlyByteBuf buf) {
		if (buf.isReadable()) {
			throw new EditorSecurityException("Unexpected trailing bytes in Quill RPC payload: " + buf.readableBytes());
		}
	}

	private static void validateMessageSize(byte[] payload) {
		if (payload == null || payload.length < 2) {
			throw new EditorSecurityException("Quill RPC payload is empty or truncated.");
		}
		if (payload.length > EditorLimits.MAX_MESSAGE_BYTES) {
			throw new EditorSecurityException("Quill RPC payload exceeds maximum size of " + EditorLimits.MAX_MESSAGE_BYTES + " bytes.");
		}
	}

	private static byte[] toBoundedBytes(ByteBuf raw) {
		int readable = raw.readableBytes();
		if (readable > EditorLimits.MAX_MESSAGE_BYTES) {
			throw new EditorSecurityException("Encoded Quill RPC payload exceeds maximum size of " + EditorLimits.MAX_MESSAGE_BYTES + " bytes.");
		}
		byte[] bytes = new byte[readable];
		raw.readBytes(bytes);
		return bytes;
	}

	private static String truncate(String text, int maxChars) {
		if (text == null) {
			return "";
		}
		return text.length() <= maxChars ? text : text.substring(0, maxChars);
	}
}
