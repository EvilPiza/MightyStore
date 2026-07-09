package com.mighty.mixins;

import com.mighty.store.AddonStore;
import com.mighty.store.PendingOperations;
import org.cobalt.addon.AddonManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(AddonManager.class)
public class AddonManagerMixin {

    @Inject(method = "loadAddons$cobalt", at = @At("HEAD"))
    private static void mighty$flushPendingOps(CallbackInfo ci) {
        for (Path dir : AddonStore.INSTANCE.getAddonsDir()) {
            PendingOperations.apply(dir);
        }
    }
}