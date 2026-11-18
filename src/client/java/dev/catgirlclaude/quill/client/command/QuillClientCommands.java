package dev.catgirlclaude.quill.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.catgirlclaude.quill.client.gui.QuillIdeScreen;
import dev.catgirlclaude.quill.client.network.ClientNetworkHandler;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.ClientSessionManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registers client-side {@code /quill} commands using Fabric API's
 * {@link ClientCommandRegistrationCallback} so commands execute locally on the client
 * without being sent to the server.
 */
public final class QuillClientCommands {
	public interface CommandActions {
		void openIde();

		void openSettings();

		void openExplorer();

		void openSearch(String query);

		void openInspector();

		void openPresets();

		void openFile(String path);

		void newDatapack();

		void reloadDatapacks();

		List<String> suggestFilePaths();
	}

	private QuillClientCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		registerTree(
				dispatcher,
				ClientCommands::literal,
				name -> ClientCommands.argument(name, StringArgumentType.greedyString()),
				LiveCommandActions::new
		);
	}

	public static <S> void registerTree(
			CommandDispatcher<S> dispatcher,
			Function<String, LiteralArgumentBuilder<S>> literalFn,
			Function<String, RequiredArgumentBuilder<S, String>> greedyStringArgFn,
			Function<S, CommandActions> actionsResolver
	) {
		LiteralArgumentBuilder<S> root = literalFn.apply("quill")
				.executes(ctx -> {
					actionsResolver.apply(ctx.getSource()).openIde();
					return 1;
				})
				.then(literalFn.apply("config")
						.executes(ctx -> {
							actionsResolver.apply(ctx.getSource()).openSettings();
							return 1;
						}))
				.then(literalFn.apply("settings")
						.executes(ctx -> {
							actionsResolver.apply(ctx.getSource()).openSettings();
							return 1;
						}))
				.then(literalFn.apply("explorer")
						.executes(ctx -> {
							actionsResolver.apply(ctx.getSource()).openExplorer();
							return 1;
						}))
				.then(literalFn.apply("search")
						.executes(ctx -> {
							actionsResolver.apply(ctx.getSource()).openSearch("");
							return 1;
						})
						.then(greedyStringArgFn.apply("query")
								.executes(ctx -> {
									String query = StringArgumentType.getString(ctx, "query");
									actionsResolver.apply(ctx.getSource()).openSearch(query);
									return 1;
								})))
				.then(literalFn.apply("inspector")
						.executes(ctx -> {
							actionsResolver.apply(ctx.getSource()).openInspector();
							return 1;
						}))
				.then(literalFn.apply("presets")
						.executes(ctx -> {
							actionsResolver.apply(ctx.getSource()).openPresets();
							return 1;
						}))
				.then(literalFn.apply("open")
						.then(greedyStringArgFn.apply("path")
								.suggests((ctx, builder) -> suggestFilePaths(
										actionsResolver.apply(ctx.getSource()).suggestFilePaths(),
										builder
								))
								.executes(ctx -> {
									String path = StringArgumentType.getString(ctx, "path");
									actionsResolver.apply(ctx.getSource()).openFile(path);
									return 1;
								})))
				.then(literalFn.apply("new")
						.then(literalFn.apply("datapack")
								.executes(ctx -> {
									actionsResolver.apply(ctx.getSource()).newDatapack();
									return 1;
								})))
				.then(literalFn.apply("reload")
						.executes(ctx -> {
							actionsResolver.apply(ctx.getSource()).reloadDatapacks();
							return 1;
						}));

		dispatcher.register(root);
	}

	private static CompletableFuture<Suggestions> suggestFilePaths(List<String> paths, SuggestionsBuilder builder) {
		if (paths != null && !paths.isEmpty()) {
			String remaining = builder.getRemainingLowerCase();
			for (String path : paths) {
				if (path != null && (remaining.isEmpty() || path.toLowerCase(Locale.ROOT).contains(remaining))) {
					builder.suggest(path);
				}
			}
		}
		return builder.buildFuture();
	}

	private static final class LiveCommandActions implements CommandActions {
		private final FabricClientCommandSource source;

		private LiveCommandActions(FabricClientCommandSource source) {
			this.source = source;
		}

		@Override
		public void openIde() {
			openIdeAndConfigure(null);
		}

		@Override
		public void openSettings() {
			openIdeAndConfigure(QuillIdeScreen::openSettings);
		}

		@Override
		public void openExplorer() {
			openIdeAndConfigure(QuillIdeScreen::openExplorer);
		}

		@Override
		public void openSearch(String query) {
			openIdeAndConfigure(screen -> screen.openSearch(query));
		}

		@Override
		public void openInspector() {
			openIdeAndConfigure(QuillIdeScreen::openInspector);
		}

		@Override
		public void openPresets() {
			openIdeAndConfigure(QuillIdeScreen::openPresets);
		}

		@Override
		public void openFile(String path) {
			openIdeAndConfigure(screen -> screen.openFileFromCommand(path));
		}

		@Override
		public void newDatapack() {
			openIdeAndConfigure(QuillIdeScreen::openNewDatapackModal);
		}

		@Override
		public void reloadDatapacks() {
			Minecraft client = source.getClient();
			ClientEditorSession session = resolveSession(true);
			if (session == null || client == null) {
				return;
			}
			source.sendFeedback(Component.literal("Quill: Reloading datapacks..."));
			session.reloadDatapacksAsync().whenComplete((report, error) -> client.execute(() -> {
				if (error != null) {
					Throwable cause = error.getCause() != null ? error.getCause() : error;
					String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
					source.sendError(Component.literal("Quill: Reload failed: " + msg));
					return;
				}
				if (report == null) {
					return;
				}
				if (report.hasErrors()) {
					source.sendError(Component.literal("Quill: Reload completed with "
							+ report.diagnostics().size() + " error(s) in " + report.durationMs() + "ms."));
				} else {
					source.sendFeedback(Component.literal("Quill: Reloaded "
							+ report.enabledPackCount() + " pack(s) in " + report.durationMs() + "ms."));
				}
			}));
		}

		@Override
		public List<String> suggestFilePaths() {
			ClientEditorSession session = ClientSessionManager.activeSession();
			if (session == null) {
				session = resolveSession(false);
				if (session == null) {
					return List.of();
				}
			}
			return session.listAllFilePaths();
		}

		private void openIdeAndConfigure(Consumer<QuillIdeScreen> configure) {
			Minecraft client = source.getClient();
			ClientEditorSession session = resolveSession(true);
			if (session == null || client == null) {
				return;
			}
			// Defer past ChatScreen closing itself right after dispatching a chat command.
			client.execute(() -> {
				QuillIdeScreen screen = new QuillIdeScreen(session);
				if (configure != null) {
					configure.accept(screen);
				}
				client.gui.setScreen(screen);
			});
		}

		private ClientEditorSession resolveSession(boolean reportErrors) {
			Minecraft client = source.getClient();
			if (client == null || client.player == null || client.level == null) {
				if (reportErrors) {
					source.sendError(Component.literal("Quill: Join a world before running /quill commands."));
				}
				return null;
			}
			boolean singleplayer = client.hasSingleplayerServer() && client.getSingleplayerServer() != null;
			if (!singleplayer && (client.getConnection() == null || !ClientNetworkHandler.isServerQuillAvailable())) {
				if (reportErrors) {
					source.sendError(Component.literal("Quill: This server does not have Quill installed."));
				}
				return null;
			}
			ClientEditorSession session = ClientSessionManager.getOrCreateSession(client);
			if (reportErrors) {
				session.refreshAll();
			}
			return session;
		}
	}
}
