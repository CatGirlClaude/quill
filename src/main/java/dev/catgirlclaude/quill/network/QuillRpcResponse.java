package dev.catgirlclaude.quill.network;

import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;

import java.util.List;
import java.util.Objects;

/**
 * Sealed hierarchy of all S2C RPC responses returned by the server for a correlated {@code requestId}.
 */
public sealed interface QuillRpcResponse permits
		QuillRpcResponse.TreeResult,
		QuillRpcResponse.DocumentResult,
		QuillRpcResponse.SaveOpResult,
		QuillRpcResponse.VoidResult,
		QuillRpcResponse.StringResult,
		QuillRpcResponse.PresetsResult,
		QuillRpcResponse.ReloadOpResult,
		QuillRpcResponse.ErrorResult {

	enum Kind {
		TREE(1),
		DOCUMENT(2),
		SAVE(3),
		VOID(4),
		STRING(5),
		PRESETS(6),
		RELOAD(7),
		ERROR(127);

		private final byte id;

		Kind(int id) {
			this.id = (byte) id;
		}

		public byte id() {
			return id;
		}

		public static Kind fromId(byte id) {
			for (Kind value : values()) {
				if (value.id == id) {
					return value;
				}
			}
			throw new IllegalArgumentException("Unknown Quill RPC response kind: " + id);
		}
	}

	enum ErrorCode {
		PERMISSION_DENIED(1),
		SECURITY_VIOLATION(2),
		RATE_LIMITED(3),
		REVISION_CONFLICT(4),
		INTERNAL_ERROR(5);

		private final byte id;

		ErrorCode(int id) {
			this.id = (byte) id;
		}

		public byte id() {
			return id;
		}

		public static ErrorCode fromId(byte id) {
			for (ErrorCode value : values()) {
				if (value.id == id) {
					return value;
				}
			}
			throw new IllegalArgumentException("Unknown Quill RPC error code: " + id);
		}
	}

	Kind kind();

	record TreeResult(FileTreeNode root) implements QuillRpcResponse {
		public TreeResult {
			Objects.requireNonNull(root, "root");
		}

		@Override
		public Kind kind() {
			return Kind.TREE;
		}
	}

	record DocumentResult(FileDocument document) implements QuillRpcResponse {
		public DocumentResult {
			Objects.requireNonNull(document, "document");
		}

		@Override
		public Kind kind() {
			return Kind.DOCUMENT;
		}
	}

	record SaveOpResult(SaveResult result) implements QuillRpcResponse {
		public SaveOpResult {
			Objects.requireNonNull(result, "result");
		}

		@Override
		public Kind kind() {
			return Kind.SAVE;
		}
	}

	record VoidResult() implements QuillRpcResponse {
		@Override
		public Kind kind() {
			return Kind.VOID;
		}
	}

	record StringResult(String value) implements QuillRpcResponse {
		public StringResult {
			Objects.requireNonNull(value, "value");
		}

		@Override
		public Kind kind() {
			return Kind.STRING;
		}
	}

	record PresetsResult(List<PresetDescriptor> presets) implements QuillRpcResponse {
		public PresetsResult {
			presets = presets == null ? List.of() : List.copyOf(presets);
		}

		@Override
		public Kind kind() {
			return Kind.PRESETS;
		}
	}

	record ReloadOpResult(ReloadReport report) implements QuillRpcResponse {
		public ReloadOpResult {
			Objects.requireNonNull(report, "report");
		}

		@Override
		public Kind kind() {
			return Kind.RELOAD;
		}
	}

	record ErrorResult(
			ErrorCode code,
			String message,
			String conflictPath,
			String expectedSha256,
			String actualSha256
	) implements QuillRpcResponse {
		public ErrorResult {
			Objects.requireNonNull(code, "code");
			message = message == null ? "" : message;
			conflictPath = conflictPath == null ? "" : conflictPath;
			expectedSha256 = expectedSha256 == null ? "" : expectedSha256;
			actualSha256 = actualSha256 == null ? "" : actualSha256;
		}

		public static ErrorResult simple(ErrorCode code, String message) {
			return new ErrorResult(code, message, "", "", "");
		}

		public static ErrorResult conflict(String relativePath, String expectedSha256, String actualSha256, String message) {
			return new ErrorResult(ErrorCode.REVISION_CONFLICT, message, relativePath, expectedSha256, actualSha256);
		}

		@Override
		public Kind kind() {
			return Kind.ERROR;
		}
	}
}
