package dev.catgirlclaude.quill.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.catgirlclaude.quill.client.gui.QuillIdeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces Minecraft's hardcoded {@link FilterMode#NEAREST} glyph sampler with
 * {@link FilterMode#LINEAR} for Quill's TrueType font atlases and for scaled bitmap text
 * inside {@link QuillIdeScreen}, eliminating blocky/jagged font pixelation.
 */
@Mixin(GlyphRenderState.class)
public abstract class GlyphRenderStateMixin {
	@Shadow
	@Final
	private TextRenderable renderable;

	@Shadow
	@Final
	private Matrix3x2fc pose;

	@Inject(method = "textureSetup", at = @At("HEAD"), cancellable = true)
	private void quill$useLinearFilterForQuillAndScaledIdeText(CallbackInfoReturnable<TextureSetup> cir) {
		if (this.renderable == null) {
			return;
		}
		GpuTextureView view = this.renderable.textureView();
		if (view == null) {
			return;
		}
		String label = view.texture().getLabel();
		boolean isQuillFont = label != null && label.startsWith("quill:");
		boolean isScaledIdeBitmap = false;
		if (!isQuillFont && this.pose != null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.gui != null && mc.gui.screen() instanceof QuillIdeScreen) {
				if (Math.abs(this.pose.m00() - 1.0f) > 0.01f || Math.abs(this.pose.m11() - 1.0f) > 0.01f) {
					isScaledIdeBitmap = true;
				}
			}
		}
		if (isQuillFont || isScaledIdeBitmap) {
			cir.setReturnValue(TextureSetup.singleTextureWithLightmap(
					view,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
			));
		}
	}
}
