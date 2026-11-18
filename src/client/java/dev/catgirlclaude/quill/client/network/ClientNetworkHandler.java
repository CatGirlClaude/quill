package dev.catgirlclaude.quill.client.network;

import dev.catgirlclaude.quill.client.session.ClientSessionManager;
import dev.catgirlclaude.quill.network.QuillNetworkPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers client-side S2C packet receivers and disconnect hooks for multiplayer Quill sessions.
 */
public final class ClientNetworkHandler {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private ClientNetworkHandler() {
	}

	public static void register() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}
		QuillNetworkPayloads.registerPayloads();

		ClientPlayNetworking.registerGlobalReceiver(
				QuillNetworkPayloads.S2CFramePayload.TYPE,
				(payload, context) -> {
					RemoteEditorBackend remote = ClientSessionManager.activeRemoteBackend();
					if (remote != null) {
						remote.handleServerFrame(payload);
					}
				}
		);

		ClientPlayNetworking.registerGlobalReceiver(
				QuillNetworkPayloads.TreeChangedPayload.TYPE,
				(payload, context) -> {
					RemoteEditorBackend remote = ClientSessionManager.activeRemoteBackend();
					if (remote != null) {
						remote.handleTreeChanged(payload);
					}
				}
		);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				ClientSessionManager.clear("Disconnected from server.")
		);
	}

	/**
	 * Checks whether the currently connected server advertises Quill's C2S channel.
	 */
	public static boolean isServerQuillAvailable() {
		return ClientPlayNetworking.canSend(QuillNetworkPayloads.C2SFramePayload.TYPE);
	}
}
