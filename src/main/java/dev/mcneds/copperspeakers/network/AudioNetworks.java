package dev.mcneds.copperspeakers.network;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.block.SpeakerBlock;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Server thread only. No tick-time world scans, persisted sessions or chunk tickets. */
public final class AudioNetworks {
    public static final int MAX_NODES = 4096;
    private static final Map<ServerLevel, AudioNetworks> LEVELS = new IdentityHashMap<>();
    private final ServerLevel level;
    private final Map<BlockPos, Session> sessions = new LinkedHashMap<>();
    private final Set<Long> unloaded = new HashSet<>();
    private final Set<BlockPos> dirtyWires = new HashSet<>();
    private AudioNetworks(ServerLevel level) { this.level = level; }
    public static AudioNetworks get(ServerLevel level) { return LEVELS.computeIfAbsent(level, AudioNetworks::new); }
    public static void clear(MinecraftServer server) {
        for (var entry : List.copyOf(LEVELS.entrySet())) if (entry.getKey().getServer() == server) {
            for (BlockPos master : List.copyOf(entry.getValue().sessions.keySet())) entry.getValue().stop(master);
            LEVELS.remove(entry.getKey());
        }
    }

    private static final class Session {
        final BlockPos master;
        Set<BlockPos> nodes;
        final Set<BlockPos> sources = new LinkedHashSet<>();
        final Map<UUID, ServerPlayer> listeners = new LinkedHashMap<>();
        final Map<BlockPos, SpeakerNotes> notes = new LinkedHashMap<>();
        SpareVoices voices;
        boolean ended, dirty;
        Session(BlockPos master, Set<BlockPos> nodes) { this.master = master.immutable(); this.nodes = nodes; }
    }
    private BlockState state(BlockPos pos) {
        return unloaded.contains(ChunkPos.pack(pos)) ? Blocks.AIR.defaultBlockState() : LoadedBlocks.state(level, pos);
    }
    public NetworkGraph.Component<BlockPos> component(BlockPos root) {
        return NetworkGraph.find(root, p -> WireConnections.neighbors(level, this::state, p),
                p -> CopperWireBlock.isNode(state(p)) || isWallPlate(p),
                p -> state(p).getBlock() instanceof CopperWireBlock || isWallPlate(p),
                p -> state(p).getBlock() instanceof SpeakerBlock, MAX_NODES);
    }
    private boolean isWallPlate(BlockPos pos) {
        return !unloaded.contains(ChunkPos.pack(pos)) && LoadedBlocks.available(level, pos) && WallPlates.get(level).isNode(pos);
    }
    /** Return true when a wired playback replaces the original jukebox start. */
    public boolean start(BlockPos root, Holder<JukeboxSong> song) {
        stop(root); // replacement also clears a naturally completed but still decoding tail
        var graph = component(root);
        if (graph.overflow() || graph.speakers().isEmpty()) return false;
        var registry = level.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG);
        var buffered = BufferedSongs.resolve(registry, song);
        var playback = buffered != null ? buffered : song;
        int songId = registry.getId(playback.value());
        // Standard jukebox playback also requires a registered song for its 1010 event.
        if (songId < 0) return false;

        Session session = new Session(root, graph.nodes());
        boolean occupied = sessions.values().stream().anyMatch(s -> !s.ended && !s.sources.isEmpty()
                && !Collections.disjoint(s.nodes, graph.nodes()));
        if (!occupied) {
            // A new group supersedes any old natural tail before claiming its positions.
            for (Session old : List.copyOf(sessions.values()))
                if (old.ended && !Collections.disjoint(old.sources, graph.nodes())) stop(old.master);
            session.sources.add(root.immutable());
            session.sources.addAll(graph.speakers());
        }
        sessions.put(root.immutable(), session);
        if (occupied) return true; // queued discs never auto-start mid-song
        session.voices = new SpareVoices();
        for (var player : level.players()) {
            // Select a listener once for the whole wired group. Distant sources must
            // already be advancing when that listener later walks into their range.
            boolean hearsGroup = session.sources.stream().anyMatch(source ->
                    player.distanceToSqr(source.getX() + .5, source.getY() + .5, source.getZ() + .5) <= 64 * 64);
            if (!hearsGroup) continue;
            session.listeners.put(player.getUUID(), player);
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            for (BlockPos source : session.sources) {
                packets.add(new ClientboundLevelEventPacket(1010, source, songId, false));
            }
            session.voices.appendStart(player, playback.value().soundEvent(), packets);
            if (!packets.isEmpty()) player.connection.send(new ClientboundBundlePacket(packets));
        }
        return true;
    }
    /** Natural completion changes server state, but does not truncate delayed client buffers. */
    public boolean finish(BlockPos master, boolean natural) {
        Session s = sessions.get(master);
        if (s == null) return false;
        if (natural) {
            s.ended = true;
            clearNotes(s);
            if (s.voices != null) s.voices.retireUnused(s.listeners.values());
        } else stop(master);
        return true;
    }
    public void stop(BlockPos master) {
        Session s = sessions.remove(master);
        if (s != null) {
            clearNotes(s);
            pruneListeners(s);
            for (BlockPos source : s.sources) if (s.voices == null || s.voices.voiceId(source) == -1)
                sendPositionalStop(s, source);
            if (s.voices != null) s.voices.destroy(s.listeners.values());
        }
    }
    private void stopSource(Session session, BlockPos source) {
        var notes = session.notes.remove(source);
        if (notes != null) notes.destroy();
        if (session.voices == null || !session.voices.release(source, session.listeners.values(), session.ended))
            sendPositionalStop(session, source);
        session.sources.remove(source);
    }
    private void clearNotes(Session session) {
        session.notes.values().forEach(SpeakerNotes::destroy);
        session.notes.clear();
    }
    private void sendPositionalStop(Session session, BlockPos source) {
        for (ServerPlayer player : session.listeners.values())
            player.connection.send(new ClientboundLevelEventPacket(1011, source, 0, false));
    }
    private void pruneListeners(Session session) {
        var it = session.listeners.values().iterator();
        while (it.hasNext()) {
            ServerPlayer player = it.next();
            ServerPlayer currentPlayer = level.getServer().getPlayerList().getPlayer(player.getUUID());
            boolean samePlayer = currentPlayer == player;
            if (!samePlayer || player.level() != level) {
                // Same-dimension respawn can retain the client world while replacing
                // its server player. Explicitly remove that listener's old playback.
                boolean sameWorld = currentPlayer != null && currentPlayer.level() == level;
                if (sameWorld) for (BlockPos source : session.sources)
                    if (session.voices == null || session.voices.voiceId(source) == -1)
                        currentPlayer.connection.send(new ClientboundLevelEventPacket(1011, source, 0, false));
                if (session.voices != null) session.voices.forget(sameWorld ? currentPlayer : player, sameWorld);
                it.remove();
            } else if (session.voices != null) session.voices.keepParkedOutOfEarshot(player);
        }
    }
    public void changed(BlockPos pos) {
        var affected = WireConnections.affected(pos);
        // Chunk-load callbacks run before some chunk systems complete availability.
        // Do not query the world here: a blocking lookup can wait on this callback itself.
        // Tick-time reconciliation filters these bounded candidates to actual wires.
        dirtyWires.addAll(affected);
        for (Session s : sessions.values()) {
            if (affected.stream().anyMatch(s.nodes::contains)) s.dirty = true;
        }
    }
    public void refreshChunk(net.minecraft.world.level.chunk.LevelChunk chunk) {
        // Only chunk lifecycle events scan palettes; normal updates are bounded to the changed block's neighborhood.
        chunk.findBlocks(WireConnections::isWire, (pos, state) -> changed(pos));
    }
    public void unload(ChunkPos chunk) {
        unloaded.add(chunk.pack());
        try {
            sessions.values().forEach(s -> s.dirty = true);
            tick(); // stop before the chunk becomes unavailable; exclude it from traversal
        } finally {
            unloaded.remove(chunk.pack()); // no retained history of explored chunks
        }
    }
    public void load(ChunkPos chunk) {
        unloaded.remove(chunk.pack());
        sessions.values().forEach(s -> s.dirty = true);
    }
    public void tick() {
        var pendingWires = Set.copyOf(dirtyWires);
        dirtyWires.clear();
        for (var pos : pendingWires) {
            var state = state(pos);
            if (!WireConnections.isWire(state)) continue;
            var connected = CopperSpeakers.WIRE.connections(state, level, pos);
            if (connected != state) level.setBlock(pos, connected, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        var changed = new LinkedHashMap<Session, NetworkGraph.Component<BlockPos>>();
        for (Session s : List.copyOf(sessions.values())) {
            pruneListeners(s);
            if (!s.dirty) continue;
            s.dirty = false;
            if (!state(s.master).is(Blocks.JUKEBOX)) { stop(s.master); continue; }
            var current = component(s.master);
            for (BlockPos source : List.copyOf(s.sources))
                if (!source.equals(s.master) && (current.overflow() || !current.speakers().contains(source))) stopSource(s, source);
            s.nodes = current.nodes();
            changed.put(s, current);
        }
        // Complete disconnections before assignments. The earliest still-active
        // session owns new speakers when independently playing circuits merge.
        for (var entry : changed.entrySet()) {
            Session s = entry.getKey();
            if (s.ended || s.voices == null || s.sources.isEmpty() || entry.getValue().overflow()) continue;
            for (BlockPos speaker : entry.getValue().speakers().stream().sorted().toList()) {
                if (s.sources.contains(speaker)) continue;
                boolean claimed = sessions.values().stream().anyMatch(other -> !other.ended && other.sources.contains(speaker));
                Session owner = sessions.values().stream().filter(other -> !other.ended && !other.sources.isEmpty()
                        && other.nodes.contains(speaker)).findFirst().orElse(null);
                if (claimed || owner != s || s.voices.available() == 0) continue;
                for (Session tail : sessions.values()) if (tail.ended && tail.sources.contains(speaker)) stopSource(tail, speaker);
                if (s.voices.bind(speaker, s.listeners.values())) s.sources.add(speaker.immutable());
            }
        }
        for (Session s : sessions.values()) {
            if (s.ended) continue;
            for (BlockPos source : s.sources) {
                if (source.equals(s.master)) continue;
                var block = state(source);
                if (!(block.getBlock() instanceof SpeakerBlock)) continue;
                var facing = block.getValue(SpeakerBlock.FACING);
                var notes = s.notes.get(source);
                if (notes != null && notes.facing() != facing) {
                    notes.destroy();
                    s.notes.remove(source);
                    notes = null;
                }
                if (notes == null) {
                    notes = SpeakerNotes.create(level, source, facing);
                    if (notes != null) s.notes.put(source, notes);
                }
                if (notes != null) notes.tick();
            }
        }
    }
    // Read-only diagnostics used by integration tests and the development setup.
    public Set<BlockPos> sources(BlockPos master) {
        Session s = sessions.get(master); return s == null ? Set.of() : Set.copyOf(s.sources);
    }
    public boolean hasSession(BlockPos master) { return sessions.containsKey(master); }
    public boolean isNaturalTail(BlockPos master) { return sessions.containsKey(master) && sessions.get(master).ended; }
    public int spareVoiceCount(BlockPos master) {
        Session s = sessions.get(master); return s == null || s.voices == null ? 0 : s.voices.available();
    }
    public Set<Integer> spareEntityIds(BlockPos master) {
        Session s = sessions.get(master); return s == null || s.voices == null ? Set.of() : s.voices.entityIds();
    }
    public int speakerVoiceId(BlockPos master, BlockPos speaker) {
        Session s = sessions.get(master); return s == null || s.voices == null ? -1 : s.voices.voiceId(speaker);
    }
    public int listenerCount(BlockPos master) {
        Session s = sessions.get(master); return s == null ? 0 : s.listeners.size();
    }
    public Map<BlockPos, SpeakerNotes> speakerNotes(BlockPos master) {
        Session s = sessions.get(master); return s == null ? Map.of() : Map.copyOf(s.notes);
    }
}
