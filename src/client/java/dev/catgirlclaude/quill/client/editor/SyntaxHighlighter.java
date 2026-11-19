package dev.catgirlclaude.quill.client.editor;

import dev.catgirlclaude.quill.client.gui.QuillTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java line tokenizer providing VS Code Dark+ syntax highlighting for
 * {@code .mcfunction} and {@code .json}/{@code .mcmeta} datapack files.
 *
 * <p>Every returned span list strictly partitions {@code [0, line.length())} with no gaps or overlaps
 * so proportional font rendering matches caret and mouse hit-testing coordinates exactly.
 */
public final class SyntaxHighlighter {
	public enum Language {
		MCFUNCTION,
		JSON,
		PLAIN
	}

	public enum TokenKind {
		PLAIN,
		COMMENT,
		COMMAND,
		KEYWORD,
		BOOLEAN,
		SELECTOR,
		COORDINATE,
		NUMBER,
		STRING,
		PROPERTY,
		IDENTIFIER,
		MACRO,
		BRACKET
	}

	public record TokenSpan(int start, int end, TokenKind kind, int color) {
	}

	private static final Set<String> EXECUTE_KEYWORDS = Set.of(
			"as", "at", "in", "if", "unless", "run", "store", "result", "success",
			"positioned", "rotated", "facing", "anchored", "align", "on", "summon",
			"over", "eyes", "feet", "entity", "block", "blocks", "biome", "dimension",
			"loaded", "predicate", "score", "data", "storage", "bossbar", "function",
			"items", "matches", "with", "from", "value", "string", "append", "prepend",
			"insert", "merge", "set", "get", "remove", "add", "reset", "enable", "operation",
			"objectives", "players", "list", "modify", "display",
			"byte", "short", "int", "long", "float", "double", "spawn"
	);

	private static final Set<String> BOOLEAN_LITERALS = Set.of("true", "false", "null");

	private enum ContainerKind {
		COMPOUND,
		KV_BRACKET,
		LIST_BRACKET
	}

	private static final class ContainerFrame {
		final ContainerKind kind;
		boolean expectValue;

		ContainerFrame(ContainerKind kind, boolean expectValue) {
			this.kind = kind;
			this.expectValue = expectValue;
		}
	}

	private SyntaxHighlighter() {
	}

	public static Language languageForPath(String relativePath) {
		if (relativePath == null) {
			return Language.PLAIN;
		}
		String lower = relativePath.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".mcfunction")) {
			return Language.MCFUNCTION;
		}
		if (lower.endsWith(".json") || lower.endsWith(".mcmeta")) {
			return Language.JSON;
		}
		return Language.PLAIN;
	}

	public static int colorForKind(TokenKind kind) {
		return switch (kind) {
			case PLAIN -> QuillTheme.SYNTAX_PLAIN;
			case COMMENT -> QuillTheme.SYNTAX_COMMENT;
			case COMMAND -> QuillTheme.SYNTAX_COMMAND;
			case KEYWORD -> QuillTheme.SYNTAX_KEYWORD;
			case BOOLEAN -> QuillTheme.SYNTAX_BOOLEAN;
			case SELECTOR -> QuillTheme.SYNTAX_SELECTOR;
			case COORDINATE -> QuillTheme.SYNTAX_COORDINATE;
			case NUMBER -> QuillTheme.SYNTAX_NUMBER;
			case STRING -> QuillTheme.SYNTAX_STRING;
			case PROPERTY -> QuillTheme.SYNTAX_PROPERTY;
			case IDENTIFIER -> QuillTheme.SYNTAX_IDENTIFIER;
			case MACRO -> QuillTheme.SYNTAX_MACRO;
			case BRACKET -> QuillTheme.SYNTAX_BRACKET;
		};
	}

	public static List<TokenSpan> tokenizeLine(String line, Language language) {
		if (line == null || line.isEmpty()) {
			return List.of();
		}
		List<TokenSpan> raw = switch (language) {
			case MCFUNCTION -> tokenizeMcfunction(line);
			case JSON -> tokenizeJson(line);
			case PLAIN -> List.of(span(0, line.length(), TokenKind.PLAIN));
		};
		return mergeAdjacent(raw);
	}

	private static List<TokenSpan> tokenizeMcfunction(String line) {
		int len = line.length();
		List<TokenSpan> out = new ArrayList<>();
		int pos = 0;

		// Leading whitespace
		while (pos < len && Character.isWhitespace(line.charAt(pos))) {
			pos++;
		}
		if (pos > 0) {
			out.add(span(0, pos, TokenKind.PLAIN));
		}
		if (pos >= len) {
			return out;
		}

		// Full-line comment
		if (line.charAt(pos) == '#') {
			out.add(span(pos, len, TokenKind.COMMENT));
			return out;
		}

		// Macro prefix '$'
		boolean macroLine = false;
		if (line.charAt(pos) == '$') {
			macroLine = true;
			out.add(span(pos, pos + 1, TokenKind.MACRO));
			pos++;
		}

		boolean expectCommandVerb = true;
		boolean inExecuteOrReturn = false;
		boolean inDataOrStoreContext = false;
		int nbtTargetTokensRemaining = 0;
		boolean expectNbtPathNext = false;
		boolean canContinueNbtPath = false;
		TokenKind lastTopLevelKind = TokenKind.PLAIN;
		int lastTopLevelEnd = -1;
		java.util.ArrayDeque<ContainerFrame> containers = new java.util.ArrayDeque<>();

		while (pos < len) {
			char c = line.charAt(pos);
			boolean inContainer = !containers.isEmpty();
			ContainerFrame topFrame = containers.peek();

			// Macro interpolation $(var_name) anywhere on a macro line
			if (macroLine && c == '$' && pos + 1 < len && line.charAt(pos + 1) == '(') {
				int close = line.indexOf(')', pos + 2);
				if (close > pos + 1) {
					out.add(span(pos, close + 1, TokenKind.MACRO));
					pos = close + 1;
					canContinueNbtPath = false;
					continue;
				}
			}

			// Whitespace
			if (Character.isWhitespace(c)) {
				int start = pos;
				while (pos < len && Character.isWhitespace(line.charAt(pos))) {
					pos++;
				}
				out.add(span(start, pos, TokenKind.PLAIN));
				canContinueNbtPath = false;
				continue;
			}

			// NBT path dot continuation (e.g., ArmorItems[0].components."minecraft:dyed_color".rgb)
			if (!inContainer && c == '.' && canContinueNbtPath && pos + 1 < len && line.charAt(pos + 1) != '.') {
				out.add(span(pos, pos + 1, TokenKind.PLAIN));
				pos++;
				if (pos < len && (line.charAt(pos) == '"' || line.charAt(pos) == '\'')) {
					int end = scanQuotedString(line, pos, line.charAt(pos));
					emitWithMacroHighlights(out, line, pos, end, TokenKind.PROPERTY, macroLine);
					pos = end;
					canContinueNbtPath = true;
					lastTopLevelKind = TokenKind.PROPERTY;
					lastTopLevelEnd = pos;
				} else if (pos < len && isPathSegmentChar(line.charAt(pos))) {
					int segStart = pos;
					while (pos < len && isPathSegmentChar(line.charAt(pos))) {
						pos++;
					}
					emitWithMacroHighlights(out, line, segStart, pos, TokenKind.PROPERTY, macroLine);
					canContinueNbtPath = true;
					lastTopLevelKind = TokenKind.PROPERTY;
					lastTopLevelEnd = pos;
				} else {
					canContinueNbtPath = false;
				}
				continue;
			}

			// Quoted string (supports escapes and macro interpolations inside macro lines)
			if (c == '"' || c == '\'') {
				int end = scanQuotedString(line, pos, c);
				int look = skipWhitespace(line, end);
				boolean isKey = false;
				if (inContainer && !topFrame.expectValue && topFrame.kind != ContainerKind.LIST_BRACKET) {
					isKey = look < len && (line.charAt(look) == ':' || line.charAt(look) == '=' || line.charAt(look) == '~');
				} else if (!inContainer && (canContinueNbtPath
						|| (nbtTargetTokensRemaining == 0 && expectNbtPathNext)
						|| (end < len && line.charAt(end) == '['))) {
					isKey = true;
				}
				TokenKind qKind = isKey ? TokenKind.PROPERTY : TokenKind.STRING;
				emitWithMacroHighlights(out, line, pos, end, qKind, macroLine);
				pos = end;
				expectCommandVerb = false;
				if (!inContainer) {
					if (nbtTargetTokensRemaining > 0) {
						nbtTargetTokensRemaining--;
						canContinueNbtPath = false;
					} else if (expectNbtPathNext) {
						expectNbtPathNext = false;
						canContinueNbtPath = true;
					} else {
						canContinueNbtPath = isKey || (pos < len && line.charAt(pos) == '.');
					}
					lastTopLevelKind = qKind;
					lastTopLevelEnd = pos;
				}
				continue;
			}

			// Braces & brackets (SNBT, item components, NBT paths, selector arguments)
			if (c == '{' || c == '}' || c == '[' || c == ']') {
				if (c == '{') {
					containers.push(new ContainerFrame(ContainerKind.COMPOUND, false));
					expectNbtPathNext = false;
					canContinueNbtPath = false;
				} else if (c == '}') {
					if (!containers.isEmpty() && containers.peek().kind == ContainerKind.COMPOUND) {
						containers.pop();
					}
					canContinueNbtPath = containers.isEmpty();
				} else if (c == '[') {
					ContainerKind bracketKind;
					if (!inContainer) {
						boolean precededBySelectorOrItem = lastTopLevelEnd == pos
								&& (lastTopLevelKind == TokenKind.SELECTOR || lastTopLevelKind == TokenKind.IDENTIFIER);
						bracketKind = isKeyValueBracket(line, pos, precededBySelectorOrItem)
								? ContainerKind.KV_BRACKET
								: ContainerKind.LIST_BRACKET;
						expectNbtPathNext = false;
					} else {
						bracketKind = ContainerKind.LIST_BRACKET;
					}
					containers.push(new ContainerFrame(bracketKind, bracketKind == ContainerKind.LIST_BRACKET));
					out.add(span(pos, pos + 1, TokenKind.BRACKET));
					pos++;
					expectCommandVerb = false;
					canContinueNbtPath = false;
					// Handle SNBT typed array prefixes: [B; ...], [I; ...], [L; ...]
					if (bracketKind == ContainerKind.LIST_BRACKET
							&& pos + 1 < len
							&& (line.charAt(pos) == 'B' || line.charAt(pos) == 'I' || line.charAt(pos) == 'L')
							&& line.charAt(pos + 1) == ';') {
						out.add(span(pos, pos + 1, TokenKind.KEYWORD));
						out.add(span(pos + 1, pos + 2, TokenKind.PLAIN));
						pos += 2;
					}
					continue;
				} else {
					if (!containers.isEmpty() && containers.peek().kind != ContainerKind.COMPOUND) {
						containers.pop();
					}
					canContinueNbtPath = containers.isEmpty();
					if (containers.isEmpty() && nbtTargetTokensRemaining > 0) {
						nbtTargetTokensRemaining--;
					}
				}
				out.add(span(pos, pos + 1, TokenKind.BRACKET));
				pos++;
				expectCommandVerb = false;
				continue;
			}

			// Punctuation inside SNBT / item components / selectors
			if (inContainer && (c == ',' || c == '=' || c == '!' || c == '~' || c == '|'
					|| (c == ':' && !topFrame.expectValue && topFrame.kind == ContainerKind.COMPOUND))) {
				if (c == ',') {
					topFrame.expectValue = (topFrame.kind == ContainerKind.LIST_BRACKET);
				} else if (c == '=' || c == ':' || c == '~') {
					topFrame.expectValue = true;
				}
				out.add(span(pos, pos + 1, TokenKind.PLAIN));
				pos++;
				continue;
			}

			// Target selector (@a, @e, @p, @r, @s, @n)
			if (!inContainer && c == '@' && pos + 1 < len && isSelectorLetter(line.charAt(pos + 1))
					&& (pos + 2 >= len || !isWordChar(line.charAt(pos + 2)))) {
				out.add(span(pos, pos + 2, TokenKind.SELECTOR));
				pos += 2;
				expectCommandVerb = false;
				canContinueNbtPath = false;
				lastTopLevelKind = TokenKind.SELECTOR;
				lastTopLevelEnd = pos;
				if (nbtTargetTokensRemaining > 0 && (pos >= len || line.charAt(pos) != '[')) {
					nbtTargetTokensRemaining--;
				}
				continue;
			}

			// Relative / local coordinates (~, ~1.5, ~-2, ^, ^0.5)
			if (!inContainer && (c == '~' || c == '^')) {
				int end = scanCoordinate(line, pos);
				emitWithMacroHighlights(out, line, pos, end, TokenKind.COORDINATE, macroLine);
				pos = end;
				expectCommandVerb = false;
				canContinueNbtPath = false;
				lastTopLevelKind = TokenKind.COORDINATE;
				lastTopLevelEnd = pos;
				if (nbtTargetTokensRemaining > 0) {
					nbtTargetTokensRemaining--;
				}
				continue;
			}

			// Structured token inside SNBT / item components / selector args
			int start = pos;
			if (inContainer) {
				boolean keyContainsColon = topFrame.kind == ContainerKind.KV_BRACKET
						|| (topFrame.kind == ContainerKind.COMPOUND && !topFrame.expectValue && looksLikeAssignmentKeyWithColon(line, pos));
				pos = scanStructuredAtom(line, pos, macroLine, topFrame.expectValue || keyContainsColon);
				String atom = line.substring(start, pos);
				int look = skipWhitespace(line, pos);
				TokenKind kind;
				boolean hasDelimiterAfter = look < len
						&& (line.charAt(look) == ':' || line.charAt(look) == '=' || line.charAt(look) == '~');
				boolean isRemovedComponent = topFrame.kind == ContainerKind.KV_BRACKET
						&& !topFrame.expectValue
						&& start > 0 && line.charAt(start - 1) == '!';
				if (!topFrame.expectValue && topFrame.kind != ContainerKind.LIST_BRACKET
						&& (hasDelimiterAfter || isRemovedComponent)) {
					kind = TokenKind.PROPERTY;
				} else if (isBooleanLiteral(atom)) {
					kind = TokenKind.BOOLEAN;
				} else if (isSnbtNumberOrRange(atom)) {
					kind = TokenKind.NUMBER;
				} else if (isResourceIdentifier(atom)) {
					kind = TokenKind.IDENTIFIER;
				} else {
					kind = TokenKind.PLAIN;
				}
				emitWithMacroHighlights(out, line, start, pos, kind, macroLine);
				expectCommandVerb = false;
				continue;
			}

			// Top-level command word or argument / NBT path
			pos = scanTopLevelToken(line, pos, macroLine);
			String token = line.substring(start, pos);
			boolean followedByBracket = pos < len && line.charAt(pos) == '[';
			boolean isKvBracketNext = followedByBracket && isKeyValueBracket(line, pos, isResourceIdentifier(token));
			TokenKind kind;
			if (expectCommandVerb) {
				kind = TokenKind.COMMAND;
				String verb = token.startsWith("/") ? token.substring(1) : token;
				expectCommandVerb = false;
				inDataOrStoreContext = "data".equals(verb);
				nbtTargetTokensRemaining = 0;
				expectNbtPathNext = false;
				canContinueNbtPath = false;
				if ("execute".equals(verb) || "return".equals(verb)) {
					inExecuteOrReturn = true;
				}
			} else if (inExecuteOrReturn && "run".equals(token)) {
				kind = TokenKind.KEYWORD;
				expectCommandVerb = true;
				inDataOrStoreContext = false;
				nbtTargetTokensRemaining = 0;
				expectNbtPathNext = false;
				canContinueNbtPath = false;
			} else if (EXECUTE_KEYWORDS.contains(token)) {
				kind = TokenKind.KEYWORD;
				canContinueNbtPath = false;
				if ("data".equals(token) || "store".equals(token)) {
					inDataOrStoreContext = true;
				}
				if (inDataOrStoreContext && ("entity".equals(token) || "storage".equals(token))) {
					nbtTargetTokensRemaining = 1;
					expectNbtPathNext = true;
				} else if (inDataOrStoreContext && "block".equals(token)) {
					nbtTargetTokensRemaining = 3;
					expectNbtPathNext = true;
				} else if ("set".equals(token) || "merge".equals(token) || "get".equals(token)
						|| "remove".equals(token) || "from".equals(token) || "value".equals(token)) {
					expectNbtPathNext = false;
				}
			} else if (isBooleanLiteral(token)) {
				kind = TokenKind.BOOLEAN;
				canContinueNbtPath = false;
			} else if (isCoordinateToken(token)) {
				kind = TokenKind.COORDINATE;
				canContinueNbtPath = false;
				if (nbtTargetTokensRemaining > 0) {
					nbtTargetTokensRemaining--;
				}
			} else if (isNumberOrRange(token) || isSnbtNumberOrRange(token)) {
				kind = TokenKind.NUMBER;
				canContinueNbtPath = false;
			} else if (isResourceIdentifier(token)) {
				kind = TokenKind.IDENTIFIER;
				canContinueNbtPath = false;
				if (nbtTargetTokensRemaining > 0) {
					nbtTargetTokensRemaining--;
				}
			} else if (isDottedNbtPath(token)) {
				emitDottedNbtPath(out, line, start, pos, macroLine);
				lastTopLevelKind = TokenKind.PROPERTY;
				lastTopLevelEnd = pos;
				if (nbtTargetTokensRemaining > 0) {
					nbtTargetTokensRemaining--;
				} else {
					expectNbtPathNext = false;
				}
				canContinueNbtPath = true;
				continue;
			} else if (followedByBracket && !token.equals("*")) {
				kind = isKvBracketNext ? TokenKind.IDENTIFIER : TokenKind.PROPERTY;
				canContinueNbtPath = !isKvBracketNext;
				if (isKvBracketNext && nbtTargetTokensRemaining > 0) {
					nbtTargetTokensRemaining--;
				} else if (!isKvBracketNext) {
					expectNbtPathNext = false;
				}
			} else if (nbtTargetTokensRemaining > 0) {
				kind = TokenKind.PLAIN;
				nbtTargetTokensRemaining--;
				canContinueNbtPath = false;
			} else if (expectNbtPathNext) {
				kind = TokenKind.PROPERTY;
				expectNbtPathNext = false;
				canContinueNbtPath = true;
			} else {
				kind = TokenKind.PLAIN;
				canContinueNbtPath = false;
			}
			emitWithMacroHighlights(out, line, start, pos, kind, macroLine);
			lastTopLevelKind = kind;
			lastTopLevelEnd = pos;
		}
		return out;
	}

	private static List<TokenSpan> tokenizeJson(String line) {
		int len = line.length();
		List<TokenSpan> out = new ArrayList<>();
		int pos = 0;

		while (pos < len) {
			char c = line.charAt(pos);
			if (Character.isWhitespace(c)) {
				int start = pos;
				while (pos < len && Character.isWhitespace(line.charAt(pos))) {
					pos++;
				}
				out.add(span(start, pos, TokenKind.PLAIN));
				continue;
			}
			if (c == '"') {
				int end = scanQuotedString(line, pos, '"');
				int after = skipWhitespace(line, end);
				boolean isKey = after < len && line.charAt(after) == ':';
				out.add(span(pos, end, isKey ? TokenKind.PROPERTY : TokenKind.STRING));
				pos = end;
				continue;
			}
			if (c == '{' || c == '}' || c == '[' || c == ']') {
				out.add(span(pos, pos + 1, TokenKind.BRACKET));
				pos++;
				continue;
			}
			if (c == ':' || c == ',') {
				out.add(span(pos, pos + 1, TokenKind.PLAIN));
				pos++;
				continue;
			}
			if (c == '-' || Character.isDigit(c)) {
				int start = pos;
				pos++;
				while (pos < len) {
					char ch = line.charAt(pos);
					if (Character.isDigit(ch) || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
						pos++;
					} else {
						break;
					}
				}
				out.add(span(start, pos, TokenKind.NUMBER));
				continue;
			}
			int start = pos;
			while (pos < len && Character.isLetterOrDigit(line.charAt(pos))) {
				pos++;
			}
			if (pos == start) {
				pos++;
			}
			String word = line.substring(start, pos);
			TokenKind kind = BOOLEAN_LITERALS.contains(word) ? TokenKind.BOOLEAN : TokenKind.PLAIN;
			out.add(span(start, pos, kind));
		}
		return out;
	}

	private static void emitWithMacroHighlights(
			List<TokenSpan> out,
			String line,
			int start,
			int end,
			TokenKind baseKind,
			boolean macroLine
	) {
		if (start >= end) {
			return;
		}
		if (!macroLine) {
			out.add(span(start, end, baseKind));
			return;
		}
		int cursor = start;
		while (cursor < end) {
			int macroStart = line.indexOf("$(", cursor);
			if (macroStart < 0 || macroStart >= end) {
				out.add(span(cursor, end, baseKind));
				return;
			}
			int macroClose = line.indexOf(')', macroStart + 2);
			if (macroClose < 0 || macroClose >= end) {
				out.add(span(cursor, end, baseKind));
				return;
			}
			if (macroStart > cursor) {
				out.add(span(cursor, macroStart, baseKind));
			}
			out.add(span(macroStart, macroClose + 1, TokenKind.MACRO));
			cursor = macroClose + 1;
		}
	}

	private static int scanQuotedString(String line, int start, char quote) {
		int len = line.length();
		int pos = start + 1;
		while (pos < len) {
			char c = line.charAt(pos);
			if (c == '\\' && pos + 1 < len) {
				pos += 2;
				continue;
			}
			if (c == quote) {
				return pos + 1;
			}
			pos++;
		}
		return len;
	}

	private static int scanCoordinate(String line, int start) {
		int len = line.length();
		int pos = start + 1;
		while (pos < len) {
			char c = line.charAt(pos);
			if (Character.isWhitespace(c) || c == '{' || c == '}' || c == '[' || c == ']') {
				break;
			}
			if (c == '~' || c == '^') {
				break;
			}
			pos++;
		}
		return pos;
	}

	private static int scanTopLevelToken(String line, int start, boolean macroLine) {
		int len = line.length();
		int pos = start;
		while (pos < len) {
			char c = line.charAt(pos);
			if (Character.isWhitespace(c) || c == '{' || c == '}' || c == '[' || c == ']' || c == '"' || c == '\'') {
				break;
			}
			if (macroLine && c == '$' && pos > start && pos + 1 < len && line.charAt(pos + 1) == '(') {
				int close = line.indexOf(')', pos + 2);
				if (close > pos + 1) {
					pos = close + 1;
					continue;
				}
			}
			pos++;
		}
		return pos == start ? start + 1 : pos;
	}

	private static int scanStructuredAtom(String line, int start, boolean macroLine, boolean allowColon) {
		int len = line.length();
		int pos = start;
		while (pos < len) {
			char c = line.charAt(pos);
			if (Character.isWhitespace(c) || c == '{' || c == '}' || c == '[' || c == ']'
					|| c == ',' || c == '=' || c == '~' || c == '|' || c == '"' || c == '\'' || c == '!') {
				break;
			}
			if (c == ':' && !allowColon) {
				break;
			}
			if (macroLine && c == '$' && pos + 1 < len && line.charAt(pos + 1) == '(') {
				int close = line.indexOf(')', pos + 2);
				if (close > pos + 1) {
					pos = close + 1;
					continue;
				}
			}
			pos++;
		}
		return pos == start ? start + 1 : pos;
	}

	private static boolean isKeyValueBracket(String line, int bracketPos, boolean precededBySelectorOrItem) {
		int len = line.length();
		int i = skipWhitespace(line, bracketPos + 1);
		if (i >= len) {
			return precededBySelectorOrItem;
		}
		char first = line.charAt(i);
		if (first == ']' || first == '{' || first == '"' || first == '\'' || first == '+' || first == '-' || Character.isDigit(first)) {
			return false;
		}
		if (i + 1 < len && (first == 'B' || first == 'I' || first == 'L') && line.charAt(i + 1) == ';') {
			return false;
		}
		int braceDepth = 0;
		int bracketDepth = 0;
		boolean inQuote = false;
		char quote = 0;
		for (int p = i; p < len; p++) {
			char c = line.charAt(p);
			if (inQuote) {
				if (c == '\\' && p + 1 < len) {
					p++;
					continue;
				}
				if (c == quote) {
					inQuote = false;
				}
				continue;
			}
			if (c == '"' || c == '\'') {
				inQuote = true;
				quote = c;
				continue;
			}
			if (c == '{') {
				braceDepth++;
			} else if (c == '}' && braceDepth > 0) {
				braceDepth--;
			} else if (c == '[') {
				bracketDepth++;
			} else if (c == ']') {
				if (bracketDepth == 0) {
					break;
				}
				bracketDepth--;
			} else if (braceDepth == 0 && bracketDepth == 0) {
				if (c == '=') {
					return true;
				}
				if (c == '~' || c == '!') {
					return true;
				}
				if (c == ',') {
					break;
				}
			}
		}
		return precededBySelectorOrItem;
	}

	private static boolean looksLikeAssignmentKeyWithColon(String line, int start) {
		int len = line.length();
		for (int p = start; p < len; p++) {
			char c = line.charAt(p);
			if (c == '=') {
				return true;
			}
			if (c == ',' || c == '{' || c == '}' || c == '[' || c == ']' || Character.isWhitespace(c)) {
				return false;
			}
		}
		return false;
	}

	private static boolean isPathSegmentChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '-';
	}

	private static boolean isDottedNbtPath(String token) {
		int dot = token.indexOf('.');
		if (dot <= 0 || dot >= token.length() - 1 || token.contains("..") || token.indexOf(':') >= 0) {
			return false;
		}
		if (token.startsWith("/") || token.startsWith("$") || token.startsWith("~") || token.startsWith("^")) {
			return false;
		}
		String[] parts = token.split("\\.", -1);
		if (parts.length < 2) {
			return false;
		}
		for (String part : parts) {
			if (part.isEmpty()) {
				return false;
			}
			char first = part.charAt(0);
			if (!(Character.isLetter(first) || first == '_')) {
				return false;
			}
			for (int i = 1; i < part.length(); i++) {
				if (!isPathSegmentChar(part.charAt(i))) {
					return false;
				}
			}
		}
		return true;
	}

	private static void emitDottedNbtPath(List<TokenSpan> out, String line, int start, int end, boolean macroLine) {
		int cursor = start;
		while (cursor < end) {
			int dot = line.indexOf('.', cursor);
			if (dot < 0 || dot >= end) {
				emitWithMacroHighlights(out, line, cursor, end, TokenKind.PROPERTY, macroLine);
				return;
			}
			if (dot > cursor) {
				emitWithMacroHighlights(out, line, cursor, dot, TokenKind.PROPERTY, macroLine);
			}
			out.add(span(dot, dot + 1, TokenKind.PLAIN));
			cursor = dot + 1;
		}
	}

	private static int skipWhitespace(String line, int pos) {
		int len = line.length();
		while (pos < len && Character.isWhitespace(line.charAt(pos))) {
			pos++;
		}
		return pos;
	}

	private static boolean isSelectorLetter(char c) {
		return c == 'a' || c == 'e' || c == 'p' || c == 'r' || c == 's' || c == 'n';
	}

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static boolean isBooleanLiteral(String token) {
		return BOOLEAN_LITERALS.contains(token);
	}

	private static boolean isCoordinateToken(String token) {
		if (token.isEmpty()) {
			return false;
		}
		char first = token.charAt(0);
		if (first != '~' && first != '^') {
			return false;
		}
		return token.length() == 1 || isSimpleNumber(token.substring(1));
	}

	private static boolean isNumberOrRange(String token) {
		if (isSimpleNumber(token)) {
			return true;
		}
		int dots = token.indexOf("..");
		if (dots >= 0 && token.indexOf("..", dots + 2) < 0) {
			String left = token.substring(0, dots);
			String right = token.substring(dots + 2);
			if (left.isEmpty() && right.isEmpty()) {
				return false;
			}
			return (left.isEmpty() || isSimpleNumber(left)) && (right.isEmpty() || isSimpleNumber(right));
		}
		return false;
	}

	private static boolean isSnbtNumberOrRange(String token) {
		if (isNumberOrRange(token)) {
			return true;
		}
		if (token.length() >= 2) {
			char suffix = token.charAt(token.length() - 1);
			if (suffix == 'b' || suffix == 'B' || suffix == 's' || suffix == 'S'
					|| suffix == 'l' || suffix == 'L' || suffix == 'f' || suffix == 'F'
					|| suffix == 'd' || suffix == 'D') {
				return isSimpleNumber(token.substring(0, token.length() - 1));
			}
		}
		return false;
	}

	private static boolean isSimpleNumber(String text) {
		if (text.isEmpty()) {
			return false;
		}
		int i = 0;
		if (text.charAt(0) == '-' || text.charAt(0) == '+') {
			i = 1;
			if (i >= text.length()) {
				return false;
			}
		}
		// Hexadecimal literal (0xFF)
		if (i + 2 < text.length() && text.charAt(i) == '0' && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
			i += 2;
			boolean sawHex = false;
			for (; i < text.length(); i++) {
				char c = text.charAt(i);
				if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
					sawHex = true;
				} else if (c != '_' || !sawHex) {
					return false;
				}
			}
			return sawHex;
		}
		// Binary literal (0b1010)
		if (i + 2 < text.length() && text.charAt(i) == '0' && (text.charAt(i + 1) == 'b' || text.charAt(i + 1) == 'B')) {
			i += 2;
			boolean sawBin = false;
			for (; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c == '0' || c == '1') {
					sawBin = true;
				} else if (c != '_' || !sawBin) {
					return false;
				}
			}
			return sawBin;
		}
		boolean sawDigit = false;
		boolean sawDot = false;
		boolean sawExp = false;
		for (; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isDigit(c)) {
				sawDigit = true;
			} else if (c == '_' && sawDigit) {
				// numeric separator
			} else if (c == '.' && !sawDot && !sawExp) {
				sawDot = true;
			} else if ((c == 'e' || c == 'E') && sawDigit && !sawExp) {
				sawExp = true;
				sawDigit = false; // require at least one exponent digit
				if (i + 1 < text.length() && (text.charAt(i + 1) == '+' || text.charAt(i + 1) == '-')) {
					i++;
				}
			} else {
				return false;
			}
		}
		return sawDigit;
	}

	private static boolean isResourceIdentifier(String token) {
		String s = token.startsWith("#") ? token.substring(1) : token;
		int colon = s.indexOf(':');
		if (colon <= 0 || colon >= s.length() - 1) {
			return false;
		}
		for (int i = 0; i < colon; i++) {
			char c = s.charAt(i);
			if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) {
				return false;
			}
		}
		for (int i = colon + 1; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '/')) {
				return false;
			}
		}
		return true;
	}

	private static TokenSpan span(int start, int end, TokenKind kind) {
		return new TokenSpan(start, end, kind, colorForKind(kind));
	}

	private static List<TokenSpan> mergeAdjacent(List<TokenSpan> spans) {
		if (spans.size() <= 1) {
			return spans;
		}
		List<TokenSpan> merged = new ArrayList<>(spans.size());
		for (TokenSpan current : spans) {
			if (current.start() >= current.end()) {
				continue;
			}
			if (!merged.isEmpty()) {
				TokenSpan prev = merged.getLast();
				if (prev.kind() == current.kind() && prev.color() == current.color() && prev.end() == current.start()) {
					merged.set(merged.size() - 1, new TokenSpan(prev.start(), current.end(), prev.kind(), prev.color()));
					continue;
				}
			}
			merged.add(current);
		}
		return merged;
	}
}
