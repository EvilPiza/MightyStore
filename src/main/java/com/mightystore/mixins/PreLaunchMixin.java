package com.mightystore.mixins;

import com.mightystore.store.PendingOperations;
import net.fabricmc.loader.api.FabricLoader;
import org.cobalt.PreLaunch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PreLaunch.class)
public class PreLaunchMixin {

    @Inject(method = "onPreLaunch", at = @At("HEAD"))
    private void mighty$flushPendingOps(CallbackInfo ci) {
        PendingOperations.apply(
            FabricLoader.getInstance().getGameDir().resolve("config/cobalt/addons")
        );
    }
}