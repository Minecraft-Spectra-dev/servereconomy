package cn.choosec.economy.mixin;

import cn.choosec.economy.service.PreservedService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies a player's title prefix to the tab-list display name.
 *
 * <p>Only the return value is overridden when the player actually has a title.
 * When there is no title we leave the original value untouched, so other mods
 * (e.g. Carpet, which renders its log/state data through the tab-list display
 * name) are no longer clobbered by us.
 */
@Mixin(ServerPlayer.class)
public class PlayerDisplayNameMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void onGetTabListDisplayName(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (PreservedService.hasTitle(self.getUUID())) {
            cir.setReturnValue(PreservedService.buildTitleDisplayName(self));
        }
    }
}
