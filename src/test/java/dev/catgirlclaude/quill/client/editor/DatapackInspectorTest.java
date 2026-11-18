package dev.catgirlclaude.quill.client.editor;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackInspectorTest {

	@Test
	void detectsScoreboardObjectivesFromCommandsExecuteAndSelectors() {
		String loadFn = """
				# scoreboard objectives add ignored_comment dummy
				scoreboard objectives add coins dummy "Coins"
				scoreboard players set @a coins 10
				scoreboard players operation @s coins += #bonus bonus_pool
				scoreboard players reset @a *
				execute as @e[type=minecraft:player, scores={mana=5.., coins=1..}] if score @s coins >= #min threshold run scoreboard players add @s coins 1
				execute store result score #count total_count run data get storage demo:state items
				""";

		Map<String, String> files = Map.of("pack/data/demo/function/load.mcfunction", loadFn);
		DatapackInspector.InspectionReport report = DatapackInspector.inspectFiles(files);

		assertEquals(1, report.scannedFileCount());
		List<String> objNames = report.scoreboardObjectives().stream().map(DatapackInspector.SymbolEntry::name).toList();
		assertEquals(List.of("bonus_pool", "coins", "mana", "threshold", "total_count"), objNames);

		DatapackInspector.SymbolEntry coins = report.scoreboardObjectives().stream()
				.filter(e -> "coins".equals(e.name()))
				.findFirst()
				.orElseThrow();
		assertEquals("dummy", coins.detail());
		assertEquals(6, coins.occurrences().size());

		// Verify exact column span of "coins" on line 2
		DatapackInspector.Occurrence defOcc = coins.occurrences().getFirst();
		assertEquals(2, defOcc.oneBasedLine());
		String line2 = loadFn.split("\n")[1];
		assertEquals("coins", line2.substring(defOcc.startCol(), defOcc.endCol()));
	}

	@Test
	void detectsStorageNamespacesAndMacroParametersAcrossFiles() {
		String callerFn = """
				data merge storage demo:config {rate: 2}
				data modify storage demo:state player.name set from storage demo:input raw_name
				execute if data storage demo:state player.name run function demo:greet with storage demo:state player
				execute store result storage demo:metrics runs int 1 run return run data get storage demo:config rate
				""";
		String macroFn = """
				# $(not_a_macro_comment)
				say $(not_a_macro_line)
				$tellraw @a {"text":"Hello $(name), level $(level)!"}
				$execute if score @s coins matches $(level).. run say $(name)
				""";

		Map<String, String> files = new LinkedHashMap<>();
		files.put("pack/data/demo/function/caller.mcfunction", callerFn);
		files.put("pack/data/demo/function/greet.mcfunction", macroFn);

		DatapackInspector.InspectionReport report = DatapackInspector.inspectFiles(files);
		assertEquals(2, report.scannedFileCount());

		List<String> storageNames = report.storageEntries().stream().map(DatapackInspector.SymbolEntry::name).toList();
		assertEquals(List.of("demo:config", "demo:input", "demo:metrics", "demo:state"), storageNames);

		DatapackInspector.SymbolEntry state = report.storageEntries().stream()
				.filter(e -> "demo:state".equals(e.name()))
				.findFirst()
				.orElseThrow();
		assertEquals(3, state.occurrences().size());
		assertTrue(state.detail().contains("player.name"));
		assertTrue(state.detail().contains("player"));

		List<String> macroNames = report.macroParameters().stream().map(DatapackInspector.SymbolEntry::name).toList();
		assertEquals(List.of("$(level)", "$(name)"), macroNames);

		DatapackInspector.SymbolEntry nameMacro = report.macroParameters().stream()
				.filter(e -> "$(name)".equals(e.name()))
				.findFirst()
				.orElseThrow();
		assertEquals(2, nameMacro.occurrences().size());
		String line3 = macroFn.split("\n")[2];
		DatapackInspector.Occurrence firstMacro = nameMacro.occurrences().getFirst();
		assertEquals(3, firstMacro.oneBasedLine());
		assertEquals("$(name)", line3.substring(firstMacro.startCol(), firstMacro.endCol()));
	}
}
