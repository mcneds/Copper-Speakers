package dev.mcneds.copperspeakers.network;

import com.google.gson.JsonParser;
import dev.mcneds.copperspeakers.CopperSpeakers;
import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.fabric.api.networking.v1.context.PacketContextProvider;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server-local audio catalogue, with per-connection pack readiness. */
public final class TailCatalog {
    public static final long PREPARATION_NANOS = 3_000_000_000L;
    private static final Map<MinecraftServer, TailCatalog> CATALOGS = new ConcurrentHashMap<>();
    private final Map<Identifier, Track> tracks = new HashMap<>();
    private final List<Pack> packs = new ArrayList<>();
    private final Map<Object, Track> active = new ConcurrentHashMap<>();
    private static final PacketContext.Key<PackState> PACK_STATE = PacketContext.key(CopperSpeakers.id("tail_packs"));
    private static final class PackState {
        final Set<UUID> ready = ConcurrentHashMap.newKeySet();
        final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    }
    public record Pack(Identifier id, String sha1, UUID uuid, boolean base) {}
    public record Entry(double offsetSeconds, Holder<SoundEvent> sound) {}
    public record Track(double durationSeconds, List<Entry> entries, List<Pack> packs) {
        public Entry atOrAfter(double seconds) {
            return entries.stream().filter(e -> e.offsetSeconds >= seconds).findFirst().orElse(null);
        }
    }
    public static void activate(MinecraftServer server, Object session, Track track) {
        var catalogue = CATALOGS.get(server);
        if (catalogue != null) catalogue.active.put(session, track);
    }
    public static void deactivate(MinecraftServer server, Object session) {
        var catalogue = CATALOGS.get(server);
        if (catalogue != null) catalogue.active.remove(session);
    }
    private static PackState state(PacketContext context) {
        var state = context.get(PACK_STATE);
        if (state == null) { state = new PackState(); context.set(PACK_STATE, state); }
        return state;
    }
    public static boolean loading(ServerPlayer player) {
        return !state(((PacketContextProvider) player.connection).getPacketContext()).pending.isEmpty();
    }
    public static boolean ensureReady(ServerPlayer player, Track track) {
        var context = ((PacketContextProvider) player.connection).getPacketContext();
        var state = state(context);
        var provider = ResourcePackDataProvider.getActive();
        if (provider == null || !provider.isReady(context)) return false;
        for (var pack : track.packs) {
            if (state.ready.contains(pack.uuid) || !state.pending.add(pack.uuid)) continue;
            var info = provider.createProperties(context, pack.uuid, pack.id, pack.sha1);
            player.connection.send(new ClientboundResourcePackPushPacket(info.id(), info.url(), info.hash(), true, Optional.ofNullable(info.prompt())));
        }
        return state.pending.isEmpty();
    }
    public static void response(ServerCommonPacketListenerImpl listener, ServerboundResourcePackPacket packet) {
        var state = ((PacketContextProvider) listener).getPacketContext().get(PACK_STATE);
        if (state == null || !state.pending.contains(packet.id())) return;
        switch (packet.action()) {
            case SUCCESSFULLY_LOADED -> { state.ready.add(packet.id()); state.pending.remove(packet.id()); }
            case ACCEPTED, DOWNLOADED -> { }
            default -> listener.disconnect(Component.literal("Copper Speakers music resource pack could not be loaded. Please reconnect."));
        }
    }
    public static Track find(MinecraftServer server, Identifier sound) {
        var catalogue = CATALOGS.get(server);
        return catalogue == null ? null : catalogue.tracks.get(sound);
    }
    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(TailCatalog::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(CATALOGS::remove);
        AutoHostUtils.SEND_RESOURCE_PACK_COLLECTOR.register((provider, context, consumer) -> {
            var server = context.get(PacketContext.SERVER_INSTANCE);
            var catalogue = server == null ? null : CATALOGS.get(server);
            if (catalogue == null) return;
            var selected = new LinkedHashSet<Pack>();
            catalogue.packs.stream().filter(Pack::base).forEach(selected::add);
            catalogue.active.values().forEach(track -> selected.addAll(track.packs));
            var state = state(context);
            for (var pack : selected) {
                state.pending.add(pack.uuid);
                consumer.accept(provider.createProperties(context, pack.uuid, pack.id, pack.sha1));
            }
        });
    }
    private static void load(MinecraftServer server) {
        Path root = FabricLoader.getInstance().getConfigDir().resolve("copper-speakers/tails").toAbsolutePath().normalize();
        Path manifest = root.resolve("catalog.json");
        if (!Files.isRegularFile(manifest)) return;
        // AutoHost intentionally skips its configuration task for an integrated
        // server's owner. Never select audio that their local pack does not contain.
        if (!server.isDedicatedServer()) {
            LoggerFactory.getLogger(CopperSpeakers.ID).info("Prepared joining packs require a dedicated server; using original playback in this world");
            return;
        }
        try {
            var json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            int format = json.get("format").getAsInt();
            if (format != 1 && format != 2) throw new IllegalArgumentException("Unsupported tail catalogue format");
            TailCatalog catalogue = new TailCatalog();
            var paths = new LinkedHashMap<Pack, Path>();
            var byFile = new HashMap<String, Pack>();
            for (var value : json.getAsJsonArray("packs")) {
                var pack = value.getAsJsonObject();
                Path file = root.resolve(pack.get("file").getAsString()).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > 200L * 1024 * 1024)
                    throw new IllegalArgumentException("Invalid or oversized tail pack: " + file.getFileName());
                String sha1 = pack.get("sha1").getAsString();
                var digest = MessageDigest.getInstance("SHA-1");
                try (var input = Files.newInputStream(file)) {
                    byte[] bytes = new byte[65536]; int count;
                    while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(sha1)) throw new IllegalArgumentException("Tail pack hash mismatch");
                var id = CopperSpeakers.id("tails/" + sha1);
                var info = new Pack(id, sha1, UUID.nameUUIDFromBytes(id.toString().getBytes(StandardCharsets.UTF_8)),
                        format == 1 || pack.get("base").getAsBoolean());
                paths.put(info, file);
                byFile.put(pack.get("file").getAsString(), info);
            }
            if (paths.isEmpty()) throw new IllegalArgumentException("Tail catalogue has no resource packs");
            for (var row : json.getAsJsonObject("tracks").entrySet()) {
                var track = row.getValue().getAsJsonObject();
                double duration = track.get("durationSeconds").getAsDouble();
                if (!Double.isFinite(duration) || duration <= 0) throw new IllegalArgumentException("Invalid track duration");
                List<Entry> entries = new ArrayList<>();
                double previous = -1;
                for (var value : track.getAsJsonArray("entries")) {
                    var entry = value.getAsJsonObject();
                    double offset = entry.get("offsetSeconds").getAsDouble();
                    var event = Identifier.parse(entry.get("event").getAsString());
                    if (!Double.isFinite(offset) || offset <= previous || offset >= duration
                            || !event.getNamespace().equals(CopperSpeakers.ID) || !event.getPath().startsWith("tail/"))
                        throw new IllegalArgumentException("Invalid tail entry");
                    entries.add(new Entry(offset, Holder.direct(SoundEvent.createVariableRangeEvent(event))));
                    previous = offset;
                }
                if (entries.isEmpty() || entries.getFirst().offsetSeconds != 0) throw new IllegalArgumentException("Missing full-track entry");
                List<Pack> required = new ArrayList<>();
                if (format == 2) for (var file : track.getAsJsonArray("packs"))
                    required.add(Objects.requireNonNull(byFile.get(file.getAsString()), "Unknown track pack"));
                catalogue.tracks.put(Identifier.parse(row.getKey()), new Track(duration, List.copyOf(entries), List.copyOf(required)));
            }
            paths.forEach((pack, path) -> {
                AutoHostUtils.registerHostedFile(pack.id, path);
                catalogue.packs.add(pack);
            });
            CATALOGS.put(server, catalogue);
            LoggerFactory.getLogger(CopperSpeakers.ID).info("Loaded {} prepared tracks in {} tail packs", catalogue.tracks.size(), catalogue.packs.size());
        } catch (Exception e) {
            // A broken optional catalogue must not replace working original songs.
            LoggerFactory.getLogger(CopperSpeakers.ID).error("Prepared audio catalogue rejected; using original playback", e);
        }
    }
}
