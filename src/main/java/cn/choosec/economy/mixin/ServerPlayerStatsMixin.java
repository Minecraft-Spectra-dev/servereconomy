package cn.choosec.economy.mixin;

import cn.choosec.economy.service.DailyTaskService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks "use" daily tasks from the vanilla statistics system. The vanilla
 * {@link Stats#ITEM_USED} statistic is awarded by the game whenever an item is
 * actually used/consumed: right-click use, eating/drinking (on completion), throwing,
 * and firework launches. Crucially it also covers firework rockets, which are launched
 * via {@code useItemOn} → {@code ItemStack.useOn} and never fire the mod's
 * {@code UseItemCallback}. Reacting to the statistic award keeps tracking vanilla-native
 * and uniform for every item.
 *
 * <p>{@code Player.awardStat(Stat)} delegates to this method, so every
 * {@code ITEM_USED} increment lands on this single funnel and is counted exactly once.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerStatsMixin {

    @Inject(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At("HEAD"))
    private void servereconomy$onAwardStat(Stat<?> stat, int amount, CallbackInfo ci) {
        if (stat.getType() == Stats.ITEM_USED && stat.getValue() instanceof Item item) {
            String id = item.builtInRegistryHolder().getRegisteredName();
            ServerPlayer self = (ServerPlayer) (Object) this;
            DailyTaskService.addProgress(self, "use", id);
            // Fireworks never reach LivingEntity.completeUsingItem (they launch via
            // useOn / elytra boost), so their "consume" task has no other source;
            // credit it alongside the use here. All other consumable items are still
            // credited through the completeUsingItem mixin.
            if ("minecraft:firework_rocket".equals(id)) {
                DailyTaskService.addProgress(self, "consume", id);
            }
        }
    }
}
