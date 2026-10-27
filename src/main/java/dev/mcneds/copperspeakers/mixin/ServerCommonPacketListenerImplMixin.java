package dev.mcneds.copperspeakers.mixin;

import dev.mcneds.copperspeakers.network.TailCatalog;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Inject(method = "handleResourcePackResponse", at = @At("TAIL"))
    private void copperSpeakers$packReady(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        TailCatalog.response((ServerCommonPacketListenerImpl) (Object) this, packet);
    }
}
