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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuillProtocolCodecTest {

	@Test
	void roundTripsAllRequestVariants() {
		List<QuillRpcRequest> requests = List.of(
				new QuillRpcRequest.ListTree(),
				new QuillRpcRequest.ReadFile("pack/data/demo/function/hello.mcfunction"),
				new QuillRpcRequest.WriteFile("pack/data/demo/function/hello.mcfunction", "say Cześć Świecie! 🎉\n", "abc123", true),
				new QuillRpcRequest.CreateEntry("pack/data/demo/function/sub", EntryKind.DIRECTORY),
				new QuillRpcRequest.RenameEntry("pack/a.mcfunction", "pack/b.mcfunction"),
				new QuillRpcRequest.DeleteEntry("pack/b.mcfunction"),
				new QuillRpcRequest.CreateDatapack("my_pack", "Created with Quill"),
				new QuillRpcRequest.CreateNamespace("my_pack", "demo_ns"),
				new QuillRpcRequest.CreateFunction("my_pack", "demo_ns", "nested/run"),
				new QuillRpcRequest.ListPresets(),
				new QuillRpcRequest.ApplyPreset("my_pack", "minecraft:load", Map.of("namespace", "demo_ns", "extra", "val")),
				new QuillRpcRequest.ReloadDatapacks()
		);

		for (QuillRpcRequest request : requests) {
			byte[] encoded = QuillProtocolCodec.encodeRequest(request);
			QuillRpcRequest decoded = QuillProtocolCodec.decodeRequest(encoded);
			assertEquals(request, decoded);
		}
	}

	@Test
	void roundTripsAllResponseVariantsIncludingTreeReloadAndConflictError() {
		FileTreeNode tree = new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(
				new FileTreeNode("pack", "pack", EntryKind.DATAPACK, List.of(
						new FileTreeNode("pack.mcmeta", "pack/pack.mcmeta", EntryKind.FILE, List.of()),
						new FileTreeNode("data", "pack/data", EntryKind.DIRECTORY, List.of(
								new FileTreeNode("hello.mcfunction", "pack/data/hello.mcfunction", EntryKind.FILE, List.of())
						))
				))
		));
		FileDocument doc = new FileDocument("pack/data/demo/function/a.mcfunction", "say hi\n", "deadbeef", 1_700_000_000_000L);
		ReloadReport report = new ReloadReport(false, 42L, 3, List.of(
				new ReloadDiagnostic("pack/data/demo/function/a.mcfunction", 2, "Unknown command 'foo'")
		));

		List<QuillRpcResponse> responses = List.of(
				new QuillRpcResponse.TreeResult(tree),
				new QuillRpcResponse.DocumentResult(doc),
				new QuillRpcResponse.SaveOpResult(new SaveResult(doc, report)),
				new QuillRpcResponse.SaveOpResult(new SaveResult(doc, null)),
				new QuillRpcResponse.VoidResult(),
				new QuillRpcResponse.StringResult("pack/data/demo/function/a.mcfunction"),
				new QuillRpcResponse.PresetsResult(List.of(
						new PresetDescriptor("minecraft:load", "Load Hook", "Runs on reload", "data/${namespace}/function/load.mcfunction", Map.of("namespace", "demo"))
				)),
				new QuillRpcResponse.ReloadOpResult(report),
				QuillRpcResponse.ErrorResult.simple(QuillRpcResponse.ErrorCode.PERMISSION_DENIED, "Denied"),
				QuillRpcResponse.ErrorResult.conflict("pack/a.mcfunction", "aaa111", "bbb222", "Conflict!")
		);

		for (QuillRpcResponse response : responses) {
			byte[] encoded = QuillProtocolCodec.encodeResponse(response);
			QuillRpcResponse decoded = QuillProtocolCodec.decodeResponse(encoded);
			assertEquals(response, decoded);
		}
	}

	@Test
	void rejectsProtocolMismatchOversizedContentAndTrailingBytes() {
		byte[] valid = QuillProtocolCodec.encodeRequest(new QuillRpcRequest.ListTree());
		byte[] wrongVersion = valid.clone();
		wrongVersion[0] = 99;
		assertThrows(EditorSecurityException.class, () -> QuillProtocolCodec.decodeRequest(wrongVersion));

		byte[] withTrailing = new byte[valid.length + 1];
		System.arraycopy(valid, 0, withTrailing, 0, valid.length);
		withTrailing[withTrailing.length - 1] = 0x7F;
		assertThrows(EditorSecurityException.class, () -> QuillProtocolCodec.decodeRequest(withTrailing));

		String oversized = "x".repeat(EditorLimits.MAX_FILE_SIZE_BYTES + 1);
		assertThrows(EditorSecurityException.class, () ->
				QuillProtocolCodec.encodeRequest(new QuillRpcRequest.WriteFile("pack/a.mcfunction", oversized, "", false))
		);
	}
}
