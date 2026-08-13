package cn.choosec.economy.mixin;

import cn.choosec.economy.service.DailyTaskService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Credits "consume" daily tasks when an item is actually used up (food eaten /
 * potion drunk / item fully used). This is a pure side-channel that never cancels
 * the item-use flow, so it cannot interfere with eating. Note: "use" tasks are
 * handled separately via {@code ServerPlayerStatsMixin} on the vanilla ITEM_USED
 * statistic, so they are not credited here.
 */
@Mixin(LivingEntity.class)
public abstract class ItemConsumeMixin {

    @Inject(method = "completeUsingItem", at = @At("HEAD"))
    private void servereconomy$onCompleteUsingItem(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer sp) {
            ItemStack used = sp.getUseItem();
            if (!used.isEmpty()) {
                String id = used.typeHolder().getRegisteredName();
                DailyTaskService.addProgress(sp, "consume", id);
            }
        }
    }
}
