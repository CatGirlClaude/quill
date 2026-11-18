package dev.catgirlclaude.quill.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java {@code Ctrl+Click} navigation resolver for {@code .mcfunction} files.
 *
 * <p>Resolves scoreboard objectives, entity tags, command storage identifiers, macro parameters,
 * function resource IDs, and command keywords under the clicked cursor column.
 * Command documentation links point to {@code https://minecraft.wiki/w/Commands/<command>}
 * (the official community-maintained Minecraft Wiki) validated against the active client
 * Brigadier root command tree or the built-in vanilla command set when offline.
 */
public final class WorkspaceNavigation {
	public enum TargetKind {
		SCOREBOARD_OBJECTIVE("Objective"),
		ENTITY_TAG("Entity Tag"),
		STORAGE_ID("Storage"),
		MACRO_PARAMETER("Macro Parameter"),
		FUNCTION_ID("Function"),
		COMMAND_DOC("Command Documentation");

		private final String displayLabel;

		TargetKind(String displayLabel) {
			this.displayLabel = displayLabel;
		}

		public String displayLabel() {
			return displayLabel;
		}
	}

	public record NavigationTarget(
			TargetKind kind,
			String identifier,
			String url,
			int startCol,
			int endCol
	) {
	}

	private record Token(String text, int startCol, int endCol) {
	}

	private static final Pattern SELECTOR_TAG_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])tag\\s*=\\s*!?([A-Za-z0-9_.+-]+)");

	private static final Map<String, String> COMMAND_DOC_ALIASES = Map.of(
			"tp", "teleport",
			"msg", "tell",
			"w", "tell",
			"xp", "experience",
			"?", "help"
	);

	private static final Set<String> FALLBACK_VANILLA_COMMANDS = Set.of(
			"advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone",
			"damage", "data", "datapack", "debug", "defaultgamemode", "deop", "difficulty", "effect",
			"enchant", "execute", "experience", "fetchprofile", "fill", "fillbiome", "forceload",
			"function", "gamemode", "gamerule", "give", "help", "item", "jfr", "kick", "kill",
			"list", "locate", "loot", "me", "msg", "op", "pardon", "pardon-ip", "particle",
			"perf", "place", "playsound", "publish", "random", "recipe", "reload", "return",
			"ride", "rotate", "save-all", "save-off", "save-on", "say", "schedule", "scoreboard",
			"seed", "setblock", "setidletimeout", "setworldspawn", "spawnpoint", "spectate",
			"spreadplayers", "stop", "stopsound", "summon", "swing", "tag", "team", "teammsg",
			"teleport", "tell", "tellraw", "test", "tick", "time", "title", "tm", "tp",
			"transfer", "trigger", "version", "w", "waypoint", "weather", "whitelist", "worldborder",
			"xp"
	);

	private WorkspaceNavigation() {
	}

	public static NavigationTarget resolveTargetAtCursor(
			String relativePath,
			String line,
			int column,
			Set<String> registeredRootCommands
	) {
		if (line == null || line.isEmpty() || SyntaxHighlighter.languageForPath(relativePath) != SyntaxHighlighter.Language.MCFUNCTION) {
			return null;
		}
		int lead = skipWhitespace(line, 0);
		if (lead >= line.length() || line.charAt(lead) == '#') {
			return null;
		}
		int col = Math.max(0, Math.min(line.length(), column));

		// 1. Reuse DatapackInspector for scoreboard objectives, storage namespaces, and macro parameters
		DatapackInspector.InspectionReport singleLine = DatapackInspector.inspectFiles(Map.of(relativePath, line));
		NavigationTarget inspectedMatch = findMatchInEntries(TargetKind.SCOREBOARD_OBJECTIVE, singleLine.scoreboardObjectives(), col);
		if (inspectedMatch != null) {
			return inspectedMatch;
		}
		inspectedMatch = findMatchInEntries(TargetKind.STORAGE_ID, singleLine.storageEntries(), col);
		if (inspectedMatch != null) {
			return inspectedMatch;
		}
		inspectedMatch = findMatchInEntries(TargetKind.MACRO_PARAMETER, singleLine.macroParameters(), col);
		if (inspectedMatch != null) {
			return inspectedMatch;
		}

		// 2. Entity tags (`tag <targets> add|remove <name>` and selector `tag=[!]<name>`)
		for (DatapackInspector.Occurrence occ : extractTagOccurrencesInLine(relativePath, 1, line, null)) {
			if (col >= occ.startCol() && col <= occ.endCol()) {
				String tagName = extractTagNameFromLineSpan(line, occ.startCol(), occ.endCol());
				if (!tagName.isEmpty()) {
					return new NavigationTarget(TargetKind.ENTITY_TAG, tagName, null, occ.startCol(), occ.endCol());
				}
			}
		}

		int cmdStart = lead;
		if (line.charAt(cmdStart) == '$' || line.charAt(cmdStart) == '/') {
			cmdStart++;
		}
		List<Token> tokens = tokenizeLine(line, cmdStart);
		if (tokens.isEmpty()) {
			return null;
		}

		// 3. Function references (`function <id>` or `schedule function <id>`)
		NavigationTarget fnTarget = findFunctionTargetAtCol(tokens, 0, col);
		if (fnTarget != null) {
			return fnTarget;
		}

		// 4. Command verbs (leading command or subcommand after `execute ... run` / `return run`)
		for (Token cmdTok : findCommandTokens(tokens, 0)) {
			if (col >= cmdTok.startCol() && col <= cmdTok.endCol()) {
				String normalized = normalizeCommandToken(cmdTok.text());
				if (isKnownCommand(normalized, registeredRootCommands)) {
					String docUrl = commandDocumentationUrl(normalized);
					if (docUrl != null) {
						return new NavigationTarget(TargetKind.COMMAND_DOC, normalized, docUrl, cmdTok.startCol(), cmdTok.endCol());
					}
				}
			}
		}
		return null;
	}

	public static List<DatapackInspector.Occurrence> findAllUsages(
			TargetKind kind,
			String identifier,
			Map<String, String> filesByPath
	) {
		if (kind == null || identifier == null || identifier.isEmpty() || filesByPath == null || filesByPath.isEmpty()) {
			return List.of();
		}
		if (kind == TargetKind.ENTITY_TAG) {
			List<DatapackInspector.Occurrence> tagUsages = new ArrayList<>();
			for (Map.Entry<String, String> entry : filesByPath.entrySet()) {
				String content = entry.getValue();
				if (content == null || content.isEmpty()) {
					continue;
				}
				String[] lines = TextDocumentBuffer.normalizeLineEndings(content).split("\n", -1);
				for (int i = 0; i < lines.length; i++) {
					tagUsages.addAll(extractTagOccurrencesInLine(entry.getKey(), i + 1, lines[i], identifier));
				}
			}
			return List.copyOf(tagUsages);
		}
		DatapackInspector.InspectionReport report = DatapackInspector.inspectFiles(filesByPath);
		List<DatapackInspector.SymbolEntry> pool = switch (kind) {
			case SCOREBOARD_OBJECTIVE -> report.scoreboardObjectives();
			case STORAGE_ID -> report.storageEntries();
			case MACRO_PARAMETER -> report.macroParameters();
			default -> List.of();
		};
		for (DatapackInspector.SymbolEntry sym : pool) {
			if (sym.name().equals(identifier)) {
				return sym.occurrences();
			}
		}
		return List.of();
	}

	public static String resolveFunctionFilePath(String functionId, List<String> allRelativePaths) {
		if (functionId == null || functionId.isBlank() || allRelativePaths == null || allRelativePaths.isEmpty()) {
			return null;
		}
		String clean = functionId.startsWith("#") ? functionId.substring(1) : functionId;
		int colon = clean.indexOf(':');
		String namespace = colon >= 0 ? clean.substring(0, colon) : "minecraft";
		String path = colon >= 0 ? clean.substring(colon + 1) : clean;
		if (namespace.isEmpty() || path.isEmpty()) {
			return null;
		}
		String expectedSuffix = functionId.startsWith("#")
				? "/data/" + namespace + "/tags/function/" + path + ".json"
				: "/data/" + namespace + "/function/" + path + ".mcfunction";
		String rootSuffix = expectedSuffix.substring(1);
		for (String rel : allRelativePaths) {
			if (rel.equals(rootSuffix) || rel.endsWith(expectedSuffix)) {
				return rel;
			}
		}
		return null;
	}

	public static String commandDocumentationUrl(String command) {
		String normalized = normalizeCommandToken(command);
		if (normalized.isEmpty()) {
			return null;
		}
		String canonical = COMMAND_DOC_ALIASES.getOrDefault(normalized, normalized);
		return "https://minecraft.wiki/w/Commands/" + canonical;
	}

	private static String normalizeCommandToken(String token) {
		if (token == null) {
			return "";
		}
		String trimmed = token.trim().toLowerCase(Locale.ROOT);
		if (trimmed.startsWith("/") || trimmed.startsWith("$")) {
			trimmed = trimmed.substring(1);
		}
		if (trimmed.startsWith("minecraft:")) {
			trimmed = trimmed.substring("minecraft:".length());
		}
		return trimmed;
	}

	private static boolean isKnownCommand(String normalizedCommand, Set<String> registeredRootCommands) {
		if (normalizedCommand.isEmpty()) {
			return false;
		}
		if (registeredRootCommands != null && !registeredRootCommands.isEmpty()) {
			if (registeredRootCommands.contains(normalizedCommand)
					|| registeredRootCommands.contains(COMMAND_DOC_ALIASES.getOrDefault(normalizedCommand, normalizedCommand))) {
				return true;
			}
		}
		return FALLBACK_VANILLA_COMMANDS.contains(normalizedCommand);
	}

	private static NavigationTarget findMatchInEntries(
			TargetKind kind,
			List<DatapackInspector.SymbolEntry> entries,
			int col
	) {
		for (DatapackInspector.SymbolEntry entry : entries) {
			for (DatapackInspector.Occurrence occ : entry.occurrences()) {
				if (col >= occ.startCol() && col <= occ.endCol()) {
					return new NavigationTarget(kind, entry.name(), null, occ.startCol(), occ.endCol());
				}
			}
		}
		return null;
	}

	private static List<DatapackInspector.Occurrence> extractTagOccurrencesInLine(
			String relativePath,
			int oneBasedLine,
			String line,
			String filterTagName
	) {
		int lead = skipWhitespace(line, 0);
		if (lead >= line.length() || line.charAt(lead) == '#') {
			return List.of();
		}
		List<DatapackInspector.Occurrence> found = new ArrayList<>();

		// Selector attributes: @e[tag=foo], @a[tag=!foo]
		Matcher selMatcher = SELECTOR_TAG_PATTERN.matcher(line);
		while (selMatcher.find()) {
			String tag = selMatcher.group(1);
			if ((filterTagName == null || filterTagName.equals(tag)) && !tag.startsWith("$(")) {
				found.add(new DatapackInspector.Occurrence(
						relativePath,
						oneBasedLine,
						selMatcher.start(1),
						selMatcher.end(1),
						"selector tag=" + tag
				));
			}
		}

		// Command tokens: `tag <targets> add|remove <name>` (including after `execute ... run` / `return run`)
		int cmdStart = lead;
		if (line.charAt(cmdStart) == '$' || line.charAt(cmdStart) == '/') {
			cmdStart++;
		}
		List<Token> tokens = tokenizeLine(line, cmdStart);
		for (Token cmdTok : findCommandTokens(tokens, 0)) {
			int cmdIdx = tokens.indexOf(cmdTok);
			if (cmdIdx >= 0 && "tag".equals(normalizeCommandToken(cmdTok.text())) && cmdIdx + 3 < tokens.size()) {
				String action = tokens.get(cmdIdx + 2).text();
				if ("add".equals(action) || "remove".equals(action)) {
					Token nameTok = tokens.get(cmdIdx + 3);
					String name = nameTok.text();
					if ((filterTagName == null || filterTagName.equals(name)) && !name.startsWith("$(")) {
						found.add(new DatapackInspector.Occurrence(
								relativePath,
								oneBasedLine,
								nameTok.startCol(),
								nameTok.endCol(),
								"tag " + tokens.get(cmdIdx + 1).text() + " " + action + " " + name
						));
					}
				}
			}
		}
		return found;
	}

	private static String extractTagNameFromLineSpan(String line, int startCol, int endCol) {
		if (startCol < 0 || endCol > line.length() || endCol <= startCol) {
			return "";
		}
		return line.substring(startCol, endCol);
	}

	private static NavigationTarget findFunctionTargetAtCol(List<Token> tokens, int startIdx, int col) {
		for (Token cmdTok : findCommandTokens(tokens, startIdx)) {
			int idx = tokens.indexOf(cmdTok);
			if (idx < 0) {
				continue;
			}
			String cmd = normalizeCommandToken(cmdTok.text());
			if ("function".equals(cmd) && idx + 1 < tokens.size()) {
				Token idTok = tokens.get(idx + 1);
				if (col >= idTok.startCol() && col <= idTok.endCol() && !idTok.text().startsWith("$(")) {
					return new NavigationTarget(TargetKind.FUNCTION_ID, idTok.text(), null, idTok.startCol(), idTok.endCol());
				}
			} else if ("schedule".equals(cmd) && idx + 2 < tokens.size() && "function".equals(tokens.get(idx + 1).text())) {
				Token idTok = tokens.get(idx + 2);
				if (col >= idTok.startCol() && col <= idTok.endCol() && !idTok.text().startsWith("$(")) {
					return new NavigationTarget(TargetKind.FUNCTION_ID, idTok.text(), null, idTok.startCol(), idTok.endCol());
				}
			}
		}
		return null;
	}

	private static List<Token> findCommandTokens(List<Token> tokens, int startIdx) {
		List<Token> cmds = new ArrayList<>();
		int idx = startIdx;
		while (idx < tokens.size()) {
			Token tok = tokens.get(idx);
			cmds.add(tok);
			String norm = normalizeCommandToken(tok.text());
			if ("execute".equals(norm)) {
				int next = -1;
				for (int i = idx + 1; i < tokens.size(); i++) {
					if ("run".equals(tokens.get(i).text())) {
						next = i + 1;
						break;
					}
				}
				if (next > 0 && next < tokens.size()) {
					idx = next;
					continue;
				}
			} else if ("return".equals(norm) && idx + 2 < tokens.size() && "run".equals(tokens.get(idx + 1).text())) {
				idx = idx + 2;
				continue;
			}
			break;
		}
		return cmds;
	}

	private static List<Token> tokenizeLine(String line, int from) {
		List<Token> tokens = new ArrayList<>();
		int len = line.length();
		int i = skipWhitespace(line, from);
		while (i < len) {
			int start = i;
			int braceDepth = 0;
			int bracketDepth = 0;
			boolean inQuotes = false;
			char quoteChar = 0;

			while (i < len) {
				char c = line.charAt(i);
				if (inQuotes) {
					if (c == '\\' && i + 1 < len) {
						i += 2;
						continue;
					}
					if (c == quoteChar) {
						inQuotes = false;
					}
					i++;
					continue;
				}
				if (c == '"' || c == '\'') {
					inQuotes = true;
					quoteChar = c;
					i++;
					continue;
				}
				if (c == '{') {
					braceDepth++;
				} else if (c == '}' && braceDepth > 0) {
					braceDepth--;
				} else if (c == '[') {
					bracketDepth++;
				} else if (c == ']' && bracketDepth > 0) {
					bracketDepth--;
				} else if (Character.isWhitespace(c) && braceDepth == 0 && bracketDepth == 0) {
					break;
				}
				i++;
			}
			if (i > start) {
				tokens.add(new Token(line.substring(start, i), start, i));
			}
			i = skipWhitespace(line, i);
		}
		return tokens;
	}

	private static int skipWhitespace(String s, int i) {
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
			i++;
		}
		return i;
	}
}
