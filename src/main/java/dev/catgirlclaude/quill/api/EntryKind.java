package dev.catgirlclaude.quill.api;

/**
 * Classifies nodes inside a world's {@code datapacks/} directory tree.
 */
public enum EntryKind {
	ROOT,
	DATAPACK,
	DIRECTORY,
	FILE
}
