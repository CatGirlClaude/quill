package dev.catgirlclaude.quill.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.security.PathSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetRegistryTest {

	@TempDir
	Path datapacksRoot;

	@Test
	void loadsDefaultLoadAndTickPresets() {
		PresetRegistry registry = PresetRegistry.loadDefault();
		List<PresetDescriptor> presets = registry.listPresets();
		assertEquals(2, presets.size());
		assertEquals("minecraft:load", presets.get(0).id());
		assertEquals("minecraft:tick", presets.get(1).id());
	}

	@Test
	void appliesAndMergesLoadAndTickPresetsWithoutOverwritingExistingHooks() throws IOException {
		PathSandbox sandbox = new PathSandbox(datapacksRoot);
		DatapackScaffolder.createDatapack(sandbox, "my_pack", "Test");
		PresetRegistry registry = PresetRegistry.loadDefault();

		FileDocument loadDoc = registry.applyPreset(sandbox, "my_pack", "minecraft:load", Map.of("namespace", "alpha"));
		assertEquals("my_pack/data/alpha/function/load.mcfunction", loadDoc.relativePath());
		assertTrue(loadDoc.content().contains("alpha:load"));

		Path loadTag = datapacksRoot.resolve("my_pack/data/minecraft/tags/function/load.json");
		assertTrue(Files.isRegularFile(loadTag));

		// Apply second namespace to the same load.json and verify merge without duplicates
		registry.applyPreset(sandbox, "my_pack", "minecraft:load", Map.of("namespace", "beta"));
		registry.applyPreset(sandbox, "my_pack", "minecraft:load", Map.of("namespace", "beta"));

		JsonObject tagObj = JsonParser.parseString(Files.readString(loadTag)).getAsJsonObject();
		JsonArray values = tagObj.getAsJsonArray("values");
		assertEquals(2, values.size());
		assertEquals("alpha:load", values.get(0).getAsString());
		assertEquals("beta:load", values.get(1).getAsString());

		// Existing function file must not be overwritten on re-apply
		Path alphaLoadFn = datapacksRoot.resolve(loadDoc.relativePath());
		Files.writeString(alphaLoadFn, "say custom command\n");
		FileDocument reapplied = registry.applyPreset(sandbox, "my_pack", "minecraft:load", Map.of("namespace", "alpha"));
		assertEquals("say custom command\n", reapplied.content());

		// Verify tick preset uses singular paths
		FileDocument tickDoc = registry.applyPreset(sandbox, "my_pack", "minecraft:tick", Map.of("namespace", "alpha"));
		assertEquals("my_pack/data/alpha/function/tick.mcfunction", tickDoc.relativePath());
		assertTrue(Files.isRegularFile(datapacksRoot.resolve("my_pack/data/minecraft/tags/function/tick.json")));
	}
}
