package dev.mcneds.copperspeakers.network;

import dev.mcneds.copperspeakers.block.CopperWireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Redstone-style steps between cables. Endpoints and wall plates retain their horizontal ports. */
public final class WireConnections {
    private WireConnections() {}
    public static boolean isWire(BlockState state) { return state.getBlock() instanceof CopperWireBlock; }

    public static boolean step(BlockGetter level, Function<BlockPos, BlockState> state, BlockPos a, BlockPos b) {
        if (!isWire(state.apply(a)) || !isWire(state.apply(b)) || Math.abs(a.getY() - b.getY()) != 1
                || Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) != 1) return false;
        BlockPos lower = a.getY() < b.getY() ? a : b;
        BlockPos upper = a.getY() < b.getY() ? b : a;
        BlockPos support = upper.below();
        // The lower cable turns up the ledge only when its overhead space admits redstone dust.
        return !state.apply(lower.above()).isRedstoneConductor(level, lower.above())
                && CopperWireBlock.supportsWire(state.apply(support), level, support);
    }
    public static List<BlockPos> neighbors(BlockGetter level, Function<BlockPos, BlockState> state, BlockPos pos) {
        var result = new ArrayList<BlockPos>(12);
        for (var side : CopperWireBlock.DIRECTIONS) {
            var next = pos.relative(side);
            result.add(next);
            if (step(level, state, pos, next.above())) result.add(next.above());
            if (step(level, state, pos, next.below())) result.add(next.below());
        }
        return result;
    }
    /** Blocks that can depend on a changed wire, its support, or its headroom. */
    public static List<BlockPos> affected(BlockPos pos) {
        var result = new ArrayList<BlockPos>(15);
        result.add(pos.immutable()); result.add(pos.above()); result.add(pos.below());
        for (var side : CopperWireBlock.DIRECTIONS) {
            var next = pos.relative(side);
            result.add(next); result.add(next.above()); result.add(next.below());
        }
        return result;
    }
}
