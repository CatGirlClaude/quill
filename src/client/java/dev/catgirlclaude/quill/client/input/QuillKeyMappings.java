package dev.catgirlclaude.quill.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.catgirlclaude.quill.Quill;
import dev.catgirlclaude.quill.client.gui.QuillIdeScreen;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.ClientSessionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Registers Quill's keybindings and handles opening {@link QuillIdeScreen} from gameplay.
 */
public final class QuillKeyMappings {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Quill.id("general"));

	public static final KeyMapping OPEN_IDE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.quill.open_ide",
			InputConstants.Type.KEYBOARD,
			InputConstants.KEY_K,
			CATEGORY
	));

	private QuillKeyMappings() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(QuillKeyMappings::onEndClientTick);
	}

	private static void onEndClientTick(Minecraft client) {
		while (OPEN_IDE.consumeClick()) {
			if (client.player == null || client.level == null) {
				continue;
			}
			if (client.gui.screen() != null) {
				continue;
			}
			if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null) {
				client.player.sendOverlayMessage(Component.literal("Quill: Multiplayer support is planned for Phase 3."));
				continue;
			}
			ClientEditorSession session = ClientSessionManager.getOrCreateSingleplayerSession(client);
			session.refreshAll();
			client.gui.setScreen(new QuillIdeScreen(session));
		}
	}
}
