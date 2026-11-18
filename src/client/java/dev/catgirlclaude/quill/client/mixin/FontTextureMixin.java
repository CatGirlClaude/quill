package dev.catgirlclaude.quill.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Zero-initializes Quill font atlas textures so bilinear glyph edge filtering
 * samples clean transparent padding around glyph cells rather than uninitialized VRAM.
 */
@Mixin(FontTexture.class)
public class FontTextureMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void quill$clearQuillFontAtlas(
			Supplier<String> description,
			GlyphRenderTypes renderTypes,
			boolean colored,
			CallbackInfo ci
	) {
		if (colored) {
			return;
		}
		String label = description != null ? description.get() : null;
		if (label == null || !label.startsWith("quill:")) {
			return;
		}
		GpuTexture texture = ((FontTexture) (Object) this).getTexture();
		if (texture == null || texture.isClosed()) {
			return;
		}
		try (NativeImage zeroImage = new NativeImage(
				NativeImage.Format.LUMINANCE,
				texture.getWidth(0),
				texture.getHeight(0),
				true
		)) {
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, zeroImage);
		}
	}
}
