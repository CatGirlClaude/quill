package dev.catgirlclaude.quill;

import dev.catgirlclaude.quill.network.ServerNetworkEditorHandler;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Quill implements ModInitializer {
	public static final String MOD_ID = "quill";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Quill common module.");
		ServerNetworkEditorHandler.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
