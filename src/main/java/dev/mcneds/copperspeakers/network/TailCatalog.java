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
import java.util.zip.ZipFile;

/** Server-local audio catalogue, with per-connection pack readiness. */
public final class TailCatalog {
    public static final long PREPARATION_NANOS = 3_000_000_000L;
    private static final Map<MinecraftServer, TailCatalog> CATALOGS = new ConcurrentHashMap<>();
    private final Map<Identifier, Track> tracks = new ConcurrentHashMap<>();
    private final Map<Object, Track> retained = new ConcurrentHashMap<>();
    private final Map<Path, TailCatalog> managed = new LinkedHashMap<>();
    private volatile Map<String, PreparedSongs.Setting> settings = Map.of();
    private PreparedSongs control;
    private Map<Identifier, Track> legacyTracks = Map.of();
    private static final Map<PacketContext, PackState> CONNECTIONS = Collections.synchronizedMap(new WeakHashMap<>());
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
        if (catalogue != null) { catalogue.active.put(session, track); catalogue.retained.put(session, track); }
    }
    public static void deactivate(MinecraftServer server, Object session) {
        var catalogue = CATALOGS.get(server);
        if (catalogue != null) catalogue.active.remove(session);
    }
    public static void release(MinecraftServer server, Object session) {
        var catalogue = CATALOGS.get(server);
        if (catalogue != null) { catalogue.active.remove(session); catalogue.retained.remove(session); }
    }
    private static PackState state(PacketContext context) {
        var state = context.get(PACK_STATE);
        if (state == null) { state = new PackState(); context.set(PACK_STATE, state); CONNECTIONS.put(context, state); }
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
        return track.packs.stream().allMatch(p -> state.ready.contains(p.uuid));
    }
    public static void response(ServerCommonPacketListenerImpl listener, ServerboundResourcePackPacket packet) {
        var state = ((PacketContextProvider) listener).getPacketContext().get(PACK_STATE);
        if (state == null || !state.pending.contains(packet.id())) return;
        switch (packet.action()) {
            case SUCCESSFULLY_LOADED -> { state.ready.add(packet.id()); state.pending.remove(packet.id()); }
            case ACCEPTED, DOWNLOADED -> { }
            default -> { state.pending.remove(packet.id()); listener.disconnect(Component.literal("Copper Speakers music resource pack could not be loaded. Please reconnect.")); }
        }
    }
    public static Track find(MinecraftServer server, Identifier sound) {
        var catalogue = CATALOGS.get(server);
        return catalogue == null || !admissionsAllowed(server, sound) ? null : catalogue.tracks.get(sound);
    }
    public static PreparedSongs control(MinecraftServer server) {
        var catalogue = CATALOGS.get(server);
        return catalogue == null ? null : catalogue.control;
    }
    public static Set<Identifier> knownSounds(MinecraftServer server) {
        var catalogue = CATALOGS.get(server);
        if (catalogue == null) return Set.of();
        var sounds = new HashSet<>(catalogue.tracks.keySet());
        catalogue.settings.keySet().forEach(sound -> sounds.add(Identifier.parse(sound)));
        return sounds;
    }
    public static boolean admissionsAllowed(MinecraftServer server, Identifier sound) {
        var catalogue = CATALOGS.get(server);
        var setting = catalogue == null ? null : catalogue.settings.get(sound.toString());
        return setting == null || setting.enabled();
    }
    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(TailCatalog::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var catalogue = CATALOGS.get(server);
            if (catalogue != null && catalogue.control != null) catalogue.control.close();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(CATALOGS::remove);
        AutoHostUtils.SEND_RESOURCE_PACK_COLLECTOR.register((provider, context, consumer) -> {
            var server = context.get(PacketContext.SERVER_INSTANCE);
            var catalogue = server == null ? null : CATALOGS.get(server);
            if (catalogue == null) return;
            var selected = new LinkedHashSet<Pack>();
            catalogue.packs.stream().filter(Pack::base).forEach(selected::add);
            // Load prepared audio with the login packs so first insertion does not
            // interrupt gameplay with a resource reload. Include managed songs too.
            catalogue.tracks.forEach((sound, track) -> {
                if (admissionsAllowed(server, sound)) selected.addAll(track.packs);
            });
            // A playing session may still use an older version of a replaced song.
            catalogue.active.values().forEach(track -> selected.addAll(track.packs));
            var state = state(context);
            for (var pack : selected) {
                state.pending.add(pack.uuid);
                consumer.accept(provider.createProperties(context, pack.uuid, pack.id, pack.sha1));
            }
        });
    }
    private static void load(MinecraftServer server) {
        if (!server.isDedicatedServer()) return;
        Path config = FabricLoader.getInstance().getConfigDir().resolve("copper-speakers").toAbsolutePath().normalize();
        TailCatalog catalogue = new TailCatalog();
        Path legacy = config.resolve("tails");
        if (Files.isRegularFile(legacy.resolve("catalog.json"))) {
            try { catalogue = read(legacy); catalogue.host(); }
            catch (Exception e) { LoggerFactory.getLogger(CopperSpeakers.ID).error("Prepared audio catalogue rejected; using original playback", e); }
        }
        catalogue.legacyTracks = Map.copyOf(catalogue.tracks);
        try { catalogue.settings = PreparedSongs.readSettings(config.resolve("joining/settings.json")); }
        catch (Exception e) { LoggerFactory.getLogger(CopperSpeakers.ID).error("Invalid joining settings; preserving catalogue defaults", e); }
        CATALOGS.put(server, catalogue);
        try { catalogue.startControl(server, config.resolve("joining")); }
        catch (Exception e) { LoggerFactory.getLogger(CopperSpeakers.ID).error("Could not start per-song joining control", e); }
    }
    private final Map<Pack, Path> files = new LinkedHashMap<>();
    static TailCatalog read(Path directory) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        Path manifest = root.resolve("catalog.json");
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
        Map<Pack, Set<String>> provided = new HashMap<>();
        for (var pack : paths.entrySet()) {
            Set<String> events = new HashSet<>();
            try (var archive = new ZipFile(pack.getValue().toFile())) {
                var definitions = archive.getEntry("assets/copper_speakers/sounds.json");
                if (definitions == null) throw new IllegalArgumentException("Missing prepared sound definitions");
                var sounds = JsonParser.parseString(new String(archive.getInputStream(definitions).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (var definition : sounds.entrySet()) {
                    String event = CopperSpeakers.ID + ":" + definition.getKey();
                    var variants = definition.getValue().getAsJsonObject().getAsJsonArray("sounds");
                    if (variants.size() != 1) throw new IllegalArgumentException("Prepared audio must have one fixed recording");
                    var audio = variants.get(0).getAsJsonObject();
                    if (!audio.get("name").getAsString().equals(event) || audio.get("stream").getAsBoolean()
                            || archive.getEntry("assets/copper_speakers/sounds/" + definition.getKey() + ".ogg") == null)
                        throw new IllegalArgumentException("Missing or invalid prepared recording: " + event);
                    events.add(event);
                }
            }
            provided.put(pack.getKey(), events);
        }
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
            if (format == 1) required.addAll(paths.keySet());
            if (format == 2) for (var file : track.getAsJsonArray("packs"))
                required.add(Objects.requireNonNull(byFile.get(file.getAsString()), "Unknown track pack"));
            paths.keySet().stream().filter(Pack::base).forEach(required::add);
            for (var entry : entries) if (required.stream().noneMatch(p -> provided.get(p).contains(entry.sound.value().location().toString())))
                throw new IllegalArgumentException("Track references audio outside its required packs");
            catalogue.tracks.put(Identifier.parse(row.getKey()), new Track(duration, List.copyOf(entries), List.copyOf(new LinkedHashSet<>(required))));
        }
        catalogue.files.putAll(paths);
        catalogue.packs.addAll(paths.keySet());
        return catalogue;
    }
    private void host() { files.forEach((pack, path) -> AutoHostUtils.registerHostedFile(pack.id, path)); }
    private void startControl(MinecraftServer server, Path root) throws Exception {
        control = new PreparedSongs(root, new PreparedSongs.Backend() {
            private <T> T onServer(java.util.concurrent.Callable<T> task) throws Exception {
                return server.submit(() -> { try { return task.call(); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); } }).get();
            }
            @Override public void settings(Map<String, PreparedSongs.Setting> value) throws Exception {
                onServer(() -> { settings = value; return null; });
            }
            @Override public boolean available(String sound) throws Exception {
                return onServer(() -> tracks.containsKey(Identifier.parse(sound)));
            }
            @Override public void install(String sound, Path directory) throws Exception {
                var addition = read(directory); // IO and verification on control worker
                if (addition.tracks.size() != 1 || !addition.tracks.containsKey(Identifier.parse(sound)))
                    throw new IllegalArgumentException("Preparation must contain exactly the requested song");
                onServer(() -> {
                    var registered = managed.get(directory);
                    if (registered == null) {
                        addition.host();
                        managed.put(directory, addition);
                        registered = addition;
                    }
                    tracks.put(Identifier.parse(sound), registered.tracks.get(Identifier.parse(sound)));
                    return null;
                });
            }
            @Override public boolean retire(String sound, Path directory) throws Exception {
                return onServer(() -> {
                    var addition = managed.get(directory);
                    if (addition == null) return true;
                    var track = addition.tracks.get(Identifier.parse(sound));
                    if (retained.containsValue(track)) return false;
                    synchronized (CONNECTIONS) {
                        for (var entry : CONNECTIONS.entrySet()) {
                            if (entry.getKey().get(PacketContext.CONNECTION).isConnected()
                                    && track.packs.stream().anyMatch(p -> entry.getValue().pending.contains(p.uuid))) return false;
                        }
                        var key = Identifier.parse(sound);
                        if (tracks.remove(key, track) && legacyTracks.containsKey(key)) tracks.put(key, legacyTracks.get(key));
                        managed.remove(directory);
                        // Identical content can be shared by generations: point its URL at a surviving owner.
                        files.forEach((pack, path) -> AutoHostUtils.registerHostedFile(pack.id, path));
                        managed.values().forEach(TailCatalog::host);
                    }
                    // AutoHost has no unregister API. The immutable URL becomes unavailable after file deletion.
                    return true;
                });
            }
            @Override public Map<String, Object> details(String sound) { return Map.of(); }
            @Override public Map<String, Map<String, Object>> snapshot(Set<String> sounds) throws Exception {
                return onServer(() -> {
                    Map<String, Map<String, Object>> result = new HashMap<>();
                    for (var sound : sounds) {
                        var key = Identifier.parse(sound);
                        var versions = Collections.newSetFromMap(new IdentityHashMap<Track, Boolean>());
                        if (tracks.containsKey(key)) versions.add(tracks.get(key));
                        if (legacyTracks.containsKey(key)) versions.add(legacyTracks.get(key));
                        managed.values().forEach(c -> { if (c.tracks.containsKey(key)) versions.add(c.tracks.get(key)); });
                        Set<UUID> required = new HashSet<>();
                        versions.forEach(t -> t.packs.forEach(p -> required.add(p.uuid)));
                        long pendingDownloads = 0;
                        synchronized (CONNECTIONS) {
                            for (var connection : CONNECTIONS.entrySet()) {
                                if (connection.getKey().get(PacketContext.SERVER_INSTANCE) == server
                                        && connection.getKey().get(PacketContext.CONNECTION).isConnected())
                                    pendingDownloads += connection.getValue().pending.stream().filter(required::contains).count();
                            }
                        }
                        result.put(sound, Map.of("available", tracks.containsKey(key),
                                "legacyCatalogueRetained", legacyTracks.containsKey(key),
                                "pendingDownloads", pendingDownloads,
                                "activeSessions", retained.values().stream().filter(versions::contains).count()));
                    }
                    return result;
                });
            }
            @Override public Map<String, Object> events() throws Exception {
                return onServer(() -> {
                    Map<String, Object> result = new TreeMap<>();
                    List<TailCatalog> catalogues = new ArrayList<>(managed.values());
                    catalogues.add(TailCatalog.this);
                    for (var catalogue : catalogues) catalogue.tracks.forEach((sound, track) -> {
                        for (var entry : track.entries) result.put(entry.sound.value().location().toString(),
                                Map.of("sound", sound.toString(), "offsetSeconds", entry.offsetSeconds));
                    });
                    return result;
                });
            }
        }, PreparedSongs.processPreparer(root));
        control.start();
    }
}
