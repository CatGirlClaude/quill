package dev.catgirlclaude.quill.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.catgirlclaude.quill.Quill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Dedicated server Access Control List (ACL) loaded from {@code config/quill-acl.json}.
 *
 * <p>Precedence for remote players:
 * <ol>
 *   <li>Explicit deny ({@code deniedUuids} or {@code deniedNames}) — always denied.</li>
 *   <li>Explicit allow ({@code allowedUuids} or {@code allowedNames}) — allowed even without OP.</li>
 *   <li>Operator fallback ({@code allowOperators == true} and player has gamemaster/level >= 2 permission).</li>
 *   <li>Otherwise denied.</li>
 * </ol>
 */
public final class QuillAccessControl {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public record AclPolicy(
			boolean allowOperators,
			Set<UUID> allowedUuids,
			Set<String> allowedNamesLower,
			Set<UUID> deniedUuids,
			Set<String> deniedNamesLower,
			int rateLimitRequestsPerMinute,
			long reloadCooldownMs
	) {
		public AclPolicy {
			allowedUuids = allowedUuids == null ? Set.of() : Set.copyOf(allowedUuids);
			allowedNamesLower = allowedNamesLower == null ? Set.of() : Set.copyOf(allowedNamesLower);
			deniedUuids = deniedUuids == null ? Set.of() : Set.copyOf(deniedUuids);
			deniedNamesLower = deniedNamesLower == null ? Set.of() : Set.copyOf(deniedNamesLower);
			rateLimitRequestsPerMinute = Math.max(10, Math.min(10_000, rateLimitRequestsPerMinute));
			reloadCooldownMs = Math.max(0L, Math.min(60_000L, reloadCooldownMs));
		}

		public static AclPolicy defaults() {
			return new AclPolicy(
					true,
					Set.of(),
					Set.of(),
					Set.of(),
					Set.of(),
					EditorLimits.DEFAULT_REQUESTS_PER_MINUTE,
					EditorLimits.RELOAD_COOLDOWN_MS
			);
		}

		public boolean isAuthorized(UUID playerId, String playerName, boolean isGamemasterOp) {
			String normalizedName = playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
			if (playerId != null && deniedUuids.contains(playerId)) {
				return false;
			}
			if (!normalizedName.isEmpty() && deniedNamesLower.contains(normalizedName)) {
				return false;
			}
			if (playerId != null && allowedUuids.contains(playerId)) {
				return true;
			}
			if (!normalizedName.isEmpty() && allowedNamesLower.contains(normalizedName)) {
				return true;
			}
			return allowOperators && isGamemasterOp;
		}
	}

	private final Path configPath;
	private volatile AclPolicy cachedPolicy = AclPolicy.defaults();
	private volatile FileTime lastLoadedMtime = null;

	public QuillAccessControl(Path configPath) {
		this.configPath = Objects.requireNonNull(configPath, "configPath");
	}

	public static QuillAccessControl inMemory(AclPolicy policy) {
		QuillAccessControl acl = new QuillAccessControl(Path.of("quill-acl-in-memory.json"));
		acl.cachedPolicy = Objects.requireNonNull(policy, "policy");
		return acl;
	}

	public Path configPath() {
		return configPath;
	}

	/**
	 * Writes a default {@code quill-acl.json} file if it does not yet exist.
	 */
	public synchronized void ensureDefaultFileExists() {
		if (Files.exists(configPath)) {
			reloadIfModified();
			return;
		}
		try {
			Path parent = configPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			String json = serializePolicy(AclPolicy.defaults());
			Files.writeString(configPath, json, StandardCharsets.UTF_8);
			this.cachedPolicy = AclPolicy.defaults();
			this.lastLoadedMtime = Files.getLastModifiedTime(configPath);
		} catch (IOException e) {
			Quill.LOGGER.warn("Could not create default Quill ACL file at {}: {}", configPath, e.getMessage());
		}
	}

	public AclPolicy currentPolicy() {
		reloadIfModified();
		return cachedPolicy;
	}

	public boolean isAuthorized(UUID playerId, String playerName, boolean isGamemasterOp) {
		return currentPolicy().isAuthorized(playerId, playerName, isGamemasterOp);
	}

	public int rateLimitRequestsPerMinute() {
		return currentPolicy().rateLimitRequestsPerMinute();
	}

	public long reloadCooldownMs() {
		return currentPolicy().reloadCooldownMs();
	}

	private void reloadIfModified() {
		if (!Files.isRegularFile(configPath)) {
			return;
		}
		try {
			FileTime currentMtime = Files.getLastModifiedTime(configPath);
			if (lastLoadedMtime != null && lastLoadedMtime.equals(currentMtime)) {
				return;
			}
			synchronized (this) {
				FileTime recheckMtime = Files.getLastModifiedTime(configPath);
				if (lastLoadedMtime != null && lastLoadedMtime.equals(recheckMtime)) {
					return;
				}
				String raw = Files.readString(configPath, StandardCharsets.UTF_8);
				this.cachedPolicy = parsePolicy(raw);
				this.lastLoadedMtime = recheckMtime;
			}
		} catch (Exception e) {
			Quill.LOGGER.warn("Failed to read Quill ACL config at {}; keeping previous policy: {}", configPath, e.getMessage());
		}
	}

	public static AclPolicy parsePolicy(String rawJson) {
		JsonElement element = JsonParser.parseString(rawJson);
		if (!element.isJsonObject()) {
			throw new IllegalArgumentException("Quill ACL root must be a JSON object.");
		}
		JsonObject obj = element.getAsJsonObject();

		boolean allowOperators = !obj.has("allowOperators") || obj.get("allowOperators").getAsBoolean();
		Set<UUID> allowedUuids = parseUuidArray(obj, "allowedUuids");
		Set<String> allowedNames = parseNameArray(obj, "allowedNames");
		Set<UUID> deniedUuids = parseUuidArray(obj, "deniedUuids");
		Set<String> deniedNames = parseNameArray(obj, "deniedNames");
		int rpm = obj.has("rateLimitRequestsPerMinute")
				? obj.get("rateLimitRequestsPerMinute").getAsInt()
				: EditorLimits.DEFAULT_REQUESTS_PER_MINUTE;
		long reloadCooldown = obj.has("reloadCooldownMs")
				? obj.get("reloadCooldownMs").getAsLong()
				: EditorLimits.RELOAD_COOLDOWN_MS;

		return new AclPolicy(allowOperators, allowedUuids, allowedNames, deniedUuids, deniedNames, rpm, reloadCooldown);
	}

	public static String serializePolicy(AclPolicy policy) {
		JsonObject root = new JsonObject();
		root.addProperty("allowOperators", policy.allowOperators());
		root.add("allowedUuids", toStringArray(policy.allowedUuids().stream().map(UUID::toString).toList()));
		root.add("allowedNames", toStringArray(policy.allowedNamesLower().stream().toList()));
		root.add("deniedUuids", toStringArray(policy.deniedUuids().stream().map(UUID::toString).toList()));
		root.add("deniedNames", toStringArray(policy.deniedNamesLower().stream().toList()));
		root.addProperty("rateLimitRequestsPerMinute", policy.rateLimitRequestsPerMinute());
		root.addProperty("reloadCooldownMs", policy.reloadCooldownMs());
		return GSON.toJson(root) + "\n";
	}

	private static Set<UUID> parseUuidArray(JsonObject obj, String field) {
		Set<UUID> out = new LinkedHashSet<>();
		if (!obj.has(field) || !obj.get(field).isJsonArray()) {
			return out;
		}
		for (JsonElement item : obj.getAsJsonArray(field)) {
			if (!item.isJsonPrimitive()) {
				continue;
			}
			String raw = item.getAsString().trim();
			if (raw.isEmpty()) {
				continue;
			}
			try {
				out.add(UUID.fromString(raw));
			} catch (IllegalArgumentException e) {
				Quill.LOGGER.warn("Ignoring invalid UUID '{}' in Quill ACL field '{}'", raw, field);
			}
		}
		return out;
	}

	private static Set<String> parseNameArray(JsonObject obj, String field) {
		Set<String> out = new LinkedHashSet<>();
		if (!obj.has(field) || !obj.get(field).isJsonArray()) {
			return out;
		}
		for (JsonElement item : obj.getAsJsonArray(field)) {
			if (!item.isJsonPrimitive()) {
				continue;
			}
			String raw = item.getAsString().trim().toLowerCase(Locale.ROOT);
			if (!raw.isEmpty()) {
				out.add(raw);
			}
		}
		return out;
	}

	private static JsonArray toStringArray(Iterable<String> items) {
		JsonArray array = new JsonArray();
		for (String item : items) {
			array.add(item);
		}
		return array;
	}
}
