package dev.mcneds.copperspeakers.network;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Local file control plane. All hashing, preparation and disk IO run off the server thread. */
public final class PreparedSongs implements AutoCloseable {
    public record Setting(String revision, boolean enabled, String source, double spacingSeconds, boolean deletePrepared) {
        public Setting {
            if (revision == null || revision.isBlank() || !Double.isFinite(spacingSeconds)
                    || spacingSeconds < 1 || spacingSeconds > 30 || enabled && deletePrepared)
                throw new IllegalArgumentException("Invalid per-song setting");
        }
        boolean sameAudio(Setting other) {
            return Objects.equals(source, other.source) && spacingSeconds == other.spacingSeconds;
        }
    }
    public interface Backend {
        // Implementations marshal changes onto the server thread. Existing sessions retain their Track.
        void settings(Map<String, Setting> settings) throws Exception;
        boolean available(String sound) throws Exception;
        void install(String sound, Path directory) throws Exception;
        boolean retire(String sound, Path directory) throws Exception;
        Map<String, Object> details(String sound) throws Exception;
        default Map<String, Map<String, Object>> snapshot(Set<String> sounds) throws Exception {
            Map<String, Map<String, Object>> result = new HashMap<>();
            for (var sound : sounds) {
                var row = new HashMap<>(details(sound));
                row.put("available", available(sound));
                result.put(sound, row);
            }
            return result;
        }
        Map<String, Object> events() throws Exception;
    }
    @FunctionalInterface public interface Preparer {
        void prepare(String sound, Setting setting, Path output) throws Exception;
    }
    private record Version(String sound, Setting setting, Path directory, String sourceHash) {}
    private record Result(String sound, Setting setting, Path directory, String sourceHash, String error) {}
    private record Job(Setting setting, Future<?> future) {}
    private final Path root;
    private final Backend backend;
    private final Preparer preparer;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("copper-joining-control").factory());
    private final ExecutorService audio = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("copper-joining-audio").factory());
    private final Queue<Result> completed = new ConcurrentLinkedQueue<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private final Map<String, String> attempted = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final List<Version> versions = new ArrayList<>();
    private final Set<Path> rejected = new HashSet<>();
    private Map<String, Setting> desired = Map.of();
    private Map<String, Object> lastEvents;
    private String settingsError;
    private volatile boolean closed;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public PreparedSongs(Path root, Backend backend, Preparer preparer) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.backend = backend;
        this.preparer = preparer;
        Files.createDirectories(this.root.resolve("songs"));
        // Only completed, owned directories are recovered. Partial jobs are never activated.
        try (var files = Files.walk(this.root.resolve("songs"), 2)) {
            for (var request : files.filter(p -> p.getFileName().toString().equals("request.json")).toList()) {
                try {
                    var json = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
                    var directory = request.getParent();
                    if (Files.isRegularFile(directory.resolve("catalog.json"))) versions.add(new Version(
                            json.get("sound").getAsString(), JSON.fromJson(json.get("setting"), Setting.class), directory,
                            json.get("sourceSha256").getAsString()));
                } catch (RuntimeException ignored) { /* An incomplete job is not a catalogue. */ }
            }
        }
        // A killed JVM may leave a partial subprocess output. No playback or
        // downloads from that JVM survive startup, so these owned remnants are safe to remove.
        try (var directories = Files.list(this.root.resolve("songs"))) {
            for (var directory : directories.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList())
                if (versions.stream().noneMatch(v -> v.directory.equals(directory))) deleteTree(directory);
        }
    }
    public void start() { poller.scheduleWithFixedDelay(this::safePoll, 0, 1, TimeUnit.SECONDS); }
    private void safePoll() {
        if (closed) return;
        try { poll(); }
        catch (Exception e) {
            settingsError = e.toString();
            try { writeStatus(); } catch (Exception ignored) { }
        }
    }
    // Package access permits deterministic lifecycle tests without timing sleeps.
    void poll() throws Exception {
        Map<String, Setting> next;
        try {
            next = readSettings(root.resolve("settings.json"));
            settingsError = null;
        } catch (Exception e) {
            settingsError = e.toString();
            writeStatus();
            return; // Malformed input never wipes the previous valid settings.
        }
        backend.settings(next);
        desired = next;
        for (var it = jobs.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (!entry.getValue().setting.equals(desired.get(entry.getKey()))) {
                entry.getValue().future.cancel(true);
                it.remove();
            }
        }
        Result result;
        while ((result = completed.poll()) != null) {
            var current = desired.get(result.sound);
            var job = jobs.get(result.sound);
            if (job != null && job.setting.equals(result.setting)) jobs.remove(result.sound);
            if (result.error != null) {
                if (result.setting.equals(current)) errors.put(result.sound, result.error);
                deleteTree(result.directory);
            } else {
                versions.add(new Version(result.sound, result.setting, result.directory, result.sourceHash));
                if (result.setting.equals(current) && current.enabled) {
                    try {
                        backend.install(result.sound, result.directory);
                        errors.remove(result.sound);
                    } catch (Exception e) {
                        rejected.add(result.directory);
                        errors.put(result.sound, e.toString());
                    }
                }
            }
        }
        for (var entry : desired.entrySet()) {
            String sound = entry.getKey();
            Setting setting = entry.getValue();
            if (!Objects.equals(attempted.get(sound), setting.revision)) errors.remove(sound);
            if (setting.enabled && !jobs.containsKey(sound) && !Objects.equals(attempted.get(sound), setting.revision)) {
                attempted.put(sound, setting.revision);
                try {
                    String sourceHash = setting.source == null ? "" : hash(Path.of(setting.source));
                    var reusable = versions.stream().filter(v -> v.sound.equals(sound) && v.setting.sameAudio(setting)
                            && v.sourceHash.equals(sourceHash) && !rejected.contains(v.directory)).reduce((a, b) -> b);
                    if (reusable.isPresent()) {
                        try { backend.install(sound, reusable.get().directory); }
                        catch (Exception e) { rejected.add(reusable.get().directory); throw e; }
                    }
                    else if (setting.source == null && backend.available(sound)) { /* Existing catalogue, no conversion. */ }
                    else {
                        if (setting.source == null || !Files.isRegularFile(Path.of(setting.source)))
                            throw new IllegalArgumentException("Supply an absolute path to the published mono Vorbis source");
                        Path output = root.resolve("songs").resolve(UUID.randomUUID().toString());
                        var task = audio.submit(() -> {
                            String error = null;
                            try {
                                preparer.prepare(sound, setting, output);
                                if (!sourceHash.equals(hash(Path.of(setting.source)))) throw new IOException("Source changed during preparation");
                                atomicJson(output.resolve("request.json"), Map.of("sound", sound, "setting", setting, "sourceSha256", sourceHash));
                            } catch (Exception e) { error = e.toString(); }
                            completed.add(new Result(sound, setting, output, sourceHash, error));
                        });
                        jobs.put(sound, new Job(setting, task));
                    }
                } catch (Exception e) { errors.put(sound, e.toString()); }
            }
        }
        // Old generations stay hosted until all sessions and pack downloads release them.
        for (var it = versions.iterator(); it.hasNext();) {
            var version = it.next();
            var setting = desired.get(version.sound);
            if (setting == null || !setting.deletePrepared || setting.enabled) continue;
            if (backend.retire(version.sound, version.directory)) {
                deleteTree(version.directory);
                it.remove();
            }
        }
        var events = backend.events();
        if (!events.equals(lastEvents) || !Files.exists(root.resolve("events.json"))) {
            atomicJson(root.resolve("events.json"), Map.of("format", 1, "events", events));
            lastEvents = events;
        }
        writeStatus();
    }
    private void writeStatus() throws Exception {
        Map<String, Object> tracks = new TreeMap<>();
        var snapshot = backend.snapshot(desired.keySet());
        for (var entry : desired.entrySet()) {
            String sound = entry.getKey();
            var setting = entry.getValue();
            long count = versions.stream().filter(v -> v.sound.equals(sound)).count();
            var row = new LinkedHashMap<String, Object>();
            row.put("revision", setting.revision);
            row.put("enabled", setting.enabled);
            row.put("spacingSeconds", setting.spacingSeconds);
            row.put("state", jobs.containsKey(sound) ? "preparing" : errors.containsKey(sound) ? "failed"
                    : setting.deletePrepared && count > 0 ? "deletion_pending" : !setting.enabled ? "disabled"
                    : Boolean.TRUE.equals(snapshot.get(sound).get("available")) ? "ready" : "unprepared");
            row.put("preparedVersions", count);
            row.put("activation", "next_insertion");
            if (errors.containsKey(sound)) row.put("error", errors.get(sound));
            var details = new HashMap<>(snapshot.get(sound));
            details.remove("available");
            row.putAll(details);
            tracks.put(sound, row);
        }
        var status = new LinkedHashMap<String, Object>();
        status.put("format", 1);
        status.put("updatedAt", java.time.Instant.now().toString());
        status.put("tracks", tracks);
        if (settingsError != null) status.put("settingsError", settingsError);
        atomicJson(root.resolve("status.json"), status);
    }
    public static Map<String, Setting> readSettings(Path file) throws IOException {
        if (!Files.exists(file)) return Map.of();
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (json.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unknown joining settings format");
        Map<String, Setting> settings = new TreeMap<>();
        for (var entry : json.getAsJsonObject("tracks").entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid sound identifier");
            var row = entry.getValue().getAsJsonObject();
            String source = row.has("source") ? row.get("source").getAsString() : null;
            if (source != null && !Path.of(source).isAbsolute()) throw new IllegalArgumentException("Source path must be absolute");
            settings.put(entry.getKey(), new Setting(row.get("revision").getAsString(), row.get("enabled").getAsBoolean(), source,
                    row.has("spacingSeconds") ? row.get("spacingSeconds").getAsDouble() : 15,
                    row.has("deletePrepared") && row.get("deletePrepared").getAsBoolean()));
        }
        return Map.copyOf(settings);
    }
    public static void atomicJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(value) + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static String hash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private void deleteTree(Path directory) throws IOException {
        Path owned = root.resolve("songs");
        if (!directory.normalize().startsWith(owned) || directory.equals(owned)) throw new IOException("Unowned preparation directory");
        if (!Files.exists(directory)) return;
        try (var files = Files.walk(directory)) {
            for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    public static Preparer processPreparer(Path root) {
        return (sound, setting, output) -> {
            List<String> command = new ArrayList<>();
            Path configuration = root.resolve("preparer.json");
            if (Files.exists(configuration)) {
                for (var arg : JsonParser.parseString(Files.readString(configuration)).getAsJsonArray()) command.add(arg.getAsString());
            } else {
                Path script = root.resolve("prepare-join-audio.py");
                try (var input = PreparedSongs.class.getResourceAsStream("/copper-speakers/prepare-join-audio.py")) {
                    if (input == null) throw new IOException("Missing bundled audio preparer");
                    Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING);
                }
                command.add(System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
                command.add(script.toString());
            }
            command.addAll(List.of("--output", output.toString(), "--song", sound + "=" + setting.source,
                    "--spacing", Double.toString(setting.spacingSeconds)));
            Path log = root.resolve("preparation.log");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                if (process.waitFor() != 0) throw new IOException("Audio preparation failed; see " + log);
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        };
    }
    @Override public void close() {
        closed = true;
        poller.shutdownNow();
        audio.shutdownNow();
    }
}
