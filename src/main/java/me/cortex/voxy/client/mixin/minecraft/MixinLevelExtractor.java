package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class MixinLevelExtractor {
    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "extract", at = @At("RETURN"))
    private void voxy$appendDistantBeaconStates(DeltaTracker deltaTracker, Camera camera, float partialTick, CallbackInfo ci) {
        var renderer = ((IVoxyRenderSystemHolder)this.levelRenderer).voxy$getRenderSystem();
        if (renderer != null) renderer.appendDistantBeaconStates(this.levelRenderState, partialTick);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$onSetLevel(ClientLevel level, CallbackInfo cir) {
        ((IVoxyRenderSystemHolder)this.levelRenderer).voxy$setWorld(level);
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void voxy$reload(CallbackInfo cir) {
        ((IVoxyRenderSystemHolder)this.levelRenderer).voxy$shutdownRenderer();
        ((IVoxyRenderSystemHolder)this.levelRenderer).voxy$createRenderer();
    }
}
