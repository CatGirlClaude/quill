package dev.catgirlclaude.quill.security;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.Objects;

/**
 * Verifies whether a player or local integrated server session is authorized to edit datapacks.
 */
public final class EditorPermissionChecker {
	private EditorPermissionChecker() {
	}

	public static boolean canEdit(MinecraftServer server, ServerPlayer player) {
		Objects.requireNonNull(server, "server");
		if (player == null) {
			return server.isSingleplayer();
		}
		if (server.isSingleplayer() && server.isSingleplayerOwner(player.nameAndId())) {
			return true;
		}
		return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	public static void requireCanEdit(MinecraftServer server, ServerPlayer player) {
		if (!canEdit(server, player)) {
			throw new EditorSecurityException("You do not have permission to edit or reload server datapacks.");
		}
	}
}
