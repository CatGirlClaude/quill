package dev.catgirlclaude.quill.client.editor;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Pure Brigadier command completion and signature lookup service for {@code .mcfunction} lines.
 * Decoupled from Minecraft rendering so it can run against either the live client
 * {@code CommandDispatcher<ClientSuggestionProvider>} or test dispatchers in headless JUnit.
 */
public final class BrigadierAssistService {
	private static final int MAX_SUGGESTIONS = 50;
	private static final int MAX_USAGE_SIGNATURES = 4;

	public record SuggestionEntry(
			String text,
			String tooltip,
			int replaceStartCol,
			int replaceEndCol
	) {
	}

	public record AssistResult(
			List<SuggestionEntry> suggestions,
			List<String> usageSignatures,
			String parseHint
	) {
		public static final AssistResult EMPTY = new AssistResult(List.of(), List.of(), null);

		public boolean isEmpty() {
			return suggestions.isEmpty() && usageSignatures.isEmpty() && (parseHint == null || parseHint.isEmpty());
		}
	}

	private record PreparedLine(String commandText, int prefixOffset, int relativeCursor) {
	}

	private BrigadierAssistService() {
	}

	public static <S> CompletableFuture<AssistResult> query(
			CommandDispatcher<S> dispatcher,
			S source,
			String rawLine,
			int cursorColumn
	) {
		Objects.requireNonNull(dispatcher, "dispatcher");
		PreparedLine prepared = prepareLine(rawLine, cursorColumn);
		if (prepared == null) {
			return CompletableFuture.completedFuture(AssistResult.EMPTY);
		}

		ParseResults<S> parsed;
		try {
			parsed = dispatcher.parse(prepared.commandText(), source);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(AssistResult.EMPTY);
		}

		List<String> usages = computeUsageSignatures(dispatcher, parsed, source, prepared.relativeCursor());
		String parseHint = extractParseHint(parsed);

		CompletableFuture<Suggestions> suggestionsFuture;
		try {
			suggestionsFuture = dispatcher.getCompletionSuggestions(parsed, prepared.relativeCursor());
		} catch (Exception e) {
			return CompletableFuture.completedFuture(new AssistResult(List.of(), usages, parseHint));
		}

		return suggestionsFuture
				.handle((suggestions, error) -> {
					if (error != null || suggestions == null) {
						return new AssistResult(List.of(), usages, parseHint);
					}
					List<SuggestionEntry> entries = convertSuggestions(suggestions, prepared.prefixOffset(), rawLine.length());
					return new AssistResult(entries, usages, parseHint);
				});
	}

	/**
	 * Synchronous signature lookup for mouse hover tooltips over an {@code .mcfunction} line.
	 */
	public static <S> List<String> queryHoverSignatures(
			CommandDispatcher<S> dispatcher,
			S source,
			String rawLine,
			int hoverColumn
	) {
		Objects.requireNonNull(dispatcher, "dispatcher");
		PreparedLine prepared = prepareLine(rawLine, hoverColumn);
		if (prepared == null || prepared.commandText().isBlank()) {
			return List.of();
		}
		try {
			ParseResults<S> parsed = dispatcher.parse(prepared.commandText(), source);
			return computeUsageSignatures(dispatcher, parsed, source, prepared.relativeCursor());
		} catch (Exception e) {
			return List.of();
		}
	}

	private static PreparedLine prepareLine(String rawLine, int cursorColumn) {
		if (rawLine == null) {
			return null;
		}
		int len = rawLine.length();
		int start = 0;
		while (start < len && Character.isWhitespace(rawLine.charAt(start))) {
			start++;
		}
		if (start < len && rawLine.charAt(start) == '#') {
			return null;
		}
		if (start < len && (rawLine.charAt(start) == '$' || rawLine.charAt(start) == '/')) {
			start++;
		}
		String commandText = rawLine.substring(start);
		int clampedCursor = Math.max(0, Math.min(len, cursorColumn));
		if (clampedCursor < start) {
			return null;
		}
		int relativeCursor = Math.max(0, Math.min(commandText.length(), clampedCursor - start));
		return new PreparedLine(commandText, start, relativeCursor);
	}

	private static <S> List<String> computeUsageSignatures(
			CommandDispatcher<S> dispatcher,
			ParseResults<S> parsed,
			S source,
			int relativeCursor
	) {
		CommandContextBuilder<S> context = parsed.getContext();
		CommandNode<S> activeNode = dispatcher.getRoot();
		StringBuilder prefix = new StringBuilder();

		CommandContextBuilder<S> cursorCtx = context;
		while (cursorCtx != null) {
			for (ParsedCommandNode<S> parsedNode : cursorCtx.getNodes()) {
				StringRange range = parsedNode.getRange();
				if (range.getStart() <= relativeCursor) {
					activeNode = parsedNode.getNode();
					if (!prefix.isEmpty()) {
						prefix.append(' ');
					}
					prefix.append(parsedNode.getNode().getUsageText());
				}
			}
			cursorCtx = cursorCtx.getChild();
		}

		if (activeNode == dispatcher.getRoot()) {
			return List.of();
		}

		Set<String> signatures = new LinkedHashSet<>();
		Map<CommandNode<S>, String> smartUsage = dispatcher.getSmartUsage(activeNode, source);
		if (smartUsage != null && !smartUsage.isEmpty()) {
			for (String usage : smartUsage.values()) {
				if (usage != null && !usage.isBlank()) {
					signatures.add(prefix + " " + usage);
				}
				if (signatures.size() >= MAX_USAGE_SIGNATURES) {
					break;
				}
			}
		} else if (activeNode.getCommand() != null) {
			signatures.add(prefix.toString());
		}
		return List.copyOf(signatures);
	}

	private static <S> String extractParseHint(ParseResults<S> parsed) {
		Map<CommandNode<S>, CommandSyntaxException> exceptions = parsed.getExceptions();
		if (exceptions == null || exceptions.isEmpty()) {
			return null;
		}
		CommandSyntaxException first = exceptions.values().iterator().next();
		if (first == null || first.getRawMessage() == null) {
			return null;
		}
		return first.getRawMessage().getString();
	}

	private static List<SuggestionEntry> convertSuggestions(
			Suggestions suggestions,
			int prefixOffset,
			int rawLineLength
	) {
		List<Suggestion> raw = new ArrayList<>(suggestions.getList());
		raw.sort(Comparator.comparing(Suggestion::getText, String.CASE_INSENSITIVE_ORDER));

		List<SuggestionEntry> out = new ArrayList<>(Math.min(MAX_SUGGESTIONS, raw.size()));
		Set<String> seen = new LinkedHashSet<>();
		for (Suggestion s : raw) {
			if (out.size() >= MAX_SUGGESTIONS) {
				break;
			}
			if (s == null || s.getText() == null || !seen.add(s.getText())) {
				continue;
			}
			StringRange range = s.getRange();
			int startCol = Math.max(0, Math.min(rawLineLength, prefixOffset + range.getStart()));
			int endCol = Math.max(startCol, Math.min(rawLineLength, prefixOffset + range.getEnd()));
			Message tooltipMsg = s.getTooltip();
			String tooltip = tooltipMsg == null ? null : tooltipMsg.getString();
			out.add(new SuggestionEntry(s.getText(), tooltip, startCol, endCol));
		}
		return out;
	}
}
