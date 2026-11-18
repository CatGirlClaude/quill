package dev.catgirlclaude.quill.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerRateLimiterTest {

	@Test
	void enforcesPerPlayerRequestBurstAndRefillsOverTime() {
		AtomicLong now = new AtomicLong(1_000L);
		ServerRateLimiter limiter = new ServerRateLimiter(now::get, 3);
		UUID playerA = UUID.randomUUID();
		UUID playerB = UUID.randomUUID();

		// Burst of 3 succeeds immediately
		assertDoesNotThrow(() -> limiter.checkRequest(playerA, 60));
		assertDoesNotThrow(() -> limiter.checkRequest(playerA, 60));
		assertDoesNotThrow(() -> limiter.checkRequest(playerA, 60));

		// 4th immediate request fails
		assertThrows(ServerRateLimiter.RateLimitException.class, () -> limiter.checkRequest(playerA, 60));

		// Another player has an independent bucket
		assertDoesNotThrow(() -> limiter.checkRequest(playerB, 60));

		// After 1 second at 60 rpm (1 req/sec), 1 token refills
		now.addAndGet(1_000L);
		assertDoesNotThrow(() -> limiter.checkRequest(playerA, 60));
		assertThrows(ServerRateLimiter.RateLimitException.class, () -> limiter.checkRequest(playerA, 60));

		// Removing player resets their state on reconnect
		limiter.removePlayer(playerA);
		assertDoesNotThrow(() -> limiter.checkRequest(playerA, 60));
	}

	@Test
	void enforcesGlobalReloadCooldown() {
		AtomicLong now = new AtomicLong(5_000L);
		ServerRateLimiter limiter = new ServerRateLimiter(now::get, 10);
		UUID playerA = UUID.randomUUID();
		UUID playerB = UUID.randomUUID();

		assertDoesNotThrow(() -> limiter.checkReload(playerA, 500L));
		// Immediate second reload by another player is blocked within cooldown window
		now.addAndGet(200L);
		assertThrows(ServerRateLimiter.RateLimitException.class, () -> limiter.checkReload(playerB, 500L));

		// Once cooldown elapses, reload succeeds again
		now.addAndGet(300L);
		assertDoesNotThrow(() -> limiter.checkReload(playerB, 500L));
	}
}
