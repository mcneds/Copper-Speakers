package dev.mcneds.copperspeakers.test;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Observation only: connects to an external server, never changes audio scheduling or seeks. */
public final class RemoteAudioObserver {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String,Object> report = new LinkedHashMap<>();
    private record SoundHandle(String event, double x, double y, double z, ChannelAccess.ChannelHandle handle) {}
    public void runTest(ClientGameTestContext context) {
        Path output = Path.of(System.getenv("COPPER_OBSERVE_OUTPUT"));
        Path control = Path.of(System.getenv("COPPER_OBSERVER_CONTROL"));
        String role = System.getenv("COPPER_OBSERVER_ROLE");
        report.put("status", "incomplete");
        report.put("role", role);
        report.put("functionalOnly", Boolean.parseBoolean(System.getenv("COPPER_OBSERVER_FUNCTIONAL_ONLY")));
        report.put("java", System.getProperty("java.version"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("scope", "Separate client JVM, ordinary server and resource-pack delivery, unchanged vanilla audio scheduler. Same host monotonic clock; not a remote-network or acoustic test.");
        List<Object> observations = new ArrayList<>();
        report.put("observations", observations);
        try {
            var offsets = new HashMap<String, Double>();
            var frames = new HashMap<String, Long>();
            var catalogue = JsonParser.parseString(Files.readString(Path.of(System.getenv("COPPER_OBSERVER_CATALOG")))).getAsJsonObject();
            for (var row : catalogue.getAsJsonObject("tracks").entrySet()) {
                var track = row.getValue().getAsJsonObject();
                if (Boolean.parseBoolean(System.getenv("COPPER_OBSERVER_ORIGINAL"))) {
                    String original = row.getKey();
                    String buffered = original.startsWith("minecraft:music_disc.")
                            ? "copper_speakers:buffered." + original.substring("minecraft:music_disc.".length()) : original;
                    offsets.put(buffered, 0.0);
                    frames.put(buffered, track.get("frames").getAsLong());
                }
                for (var e : track.getAsJsonArray("entries")) {
                    var entry = e.getAsJsonObject();
                    offsets.put(entry.get("event").getAsString(), entry.get("offsetSeconds").getAsDouble());
                    frames.put(entry.get("event").getAsString(), track.get("frames").getAsLong() - entry.get("offsetFrames").getAsLong());
                }
            }
            Files.createDirectories(control);
            Files.writeString(control.resolve(role + ".ready"), "ready");
            context.waitFor(c -> {
                if (Files.exists(control.resolve("stop"))) throw new AssertionError("Attempt cancelled before connection");
                return Files.exists(control.resolve(role + ".connect"));
            }, 12000);
            context.runOnClient(c -> {
                c.options.renderDistance().set(2);
                for (var s : SoundSource.values()) c.options.getSoundSourceOptionInstance(s)
                        .set(s == SoundSource.MASTER ? 1.0 : s == SoundSource.RECORDS ? .04 : 0.0);
                String address = "127.0.0.1:28901";
                var data = new ServerData("Copper audio test", address, ServerData.Type.OTHER);
                data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
                ConnectScreen.startConnecting(new TitleScreen(), c, ServerAddress.parseString(address), data, false, null);
            });
            context.waitFor(c -> {
                if (Files.exists(control.resolve("stop")) || c.gui.screen() instanceof DisconnectedScreen)
                    throw new AssertionError("Connection failed or attempt cancelled");
                return c.level != null && c.player != null && c.getConnection() != null;
            }, 6000);
            context.waitTicks(20);
            Files.writeString(control.resolve(role + ".connected"), context.computeOnClient(c -> c.getUser().getName()));
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
            while (!Files.exists(control.resolve("stop"))) {
                if (System.nanoTime() > deadline) throw new AssertionError("Observer timed out");
                observations.add(observe(context, offsets, frames));
                if (observations.size() % 10 == 0) save(output);
                context.waitTicks(2);
            }
            context.runOnClient(c -> c.disconnect(new TitleScreen(), false));
            report.put("status", "complete");
        } catch (Throwable e) {
            report.put("status", "failed"); report.put("failure", e.toString());
            throw new RuntimeException(e);
        } finally { save(output); }
    }
    private Object observe(ClientGameTestContext context, Map<String,Double> offsets, Map<String,Long> expectedFrames) throws Exception {
        var result = new CompletableFuture<Object>();
        context.runOnClient(c -> {
            Object engine = field(c.getSoundManager(), "soundEngine");
            Map<SoundInstance, ChannelAccess.ChannelHandle> all = field(engine, "instanceToChannel");
            var handles = all.entrySet().stream().filter(e -> offsets.containsKey(e.getKey().getIdentifier().toString()) && e.getKey().getY() > 0)
                    .map(e -> new SoundHandle(e.getKey().getIdentifier().toString(), e.getKey().getX(), e.getKey().getY(), e.getKey().getZ(), e.getValue())).toList();
            var frame = new LinkedHashMap<String,Object>();
            frame.put("hostNanos", System.nanoTime());
            frame.put("playerX", c.player == null ? null : c.player.getX());
            frame.put("dimension", c.level == null ? null : c.level.dimension().identifier().toString());
            net.minecraft.network.chat.Component message = field(c.gui.hud, "overlayMessageString");
            int remaining = field(c.gui.hud, "overlayMessageTime");
            frame.put("actionBar", remaining > 0 && message != null ? message.getString() : "");
            ChannelAccess access = field(engine, "channelAccess");
            access.executeOnChannels(unused -> {
                try {
                    List<Object> sounds = new ArrayList<>();
                    for (var h : handles) {
                        Object channel = field(h.handle, "channel");
                        if (channel == null) continue;
                        int source = field(channel, "source");
                        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) continue;
                        double[] clock = new double[2];
                        long before = System.nanoTime();
                        SOFTSourceLatency.alGetSourcedvSOFT(source, SOFTDeviceClock.AL_SEC_OFFSET_CLOCK_SOFT, clock);
                        long after = System.nanoTime();
                        int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
                        int bits = AL10.alGetBufferi(buffer, AL10.AL_BITS), channels = AL10.alGetBufferi(buffer, AL10.AL_CHANNELS);
                        long pcmFrames = AL10.alGetBufferi(buffer, AL10.AL_SIZE) / (bits / 8 * channels);
                        if (pcmFrames != expectedFrames.get(h.event)) throw new AssertionError("Native decoder length differs for " + h.event + ": " + pcmFrames + " vs " + expectedFrames.get(h.event));
                        var sample = new LinkedHashMap<String,Object>();
                        sample.put("event", h.event); sample.put("source", source); sample.put("buffer", buffer);
                        sample.put("x", h.x); sample.put("y", h.y); sample.put("z", h.z);
                        sample.put("offsetSeconds", clock[0]); sample.put("nativeStartSeconds", clock[1] - clock[0]);
                        sample.put("pcmFrames", pcmFrames);
                        sample.put("mediaEpochNanos", before + (after - before) / 2 - Math.round((clock[0] + offsets.get(h.event)) * 1_000_000_000));
                        sample.put("queryNanos", after - before);
                        sounds.add(sample);
                    }
                    if (AL10.alGetError() != AL10.AL_NO_ERROR) throw new AssertionError("OpenAL observation failed");
                    frame.put("sounds", sounds); result.complete(frame);
                } catch (Throwable e) { result.completeExceptionally(e); }
            });
        });
        return result.get(10, TimeUnit.SECONDS);
    }
    private void save(Path path) {
        try { Files.createDirectories(path.getParent()); Files.writeString(path, gson.toJson(report)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object object, String name) {
        try { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return (T)f.get(object); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }
}
