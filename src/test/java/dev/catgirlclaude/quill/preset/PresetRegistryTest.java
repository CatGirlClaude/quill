package dev.catgirlclaude.quill.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetRegistryTest {

	@TempDir
	Path datapacksRoot;

	@Test
	void loadsFunctionTagAndRegistryJsonPresets() {
		PresetRegistry registry = PresetRegistry.loadDefault();
		List<PresetDescriptor> presets = registry.listPresets();
		assertEquals(7, presets.size());
		assertEquals("minecraft:load", presets.get(0).id());
		assertEquals("minecraft:tick", presets.get(1).id());
		assertTrue(presets.stream().anyMatch(p -> p.id().equals("registry:recipe_shaped")));
		assertTrue(presets.stream().anyMatch(p -> p.id().equals("registry:recipe_shapeless")));
		assertTrue(presets.stream().anyMatch(p -> p.id().equals("registry:loot_table")));
		assertTrue(presets.stream().anyMatch(p -> p.id().equals("registry:advancement")));
		assertTrue(presets.stream().anyMatch(p -> p.id().equals("registry:predicate")));
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

	@Test
	void appliesMinecraft263RegistryJsonTemplatesInSingularDirectories() {
		PathSandbox sandbox = new PathSandbox(datapacksRoot);
		DatapackScaffolder.createDatapack(sandbox, "my_pack", "Registry Test");
		PresetRegistry registry = PresetRegistry.loadDefault();

		FileDocument shaped = registry.applyPreset(sandbox, "my_pack", "registry:recipe_shaped",
				Map.of("namespace", "demo", "name", "diamond_spade.json"));
		assertEquals("my_pack/data/demo/recipe/diamond_spade.json", shaped.relativePath());
		assertEquals("minecraft:crafting_shaped", JsonParser.parseString(shaped.content()).getAsJsonObject().get("type").getAsString());

		FileDocument shapeless = registry.applyPreset(sandbox, "my_pack", "registry:recipe_shapeless",
				Map.of("namespace", "demo", "name", "fast_torch"));
		assertEquals("my_pack/data/demo/recipe/fast_torch.json", shapeless.relativePath());
		assertEquals("minecraft:crafting_shapeless", JsonParser.parseString(shapeless.content()).getAsJsonObject().get("type").getAsString());

		FileDocument loot = registry.applyPreset(sandbox, "my_pack", "registry:loot_table",
				Map.of("namespace", "demo", "name", "chests/starter"));
		assertEquals("my_pack/data/demo/loot_table/chests/starter.json", loot.relativePath());
		JsonObject lootObj = JsonParser.parseString(loot.content()).getAsJsonObject();
		assertEquals("demo:chests/starter", lootObj.get("random_sequence").getAsString());

		FileDocument adv = registry.applyPreset(sandbox, "my_pack", "registry:advancement",
				Map.of("namespace", "demo", "name", "story/start"));
		assertEquals("my_pack/data/demo/advancement/story/start.json", adv.relativePath());
		assertTrue(JsonParser.parseString(adv.content()).getAsJsonObject().has("criteria"));

		FileDocument pred = registry.applyPreset(sandbox, "my_pack", "registry:predicate",
				Map.of("namespace", "demo", "name", "sneaking"));
		assertEquals("my_pack/data/demo/predicate/sneaking.json", pred.relativePath());
		JsonObject predObj = JsonParser.parseString(pred.content()).getAsJsonObject();
		assertEquals("minecraft:entity_properties", predObj.get("type").getAsString());
		assertTrue(predObj.getAsJsonObject("predicate").has("minecraft:flags"));

		assertThrows(EditorSecurityException.class, () ->
				registry.applyPreset(sandbox, "my_pack", "registry:predicate", Map.of("namespace", "demo", "name", "../escape")));
	}

	@Test
	void infersFunctionIdFromDatapackRelativePath() {
		assertEquals("demo:load", DatapackScaffolder.inferFunctionId("my_pack/data/demo/function/load.mcfunction"));
		assertEquals("demo:sub/nested/init", DatapackScaffolder.inferFunctionId("my_pack/data/demo/function/sub/nested/init.mcfunction"));
		assertNull(DatapackScaffolder.inferFunctionId("my_pack/pack.mcmeta"));
		assertNull(DatapackScaffolder.inferFunctionId("my_pack/data/demo/recipe/test.json"));
		assertNull(DatapackScaffolder.inferFunctionId("my_pack/data/demo/functions/legacy.mcfunction"));
	}
}
