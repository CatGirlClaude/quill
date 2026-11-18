package dev.catgirlclaude.quill.client.editor;

import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.OpenTabState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure-Java full-text search and replace engine across datapack files,
 * honoring live unsaved {@link OpenTabState} buffers (with single-step undo) and
 * SHA-256 optimistic concurrency for closed files via {@code EditorBackend}.
 */
public final class WorkspaceSearchEngine {
	private static final int MAX_MATCHES_PER_FILE = 500;

	public record SearchOptions(
			String query,
			boolean caseSensitive,
			boolean regex,
			String includeGlob,
			String excludeGlob
	) {
		public SearchOptions(String query, boolean caseSensitive, boolean regex) {
			this(query, caseSensitive, regex, "", "");
		}
	}

	public record LineMatch(
			String relativePath,
			int oneBasedLine,
			int startCol,
			int endCol,
			String lineText
	) {
	}

	public record FileSearchResult(String relativePath, List<LineMatch> matches) {
	}

	public record SearchOutcome(List<FileSearchResult> files, int totalMatches, String errorMessage) {
		public boolean hasError() {
			return errorMessage != null && !errorMessage.isEmpty();
		}
	}

	public record ReplaceTextResult(String updatedContent, int replacementCount) {
	}

	public record WorkspaceReplaceSummary(
			int totalReplacements,
			int modifiedOpenTabs,
			int modifiedClosedFiles,
			List<String> errors
	) {
		public int totalModifiedFiles() {
			return modifiedOpenTabs + modifiedClosedFiles;
		}
	}

	private WorkspaceSearchEngine() {
	}

	public static boolean isSearchableFile(String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			return false;
		}
		String lower = relativePath.toLowerCase(Locale.ROOT);
		return lower.endsWith(".mcfunction")
				|| lower.endsWith(".json")
				|| lower.endsWith(".mcmeta")
				|| lower.endsWith(".txt");
	}

	public static boolean matchesIncludeExclude(String relativePath, String includeGlob, String excludeGlob) {
		if (relativePath == null || relativePath.isEmpty()) {
			return false;
		}
		String norm = relativePath.replace('\\', '/');
		while (norm.startsWith("/")) {
			norm = norm.substring(1);
		}
		int slash = norm.lastIndexOf('/');
		String fileName = slash >= 0 ? norm.substring(slash + 1) : norm;

		List<String> positiveIncludes = new ArrayList<>();
		List<String> excludes = new ArrayList<>();
		parseFilterTokens(includeGlob, positiveIncludes, excludes);
		if (excludeGlob != null && !excludeGlob.isBlank()) {
			for (String raw : excludeGlob.split(",")) {
				String tok = raw.trim();
				if (tok.startsWith("!")) {
					tok = tok.substring(1).trim();
				}
				if (!tok.isEmpty()) {
					excludes.add(tok);
				}
			}
		}

		if (!positiveIncludes.isEmpty()) {
			boolean matched = false;
			for (String inc : positiveIncludes) {
				if (matchesSingleGlob(norm, fileName, inc)) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				return false;
			}
		}
		for (String exc : excludes) {
			if (matchesSingleGlob(norm, fileName, exc)) {
				return false;
			}
		}
		return true;
	}

	private static void parseFilterTokens(String spec, List<String> positiveIncludes, List<String> excludes) {
		if (spec == null || spec.isBlank()) {
			return;
		}
		for (String raw : spec.split(",")) {
			String tok = raw.trim();
			if (tok.isEmpty()) {
				continue;
			}
			if (tok.startsWith("!")) {
				String negated = tok.substring(1).trim();
				if (!negated.isEmpty()) {
					excludes.add(negated);
				}
			} else {
				positiveIncludes.add(tok);
			}
		}
	}

	private static boolean matchesSingleGlob(String normalizedPath, String fileName, String rawPattern) {
		String pat = rawPattern.replace('\\', '/').trim();
		while (pat.startsWith("./")) {
			pat = pat.substring(2);
		}
		while (pat.startsWith("/")) {
			pat = pat.substring(1);
		}
		if (pat.isEmpty()) {
			return false;
		}
		boolean hasWildcard = pat.indexOf('*') >= 0 || pat.indexOf('?') >= 0;
		if (!pat.contains("/")) {
			if (!hasWildcard) {
				return fileName.equalsIgnoreCase(pat)
						|| normalizedPath.toLowerCase(Locale.ROOT).startsWith(pat.toLowerCase(Locale.ROOT) + "/")
						|| normalizedPath.toLowerCase(Locale.ROOT).contains("/" + pat.toLowerCase(Locale.ROOT) + "/");
			}
			Pattern regex = compileGlobRegex(pat);
			return regex.matcher(fileName).matches() || regex.matcher(normalizedPath).matches();
		}
		if (!hasWildcard) {
			String lowerPath = normalizedPath.toLowerCase(Locale.ROOT);
			String lowerPat = pat.endsWith("/") ? pat.substring(0, pat.length() - 1).toLowerCase(Locale.ROOT) : pat.toLowerCase(Locale.ROOT);
			return lowerPath.equals(lowerPat) || lowerPath.startsWith(lowerPat + "/");
		}
		return compileGlobRegex(pat).matcher(normalizedPath).matches();
	}

	private static Pattern compileGlobRegex(String glob) {
		StringBuilder rx = new StringBuilder("^");
		int i = 0;
		while (i < glob.length()) {
			char c = glob.charAt(i);
			if (c == '*') {
				if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
					if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
						rx.append("(?:.*/)?");
						i += 3;
					} else {
						rx.append(".*");
						i += 2;
					}
				} else {
					rx.append("[^/]*");
					i++;
				}
			} else if (c == '?') {
				rx.append("[^/]");
				i++;
			} else {
				if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
					rx.append('\\');
				}
				rx.append(c);
				i++;
			}
		}
		rx.append('$');
		return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	public static Pattern compilePattern(SearchOptions options) {
		if (options == null || options.query() == null || options.query().isEmpty()) {
			return null;
		}
		String source = options.regex() ? options.query() : Pattern.quote(options.query());
		int flags = options.caseSensitive() ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		return Pattern.compile(source, flags);
	}

	public static List<LineMatch> searchContent(String relativePath, String content, Pattern pattern) {
		if (pattern == null || content == null || content.isEmpty()) {
			return List.of();
		}
		String normalized = TextDocumentBuffer.normalizeLineEndings(content);
		String[] lines = normalized.split("\n", -1);
		List<LineMatch> matches = new ArrayList<>();

		for (int i = 0; i < lines.length && matches.size() < MAX_MATCHES_PER_FILE; i++) {
			String line = lines[i];
			Matcher matcher = pattern.matcher(line);
			int searchFrom = 0;
			while (searchFrom <= line.length() && matcher.find(searchFrom) && matches.size() < MAX_MATCHES_PER_FILE) {
				int start = matcher.start();
				int end = matcher.end();
				if (end > start) {
					matches.add(new LineMatch(relativePath, i + 1, start, end, line));
					searchFrom = end;
				} else {
					// Prevent infinite loop on zero-width regex matches (e.g. "^" or "a*")
					searchFrom = start + 1;
				}
			}
		}
		return matches;
	}

	public static ReplaceTextResult replaceInContent(String content, SearchOptions options, String replacement) {
		Pattern pattern = compilePattern(options);
		if (pattern == null || content == null) {
			return new ReplaceTextResult(content == null ? "" : content, 0);
		}
		String rep = replacement == null ? "" : replacement;
		String effectiveRep = options.regex() ? rep : Matcher.quoteReplacement(rep);

		String normalized = TextDocumentBuffer.normalizeLineEndings(content);
		String[] lines = normalized.split("\n", -1);
		StringBuilder out = new StringBuilder(normalized.length());
		int count = 0;

		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				out.append('\n');
			}
			String line = lines[i];
			Matcher matcher = pattern.matcher(line);
			StringBuilder replacedLine = new StringBuilder(line.length());
			while (matcher.find()) {
				if (matcher.end() <= matcher.start()) {
					continue;
				}
				try {
					matcher.appendReplacement(replacedLine, effectiveRep);
				} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
					throw new IllegalArgumentException("Invalid regex replacement: " + e.getMessage(), e);
				}
				count++;
			}
			matcher.appendTail(replacedLine);
			out.append(replacedLine);
		}
		return new ReplaceTextResult(out.toString(), count);
	}

	public static CompletableFuture<SearchOutcome> searchWorkspace(
			ClientEditorSession session,
			SearchOptions options,
			WorkspaceFileCache cache
	) {
		if (options == null || options.query() == null || options.query().isEmpty()) {
			return CompletableFuture.completedFuture(new SearchOutcome(List.of(), 0, null));
		}
		final Pattern pattern;
		try {
			pattern = compilePattern(options);
		} catch (PatternSyntaxException e) {
			return CompletableFuture.completedFuture(new SearchOutcome(List.of(), 0, "Invalid regex: " + e.getDescription()));
		}

		String inc = options.includeGlob();
		String exc = options.excludeGlob();
		return cache.loadFiles(session, path -> isSearchableFile(path) && matchesIncludeExclude(path, inc, exc))
				.thenApply(snapshots -> {
					List<FileSearchResult> results = new ArrayList<>();
					int total = 0;
					for (WorkspaceFileCache.WorkspaceFileSnapshot snap : snapshots) {
						List<LineMatch> fileMatches = searchContent(snap.relativePath(), snap.content(), pattern);
						if (!fileMatches.isEmpty()) {
							results.add(new FileSearchResult(snap.relativePath(), fileMatches));
							total += fileMatches.size();
						}
					}
					return new SearchOutcome(results, total, null);
				});
	}

	public static CompletableFuture<WorkspaceReplaceSummary> replaceInFiles(
			ClientEditorSession session,
			List<String> targetPaths,
			SearchOptions options,
			String replacement,
			WorkspaceFileCache cache
	) {
		if (options == null || options.query() == null || options.query().isEmpty() || targetPaths == null || targetPaths.isEmpty()) {
			return CompletableFuture.completedFuture(new WorkspaceReplaceSummary(0, 0, 0, List.of()));
		}
		try {
			compilePattern(options);
		} catch (PatternSyntaxException e) {
			return CompletableFuture.completedFuture(new WorkspaceReplaceSummary(0, 0, 0, List.of("Invalid regex: " + e.getDescription())));
		}

		Set<String> uniqueTargets = new LinkedHashSet<>(targetPaths);
		return cache.loadFiles(session, uniqueTargets::contains).thenCompose(snapshots -> {
			List<String> errors = new ArrayList<>();
			int[] openTabCount = {0};
			int[] closedFileCount = {0};
			int[] totalReplacements = {0};

			List<CompletableFuture<Void>> writeFutures = new ArrayList<>();

			for (WorkspaceFileCache.WorkspaceFileSnapshot snap : snapshots) {
				ReplaceTextResult replaced;
				try {
					replaced = replaceInContent(snap.content(), options, replacement);
				} catch (IllegalArgumentException e) {
					errors.add(snap.relativePath() + ": " + e.getMessage());
					continue;
				}
				if (replaced.replacementCount() <= 0) {
					continue;
				}

				OpenTabState openTab = session.findOpenTab(snap.relativePath());
				if (openTab != null) {
					CompletableFuture<Void> uiDone = new CompletableFuture<>();
					session.runOnUi(() -> {
						OpenTabState current = session.findOpenTab(snap.relativePath());
						if (current != null) {
							current.buffer().replaceAllText(replaced.updatedContent());
							openTabCount[0]++;
							totalReplacements[0] += replaced.replacementCount();
						}
						uiDone.complete(null);
					});
					writeFutures.add(uiDone);
				} else {
					CompletableFuture<Void> writeFuture = session.backend()
							.writeFile(snap.relativePath(), replaced.updatedContent(), snap.sha256(), false)
							.handle((saveRes, err) -> {
								if (err != null) {
									Throwable cause = err.getCause() != null ? err.getCause() : err;
									synchronized (errors) {
										errors.add(snap.relativePath() + ": " + cause.getMessage());
									}
								} else {
									synchronized (closedFileCount) {
										closedFileCount[0]++;
										totalReplacements[0] += replaced.replacementCount();
									}
								}
								return null;
							});
					writeFutures.add(writeFuture);
				}
			}

			return CompletableFuture.allOf(writeFutures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
				cache.invalidate();
				WorkspaceReplaceSummary summary = new WorkspaceReplaceSummary(
						totalReplacements[0],
						openTabCount[0],
						closedFileCount[0],
						List.copyOf(errors)
				);
				session.runOnUi(() -> {
					session.bumpWorkspaceRevision();
					if (summary.modifiedClosedFiles() > 0) {
						session.reloadDatapacks();
					}
					String msg = "Replaced " + summary.totalReplacements() + " occurrence(s) across "
							+ summary.totalModifiedFiles() + " file(s)";
					if (summary.modifiedOpenTabs() > 0) {
						msg += " (" + summary.modifiedOpenTabs() + " open tab(s) unsaved - Ctrl+Z to undo)";
					}
					session.setStatus(msg, !summary.errors().isEmpty());
					session.appendOutput(
							summary.errors().isEmpty() ? ClientEditorSession.LogLevel.SUCCESS : ClientEditorSession.LogLevel.WARN,
							msg
					);
					for (String err : summary.errors()) {
						session.appendOutput(ClientEditorSession.LogLevel.ERROR, "  Replace error: " + err);
					}
				});
				return summary;
			});
		});
	}
}
