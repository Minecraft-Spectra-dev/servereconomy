package cn.choosec.economy.mixin;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerCommonPacketListenerImpl.class)
public interface ConnectionAccessor {
    @Accessor("latency")
    int getLatency();
}
