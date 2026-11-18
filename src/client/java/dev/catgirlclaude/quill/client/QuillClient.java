package dev.catgirlclaude.quill.client;

import dev.catgirlclaude.quill.Quill;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.input.QuillKeyMappings;
import dev.catgirlclaude.quill.client.network.ClientNetworkHandler;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.ClientSessionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public class QuillClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Quill.LOGGER.info("Initializing Quill client module.");
		QuillClientConfig.loadFromDefaultPath();
		ClientNetworkHandler.register();
		QuillKeyMappings.register();

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || message == null) {
				return;
			}
			ClientEditorSession session = ClientSessionManager.activeSession();
			if (session != null) {
				session.onGameMessageReceived(message.getString());
			}
		});
	}
}
