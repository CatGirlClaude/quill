package dev.catgirlclaude.quill.reload;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import dev.catgirlclaude.quill.Quill;
import dev.catgirlclaude.quill.api.ReloadDiagnostic;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.security.PathSandbox;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates safe datapack discovery, resource reload on the {@link MinecraftServer} thread,
 * and post-reload syntax diagnostics for {@code .mcfunction} and {@code .json}/{@code .mcmeta} files.
 */
public final class DatapackReloadCoordinator {
	private static final Pattern LINE_PATTERN = Pattern.compile("\\bline\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);

	private DatapackReloadCoordinator() {
	}

	public static CompletableFuture<ReloadReport> reload(MinecraftServer server, PathSandbox sandbox) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(sandbox, "sandbox");

		CompletableFuture<ReloadReport> resultFuture = new CompletableFuture<>();
		long startNanos = System.nanoTime();

		server.execute(() -> {
			try {
				PackRepository packRepository = server.getPackRepository();
				packRepository.reload();

				Collection<String> disabled = server.getWorldData()
						.getDataConfiguration()
						.dataPacks()
						.getDisabled();

				Set<String> selected = new LinkedHashSet<>(packRepository.getSelectedIds());
				for (String availableId : packRepository.getAvailableIds()) {
					if (!disabled.contains(availableId)) {
						selected.add(availableId);
					}
				}

				server.reloadResources(List.copyOf(selected)).whenComplete((ignored, throwable) -> {
					server.execute(() -> {
						long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
						List<ReloadDiagnostic> diagnostics = new ArrayList<>();
						boolean reloadFailed = false;

						if (throwable != null) {
							reloadFailed = true;
							Throwable rootCause = unwrap(throwable);
							diagnostics.add(new ReloadDiagnostic(
									"<reload>",
									0,
									rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.getClass().getSimpleName()
							));
							Quill.LOGGER.warn("Datapack reload failed", rootCause);
						}

						diagnostics.addAll(validateDatapacks(server, sandbox, packRepository.getSelectedIds()));
						int enabledCount = packRepository.getSelectedIds().size();
						boolean success = !reloadFailed && diagnostics.isEmpty();
						resultFuture.complete(new ReloadReport(success, durationMs, enabledCount, diagnostics));
					});
				});
			} catch (Throwable t) {
				long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
				Quill.LOGGER.error("Unexpected error starting datapack reload", t);
				resultFuture.complete(new ReloadReport(
						false,
						durationMs,
						0,
						List.of(new ReloadDiagnostic("<reload>", 0, t.getMessage() != null ? t.getMessage() : t.toString()))
				));
			}
		});

		return resultFuture;
	}

	/**
	 * Validates enabled directory datapacks in {@code <world>/datapacks/} so per-function
	 * parse errors (which Minecraft normally logs without failing the reload future) are
	 * surfaced directly in the IDE diagnostics banner with exact line numbers.
	 */
	public static List<ReloadDiagnostic> validateDatapacks(
			MinecraftServer server,
			PathSandbox sandbox,
			Collection<String> selectedPackIds
	) {
		Path root = sandbox.root();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			return List.of();
		}

		List<ReloadDiagnostic> diagnostics = new ArrayList<>();
		CommandDispatcher<CommandSourceStack> dispatcher = server.getFunctions().getDispatcher();
		CommandSourceStack compilationSource = server.createCommandSourceStack()
				.withPermission(LevelBasedPermissionSet.GAMEMASTER);

		try (var stream = Files.list(root)) {
			for (Path packDir : stream.toList()) {
				if (Files.isSymbolicLink(packDir) || !Files.isDirectory(packDir, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				String packName = packDir.getFileName().toString();
				String packId = "file/" + packName;
				if (!selectedPackIds.contains(packId)) {
					continue;
				}

				validatePackMetadata(packDir, sandbox, diagnostics);
				validatePackData(packDir, sandbox, dispatcher, compilationSource, diagnostics);
			}
		} catch (IOException e) {
			diagnostics.add(new ReloadDiagnostic("datapacks", 0, "Failed to scan datapacks: " + e.getMessage()));
		}

		return diagnostics;
	}

	private static void validatePackMetadata(Path packDir, PathSandbox sandbox, List<ReloadDiagnostic> diagnostics) {
		Path mcmeta = packDir.resolve("pack.mcmeta");
		if (!Files.isRegularFile(mcmeta, LinkOption.NOFOLLOW_LINKS)) {
			diagnostics.add(new ReloadDiagnostic(sandbox.relativize(packDir), 0, "Missing pack.mcmeta"));
			return;
		}
		String relative = sandbox.relativize(mcmeta);
		try {
			String raw = Files.readString(mcmeta, StandardCharsets.UTF_8);
			JsonElement parsed = JsonParser.parseString(raw);
			if (!parsed.isJsonObject()) {
				diagnostics.add(new ReloadDiagnostic(relative, 1, "pack.mcmeta root must be a JSON object."));
				return;
			}
			JsonObject rootObj = parsed.getAsJsonObject();
			if (!rootObj.has("pack") || !rootObj.get("pack").isJsonObject()) {
				diagnostics.add(new ReloadDiagnostic(relative, 1, "pack.mcmeta must contain a \"pack\" object."));
				return;
			}
			JsonObject packObj = rootObj.getAsJsonObject("pack");
			if (packObj.has("supported_formats")) {
				diagnostics.add(new ReloadDiagnostic(relative, 0, "\"supported_formats\" is rejected in Minecraft 26.3 when min_format >= 65; use min_format and max_format."));
			}
		} catch (Exception e) {
			diagnostics.add(new ReloadDiagnostic(relative, extractLineNumber(e.getMessage()), cleanMessage(e)));
		}
	}

	private static void validatePackData(
			Path packDir,
			PathSandbox sandbox,
			CommandDispatcher<CommandSourceStack> dispatcher,
			CommandSourceStack compilationSource,
			List<ReloadDiagnostic> diagnostics
	) {
		Path dataDir = packDir.resolve("data");
		if (!Files.isDirectory(dataDir, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var walk = Files.walk(dataDir)) {
			for (Path file : walk.toList()) {
				if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				String fileName = file.getFileName().toString();
				if (fileName.endsWith(".mcfunction")) {
					validateMcfunction(dataDir, file, sandbox, dispatcher, compilationSource, diagnostics);
				} else if (fileName.endsWith(".json")) {
					validateJsonFile(file, sandbox, diagnostics);
				}
			}
		} catch (IOException e) {
			diagnostics.add(new ReloadDiagnostic(sandbox.relativize(dataDir), 0, "Failed to validate data directory: " + e.getMessage()));
		}
	}

	private static void validateMcfunction(
			Path dataDir,
			Path file,
			PathSandbox sandbox,
			CommandDispatcher<CommandSourceStack> dispatcher,
			CommandSourceStack compilationSource,
			List<ReloadDiagnostic> diagnostics
	) {
		Path relFromData = dataDir.relativize(file);
		if (relFromData.getNameCount() < 3) {
			return;
		}
		String namespace = relFromData.getName(0).toString();
		String category = relFromData.getName(1).toString();
		if (!"function".equals(category)) {
			return;
		}

		StringBuilder pathBuilder = new StringBuilder();
		for (int i = 2; i < relFromData.getNameCount(); i++) {
			if (i > 2) {
				pathBuilder.append('/');
			}
			pathBuilder.append(relFromData.getName(i).toString());
		}
		String rawFnPath = pathBuilder.toString();
		if (!rawFnPath.endsWith(".mcfunction")) {
			return;
		}
		String fnPath = rawFnPath.substring(0, rawFnPath.length() - ".mcfunction".length());
		String relativePath = sandbox.relativize(file);

		Identifier id = Identifier.tryBuild(namespace, fnPath);
		if (id == null) {
			diagnostics.add(new ReloadDiagnostic(
					relativePath,
					0,
					"Invalid function identifier '" + namespace + ":" + fnPath + "' (lowercase [a-z0-9/._-] only)."
			));
			return;
		}

		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			CommandFunction.fromLines(id, dispatcher, compilationSource, lines);
		} catch (Exception e) {
			diagnostics.add(new ReloadDiagnostic(relativePath, extractLineNumber(e.getMessage()), cleanMessage(e)));
		}
	}

	private static void validateJsonFile(Path file, PathSandbox sandbox, List<ReloadDiagnostic> diagnostics) {
		String relative = sandbox.relativize(file);
		try {
			String raw = Files.readString(file, StandardCharsets.UTF_8);
			JsonParser.parseString(raw);
		} catch (Exception e) {
			diagnostics.add(new ReloadDiagnostic(relative, extractLineNumber(e.getMessage()), cleanMessage(e)));
		}
	}

	static int extractLineNumber(String message) {
		if (message == null || message.isBlank()) {
			return 0;
		}
		Matcher matcher = LINE_PATTERN.matcher(message);
		if (matcher.find()) {
			try {
				return Integer.parseInt(matcher.group(1));
			} catch (NumberFormatException ignored) {
				return 0;
			}
		}
		return 0;
	}

	private static String cleanMessage(Throwable t) {
		String msg = t.getMessage();
		return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
	}

	private static Throwable unwrap(Throwable t) {
		Throwable current = t;
		while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
				|| current instanceof java.util.concurrent.ExecutionException)) {
			current = current.getCause();
		}
		return current;
	}
}
