package dev.catgirlclaude.quill.client.gui;

/**
 * Shared frame-rate-independent animation and color interpolation utilities.
 * All helpers immediately snap to target values when global animations are disabled.
 */
public final class QuillAnimator {
	private QuillAnimator() {
	}

	/**
	 * Advances {@code current} toward {@code target} smoothly, or snaps directly to {@code target}
	 * when {@code animationsEnabled} is {@code false}.
	 */
	public static float stepTowards(
			float current,
			float target,
			float dtSeconds,
			float speedPerSecond,
			boolean animationsEnabled
	) {
		if (!animationsEnabled || dtSeconds <= 0.0f || speedPerSecond <= 0.0f) {
			return target;
		}
		float diff = target - current;
		if (Math.abs(diff) < 0.0005f) {
			return target;
		}
		float factor = 1.0f - (float) Math.exp(-speedPerSecond * Math.min(0.1f, dtSeconds));
		float next = current + diff * factor;
		if (Math.abs(target - next) < 0.0005f) {
			return target;
		}
		return next;
	}

	/**
	 * Linearly interpolates between two ARGB colors.
	 */
	public static int lerpColor(int fromArgb, int toArgb, float t) {
		float clamped = Math.max(0.0f, Math.min(1.0f, t));
		int a1 = (fromArgb >>> 24) & 0xFF;
		int r1 = (fromArgb >>> 16) & 0xFF;
		int g1 = (fromArgb >>> 8) & 0xFF;
		int b1 = fromArgb & 0xFF;

		int a2 = (toArgb >>> 24) & 0xFF;
		int r2 = (toArgb >>> 16) & 0xFF;
		int g2 = (toArgb >>> 8) & 0xFF;
		int b2 = toArgb & 0xFF;

		int a = Math.round(a1 + (a2 - a1) * clamped);
		int r = Math.round(r1 + (r2 - r1) * clamped);
		int g = Math.round(g1 + (g2 - g1) * clamped);
		int b = Math.round(b1 + (b2 - b1) * clamped);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}
