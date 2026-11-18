package dev.catgirlclaude.quill.client.session;

import dev.catgirlclaude.quill.backend.ServerEditorBackend;
import dev.catgirlclaude.quill.client.network.RemoteEditorBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;

/**
 * Holds the active {@link ClientEditorSession} for either the local singleplayer
 * {@link IntegratedServer} or the active remote multiplayer server connection.
 */
public final class ClientSessionManager {
	private static IntegratedServer boundServer;
	private static ClientPacketListener boundConnection;
	private static RemoteEditorBackend activeRemoteBackend;
	private static ClientEditorSession activeSession;

	private ClientSessionManager() {
	}

	public static synchronized ClientEditorSession getOrCreateSession(Minecraft client) {
		IntegratedServer server = client.getSingleplayerServer();
		if (client.hasSingleplayerServer() && server != null) {
			return getOrCreateSingleplayerSession(client);
		}
		return getOrCreateRemoteSession(client);
	}

	public static synchronized ClientEditorSession getOrCreateSingleplayerSession(Minecraft client) {
		IntegratedServer server = client.getSingleplayerServer();
		if (server == null) {
			throw new IllegalStateException("No singleplayer integrated server is running.");
		}
		if (activeSession == null || boundServer != server || activeRemoteBackend != null) {
			closeActiveRemote("Switched to singleplayer session.");
			boundConnection = null;
			boundServer = server;
			activeSession = new ClientEditorSession(
					new ServerEditorBackend(server),
					"Local",
					server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
			);
			activeSession.refreshAll();
		}
		return activeSession;
	}

	public static synchronized ClientEditorSession getOrCreateRemoteSession(Minecraft client) {
		ClientPacketListener connection = client.getConnection();
		if (connection == null) {
			throw new IllegalStateException("No active multiplayer connection.");
		}
		if (activeSession == null || boundConnection != connection || activeRemoteBackend == null || activeRemoteBackend.isClosed()) {
			closeActiveRemote("Switched server session.");
			boundServer = null;
			boundConnection = connection;

			RemoteEditorBackend remote = new RemoteEditorBackend();
			String serverName = client.getCurrentServer() != null ? client.getCurrentServer().name : null;
			String label = (serverName != null && !serverName.isBlank())
					? "Remote: " + serverName
					: "Remote";
			ClientEditorSession session = new ClientEditorSession(remote, label);
			remote.setTreeChangedListener(notification -> session.onRemoteTreeChanged(
					notification.actorName(),
					notification.changeKind(),
					notification.affectedPath(),
					notification.newSha256()
			));
			activeRemoteBackend = remote;
			activeSession = session;
			activeSession.refreshAll();
		}
		return activeSession;
	}

	public static RemoteEditorBackend activeRemoteBackend() {
		return activeRemoteBackend;
	}

	public static ClientEditorSession activeSession() {
		return activeSession;
	}

	public static synchronized void clear() {
		clear("Editor session closed.");
	}

	public static synchronized void clear(String reason) {
		closeActiveRemote(reason);
		boundServer = null;
		boundConnection = null;
		activeSession = null;
	}

	private static void closeActiveRemote(String reason) {
		RemoteEditorBackend remote = activeRemoteBackend;
		activeRemoteBackend = null;
		if (remote != null) {
			remote.close(reason);
		}
	}
}
