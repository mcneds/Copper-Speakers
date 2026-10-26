package dev.mcneds.copperspeakers.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Topology reads must never wait for a chunk transition or request a chunk ticket. */
public final class LoadedBlocks {
    private LoadedBlocks() {}
    public static boolean available(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel server)
            return server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
        return !(level instanceof LevelReader reader) || reader.hasChunkAt(pos);
    }
    public static BlockState state(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            var chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
        }
        return available(level, pos) ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
    }
}
