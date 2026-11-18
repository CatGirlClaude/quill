package dev.catgirlclaude.quill.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.catgirlclaude.quill.security.EditorSecurityException;
import dev.catgirlclaude.quill.security.PathSandbox;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Scaffolds Minecraft 26.3-compliant datapack folders, {@code pack.mcmeta}, namespaces,
 * and {@code .mcfunction} files inside a {@link PathSandbox}.
 */
public final class DatapackScaffolder {
	public static final int DATA_PACK_FORMAT_MAJOR = 121;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private DatapackScaffolder() {
	}

	/**
	 * Creates {@code <packName>/pack.mcmeta} and {@code <packName>/data/}.
	 *
	 * @return Relative path of the created datapack directory ({@code <packName>})
	 */
	public static String createDatapack(PathSandbox sandbox, String packName, String description) {
		Objects.requireNonNull(sandbox, "sandbox");
		String cleanPackName = validatePackName(packName);
		String cleanDescription = (description == null || description.isBlank())
				? "Created with Quill"
				: description.trim();

		Path packDir = sandbox.resolveEntryForCreate(cleanPackName, dev.catgirlclaude.quill.api.EntryKind.DATAPACK);
		Path mcmetaPath = sandbox.resolveWritableFile(cleanPackName + "/pack.mcmeta");
		Path dataDir = packDir.resolve("data");

		JsonObject packObj = new JsonObject();
		packObj.addProperty("description", cleanDescription);
		packObj.addProperty("min_format", DATA_PACK_FORMAT_MAJOR);
		packObj.addProperty("max_format", DATA_PACK_FORMAT_MAJOR);

		JsonObject rootObj = new JsonObject();
		rootObj.add("pack", packObj);

		try {
			sandbox.ensureDirectoryTree(dataDir);
			sandbox.atomicWriteUtf8(mcmetaPath, GSON.toJson(rootObj) + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to scaffold datapack: " + cleanPackName, e);
		}
		return cleanPackName;
	}

	/**
	 * Creates {@code <packName>/data/<namespace>/function/}.
	 *
	 * @return Relative path of the function directory ({@code <packName>/data/<namespace>/function})
	 */
	public static String createNamespace(PathSandbox sandbox, String packName, String namespace) {
		Objects.requireNonNull(sandbox, "sandbox");
		String cleanPackName = requireExistingPack(sandbox, packName);
		String cleanNamespace = validateNamespace(namespace);

		String relativeFunctionDir = cleanPackName + "/data/" + cleanNamespace + "/function";
		Path target = sandbox.resolveEntryForCreate(relativeFunctionDir, dev.catgirlclaude.quill.api.EntryKind.DIRECTORY);
		try {
			sandbox.ensureDirectoryTree(target);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create namespace directory: " + relativeFunctionDir, e);
		}
		return relativeFunctionDir;
	}

	/**
	 * Creates {@code <packName>/data/<namespace>/function/<functionPath>.mcfunction}.
	 *
	 * @return Relative path of the created {@code .mcfunction} file
	 */
	public static String createFunction(PathSandbox sandbox, String packName, String namespace, String functionPath) {
		Objects.requireNonNull(sandbox, "sandbox");
		String cleanPackName = requireExistingPack(sandbox, packName);
		String cleanNamespace = validateNamespace(namespace);
		String cleanFnPath = validateFunctionPath(functionPath);

		String relativeFile = cleanPackName + "/data/" + cleanNamespace + "/function/" + cleanFnPath + ".mcfunction";
		Path target = sandbox.resolveEntryForCreate(relativeFile, dev.catgirlclaude.quill.api.EntryKind.FILE);
		String starter = "# " + cleanNamespace + ":" + cleanFnPath + "\n";

		try {
			sandbox.atomicWriteUtf8(target, starter);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create function: " + relativeFile, e);
		}
		return relativeFile;
	}

	public static String validatePackName(String packName) {
		if (packName == null || packName.isBlank()) {
			throw new EditorSecurityException("Datapack name must not be empty.");
		}
		String trimmed = packName.trim();
		PathSandbox.validatePackOrFolderName(trimmed);
		return trimmed;
	}

	public static String validateNamespace(String namespace) {
		if (namespace == null || namespace.isBlank()) {
			throw new EditorSecurityException("Namespace must not be empty.");
		}
		String trimmed = namespace.trim();
		if (!Identifier.isValidNamespace(trimmed)) {
			throw new EditorSecurityException("Invalid namespace '" + trimmed + "'. Allowed characters: [a-z0-9_.-].");
		}
		return trimmed;
	}

	public static String validateFunctionPath(String rawFunctionPath) {
		if (rawFunctionPath == null || rawFunctionPath.isBlank()) {
			throw new EditorSecurityException("Function path must not be empty.");
		}
		String trimmed = rawFunctionPath.trim();
		if (trimmed.endsWith(".mcfunction")) {
			trimmed = trimmed.substring(0, trimmed.length() - ".mcfunction".length());
		}
		if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.endsWith("/")
				|| trimmed.contains("//") || trimmed.contains("..") || !Identifier.isValidPath(trimmed)) {
			throw new EditorSecurityException("Invalid function path '" + rawFunctionPath + "'. Allowed characters: [a-z0-9/._-].");
		}
		return trimmed;
	}

	/**
	 * Validates a relative resource path (e.g., {@code chests/bonus} or {@code custom_shovel})
	 * used by registry JSON presets, stripping any trailing {@code .json} suffix.
	 */
	public static String validateResourcePath(String rawPath) {
		if (rawPath == null || rawPath.isBlank()) {
			throw new EditorSecurityException("Resource path must not be empty.");
		}
		String trimmed = rawPath.trim();
		if (trimmed.endsWith(".json")) {
			trimmed = trimmed.substring(0, trimmed.length() - ".json".length());
		}
		if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.endsWith("/")
				|| trimmed.contains("//") || trimmed.contains("..") || !Identifier.isValidPath(trimmed)) {
			throw new EditorSecurityException("Invalid resource path '" + rawPath + "'. Allowed characters: [a-z0-9/._-].");
		}
		return trimmed;
	}

	/**
	 * Extracts the Minecraft function ID ({@code <namespace>:<path>}) from a datapack-relative path
	 * like {@code my_pack/data/demo/function/sub/hello.mcfunction}, or returns {@code null} if not a valid function path.
	 */
	public static String inferFunctionId(String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			return null;
		}
		String normalized = relativePath.replace('\\', '/');
		if (!normalized.endsWith(".mcfunction")) {
			return null;
		}
		String[] parts = normalized.split("/");
		// Expected: <pack>/data/<namespace>/function/<subpath...>.mcfunction
		if (parts.length < 5 || !"data".equals(parts[1]) || !"function".equals(parts[3])) {
			return null;
		}
		String namespace = parts[2];
		if (!Identifier.isValidNamespace(namespace)) {
			return null;
		}
		StringBuilder fnPath = new StringBuilder();
		for (int i = 4; i < parts.length; i++) {
			if (i > 4) {
				fnPath.append('/');
			}
			fnPath.append(parts[i]);
		}
		String withoutExt = fnPath.substring(0, fnPath.length() - ".mcfunction".length());
		if (withoutExt.isEmpty() || !Identifier.isValidPath(withoutExt)) {
			return null;
		}
		return namespace + ":" + withoutExt;
	}

	static String requireExistingPack(PathSandbox sandbox, String packName) {
		String cleanPackName = validatePackName(packName);
		Path packDir = sandbox.resolveExistingEntryForModify(cleanPackName);
		if (!Files.isDirectory(packDir, LinkOption.NOFOLLOW_LINKS)) {
			throw new EditorSecurityException("Target datapack is not a directory: " + cleanPackName);
		}
		return cleanPackName;
	}
}
