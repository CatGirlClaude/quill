package dev.catgirlclaude.quill.client;

import dev.catgirlclaude.quill.Quill;
import dev.catgirlclaude.quill.client.input.QuillKeyMappings;
import net.fabricmc.api.ClientModInitializer;

public class QuillClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Quill.LOGGER.info("Initializing Quill client module.");
		QuillKeyMappings.register();
	}
}
