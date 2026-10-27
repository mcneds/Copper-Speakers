package dev.mcneds.copperspeakers.network;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.entity.WallPlateEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import java.util.*;

/** Loaded anchors only. Entity persistence owns the pair; no saved graph, world scans or chunk tickets. */
public final class WallPlates {
    private static final Map<ServerLevel, WallPlates> LEVELS = new IdentityHashMap<>();
    private final ServerLevel level;
    private final Map<BlockPos, WallPlateEntity> plates = new HashMap<>();
    private final Set<BlockPos> dirty = new HashSet<>();
    private WallPlates(ServerLevel level) { this.level = level; }
    public static WallPlates get(ServerLevel level) { return LEVELS.computeIfAbsent(level, WallPlates::new); }
    public static void clear(MinecraftServer server) { LEVELS.keySet().removeIf(l -> l.getServer() == server); }
    public WallPlateEntity at(BlockPos pos) { return plates.get(pos); }
    public static boolean eligible(Level level, BlockPos pos) {
        if (!LoadedBlocks.available(level, pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        var state = LoadedBlocks.state(level, pos);
        return !state.hasBlockEntity() && !CopperWireBlock.isNode(state) && state.getFluidState().isEmpty()
                && state.isSolid() && state.isCollisionShapeFullBlock(level, pos);
    }
    public boolean isNode(BlockPos pos) {
        var plate = plates.get(pos);
        return plate != null && !plate.isRemoved() && eligible(level, pos);
    }
    /** Only the two sockets expose horizontal ports on a plated block. */
    public boolean connects(BlockPos host, BlockPos neighbor) {
        if (!isNode(host)) return false;
        var facing = plates.get(host).facing();
        return neighbor.equals(host.relative(facing)) || neighbor.equals(host.relative(facing.getOpposite()));
    }
    public void add(WallPlateEntity plate) {
        var previous = plates.putIfAbsent(plate.host(), plate);
        if (previous != null && previous != plate) { plate.discard(); return; }
        plate.showFrames();
        dirty.add(plate.host());
        refresh(plate.host());
    }
    public void remove(WallPlateEntity plate) {
        plate.hideFrames();
        if (plates.remove(plate.host(), plate)) refresh(plate.host());
    }
    public void changed(BlockPos pos) {
        if (plates.containsKey(pos)) dirty.add(pos.immutable());
        for (var side : CopperWireBlock.DIRECTIONS) {
            var host = pos.relative(side);
            if (plates.containsKey(host)) dirty.add(host);
        }
    }
    public void chunkChanged(ChunkPos chunk) {
        for (var plate : plates.values()) {
            if (ChunkPos.containing(plate.host()).equals(chunk)
                    || ChunkPos.containing(plate.host().relative(plate.facing())).equals(chunk)
                    || ChunkPos.containing(plate.host().relative(plate.facing().getOpposite())).equals(chunk))
                dirty.add(plate.host());
        }
    }
    public void tick() {
        var pending = Set.copyOf(dirty);
        dirty.clear();
        for (var pos : pending) {
            var plate = plates.get(pos);
            if (plate == null) continue;
            // A neighboring chunk unloading must never destroy a persistent pair or force that chunk to load.
            if (!eligible(level, pos) || plate.facesLoaded() && !plate.survives()) plate.breakPlate(null, plate.facing());
            else refresh(pos);
        }
    }
    private void refresh(BlockPos host) {
        AudioNetworks.get(level).changed(host);
        for (var direction : CopperWireBlock.DIRECTIONS) {
            var pos = host.relative(direction);
            if (!LoadedBlocks.available(level, pos)) continue;
            var state = LoadedBlocks.state(level, pos);
            if (state.is(CopperSpeakers.WIRE)) {
                var connected = CopperSpeakers.WIRE.connections(state, level, pos);
                if (connected != state) level.setBlock(pos, connected, Block.UPDATE_CLIENTS);
            }
        }
        var plate = plates.get(host);
        if (plate != null) plate.refreshConnections();
    }
}
