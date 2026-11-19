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

	@Test
	void highlightsItemComponentsAndPredicates() {
		String giveLine = "give @p minecraft:diamond_sword[minecraft:custom_name='\"Excalibur\"', enchantment_glint_override=true, minecraft:enchantments={levels:{\"minecraft:sharpness\":5}}, !minecraft:damage] 1";
		List<SyntaxHighlighter.TokenSpan> spans = SyntaxHighlighter.tokenizeLine(giveLine, SyntaxHighlighter.Language.MCFUNCTION);
		assertFullPartition(giveLine, spans);

		assertEquals(SyntaxHighlighter.TokenKind.COMMAND, tokenKindAt(giveLine, spans, "give"));
		assertEquals(SyntaxHighlighter.TokenKind.SELECTOR, tokenKindAt(giveLine, spans, "@p"));
		assertEquals(SyntaxHighlighter.TokenKind.IDENTIFIER, tokenKindAt(giveLine, spans, "minecraft:diamond_sword"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(giveLine, spans, "minecraft:custom_name"));
		assertEquals(SyntaxHighlighter.TokenKind.STRING, tokenKindAt(giveLine, spans, "'\"Excalibur\"'"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(giveLine, spans, "enchantment_glint_override"));
		assertEquals(SyntaxHighlighter.TokenKind.BOOLEAN, tokenKindAt(giveLine, spans, "true"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(giveLine, spans, "minecraft:enchantments"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(giveLine, spans, "levels"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(giveLine, spans, "\"minecraft:sharpness\""));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(giveLine, spans, "5"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(giveLine, spans, "minecraft:damage"));

		String predLine = "execute if items entity @s weapon.* *[minecraft:custom_data~{quest:1b}]";
		List<SyntaxHighlighter.TokenSpan> predSpans = SyntaxHighlighter.tokenizeLine(predLine, SyntaxHighlighter.Language.MCFUNCTION);
		assertFullPartition(predLine, predSpans);
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(predLine, predSpans, "minecraft:custom_data"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(predLine, predSpans, "quest"));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(predLine, predSpans, "1b"));
	}

	@Test
	void highlightsEntityDataNbtPathsTypedArraysAndScientificNumbers() {
		String modifyLine = "data modify entity @s ArmorItems[0].components.\"minecraft:dyed_color\".rgb set value 16711680";
		List<SyntaxHighlighter.TokenSpan> modSpans = SyntaxHighlighter.tokenizeLine(modifyLine, SyntaxHighlighter.Language.MCFUNCTION);
		assertFullPartition(modifyLine, modSpans);

		assertEquals(SyntaxHighlighter.TokenKind.COMMAND, tokenKindAt(modifyLine, modSpans, "data"));
		assertEquals(SyntaxHighlighter.TokenKind.KEYWORD, tokenKindAt(modifyLine, modSpans, "modify"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(modifyLine, modSpans, "ArmorItems"));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(modifyLine, modSpans, "0"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(modifyLine, modSpans, "components"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(modifyLine, modSpans, "\"minecraft:dyed_color\""));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(modifyLine, modSpans, "rgb"));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(modifyLine, modSpans, "16711680"));

		String getLine = "execute store result entity @s Health float 1.5e-2 run data get storage demo:state player.stats.kills";
		List<SyntaxHighlighter.TokenSpan> getSpans = SyntaxHighlighter.tokenizeLine(getLine, SyntaxHighlighter.Language.MCFUNCTION);
		assertFullPartition(getLine, getSpans);

		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(getLine, getSpans, "Health"));
		assertEquals(SyntaxHighlighter.TokenKind.KEYWORD, tokenKindAt(getLine, getSpans, "float"));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(getLine, getSpans, "1.5e-2"));
		assertEquals(SyntaxHighlighter.TokenKind.IDENTIFIER, tokenKindAt(getLine, getSpans, "demo:state"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(getLine, getSpans, "player"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(getLine, getSpans, "stats"));
		assertEquals(SyntaxHighlighter.TokenKind.PROPERTY, tokenKindAt(getLine, getSpans, "kills"));

		String snbtLine = "summon minecraft:interaction ~ ~ ~ {Tags:[\"alpha\",\"beta\"],Pos:[0.0d,64.0d,-1.5e2d],UUID:[I;-123,0xFF,42],Items:[minecraft:stone]}";
		List<SyntaxHighlighter.TokenSpan> snbtSpans = SyntaxHighlighter.tokenizeLine(snbtLine, SyntaxHighlighter.Language.MCFUNCTION);
		assertFullPartition(snbtLine, snbtSpans);

		assertEquals(SyntaxHighlighter.TokenKind.STRING, tokenKindAt(snbtLine, snbtSpans, "\"alpha\""));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(snbtLine, snbtSpans, "-1.5e2d"));
		int typedArrayPrefixIdx = snbtLine.indexOf("[I;") + 1;
		assertEquals(SyntaxHighlighter.TokenKind.KEYWORD, tokenKindAtIndex(snbtSpans, typedArrayPrefixIdx, 1));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(snbtLine, snbtSpans, "-123"));
		assertEquals(SyntaxHighlighter.TokenKind.NUMBER, tokenKindAt(snbtLine, snbtSpans, "0xFF"));
		assertEquals(SyntaxHighlighter.TokenKind.IDENTIFIER, tokenKindAt(snbtLine, snbtSpans, "minecraft:stone"));
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

	private static SyntaxHighlighter.TokenKind tokenKindAtIndex(
			List<SyntaxHighlighter.TokenSpan> spans,
			int idx,
			int len
	) {
		for (SyntaxHighlighter.TokenSpan span : spans) {
			if (span.start() <= idx && span.end() >= idx + len) {
				return span.kind();
			}
		}
		throw new AssertionError("No single span covers [" + idx + ", " + (idx + len) + ") in spans=" + spans);
	}
}
