package dev.catgirlclaude.quill.client.session;

import dev.catgirlclaude.quill.backend.ServerEditorBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * Holds the active {@link ClientEditorSession} for the current singleplayer server instance.
 */
public final class ClientSessionManager {
	private static IntegratedServer boundServer;
	private static ClientEditorSession activeSession;

	private ClientSessionManager() {
	}

	public static ClientEditorSession getOrCreateSingleplayerSession(Minecraft client) {
		IntegratedServer server = client.getSingleplayerServer();
		if (server == null) {
			throw new IllegalStateException("No singleplayer integrated server is running.");
		}
		if (activeSession == null || boundServer != server) {
			boundServer = server;
			activeSession = new ClientEditorSession(new ServerEditorBackend(server));
			activeSession.refreshAll();
		}
		return activeSession;
	}

	public static void clear() {
		boundServer = null;
		activeSession = null;
	}
}
