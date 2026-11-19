package dev.catgirlclaude.quill.client.editor;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrigadierAssistServiceTest {
	private CommandDispatcher<Object> dispatcher;
	private final Object source = new Object();

	@BeforeEach
	void setUp() {
		dispatcher = new CommandDispatcher<>();
		dispatcher.register(LiteralArgumentBuilder.literal("say")
				.then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString())
						.executes(ctx -> 1)));
		dispatcher.register(LiteralArgumentBuilder.literal("scoreboard")
				.then(LiteralArgumentBuilder.literal("objectives")
						.then(LiteralArgumentBuilder.literal("add")
								.then(RequiredArgumentBuilder.argument("objective", StringArgumentType.word())
										.executes(ctx -> 1))))
				.then(LiteralArgumentBuilder.literal("players")
						.then(LiteralArgumentBuilder.literal("set")
								.then(RequiredArgumentBuilder.argument("target", StringArgumentType.word())
										.then(RequiredArgumentBuilder.argument("score", IntegerArgumentType.integer())
												.executes(ctx -> 1))))));
		dispatcher.register(LiteralArgumentBuilder.literal("schedule")
				.then(LiteralArgumentBuilder.literal("function")
						.executes(ctx -> 1)));
	}

	@Test
	void suggestsCommandPrefixesAndReplacementRanges() {
		BrigadierAssistService.AssistResult result = BrigadierAssistService.query(dispatcher, source, "sc", 2).join();
		List<String> texts = result.suggestions().stream().map(BrigadierAssistService.SuggestionEntry::text).toList();
		assertEquals(List.of("schedule", "scoreboard"), texts);
		assertEquals(0, result.suggestions().getFirst().replaceStartCol());
		assertEquals(2, result.suggestions().getFirst().replaceEndCol());
	}

	@Test
	void suggestsNestedSubcommandsAndUsageSignatures() {
		String line = "scoreboard ";
		BrigadierAssistService.AssistResult result = BrigadierAssistService.query(dispatcher, source, line, line.length()).join();
		List<String> texts = result.suggestions().stream().map(BrigadierAssistService.SuggestionEntry::text).toList();
		assertEquals(List.of("objectives", "players"), texts);
		assertFalse(result.usageSignatures().isEmpty());
		assertTrue(result.usageSignatures().stream().anyMatch(u -> u.contains("scoreboard") && u.contains("objectives")));
	}

	@Test
	void supportsMacroPrefixAndLeadingSlashWithoutCorruptingOffsets() {
		String macroLine = "$score";
		BrigadierAssistService.AssistResult macroResult = BrigadierAssistService.query(dispatcher, source, macroLine, macroLine.length()).join();
		assertEquals(1, macroResult.suggestions().size());
		assertEquals("scoreboard", macroResult.suggestions().getFirst().text());
		assertEquals(1, macroResult.suggestions().getFirst().replaceStartCol());
		assertEquals(6, macroResult.suggestions().getFirst().replaceEndCol());

		String slashLine = "/sa";
		BrigadierAssistService.AssistResult slashResult = BrigadierAssistService.query(dispatcher, source, slashLine, slashLine.length()).join();
		assertEquals(1, slashResult.suggestions().size());
		assertEquals("say", slashResult.suggestions().getFirst().text());
		assertEquals(1, slashResult.suggestions().getFirst().replaceStartCol());
		assertEquals(3, slashResult.suggestions().getFirst().replaceEndCol());
	}

	@Test
	void skipsCommentLinesAndProvidesHoverSignatures() {
		BrigadierAssistService.AssistResult commentResult = BrigadierAssistService.query(dispatcher, source, "# scoreboard ", 12).join();
		assertTrue(commentResult.isEmpty());

		List<String> hover = BrigadierAssistService.queryHoverSignatures(dispatcher, source, "scoreboard players set @a 10", 20);
		assertFalse(hover.isEmpty());
		assertTrue(hover.getFirst().contains("scoreboard players set"));
	}

	@Test
	void hoverSignaturesFocusOnRedirectedSubcommandAfterExecuteRun() {
		com.mojang.brigadier.arguments.ArgumentType<String> selectorOrWord = reader -> {
			int start = reader.getCursor();
			while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
				reader.skip();
			}
			return reader.getString().substring(start, reader.getCursor());
		};
		dispatcher.register(LiteralArgumentBuilder.literal("execute")
				.then(LiteralArgumentBuilder.literal("as")
						.then(RequiredArgumentBuilder.argument("targets", selectorOrWord)
								.then(LiteralArgumentBuilder.literal("run")
										.redirect(dispatcher.getRoot())))));

		String line = "execute as @a run scoreboard players set @s 5";
		List<String> execHover = BrigadierAssistService.queryHoverSignatures(dispatcher, source, line, 2);
		assertFalse(execHover.isEmpty());
		assertTrue(execHover.stream().anyMatch(s -> s.startsWith("execute ")));

		List<String> scoreHover = BrigadierAssistService.queryHoverSignatures(dispatcher, source, line, line.indexOf("scoreboard") + 2);
		assertFalse(scoreHover.isEmpty());
		assertTrue(scoreHover.stream().anyMatch(s -> s.startsWith("scoreboard ")),
				"Expected concise subcommand usage starting with 'scoreboard ', got: " + scoreHover);
	}
}
