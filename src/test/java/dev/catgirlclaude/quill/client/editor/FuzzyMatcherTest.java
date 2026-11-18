package dev.catgirlclaude.quill.client.editor;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzyMatcherTest {

	@Test
	void emptyQueryMatchesEverythingWithZeroScore() {
		Optional<FuzzyMatcher.MatchResult> res = FuzzyMatcher.match("", "my_pack/data/demo/function/load.mcfunction");
		assertTrue(res.isPresent());
		assertEquals(0, res.get().score());
		assertTrue(res.get().matchedIndices().isEmpty());
	}

	@Test
	void rejectsNonSubsequences() {
		assertTrue(FuzzyMatcher.match("xyz", "my_pack/data/demo/function/load.mcfunction").isEmpty());
		assertTrue(FuzzyMatcher.match("daol", "load.mcfunction").isEmpty());
	}

	@Test
	void prefersExactBasenameAndWordBoundaryMatches() {
		FuzzyMatcher.MatchResult exactFile = FuzzyMatcher.match("load", "pack/data/demo/function/load.mcfunction").orElseThrow();
		FuzzyMatcher.MatchResult dirOnly = FuzzyMatcher.match("load", "load_pack/data/demo/function/tick.mcfunction").orElseThrow();
		assertTrue(exactFile.score() > dirOnly.score(), "Filename match should rank above parent directory match");

		FuzzyMatcher.MatchResult boundary = FuzzyMatcher.match("nd", "Datapack: New Datapack").orElseThrow();
		FuzzyMatcher.MatchResult scattered = FuzzyMatcher.match("nd", "Running Command").orElseThrow();
		assertTrue(boundary.score() > scattered.score(), "Word-boundary match should rank above scattered letters");
		assertEquals(2, boundary.matchedIndices().size());
	}
}
