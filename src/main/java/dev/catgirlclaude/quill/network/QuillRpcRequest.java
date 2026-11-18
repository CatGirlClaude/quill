package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.api.EntryKind;

import java.util.Map;
import java.util.Objects;

/**
 * Sealed hierarchy of all C2S RPC requests corresponding to {@link dev.catgirlclaude.quill.api.EditorBackend}.
 */
public sealed interface QuillRpcRequest permits
		QuillRpcRequest.ListTree,
		QuillRpcRequest.ReadFile,
		QuillRpcRequest.WriteFile,
		QuillRpcRequest.CreateEntry,
		QuillRpcRequest.RenameEntry,
		QuillRpcRequest.DeleteEntry,
		QuillRpcRequest.CreateDatapack,
		QuillRpcRequest.CreateNamespace,
		QuillRpcRequest.CreateFunction,
		QuillRpcRequest.ListPresets,
		QuillRpcRequest.ApplyPreset,
		QuillRpcRequest.ReloadDatapacks {

	enum OpCode {
		LIST_TREE(1),
		READ_FILE(2),
		WRITE_FILE(3),
		CREATE_ENTRY(4),
		RENAME_ENTRY(5),
		DELETE_ENTRY(6),
		CREATE_DATAPACK(7),
		CREATE_NAMESPACE(8),
		CREATE_FUNCTION(9),
		LIST_PRESETS(10),
		APPLY_PRESET(11),
		RELOAD_DATAPACKS(12);

		private final byte id;

		OpCode(int id) {
			this.id = (byte) id;
		}

		public byte id() {
			return id;
		}

		public static OpCode fromId(byte id) {
			for (OpCode value : values()) {
				if (value.id == id) {
					return value;
				}
			}
			throw new IllegalArgumentException("Unknown Quill RPC request opcode: " + id);
		}
	}

	OpCode opCode();

	default boolean requiresReloadCooldown() {
		return false;
	}

	record ListTree() implements QuillRpcRequest {
		@Override
		public OpCode opCode() {
			return OpCode.LIST_TREE;
		}
	}

	record ReadFile(String relativePath) implements QuillRpcRequest {
		public ReadFile {
			Objects.requireNonNull(relativePath, "relativePath");
		}

		@Override
		public OpCode opCode() {
			return OpCode.READ_FILE;
		}
	}

	record WriteFile(
			String relativePath,
			String content,
			String expectedBaseSha256,
			boolean reloadAfterSave
	) implements QuillRpcRequest {
		public WriteFile {
			Objects.requireNonNull(relativePath, "relativePath");
			Objects.requireNonNull(content, "content");
			expectedBaseSha256 = expectedBaseSha256 == null ? "" : expectedBaseSha256;
		}

		@Override
		public OpCode opCode() {
			return OpCode.WRITE_FILE;
		}

		@Override
		public boolean requiresReloadCooldown() {
			return reloadAfterSave;
		}
	}

	record CreateEntry(String relativePath, EntryKind kind) implements QuillRpcRequest {
		public CreateEntry {
			Objects.requireNonNull(relativePath, "relativePath");
			Objects.requireNonNull(kind, "kind");
		}

		@Override
		public OpCode opCode() {
			return OpCode.CREATE_ENTRY;
		}
	}

	record RenameEntry(String sourceRelativePath, String targetRelativePath) implements QuillRpcRequest {
		public RenameEntry {
			Objects.requireNonNull(sourceRelativePath, "sourceRelativePath");
			Objects.requireNonNull(targetRelativePath, "targetRelativePath");
		}

		@Override
		public OpCode opCode() {
			return OpCode.RENAME_ENTRY;
		}
	}

	record DeleteEntry(String relativePath) implements QuillRpcRequest {
		public DeleteEntry {
			Objects.requireNonNull(relativePath, "relativePath");
		}

		@Override
		public OpCode opCode() {
			return OpCode.DELETE_ENTRY;
		}
	}

	record CreateDatapack(String packName, String description) implements QuillRpcRequest {
		public CreateDatapack {
			Objects.requireNonNull(packName, "packName");
			description = description == null ? "" : description;
		}

		@Override
		public OpCode opCode() {
			return OpCode.CREATE_DATAPACK;
		}
	}

	record CreateNamespace(String packName, String namespace) implements QuillRpcRequest {
		public CreateNamespace {
			Objects.requireNonNull(packName, "packName");
			Objects.requireNonNull(namespace, "namespace");
		}

		@Override
		public OpCode opCode() {
			return OpCode.CREATE_NAMESPACE;
		}
	}

	record CreateFunction(String packName, String namespace, String functionPath) implements QuillRpcRequest {
		public CreateFunction {
			Objects.requireNonNull(packName, "packName");
			Objects.requireNonNull(namespace, "namespace");
			Objects.requireNonNull(functionPath, "functionPath");
		}

		@Override
		public OpCode opCode() {
			return OpCode.CREATE_FUNCTION;
		}
	}

	record ListPresets() implements QuillRpcRequest {
		@Override
		public OpCode opCode() {
			return OpCode.LIST_PRESETS;
		}
	}

	record ApplyPreset(String packName, String presetId, Map<String, String> variables) implements QuillRpcRequest {
		public ApplyPreset {
			Objects.requireNonNull(packName, "packName");
			Objects.requireNonNull(presetId, "presetId");
			variables = variables == null ? Map.of() : Map.copyOf(variables);
		}

		@Override
		public OpCode opCode() {
			return OpCode.APPLY_PRESET;
		}
	}

	record ReloadDatapacks() implements QuillRpcRequest {
		@Override
		public OpCode opCode() {
			return OpCode.RELOAD_DATAPACKS;
		}

		@Override
		public boolean requiresReloadCooldown() {
			return true;
		}
	}
}
