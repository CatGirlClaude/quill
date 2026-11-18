package dev.catgirlclaude.quill.client.editor;

import dev.catgirlclaude.quill.client.session.ClientEditorSession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java static analyzer scanning {@code .mcfunction} files across datapacks to extract
 * scoreboard objectives, command storage namespaces/paths, and macro parameters with clickable occurrences.
 */
public final class DatapackInspector {
	private static final Pattern MACRO_VAR_PATTERN = Pattern.compile("\\$\\(([A-Za-z0-9_]+)\\)");
	private static final Pattern SCORES_BLOCK_PATTERN = Pattern.compile("scores\\s*=\\s*\\{([^}]*)\\}");
	private static final Pattern SCORE_KEY_PATTERN = Pattern.compile("([A-Za-z0-9_.+-]+)\\s*=");
	private static final Set<String> SCOREBOARD_PLAYER_OPS = Set.of(
			"get", "set", "add", "remove", "reset", "enable", "display"
	);
	private static final Set<String> DATA_OPS = Set.of("get", "merge", "modify", "remove");
	private static final Set<String> SCORE_COMPARE_OPS = Set.of("<", "<=", "=", ">=", ">");

	public record Occurrence(
			String relativePath,
			int oneBasedLine,
			int startCol,
			int endCol,
			String context
	) {
	}

	public record SymbolEntry(
			String name,
			String detail,
			List<Occurrence> occurrences
	) {
	}

	public record InspectionReport(
			List<SymbolEntry> scoreboardObjectives,
			List<SymbolEntry> storageEntries,
			List<SymbolEntry> macroParameters,
			int scannedFileCount
	) {
		public static final InspectionReport EMPTY = new InspectionReport(List.of(), List.of(), List.of(), 0);
	}

	private record Token(String text, int startCol, int endCol) {
	}

	private static final class SymbolAccumulator {
		final String name;
		final Set<String> details = new LinkedHashSet<>();
		final List<Occurrence> occurrences = new ArrayList<>();

		SymbolAccumulator(String name) {
			this.name = name;
		}

		void addDetail(String detail) {
			if (detail != null && !detail.isBlank()) {
				details.add(detail);
			}
		}

		void addOccurrence(Occurrence occ) {
			occurrences.add(occ);
		}

		SymbolEntry build() {
			String joined = details.isEmpty() ? "" : String.join(", ", details);
			return new SymbolEntry(name, joined, List.copyOf(occurrences));
		}
	}

	private DatapackInspector() {
	}

	public static CompletableFuture<InspectionReport> inspectWorkspace(
			ClientEditorSession session,
			WorkspaceFileCache cache
	) {
		return cache.loadFiles(session, path -> path.toLowerCase(Locale.ROOT).endsWith(".mcfunction"))
				.thenApply(snapshots -> {
					Map<String, String> files = new LinkedHashMap<>();
					for (WorkspaceFileCache.WorkspaceFileSnapshot snap : snapshots) {
						files.put(snap.relativePath(), snap.content());
					}
					return inspectFiles(files);
				});
	}

	public static InspectionReport inspectFiles(Map<String, String> filesByPath) {
		if (filesByPath == null || filesByPath.isEmpty()) {
			return InspectionReport.EMPTY;
		}
		Map<String, SymbolAccumulator> objectives = new LinkedHashMap<>();
		Map<String, SymbolAccumulator> storages = new LinkedHashMap<>();
		Map<String, SymbolAccumulator> macros = new LinkedHashMap<>();

		for (Map.Entry<String, String> entry : filesByPath.entrySet()) {
			String path = entry.getKey();
			String content = entry.getValue();
			if (content == null || content.isEmpty()) {
				continue;
			}
			analyzeFile(path, content, objectives, storages, macros);
		}

		return new InspectionReport(
				toSortedEntries(objectives),
				toSortedEntries(storages),
				toSortedEntries(macros),
				filesByPath.size()
		);
	}

	private static List<SymbolEntry> toSortedEntries(Map<String, SymbolAccumulator> map) {
		List<SymbolEntry> list = new ArrayList<>(map.size());
		for (SymbolAccumulator acc : map.values()) {
			list.add(acc.build());
		}
		list.sort(Comparator.comparing(SymbolEntry::name, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(list);
	}

	private static void analyzeFile(
			String relativePath,
			String content,
			Map<String, SymbolAccumulator> objectives,
			Map<String, SymbolAccumulator> storages,
			Map<String, SymbolAccumulator> macros
	) {
		String normalized = TextDocumentBuffer.normalizeLineEndings(content);
		String[] lines = normalized.split("\n", -1);

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int oneBasedLine = i + 1;
			int lead = skipWhitespace(line, 0);
			if (lead >= line.length() || line.charAt(lead) == '#') {
				continue;
			}

			boolean macroLine = line.charAt(lead) == '$';
			if (macroLine) {
				extractMacroParameters(relativePath, oneBasedLine, line, macros);
			}

			// Extract selector scores={...} anywhere on the command line
			extractSelectorScores(relativePath, oneBasedLine, line, objectives);

			// Parse command tokens (stripping leading '$' or '/')
			int cmdStart = lead;
			if (line.charAt(cmdStart) == '$' || line.charAt(cmdStart) == '/') {
				cmdStart++;
			}
			List<Token> tokens = tokenizeLine(line, cmdStart);
			if (tokens.isEmpty()) {
				continue;
			}
			analyzeCommandTokens(relativePath, oneBasedLine, line, tokens, 0, objectives, storages);
		}
	}

	private static void analyzeCommandTokens(
			String relativePath,
			int oneBasedLine,
			String rawLine,
			List<Token> tokens,
			int startIdx,
			Map<String, SymbolAccumulator> objectives,
			Map<String, SymbolAccumulator> storages
	) {
		if (startIdx >= tokens.size()) {
			return;
		}
		String cmd = tokens.get(startIdx).text();

		if ("execute".equals(cmd)) {
			analyzeExecuteChain(relativePath, oneBasedLine, rawLine, tokens, startIdx + 1, objectives, storages);
			return;
		}
		if ("return".equals(cmd) && startIdx + 2 < tokens.size() && "run".equals(tokens.get(startIdx + 1).text())) {
			analyzeCommandTokens(relativePath, oneBasedLine, rawLine, tokens, startIdx + 2, objectives, storages);
			return;
		}
		if ("scoreboard".equals(cmd)) {
			analyzeScoreboardCommand(relativePath, oneBasedLine, tokens, startIdx, objectives);
			return;
		}
		if ("data".equals(cmd)) {
			analyzeDataCommand(relativePath, oneBasedLine, tokens, startIdx, storages);
			return;
		}
		if ("function".equals(cmd)) {
			// function <id> with storage <ns:id> [path]
			for (int i = startIdx + 2; i + 2 < tokens.size(); i++) {
				if ("with".equals(tokens.get(i).text()) && "storage".equals(tokens.get(i + 1).text())) {
					Token nsTok = tokens.get(i + 2);
					String nbtPath = (i + 3 < tokens.size()) ? tokens.get(i + 3).text() : "";
					recordStorage(storages, nsTok, nbtPath, relativePath, oneBasedLine,
							"function with storage " + nsTok.text() + (nbtPath.isEmpty() ? "" : " " + nbtPath));
					break;
				}
			}
		}
	}

	private static void analyzeExecuteChain(
			String relativePath,
			int oneBasedLine,
			String rawLine,
			List<Token> tokens,
			int idx,
			Map<String, SymbolAccumulator> objectives,
			Map<String, SymbolAccumulator> storages
	) {
		int i = idx;
		while (i < tokens.size()) {
			String t = tokens.get(i).text();
			if ("run".equals(t)) {
				analyzeCommandTokens(relativePath, oneBasedLine, rawLine, tokens, i + 1, objectives, storages);
				return;
			}
			if (("if".equals(t) || "unless".equals(t)) && i + 1 < tokens.size()) {
				String sub = tokens.get(i + 1).text();
				if ("score".equals(sub) && i + 4 < tokens.size()) {
					Token obj1 = tokens.get(i + 3);
					recordObjective(objectives, obj1, null, relativePath, oneBasedLine,
							t + " score " + tokens.get(i + 2).text() + " " + obj1.text());
					String op = tokens.get(i + 4).text();
					if (SCORE_COMPARE_OPS.contains(op) && i + 6 < tokens.size()) {
						Token obj2 = tokens.get(i + 6);
						recordObjective(objectives, obj2, null, relativePath, oneBasedLine,
								t + " score ... " + op + " " + tokens.get(i + 5).text() + " " + obj2.text());
						i += 7;
						continue;
					}
					i += 5;
					continue;
				}
				if ("data".equals(sub) && i + 4 < tokens.size() && "storage".equals(tokens.get(i + 2).text())) {
					Token nsTok = tokens.get(i + 3);
					String nbtPath = tokens.get(i + 4).text();
					recordStorage(storages, nsTok, nbtPath, relativePath, oneBasedLine,
							t + " data storage " + nsTok.text() + " " + nbtPath);
					i += 5;
					continue;
				}
			}
			if ("store".equals(t) && i + 2 < tokens.size()) {
				String storeTarget = tokens.get(i + 2).text();
				if ("score".equals(storeTarget) && i + 4 < tokens.size()) {
					Token obj = tokens.get(i + 4);
					recordObjective(objectives, obj, null, relativePath, oneBasedLine,
							"store " + tokens.get(i + 1).text() + " score " + tokens.get(i + 3).text() + " " + obj.text());
					i += 5;
					continue;
				}
				if ("storage".equals(storeTarget) && i + 4 < tokens.size()) {
					Token nsTok = tokens.get(i + 3);
					String nbtPath = tokens.get(i + 4).text();
					recordStorage(storages, nsTok, nbtPath, relativePath, oneBasedLine,
							"store " + tokens.get(i + 1).text() + " storage " + nsTok.text() + " " + nbtPath);
					i += 5;
					continue;
				}
			}
			i++;
		}
	}

	private static void analyzeScoreboardCommand(
			String relativePath,
			int oneBasedLine,
			List<Token> tokens,
			int startIdx,
			Map<String, SymbolAccumulator> objectives
	) {
		if (startIdx + 2 >= tokens.size()) {
			return;
		}
		String group = tokens.get(startIdx + 1).text();
		String action = tokens.get(startIdx + 2).text();

		if ("objectives".equals(group)) {
			if ("add".equals(action) && startIdx + 4 < tokens.size()) {
				Token obj = tokens.get(startIdx + 3);
				String criteria = tokens.get(startIdx + 4).text();
				recordObjective(objectives, obj, criteria, relativePath, oneBasedLine,
						"objectives add " + obj.text() + " " + criteria);
			} else if (("remove".equals(action) || "modify".equals(action)) && startIdx + 3 < tokens.size()) {
				Token obj = tokens.get(startIdx + 3);
				recordObjective(objectives, obj, null, relativePath, oneBasedLine,
						"objectives " + action + " " + obj.text());
			} else if ("setdisplay".equals(action) && startIdx + 4 < tokens.size()) {
				Token obj = tokens.get(startIdx + 4);
				recordObjective(objectives, obj, null, relativePath, oneBasedLine,
						"objectives setdisplay " + tokens.get(startIdx + 3).text() + " " + obj.text());
			}
			return;
		}

		if ("players".equals(group)) {
			if ("operation".equals(action) && startIdx + 7 < tokens.size()) {
				Token obj1 = tokens.get(startIdx + 4);
				Token obj2 = tokens.get(startIdx + 7);
				recordObjective(objectives, obj1, null, relativePath, oneBasedLine,
						"players operation " + tokens.get(startIdx + 3).text() + " " + obj1.text());
				recordObjective(objectives, obj2, null, relativePath, oneBasedLine,
						"players operation ... " + tokens.get(startIdx + 5).text() + " " + tokens.get(startIdx + 6).text() + " " + obj2.text());
			} else if (SCOREBOARD_PLAYER_OPS.contains(action) && startIdx + 4 < tokens.size()) {
				Token obj = tokens.get(startIdx + 4);
				recordObjective(objectives, obj, null, relativePath, oneBasedLine,
						"players " + action + " " + tokens.get(startIdx + 3).text() + " " + obj.text());
			}
		}
	}

	private static void analyzeDataCommand(
			String relativePath,
			int oneBasedLine,
			List<Token> tokens,
			int startIdx,
			Map<String, SymbolAccumulator> storages
	) {
		if (startIdx + 3 >= tokens.size()) {
			return;
		}
		String op = tokens.get(startIdx + 1).text();
		if (!DATA_OPS.contains(op)) {
			return;
		}
		// Primary target: data <op> storage <ns:id> [path]
		if ("storage".equals(tokens.get(startIdx + 2).text())) {
			Token nsTok = tokens.get(startIdx + 3);
			String nbtPath = "";
			if (!"merge".equals(op) && startIdx + 4 < tokens.size() && !tokens.get(startIdx + 4).text().startsWith("{")) {
				nbtPath = tokens.get(startIdx + 4).text();
			}
			recordStorage(storages, nsTok, nbtPath, relativePath, oneBasedLine,
					"data " + op + " storage " + nsTok.text() + (nbtPath.isEmpty() ? "" : " " + nbtPath));
		}
		// Secondary source in `data modify ... from/string storage <ns:id> [path]`
		if ("modify".equals(op)) {
			for (int i = startIdx + 4; i + 2 < tokens.size(); i++) {
				String kw = tokens.get(i).text();
				if (("from".equals(kw) || "string".equals(kw)) && "storage".equals(tokens.get(i + 1).text())) {
					Token srcNs = tokens.get(i + 2);
					String srcPath = (i + 3 < tokens.size()) ? tokens.get(i + 3).text() : "";
					recordStorage(storages, srcNs, srcPath, relativePath, oneBasedLine,
							"data modify ... " + kw + " storage " + srcNs.text() + (srcPath.isEmpty() ? "" : " " + srcPath));
					break;
				}
			}
		}
	}

	private static void extractSelectorScores(
			String relativePath,
			int oneBasedLine,
			String line,
			Map<String, SymbolAccumulator> objectives
	) {
		Matcher blockMatcher = SCORES_BLOCK_PATTERN.matcher(line);
		while (blockMatcher.find()) {
			String body = blockMatcher.group(1);
			int bodyStart = blockMatcher.start(1);
			Matcher keyMatcher = SCORE_KEY_PATTERN.matcher(body);
			while (keyMatcher.find()) {
				String objName = keyMatcher.group(1);
				int startCol = bodyStart + keyMatcher.start(1);
				int endCol = bodyStart + keyMatcher.end(1);
				recordObjective(objectives, new Token(objName, startCol, endCol), null, relativePath, oneBasedLine,
						"selector scores={" + objName + "=...}");
			}
		}
	}

	private static void extractMacroParameters(
			String relativePath,
			int oneBasedLine,
			String line,
			Map<String, SymbolAccumulator> macros
	) {
		Matcher m = MACRO_VAR_PATTERN.matcher(line);
		while (m.find()) {
			String param = m.group(1);
			String displayName = "$(" + param + ")";
			SymbolAccumulator acc = macros.computeIfAbsent(displayName, SymbolAccumulator::new);
			acc.addOccurrence(new Occurrence(relativePath, oneBasedLine, m.start(), m.end(), line.trim()));
		}
	}

	private static void recordObjective(
			Map<String, SymbolAccumulator> objectives,
			Token token,
			String criteria,
			String relativePath,
			int oneBasedLine,
			String context
	) {
		String name = token.text();
		if (name.isEmpty() || "*".equals(name) || name.startsWith("$(")) {
			return;
		}
		SymbolAccumulator acc = objectives.computeIfAbsent(name, SymbolAccumulator::new);
		acc.addDetail(criteria);
		acc.addOccurrence(new Occurrence(relativePath, oneBasedLine, token.startCol(), token.endCol(), context));
	}

	private static void recordStorage(
			Map<String, SymbolAccumulator> storages,
			Token nsToken,
			String nbtPath,
			String relativePath,
			int oneBasedLine,
			String context
	) {
		String ns = nsToken.text();
		if (ns.isEmpty() || ns.startsWith("$(")) {
			return;
		}
		SymbolAccumulator acc = storages.computeIfAbsent(ns, SymbolAccumulator::new);
		if (nbtPath != null && !nbtPath.isBlank() && !nbtPath.startsWith("{") && !nbtPath.startsWith("$(")) {
			acc.addDetail(nbtPath);
		}
		acc.addOccurrence(new Occurrence(relativePath, oneBasedLine, nsToken.startCol(), nsToken.endCol(), context));
	}

	/**
	 * Splits a command line into top-level whitespace-delimited tokens while keeping bracketed
	 * selectors ({@code @e[...]}), SNBT compounds ({@code {...}}), and quoted strings intact.
	 */
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
