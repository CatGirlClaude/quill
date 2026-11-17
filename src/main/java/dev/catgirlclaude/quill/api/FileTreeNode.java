package dev.catgirlclaude.quill.api;

import java.util.List;
import java.util.Objects;

/**
 * Immutable node representing a folder or editable file in {@code datapacks/}.
 */
public record FileTreeNode(
		String name,
		String relativePath,
		EntryKind kind,
		List<FileTreeNode> children
) {
	public FileTreeNode {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(relativePath, "relativePath");
		Objects.requireNonNull(kind, "kind");
		children = children == null ? List.of() : List.copyOf(children);
	}
}
