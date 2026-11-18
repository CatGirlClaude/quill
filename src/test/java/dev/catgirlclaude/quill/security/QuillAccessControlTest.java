package dev.catgirlclaude.quill.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuillAccessControlTest {

	@TempDir
	Path tempDir;

	@Test
	void defaultPolicyAllowsOperatorsAndDeniesRegularPlayers() {
		Path aclFile = tempDir.resolve("quill-acl.json");
		QuillAccessControl acl = new QuillAccessControl(aclFile);
		acl.ensureDefaultFileExists();

		assertTrue(Files.isRegularFile(aclFile));
		UUID player = UUID.randomUUID();
		assertTrue(acl.isAuthorized(player, "Admin", true));
		assertFalse(acl.isAuthorized(player, "Player", false));
		assertEquals(EditorLimits.DEFAULT_REQUESTS_PER_MINUTE, acl.rateLimitRequestsPerMinute());
		assertEquals(EditorLimits.RELOAD_COOLDOWN_MS, acl.reloadCooldownMs());
	}

	@Test
	void explicitAllowAndDenyPrecedenceOverridesOperators() {
		UUID allowedUuid = UUID.randomUUID();
		UUID deniedOpUuid = UUID.randomUUID();
		UUID otherOpUuid = UUID.randomUUID();

		QuillAccessControl acl = QuillAccessControl.inMemory(new QuillAccessControl.AclPolicy(
				true,
				Set.of(allowedUuid, deniedOpUuid),
				Set.of("buildername"),
				Set.of(deniedOpUuid),
				Set.of("bannedop"),
				200,
				750L
		));

		// Explicitly allowed non-OP by UUID and case-insensitive name
		assertTrue(acl.isAuthorized(allowedUuid, "Random", false));
		assertTrue(acl.isAuthorized(UUID.randomUUID(), "BuilderName", false));

		// Explicitly denied overrides both OP and allowedUuids
		assertFalse(acl.isAuthorized(deniedOpUuid, "Admin", true));
		assertFalse(acl.isAuthorized(UUID.randomUUID(), "BannedOp", true));

		// Normal OP still allowed when allowOperators == true
		assertTrue(acl.isAuthorized(otherOpUuid, "NormalOp", true));

		// Singleplayer owner always allowed regardless of ACL
		assertTrue(EditorPermissionChecker.canEditIdentity(true, true, deniedOpUuid, "BannedOp", false, acl));
		// Remote non-owner uses ACL
		assertFalse(EditorPermissionChecker.canEditIdentity(false, false, deniedOpUuid, "BannedOp", true, acl));
	}

	@Test
	void reloadsAclWhenFileChangesOnDisk() throws IOException {
		Path aclFile = tempDir.resolve("quill-acl.json");
		QuillAccessControl acl = new QuillAccessControl(aclFile);
		acl.ensureDefaultFileExists();

		UUID builder = UUID.randomUUID();
		assertFalse(acl.isAuthorized(builder, "Dev", false));

		QuillAccessControl.AclPolicy updated = new QuillAccessControl.AclPolicy(
				false,
				Set.of(builder),
				Set.of(),
				Set.of(),
				Set.of(),
				60,
				1_000L
		);
		Files.writeString(aclFile, QuillAccessControl.serializePolicy(updated));
		// Bump mtime forward explicitly so filesystems with 1s granularity always detect modification
		Files.setLastModifiedTime(aclFile, FileTime.from(Instant.now().plusSeconds(5)));

		assertTrue(acl.isAuthorized(builder, "Dev", false));
		assertFalse(acl.isAuthorized(UUID.randomUUID(), "RandomOp", true));
		assertEquals(60, acl.rateLimitRequestsPerMinute());
		assertEquals(1_000L, acl.reloadCooldownMs());
	}
}
