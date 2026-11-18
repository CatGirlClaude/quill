package dev.catgirlclaude.quill.client.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuillDrawWrapTest {

	private static final ToIntFunction<String> MONO_6PX = s -> s == null ? 0 : s.length() * 6;

	@Test
	void wrapsInstantReloadWarningWithoutEllipsisTruncation() {
		String warning = SettingsPanelWidget.INSTANT_RELOAD_WARNING_BODY;
		List<String> lines = QuillDraw.wrapLines(warning, 180, MONO_6PX);

		assertTrue(lines.size() >= 3, "Narrow panel width must wrap warning across multiple lines: " + lines);
		for (String line : lines) {
			assertFalse(line.contains("..."), "Wrapped text must not be truncated with '...': " + line);
			assertTrue(MONO_6PX.applyAsInt(line) <= 180, "Wrapped line exceeds max width: " + line);
		}
		assertEquals(warning, String.join(" ", lines));
	}

	@Test
	void wrapsSearchSummaryAndSplitsOverlongTokensWithoutEllipsis() {
		String summary = "Invalid regex: Unclosed group near index 14";
		List<String> wrappedSummary = QuillDraw.wrapLines(summary, 140, MONO_6PX);
		assertTrue(wrappedSummary.size() >= 2);
		for (String line : wrappedSummary) {
			assertFalse(line.contains("..."));
			assertTrue(MONO_6PX.applyAsInt(line) <= 140);
		}
		assertEquals(summary, String.join(" ", wrappedSummary));

		String longToken = "abcdefghijklmnopqrstuvwxyz";
		List<String> splitWord = QuillDraw.wrapLines(longToken, 60, MONO_6PX);
		assertEquals(List.of("abcdefghij", "klmnopqrst", "uvwxyz"), splitWord);
	}
}
