package dev.catgirlclaude.quill.security;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Enforces per-player RPC request rate limits (token bucket) and reload cooldowns
 * so remote clients cannot flood the server with filesystem I/O or {@code /reload} storms.
 */
public final class ServerRateLimiter {
	public static final class RateLimitException extends EditorSecurityException {
		public RateLimitException(String message) {
			super(message);
		}
	}

	private static final class TokenBucket {
		double tokens;
		long lastRefillMillis;

		TokenBucket(double initialTokens, long nowMillis) {
			this.tokens = initialTokens;
			this.lastRefillMillis = nowMillis;
		}
	}

	private final LongSupplier clockMillis;
	private final int burstCapacity;
	private final ConcurrentHashMap<UUID, TokenBucket> requestBuckets = new ConcurrentHashMap<>();
	private final Object reloadLock = new Object();
	private long lastGlobalReloadMillis = Long.MIN_VALUE;

	public ServerRateLimiter() {
		this(System::currentTimeMillis, EditorLimits.DEFAULT_RATE_LIMIT_BURST);
	}

	public ServerRateLimiter(LongSupplier clockMillis, int burstCapacity) {
		this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
		this.burstCapacity = Math.max(1, burstCapacity);
	}

	/**
	 * Consumes one request token for {@code playerId}, throwing {@link RateLimitException} if exceeded.
	 */
	public void checkRequest(UUID playerId, int requestsPerMinute) {
		Objects.requireNonNull(playerId, "playerId");
		int rpm = Math.max(1, requestsPerMinute);
		long now = clockMillis.getAsLong();
		double refillPerMs = rpm / 60_000.0;

		TokenBucket bucket = requestBuckets.computeIfAbsent(playerId, ignored -> new TokenBucket(burstCapacity, now));
		synchronized (bucket) {
			long elapsed = Math.max(0L, now - bucket.lastRefillMillis);
			if (elapsed > 0L) {
				bucket.tokens = Math.min(burstCapacity, bucket.tokens + elapsed * refillPerMs);
				bucket.lastRefillMillis = now;
			}
			if (bucket.tokens < 1.0) {
				throw new RateLimitException("Rate limit exceeded. Please slow down your editor requests.");
			}
			bucket.tokens -= 1.0;
		}
	}

	/**
	 * Enforces the server-wide datapack reload cooldown across multiplayer requests.
	 */
	public void checkReload(UUID playerId, long reloadCooldownMs) {
		Objects.requireNonNull(playerId, "playerId");
		long cooldown = Math.max(0L, reloadCooldownMs);
		if (cooldown == 0L) {
			return;
		}
		long now = clockMillis.getAsLong();
		synchronized (reloadLock) {
			if (lastGlobalReloadMillis != Long.MIN_VALUE) {
				long elapsed = now - lastGlobalReloadMillis;
				if (elapsed >= 0L && elapsed < cooldown) {
					long remaining = cooldown - elapsed;
					throw new RateLimitException("Reload cooldown active. Please wait " + remaining + " ms before reloading again.");
				}
			}
			lastGlobalReloadMillis = now;
		}
	}

	public void removePlayer(UUID playerId) {
		if (playerId != null) {
			requestBuckets.remove(playerId);
		}
	}

	public void clear() {
		requestBuckets.clear();
		synchronized (reloadLock) {
			lastGlobalReloadMillis = Long.MIN_VALUE;
		}
	}
}
