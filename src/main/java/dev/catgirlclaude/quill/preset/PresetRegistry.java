package dev.catgirlclaude.quill.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.backend.ServerEditorBackend;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads resource-driven datapack presets from {@code assets/quill/presets/index.json}
 * and applies them idempotently (merging tag {@code values} arrays without overwriting existing hooks).
 */
public final class PresetRegistry {
	private static final String RESOURCE_BASE = "/assets/quill/presets/";
	private static final String INDEX_PATH = RESOURCE_BASE + "index.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private record PresetEntry(
			PresetDescriptor descriptor,
			String tagRelativePath,
			String tagTemplate,
			String primaryTemplate,
			String functionIdPattern
	) {
		boolean hasTagMerge() {
			return tagRelativePath != null && !tagRelativePath.isBlank()
					&& tagTemplate != null && !tagTemplate.isBlank()
					&& functionIdPattern != null && !functionIdPattern.isBlank();
		}
	}

	private final Map<String, PresetEntry> presetsById;

	private PresetRegistry(Map<String, PresetEntry> presetsById) {
		this.presetsById = presetsById;
	}

	public static PresetRegistry loadDefault() {
		String rawIndex = readResourceText(INDEX_PATH);
		JsonObject root = JsonParser.parseString(rawIndex).getAsJsonObject();
		JsonArray array = root.getAsJsonArray("presets");
		if (array == null) {
			throw new IllegalStateException("Preset index missing 'presets' array: " + INDEX_PATH);
		}

		Map<String, PresetEntry> loaded = new LinkedHashMap<>();
		for (JsonElement element : array) {
			JsonObject obj = element.getAsJsonObject();
			String id = obj.get("id").getAsString();
			String displayName = obj.get("displayName").getAsString();
			String description = obj.get("description").getAsString();
			String targetPattern = obj.get("targetRelativePathPattern").getAsString();
			String tagRelativePath = optionalString(obj, "tagRelativePath");
			String tagTemplate = optionalString(obj, "tagTemplate");
			String primaryTemplate = obj.has("template")
					? obj.get("template").getAsString()
					: obj.get("functionTemplate").getAsString();
			String functionIdPattern = optionalString(obj, "functionIdPattern");

			Map<String, String> defaults = new LinkedHashMap<>();
			if (obj.has("defaultVariables") && obj.get("defaultVariables").isJsonObject()) {
				for (var entry : obj.getAsJsonObject("defaultVariables").entrySet()) {
					defaults.put(entry.getKey(), entry.getValue().getAsString());
				}
			}

			PresetDescriptor descriptor = new PresetDescriptor(
					id,
					displayName,
					description,
					targetPattern,
					defaults
			);
			loaded.put(id, new PresetEntry(descriptor, tagRelativePath, tagTemplate, primaryTemplate, functionIdPattern));
		}
		return new PresetRegistry(Collections.unmodifiableMap(loaded));
	}

	private static String optionalString(JsonObject obj, String field) {
		return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : null;
	}

	public List<PresetDescriptor> listPresets() {
		return presetsById.values().stream()
				.map(PresetEntry::descriptor)
				.toList();
	}

	public FileDocument applyPreset(
			PathSandbox sandbox,
			String packName,
			String presetId,
			Map<String, String> providedVariables
	) {
		Objects.requireNonNull(sandbox, "sandbox");
		String cleanPackName = DatapackScaffolder.requireExistingPack(sandbox, packName);
		PresetEntry entry = presetsById.get(presetId);
		if (entry == null) {
			throw new EditorSecurityException("Unknown preset id: " + presetId);
		}

		Map<String, String> vars = new LinkedHashMap<>(entry.descriptor().defaultVariables());
		if (providedVariables != null) {
			vars.putAll(providedVariables);
		}
		if (vars.containsKey("namespace")) {
			String cleanNamespace = DatapackScaffolder.validateNamespace(vars.get("namespace"));
			vars.put("namespace", cleanNamespace);
		}
		if (vars.containsKey("name")) {
			String cleanName = DatapackScaffolder.validateResourcePath(vars.get("name"));
			vars.put("name", cleanName);
		}
		if (vars.containsKey("functionName")) {
			String cleanFnName = DatapackScaffolder.validateFunctionPath(vars.get("functionName"));
			vars.put("functionName", cleanFnName);
		}

		String targetRelWithinPack = substitute(entry.descriptor().targetRelativePathPattern(), vars);
		String fullTargetRelPath = cleanPackName + "/" + targetRelWithinPack;
		Path primaryTarget = sandbox.resolveWritableFile(fullTargetRelPath);

		try {
			if (!Files.exists(primaryTarget, LinkOption.NOFOLLOW_LINKS)) {
				String templateText = readResourceText(RESOURCE_BASE + entry.primaryTemplate());
				sandbox.atomicWriteUtf8(primaryTarget, substitute(templateText, vars));
			}

			if (entry.hasTagMerge()) {
				String tagRelWithinPack = substitute(entry.tagRelativePath(), vars);
				String fullTagRelPath = cleanPackName + "/" + tagRelWithinPack;
				Path tagTarget = sandbox.resolveWritableFile(fullTagRelPath);
				String functionId = substitute(entry.functionIdPattern(), vars);
				String updatedTagJson = mergeOrCreateFunctionTag(tagTarget, entry.tagTemplate(), functionId, vars);
				sandbox.atomicWriteUtf8(tagTarget, updatedTagJson);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to apply preset '" + presetId + "' to pack '" + cleanPackName + "'", e);
		}

		return ServerEditorBackend.readDocumentSync(sandbox, fullTargetRelPath);
	}

	private static String mergeOrCreateFunctionTag(
			Path tagTarget,
			String tagTemplateName,
			String functionId,
			Map<String, String> vars
	) throws IOException {
		if (!Files.exists(tagTarget, LinkOption.NOFOLLOW_LINKS)) {
			String template = readResourceText(RESOURCE_BASE + tagTemplateName);
			return substitute(template, vars);
		}

		String existingRaw = Files.readString(tagTarget, StandardCharsets.UTF_8);
		JsonElement parsed = JsonParser.parseString(existingRaw);
		if (!parsed.isJsonObject()) {
			throw new EditorSecurityException("Existing tag JSON is not an object: " + tagTarget.getFileName());
		}

		JsonObject obj = parsed.getAsJsonObject();
		if (!obj.has("replace")) {
			obj.addProperty("replace", false);
		}
		JsonArray values = obj.has("values") && obj.get("values").isJsonArray()
				? obj.getAsJsonArray("values")
				: new JsonArray();
		obj.add("values", values);

		boolean alreadyPresent = false;
		for (JsonElement item : values) {
			if (item.isJsonPrimitive() && functionId.equals(item.getAsString())) {
				alreadyPresent = true;
				break;
			}
			if (item.isJsonObject() && item.getAsJsonObject().has("id")
					&& functionId.equals(item.getAsJsonObject().get("id").getAsString())) {
				alreadyPresent = true;
				break;
			}
		}
		if (!alreadyPresent) {
			values.add(functionId);
		}

		return GSON.toJson(obj) + "\n";
	}

	private static String substitute(String template, Map<String, String> variables) {
		String result = template;
		for (var entry : variables.entrySet()) {
			result = result.replace("${" + entry.getKey() + "}", entry.getValue());
		}
		return result;
	}

	private static String readResourceText(String resourcePath) {
		try (InputStream stream = PresetRegistry.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new IllegalStateException("Missing bundled preset resource: " + resourcePath);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read preset resource: " + resourcePath, e);
		}
	}
}
