package dev.catgirlclaude.quill.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure-Java VS Code-style fuzzy subsequence matcher with path/word-boundary and filename bonuses.
 */
public final class FuzzyMatcher {
	public record MatchResult(int score, List<Integer> matchedIndices) {
	}

	private FuzzyMatcher() {
	}

	public static Optional<MatchResult> match(String rawQuery, String target) {
		if (target == null) {
			return Optional.empty();
		}
		String query = rawQuery == null ? "" : rawQuery.trim();
		if (query.isEmpty()) {
			return Optional.of(new MatchResult(0, List.of()));
		}

		String qLower = query.toLowerCase(Locale.ROOT);
		String tLower = target.toLowerCase(Locale.ROOT);
		int lastSlash = target.lastIndexOf('/');

		MatchResult best = scoreSubsequence(query, qLower, target, tLower, 0, lastSlash);
		if (best == null) {
			return Optional.empty();
		}

		// Also try matching starting directly inside the basename so filenames rank above matching parent directories
		if (lastSlash >= 0 && lastSlash + 1 < target.length()) {
			MatchResult baseMatch = scoreSubsequence(query, qLower, target, tLower, lastSlash + 1, lastSlash);
			if (baseMatch != null && baseMatch.score() > best.score()) {
				best = baseMatch;
			}
		}

		// Also try preferring word-boundary anchors if multiple occurrences exist
		MatchResult boundaryMatch = scoreBoundaryAnchored(query, qLower, target, tLower, lastSlash);
		if (boundaryMatch != null && boundaryMatch.score() > best.score()) {
			best = boundaryMatch;
		}

		return Optional.of(best);
	}

	private static MatchResult scoreSubsequence(
			String query,
			String qLower,
			String target,
			String tLower,
			int startPos,
			int lastSlash
	) {
		List<Integer> indices = new ArrayList<>(qLower.length());
		int tIdx = startPos;
		for (int qi = 0; qi < qLower.length(); qi++) {
			char qc = qLower.charAt(qi);
			int found = tLower.indexOf(qc, tIdx);
			if (found < 0) {
				return null;
			}
			indices.add(found);
			tIdx = found + 1;
		}
		return new MatchResult(computeScore(query, target, indices, lastSlash), List.copyOf(indices));
	}

	private static MatchResult scoreBoundaryAnchored(
			String query,
			String qLower,
			String target,
			String tLower,
			int lastSlash
	) {
		List<Integer> indices = new ArrayList<>(qLower.length());
		int tIdx = 0;
		for (int qi = 0; qi < qLower.length(); qi++) {
			char qc = qLower.charAt(qi);
			int bestPos = -1;
			int scan = tLower.indexOf(qc, tIdx);
			while (scan >= 0) {
				if (bestPos < 0) {
					bestPos = scan;
				}
				boolean consecutive = !indices.isEmpty() && scan == indices.getLast() + 1;
				if (consecutive || isBoundary(target, scan)) {
					bestPos = scan;
					break;
				}
				scan = tLower.indexOf(qc, scan + 1);
			}
			if (bestPos < 0) {
				return null;
			}
			indices.add(bestPos);
			tIdx = bestPos + 1;
		}
		return new MatchResult(computeScore(query, target, indices, lastSlash), List.copyOf(indices));
	}

	private static int computeScore(String query, String target, List<Integer> indices, int lastSlash) {
		int score = 0;
		int prev = -2;
		int consecutiveRun = 0;
		for (int i = 0; i < indices.size(); i++) {
			int idx = indices.get(i);
			score += 10;
			if (query.charAt(i) == target.charAt(idx)) {
				score += 2;
			}
			if (idx == 0) {
				score += 22;
			} else if (isBoundary(target, idx)) {
				score += 18;
			}
			if (idx > lastSlash) {
				score += 12;
			}
			if (idx == prev + 1) {
				consecutiveRun++;
				score += 12 + Math.min(18, consecutiveRun * 3);
			} else {
				consecutiveRun = 0;
				if (prev >= 0) {
					score -= Math.min(15, idx - prev - 1);
				}
			}
			prev = idx;
		}

		// Exact basename or full-string prefix boost
		String base = lastSlash >= 0 ? target.substring(lastSlash + 1) : target;
		if (base.equalsIgnoreCase(query)) {
			score += 60;
		} else if (base.toLowerCase(Locale.ROOT).startsWith(query.toLowerCase(Locale.ROOT))) {
			score += 30;
		}

		score -= target.length();
		return score;
	}

	private static boolean isBoundary(String target, int idx) {
		if (idx <= 0) {
			return true;
		}
		char prev = target.charAt(idx - 1);
		char curr = target.charAt(idx);
		if (prev == '/' || prev == '\\' || prev == '_' || prev == '-' || prev == '.' || prev == ' ' || prev == ':') {
			return true;
		}
		return Character.isLowerCase(prev) && Character.isUpperCase(curr);
	}
}
