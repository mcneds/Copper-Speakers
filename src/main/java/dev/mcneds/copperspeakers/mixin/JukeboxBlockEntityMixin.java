package dev.mcneds.copperspeakers.mixin;

import dev.mcneds.copperspeakers.network.AudioNetworks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {
    @Inject(method = "setTheItem", at = @At("HEAD"))
    private void copperSpeakers$replace(ItemStack stack, CallbackInfo ci) { copperSpeakers$clear(); }
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void copperSpeakers$remove(CallbackInfo ci) { copperSpeakers$clear(); }
    private void copperSpeakers$clear() {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel server) AudioNetworks.get(server).stop(self.getBlockPos());
    }
}
