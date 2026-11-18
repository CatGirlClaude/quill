package dev.catgirlclaude.quill.client.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntaxHighlighterTest {

	@Test
	void detectsLanguageByFileExtension() {
		assertEquals(SyntaxHighlighter.Language.MCFUNCTION, SyntaxHighlighter.languageForPath("pack/data/demo/function/load.mcfunction"));
		assertEquals(SyntaxHighlighter.Language.JSON, SyntaxHighlighter.languageForPath("pack/pack.mcmeta"));
		assertEquals(SyntaxHighlighter.Language.JSON, SyntaxHighlighter.languageForPath("pack/data/minecraft/tags/function/tick.json"));
		assertEquals(SyntaxHighlighter.Language.PLAIN, SyntaxHighlighter.languageForPath("pack/README.md"));
	}

	@Test
	void highlightsCommentsCommandsSelectorsCoordinatesAndSnbt() {
		String comment = "  # comment about @a ~ ~ ~";
		List<SyntaxHighlighter.TokenSpan> commentSpans = SyntaxHighlighter.tokenizeLine(comment, SyntaxHighlighter.Language.MCFUNCTION);
		assertFullPartition(comment, commentSpans);
		assertEquals(SyntaxHighlighter.TokenKind.COMMENT, tokenKindAt(comment, commentSpans, "# comment"));

		String cmd = "execute as @e[type=minecraft:zombie,scores={kills=1..}] at @s run summon minecraft:armor_stand ~ ~1.5 ^-2 {IsBaby:true,Count:1b,CustomName:\"Boss\"}";
		List<SyntaxHighlighter.TokenSpan> spans = SyntaxHighlighter.tokenizeLine(cmd, SyntaxHighlighter.Language.MCFUNCTION);
		assertFullPartition(cmd, spans);

		assertEquals(SyntaxHighlighter.TokenKind.COMMAND, tokenKindAt(cmd, spans, "execute"));
		assertEquals(SyntaxHighlighter.TokenKind.KEYWORD, tokenKindAt(cmd, spans, "as"));
		assertEquals(SyntaxHighlighter.TokenKind.SELECTOR, tokenKindAt(cmd, spans, "@e"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(cmd, spans, "type"));
		assertEquals(SyntaxHighlighter.TokenKind.IDENTIFIER, tokenKindAt(cmd, spans, "minecraft:zombie"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(cmd, spans, "kills"));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(cmd, spans, "1.."));
		assertEquals(SyntaxHighlighter.TokenKind.SELECTOR, tokenKindAt(cmd, spans, "@s"));
		assertEquals(SyntaxHighlighter.TokenKind.KEYWORD, tokenKindAt(cmd, spans, "run"));
		assertEquals(SyntaxHighlighter.TokenKind.COMMAND, tokenKindAt(cmd, spans, "summon"));
		assertEquals(SyntaxHighlighter.TokenKind.IDENTIFIER, tokenKindAt(cmd, spans, "minecraft:armor_stand"));
		assertEquals(SyntaxHighlighter.TokenKind.COORDINATE, tokenKindAt(cmd, spans, "~1.5"));
		assertEquals(SyntaxHighlighter.TokenKind.COORDINATE, tokenKindAt(cmd, spans, "^-2"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(cmd, spans, "IsBaby"));
		assertEquals(SyntaxHighlighter.TokenKind.BOOLEAN, tokenKindAt(cmd, spans, "true"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(cmd, spans, "Count"));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(cmd, spans, "1b"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(cmd, spans, "CustomName"));
		assertEquals(SyntaxHighlighter.TokenKind.STRING, tokenKindAt(cmd, spans, "\"Boss\""));
	}

	@Test
	void highlightsMacroLinesAndInterpolationsInsideStringsAndArgs() {
		String line = "$tellraw @a \"Hello $(player_name), score=$(score)!\"";
		List<SyntaxHighlighter.TokenSpan> spans = SyntaxHighlighter.tokenizeLine(line, SyntaxHighlighter.Language.MCFUNCTION);
		assertFullPartition(line, spans);

		assertEquals(SyntaxHighlighter.TokenKind.MACRO, spans.getFirst().kind());
		assertEquals("$", line.substring(spans.getFirst().start(), spans.getFirst().end()));
		assertEquals(SyntaxHighlighter.TokenKind.COMMAND, tokenKindAt(line, spans, "tellraw"));
		assertEquals(SyntaxHighlighter.TokenKind.SELECTOR, tokenKindAt(line, spans, "@a"));
		assertEquals(SyntaxHighlighter.TokenKind.MACRO, tokenKindAt(line, spans, "$(player_name)"));
		assertEquals(SyntaxHighlighter.TokenKind.MACRO, tokenKindAt(line, spans, "$(score)"));
		assertEquals(SyntaxHighlighter.TokenKind.STRING, tokenKindAt(line, spans, "\"Hello "));
	}

	@Test
	void highlightsJsonKeysStringsNumbersBooleansAndBrackets() {
		String line = "  \"min_format\": 121, \"replace\": false, \"id\": \"minecraft:stone\" }";
		List<SyntaxHighlighter.TokenSpan> spans = SyntaxHighlighter.tokenizeLine(line, SyntaxHighlighter.Language.JSON);
		assertFullPartition(line, spans);

		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(line, spans, "\"min_format\""));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(line, spans, "121"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(line, spans, "\"replace\""));
		assertEquals(SyntaxHighlighter.TokenKind.BOOLEAN, tokenKindAt(line, spans, "false"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(line, spans, "\"id\""));
		assertEquals(SyntaxHighlighter.TokenKind.STRING, tokenKindAt(line, spans, "\"minecraft:stone\""));
		assertEquals(SyntaxHighlighter.TokenKind.BRACKET, tokenKindAt(line, spans, "}"));
	}

	private static void assertFullPartition(String line, List<SyntaxHighlighter.TokenSpan> spans) {
		int cursor = 0;
		for (SyntaxHighlighter.TokenSpan span : spans) {
			assertEquals(cursor, span.start(), "Gap or overlap at index " + cursor);
			assertTrue(span.end() > span.start(), "Empty span at " + span.start());
			cursor = span.end();
		}
		assertEquals(line.length(), cursor, "Spans must cover entire line");
	}

	private static SyntaxHighlighter.TokenKind tokenKindAt(
			String line,
			List<SyntaxHighlighter.TokenSpan> spans,
			String needle
	) {
		int idx = line.indexOf(needle);
		assertTrue(idx >= 0, "Needle not found: " + needle);
		for (SyntaxHighlighter.TokenSpan span : spans) {
			if (span.start() <= idx && span.end() >= idx + needle.length()) {
				return span.kind();
			}
		}
		throw new AssertionError("No single span covers '" + needle + "' in spans=" + spans);
	}
}
