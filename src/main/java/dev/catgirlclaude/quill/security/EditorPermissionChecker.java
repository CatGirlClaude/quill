package dev.catgirlclaude.quill.security;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Verifies whether a player or local integrated server session is authorized to edit datapacks,
 * combining singleplayer ownership, operator level >= 2 ({@link Permissions#COMMANDS_GAMEMASTER}),
 * and Quill's dedicated server ACL ({@link QuillAccessControl}).
 */
public final class EditorPermissionChecker {
	private static volatile QuillAccessControl defaultAcl;

	private EditorPermissionChecker() {
	}

	public static QuillAccessControl defaultAccessControl() {
		QuillAccessControl current = defaultAcl;
		if (current != null) {
			return current;
		}
		synchronized (EditorPermissionChecker.class) {
			if (defaultAcl == null) {
				Path configPath = FabricLoader.getInstance().getConfigDir().resolve("quill-acl.json");
				defaultAcl = new QuillAccessControl(configPath);
			}
			return defaultAcl;
		}
	}

	public static boolean canEdit(MinecraftServer server, ServerPlayer player) {
		return canEdit(server, player, defaultAccessControl());
	}

	public static boolean canEdit(MinecraftServer server, ServerPlayer player, QuillAccessControl accessControl) {
		Objects.requireNonNull(server, "server");
		if (player == null) {
			return server.isSingleplayer();
		}
		boolean singleplayer = server.isSingleplayer();
		boolean singleplayerOwner = singleplayer && server.isSingleplayerOwner(player.nameAndId());
		boolean isGamemasterOp = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		return canEditIdentity(
				singleplayer,
				singleplayerOwner,
				player.getUUID(),
				player.nameAndId().name(),
				isGamemasterOp,
				accessControl
		);
	}

	/**
	 * Pure identity-level authorization check (unit-testable without a live {@link MinecraftServer}).
	 */
	public static boolean canEditIdentity(
			boolean singleplayer,
			boolean singleplayerOwner,
			UUID playerId,
			String playerName,
			boolean isGamemasterOp,
			QuillAccessControl accessControl
	) {
		if (singleplayer && singleplayerOwner) {
			return true;
		}
		if (accessControl != null) {
			return accessControl.isAuthorized(playerId, playerName, isGamemasterOp);
		}
		return isGamemasterOp;
	}

	public static void requireCanEdit(MinecraftServer server, ServerPlayer player) {
		requireCanEdit(server, player, defaultAccessControl());
	}

	public static void requireCanEdit(MinecraftServer server, ServerPlayer player, QuillAccessControl accessControl) {
		if (!canEdit(server, player, accessControl)) {
			throw new EditorSecurityException("You do not have permission to edit or reload server datapacks.");
		}
	}
}
