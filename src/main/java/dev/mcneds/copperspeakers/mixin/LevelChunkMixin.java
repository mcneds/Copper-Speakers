package dev.mcneds.copperspeakers.mixin;

import dev.mcneds.copperspeakers.network.AudioNetworks;
import dev.mcneds.copperspeakers.network.WallPlates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void copperSpeakers$changed(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> ci) {
        BlockState before = ci.getReturnValue();
        if (before != null && before != state
                && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel server) {
            // Support shape changes and obstruction of either frame matter even if the block type stays the same.
            WallPlates.get(server).changed(pos);
            // Solid supports and overhead obstructions can connect/disconnect a diagonal cable step.
            AudioNetworks.get(server).changed(pos);
        }
    }
}
