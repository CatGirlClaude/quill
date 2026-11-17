package dev.catgirlclaude.quill.preset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackScaffolderTest {

	@TempDir
	Path datapacksRoot;

	@Test
	void createsMinecraft263PackMcmetaWithMinAndMaxFormat121() throws IOException {
		PathSandbox sandbox = new PathSandbox(datapacksRoot);
		String packPath = DatapackScaffolder.createDatapack(sandbox, "my_pack", "Quill Test Pack");
		assertEquals("my_pack", packPath);

		Path mcmeta = datapacksRoot.resolve("my_pack/pack.mcmeta");
		assertTrue(Files.isRegularFile(mcmeta));
		assertTrue(Files.isDirectory(datapacksRoot.resolve("my_pack/data")));

		JsonObject root = JsonParser.parseString(Files.readString(mcmeta)).getAsJsonObject();
		JsonObject pack = root.getAsJsonObject("pack");
		assertEquals("Quill Test Pack", pack.get("description").getAsString());
		assertEquals(121, pack.get("min_format").getAsInt());
		assertEquals(121, pack.get("max_format").getAsInt());
		assertFalse(pack.has("supported_formats"));
	}

	@Test
	void scaffoldsNamespaceAndFunctionWithSingularDirectoryAndValidation() throws IOException {
		PathSandbox sandbox = new PathSandbox(datapacksRoot);
		DatapackScaffolder.createDatapack(sandbox, "demo_pack", "");

		String nsDir = DatapackScaffolder.createNamespace(sandbox, "demo_pack", "custom_ns");
		assertEquals("demo_pack/data/custom_ns/function", nsDir);
		assertTrue(Files.isDirectory(datapacksRoot.resolve(nsDir)));

		String fnFile = DatapackScaffolder.createFunction(sandbox, "demo_pack", "custom_ns", "sub/hello.mcfunction");
		assertEquals("demo_pack/data/custom_ns/function/sub/hello.mcfunction", fnFile);
		assertTrue(Files.readString(datapacksRoot.resolve(fnFile)).contains("# custom_ns:sub/hello"));

		assertThrows(EditorSecurityException.class, () ->
				DatapackScaffolder.createNamespace(sandbox, "demo_pack", "Invalid_Upper")
		);
		assertThrows(EditorSecurityException.class, () ->
				DatapackScaffolder.createFunction(sandbox, "demo_pack", "custom_ns", "Bad Name")
		);
	}
}
