package dev.catgirlclaude.quill.client.gui;

import dev.catgirlclaude.quill.security.PathSandbox;

import java.util.Locale;

/**
 * Classifies workspace files by extension for Explorer/Tab type badges, icon colors,
 * and editor support checks.
 */
public final class QuillFileTypes {
	public enum Category {
		FUNCTION,
		JSON,
		MARKDOWN,
		TEXT,
		IMAGE,
		AUDIO,
		NBT,
		ARCHIVE,
		BINARY
	}

	public record FileTypeInfo(Category category, String shortLabel, boolean editable) {
		public int badgeColor() {
			return switch (category) {
				case FUNCTION -> QuillTheme.TEXT_FUNCTION;
				case JSON -> QuillTheme.TEXT_JSON;
				case MARKDOWN -> QuillTheme.SYNTAX_KEYWORD;
				case TEXT -> QuillTheme.TEXT_MUTED;
				case IMAGE -> QuillTheme.SYNTAX_SELECTOR;
				case AUDIO -> QuillTheme.SYNTAX_MACRO;
				case NBT -> QuillTheme.SYNTAX_NUMBER;
				case ARCHIVE -> QuillTheme.TEXT_FOLDER;
				case BINARY -> QuillTheme.TEXT_MUTED;
			};
		}
	}

	private QuillFileTypes() {
	}

	public static FileTypeInfo classify(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return new FileTypeInfo(Category.BINARY, "bin", false);
		}
		String lower = fileName.toLowerCase(Locale.ROOT);
		boolean editable = PathSandbox.hasAllowedExtension(lower);

		if (lower.endsWith(".mcfunction")) {
			return new FileTypeInfo(Category.FUNCTION, "fx", editable);
		}
		if (lower.endsWith(".json") || lower.endsWith(".mcmeta")) {
			return new FileTypeInfo(Category.JSON, "{}", editable);
		}
		if (lower.endsWith(".md")) {
			return new FileTypeInfo(Category.MARKDOWN, "md", editable);
		}
		if (lower.endsWith(".txt")) {
			return new FileTypeInfo(Category.TEXT, "tx", editable);
		}
		if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
				|| lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
				|| lower.endsWith(".tga")) {
			return new FileTypeInfo(Category.IMAGE, "img", false);
		}
		if (lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac")) {
			return new FileTypeInfo(Category.AUDIO, "aud", false);
		}
		if (lower.endsWith(".nbt") || lower.endsWith(".snbt") || lower.endsWith(".mcstructure")
				|| lower.endsWith(".schem") || lower.endsWith(".schematic") || lower.endsWith(".litematic")) {
			return new FileTypeInfo(Category.NBT, "nbt", false);
		}
		if (lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".gz")
				|| lower.endsWith(".tar") || lower.endsWith(".7z") || lower.endsWith(".rar")) {
			return new FileTypeInfo(Category.ARCHIVE, "zip", false);
		}
		return new FileTypeInfo(Category.BINARY, "bin", editable);
	}
}
