package com.example.sonic.mixin;

import com.example.sonic.SonicClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void sonicmod$boostFov(Camera camera, float tickDelta, boolean changingFov,
                                   CallbackInfoReturnable<Double> cir) {
        double extra = SonicClient.fovBonus;
        if (extra > 0.01) {
            cir.setReturnValue(cir.getReturnValue() + extra);
        }
    }
}
