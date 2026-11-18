package dev.catgirlclaude.quill.client.editor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceNavigationTest {

	@Test
	void ctrlClickResolvesScoreboardTagStorageMacroAndFindsAllUsagesAcrossFiles() {
		String lineScore = "scoreboard players add @s coins 1";
		String lineTag = "tag @a[tag=boss_fight] add phase_two";
		String lineStorage = "data modify storage demo:state flags set value 1b";
		String lineSelectorScore = "execute if entity @a[scores={coins=5..}] run say rich";

		var scoreTarget = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				lineScore,
				lineScore.indexOf("coins") + 2,
				Set.of("scoreboard", "execute", "say", "tag", "data")
		);
		assertNotNull(scoreTarget);
		assertEquals(WorkspaceNavigation.TargetKind.SCOREBOARD_OBJECTIVE, scoreTarget.kind());
		assertEquals("coins", scoreTarget.identifier());

		var tagTargetInSelector = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				lineTag,
				lineTag.indexOf("boss_fight") + 3,
				Set.of("tag")
		);
		assertNotNull(tagTargetInSelector);
		assertEquals(WorkspaceNavigation.TargetKind.ENTITY_TAG, tagTargetInSelector.kind());
		assertEquals("boss_fight", tagTargetInSelector.identifier());

		var tagTargetInCmd = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				lineTag,
				lineTag.indexOf("phase_two") + 2,
				Set.of("tag")
		);
		assertNotNull(tagTargetInCmd);
		assertEquals(WorkspaceNavigation.TargetKind.ENTITY_TAG, tagTargetInCmd.kind());
		assertEquals("phase_two", tagTargetInCmd.identifier());

		var storageTarget = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				lineStorage,
				lineStorage.indexOf("demo:state") + 4,
				Set.of("data")
		);
		assertNotNull(storageTarget);
		assertEquals(WorkspaceNavigation.TargetKind.STORAGE_ID, storageTarget.kind());
		assertEquals("demo:state", storageTarget.identifier());

		var selectorScoreTarget = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				lineSelectorScore,
				lineSelectorScore.indexOf("coins") + 1,
				Set.of("execute", "say")
		);
		assertNotNull(selectorScoreTarget);
		assertEquals(WorkspaceNavigation.TargetKind.SCOREBOARD_OBJECTIVE, selectorScoreTarget.kind());
		assertEquals("coins", selectorScoreTarget.identifier());

		Map<String, String> files = Map.of(
				"pack/data/demo/function/a.mcfunction",
				"scoreboard objectives add coins dummy\n" + lineScore + "\n" + lineTag + "\n" + lineStorage,
				"pack/data/demo/function/b.mcfunction",
				lineSelectorScore + "\ntag @s remove boss_fight\nexecute if data storage demo:state flags run function demo:sub"
		);

		List<DatapackInspector.Occurrence> coinsUsages = WorkspaceNavigation.findAllUsages(
				WorkspaceNavigation.TargetKind.SCOREBOARD_OBJECTIVE, "coins", files);
		assertEquals(3, coinsUsages.size());

		List<DatapackInspector.Occurrence> tagUsages = WorkspaceNavigation.findAllUsages(
				WorkspaceNavigation.TargetKind.ENTITY_TAG, "boss_fight", files);
		assertEquals(2, tagUsages.size());

		List<DatapackInspector.Occurrence> storageUsages = WorkspaceNavigation.findAllUsages(
				WorkspaceNavigation.TargetKind.STORAGE_ID, "demo:state", files);
		assertEquals(2, storageUsages.size());
	}

	@Test
	void ctrlClickResolvesCommandsToMinecraftWikiDocsAndFunctionsToFiles() {
		String execLine = "execute as @a run say hello";
		var execDoc = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				execLine,
				2,
				Set.of("execute", "say")
		);
		assertNotNull(execDoc);
		assertEquals(WorkspaceNavigation.TargetKind.COMMAND_DOC, execDoc.kind());
		assertEquals("https://minecraft.wiki/w/Commands/execute", execDoc.url());

		var sayDoc = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				execLine,
				execLine.indexOf("say") + 1,
				Set.of("execute", "say")
		);
		assertNotNull(sayDoc);
		assertEquals(WorkspaceNavigation.TargetKind.COMMAND_DOC, sayDoc.kind());
		assertEquals("https://minecraft.wiki/w/Commands/say", sayDoc.url());

		String tpLine = "/tp @s ~ ~1 ~";
		var tpDoc = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				tpLine,
				2,
				Set.of()
		);
		assertNotNull(tpDoc);
		assertEquals(WorkspaceNavigation.TargetKind.COMMAND_DOC, tpDoc.kind());
		assertEquals("https://minecraft.wiki/w/Commands/teleport", tpDoc.url());

		// Comments must not trigger navigation
		assertNull(WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				"# execute say coins",
				4,
				Set.of("execute", "say")
		));

		// Function call resolves to workspace path
		String fnLine = "function demo:nested/init";
		var fnTarget = WorkspaceNavigation.resolveTargetAtCursor(
				"pack/data/demo/function/a.mcfunction",
				fnLine,
				fnLine.indexOf("demo:nested/init") + 3,
				Set.of("function")
		);
		assertNotNull(fnTarget);
		assertEquals(WorkspaceNavigation.TargetKind.FUNCTION_ID, fnTarget.kind());
		assertEquals("demo:nested/init", fnTarget.identifier());

		List<String> allPaths = List.of(
				"my_pack/pack.mcmeta",
				"my_pack/data/demo/function/nested/init.mcfunction"
		);
		assertEquals(
				"my_pack/data/demo/function/nested/init.mcfunction",
				WorkspaceNavigation.resolveFunctionFilePath("demo:nested/init", allPaths)
		);
	}
}
