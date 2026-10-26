package dev.mcneds.copperspeakers.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(JukeboxSongPlayer.class)
public abstract class JukeboxSongPlayerMixin {
    @Shadow @Final private BlockPos blockPos;
    @Shadow private Holder<JukeboxSong> song;
    @Unique private boolean copperSpeakers$natural;

    @WrapOperation(method = "play", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelAccessor;levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V"))
    private void copperSpeakers$start(LevelAccessor level, Entity entity, int event, BlockPos pos, int data, Operation<Void> original) {
        if (!(level instanceof ServerLevel server) || !AudioNetworks.get(server).start(blockPos, song))
            original.call(level, entity, event, pos, data);
    }
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/JukeboxSongPlayer;stop(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void copperSpeakers$naturalStop(JukeboxSongPlayer self, LevelAccessor level, BlockState state, Operation<Void> original) {
        copperSpeakers$natural = true;
        try { original.call(self, level, state); }
        finally { copperSpeakers$natural = false; }
    }
    @WrapOperation(method = "stop", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelAccessor;levelEvent(ILnet/minecraft/core/BlockPos;I)V"))
    private void copperSpeakers$stop(LevelAccessor level, int event, BlockPos pos, int data, Operation<Void> original) {
        if (!(level instanceof ServerLevel server) || !AudioNetworks.get(server).finish(blockPos, copperSpeakers$natural))
            original.call(level, event, pos, data);
    }
}
