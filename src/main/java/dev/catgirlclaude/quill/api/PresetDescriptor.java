package dev.catgirlclaude.quill.api;

import java.util.Map;
import java.util.Objects;

/**
 * Metadata describing a resource-driven datapack preset (e.g. {@code minecraft:load}).
 */
public record PresetDescriptor(
		String id,
		String displayName,
		String description,
		String targetRelativePathPattern,
		Map<String, String> defaultVariables
) {
	public PresetDescriptor {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(targetRelativePathPattern, "targetRelativePathPattern");
		defaultVariables = defaultVariables == null ? Map.of() : Map.copyOf(defaultVariables);
	}
}
