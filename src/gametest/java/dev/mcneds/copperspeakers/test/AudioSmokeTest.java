package dev.mcneds.copperspeakers.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.sounds.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.lwjgl.openal.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Lab's clock-paired OpenAL observation method, applied to actual mod insertion packets. */
public final class AudioSmokeTest implements FabricClientGameTest {
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Object> trials = new ArrayList<>();
    private static final BlockPos MASTER = new BlockPos(0, 90, 0);
    private final int sourceCount = option("COPPER_AUDIO_SOURCES", 3, 2, 32);
    private final int rounds = option("COPPER_AUDIO_ROUNDS", 3, 1, 100);
    private final int sampleTicks = option("COPPER_AUDIO_SAMPLE_TICKS", 5, 1, 400);
    private final List<BlockPos> sources = makeSources();
    private record Sample(int source, int state, int buffer, int pcmBytes, double offset, double clock, double start) {}
    private static int option(String name, int fallback, int min, int max) {
        int value = Integer.parseInt(System.getenv().getOrDefault(name, "" + fallback));
        if (value < min || value > max) throw new IllegalArgumentException(name + " out of range");
        return value;
    }
    private List<BlockPos> makeSources() {
        List<BlockPos> result = new ArrayList<>(); result.add(MASTER);
        for (int i = 1; i < sourceCount; i++) result.add(new BlockPos(i, 90, 1));
        return result;
    }
    private final boolean custom = "custom".equals(System.getenv("COPPER_CLIENT_TEST"));
    private Identifier songId() { return custom ? CustomDiscGameTests.SONG : Identifier.withDefaultNamespace("cat"); }
    private Identifier soundId() { return custom ? CustomDiscGameTests.SOUND : CopperSpeakers.id("buffered.cat"); }
    @Override public void runTest(ClientGameTestContext context) {
        if ("observe".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new RemoteAudioObserver().runTest(context);
            return;
        }
        if ("tails".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new TailPlaybackTest().runTest(context);
            return;
        }
        if ("sections".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new SectionPlaybackTest().runTest(context);
            return;
        }
        if ("generic".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new LateJoinTest(true).runTest(context);
            return;
        }
        if ("notes".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new SpeakerNoteVisualTest().runTest(context);
            return;
        }
        if ("plate".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new WallPlateVisualTest().runTest(context);
            return;
        }
        if ("wire".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new WireVisualTest().runTest(context);
            return;
        }
        if ("late".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new LateJoinTest().runTest(context);
            return;
        }
        if ("distance".equals(System.getenv("COPPER_CLIENT_TEST"))) {
            new DistancePlaybackTest().runTest(context);
            return;
        }
        Path output = Path.of(System.getenv().getOrDefault("COPPER_SMOKE_OUTPUT", "build/audio-smoke.json")).toAbsolutePath();
        report.put("status", "incomplete"); report.put("started", java.time.Instant.now().toString());
        report.put("method", "Real disc insertion -> production group packets -> unchanged vanilla audio engine; AL_SEC_OFFSET_CLOCK_SOFT");
        report.put("scope", "Instrumented client observation, not unmodified-client pack delivery or acoustic capture");
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("networkSources", sourceCount); report.put("reservedSources", 4);
        report.put("rounds", rounds); report.put("sampleTicks", sampleTicks);
        report.put("startupMethod", "Wall time before local server insertion to all sources observed playing; includes packet delivery and polling. Upper bound, not physical audio latency.");
        report.put("song", songId().toString());
        report.put("mods", FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId() + "=" + m.getMetadata().getVersion()).sorted().toList());
        report.put("trials", trials);
        try {
            save(output); run(context); report.put("status", "complete"); save(output);
        } catch (Throwable error) {
            report.put("failure", error.toString()); save(output); throw new RuntimeException(error);
        }
    }
    private void run(ClientGameTestContext context) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("online-mode", "false");
        properties.setProperty("view-distance", "5");
        properties.setProperty("simulation-distance", "5");
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            properties.setProperty("server-port", "" + socket.getLocalPort());
        }
        properties.setProperty("level-name", Path.of("build/smoke-world-" + System.currentTimeMillis()).toAbsolutePath().toString());
        context.runOnClient(c -> c.options.renderDistance().set(custom ? 2 : 5));
        try (var server = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("1")).createServer(properties);
             var connection = server.connect()) {
            server.runCommand("tp @a 2 92 -6 0 20");
            server.runCommand("gamemode spectator @a");
            server.runCommand("forceload add 0 0 " + sourceCount + " 1");
            server.runOnServer(s -> {
                var level = s.overworld();
                for (int x = -1; x <= sourceCount; x++) for (int z = -2; z <= 2; z++)
                    level.setBlockAndUpdate(new BlockPos(x, 89, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(MASTER, Blocks.JUKEBOX.defaultBlockState());
                for (int x = 1; x < sourceCount; x++) level.setBlockAndUpdate(new BlockPos(x,90,0), CopperSpeakers.WIRE.defaultBlockState());
                for (BlockPos pos : sources.subList(1, sources.size())) level.setBlockAndUpdate(pos, CopperSpeakers.SPEAKER.defaultBlockState());
            });
            connection.waitForClientboundPackets();
            connection.waitForChunksRender(false);
            context.runOnClient(c -> {
                for (SoundSource category : SoundSource.values())
                    c.options.getSoundSourceOptionInstance(category).set(category == SoundSource.MASTER ? 1.0 : category == SoundSource.RECORDS ? .08 : 0.0);
                c.getSoundManager().stop();
            });
            SoundEngine engine = context.computeOnClient(c -> field(c.getSoundManager(), "soundEngine"));
            SoundBufferLibrary buffers = field(engine, "soundBuffers");
            ChannelAccess access = field(engine, "channelAccess");
            Library library = field(engine, "library");
            report.put("device", library.currentDeviceName());
            for (int round = 0; round < rounds; round++) for (boolean cold : new boolean[]{true, false}) {
                if (cold) {
                    context.runOnClient(c -> c.getSoundManager().stop());
                    CompletableFuture<Void> cleared = new CompletableFuture<>();
                    access.executeOnChannels(ignored -> {
                        try {
                            buffers.clear();
                            if (AL10.alGetError() != AL10.AL_NO_ERROR) throw new AssertionError("Cold cache reset failed");
                            cleared.complete(null);
                        } catch (Throwable e) { cleared.completeExceptionally(e); }
                    });
                    cleared.get(10, TimeUnit.SECONDS);
                    Map<?, ?> cache = field(buffers, "cache");
                    if (!cache.isEmpty()) throw new AssertionError("Cold trial requires an empty decoded cache");
                }
                long insertionNanos = System.nanoTime();
                server.runOnServer(s -> {
                    var song = s.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).get(songId()).orElseThrow();
                    var disc = custom ? CustomDiscGameTests.disc(song) : new ItemStack(Items.MUSIC_DISC_CAT);
                    ((JukeboxBlockEntity) s.overworld().getBlockEntity(MASTER)).setTheItem(disc);
                });
                connection.waitForClientboundPackets();
                var spareIds = server.computeOnServer(s -> AudioNetworks.get(s.overworld()).spareEntityIds(MASTER));
                if (spareIds.size() != 4) throw new AssertionError("Four reserved voices required");
                context.waitFor(c -> {
                    Map<BlockPos, SoundInstance> songs = field(field(c.level, "levelEventHandler"), "playingJukeboxSongs");
                    Map<SoundInstance, ChannelAccess.ChannelHandle> channels = field(engine, "instanceToChannel");
                    long spareCount = channels.keySet().stream().filter(sound -> sound instanceof EntityBoundSoundInstance
                            && spareIds.contains(((Entity) field(sound, "entity")).getId())).count();
                    return sources.stream().allMatch(songs::containsKey) && spareCount == 4;
                });
                List<ChannelAccess.ChannelHandle> handles = context.computeOnClient(c -> {
                    Map<BlockPos, SoundInstance> songs = field(field(c.level, "levelEventHandler"), "playingJukeboxSongs");
                    Map<SoundInstance, ChannelAccess.ChannelHandle> channels = field(engine, "instanceToChannel");
                    List<SoundInstance> sounds = new ArrayList<>(sources.stream().map(songs::get).toList());
                    sounds.addAll(channels.keySet().stream().filter(sound -> sound instanceof EntityBoundSoundInstance
                            && spareIds.contains(((Entity) field(sound, "entity")).getId()))
                            .sorted(Comparator.comparingInt(sound -> ((Entity) field(sound, "entity")).getId())).toList());
                    for (var sound : sounds) {
                        if (!sound.getIdentifier().equals(soundId()) || sound.getSound().shouldStream())
                            throw new AssertionError("Every source, including master, must use the buffered alias");
                    }
                    return sounds.stream().map(channels::get).toList();
                });
                List<Sample> first = List.of();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline) {
                    first = observe(access, handles);
                    if (first.size() == sourceCount + 4 && first.stream().allMatch(s -> s.state == AL10.AL_PLAYING)) break;
                    context.waitTicks(2);
                }
                double observedReadyMs = (System.nanoTime() - insertionNanos) / 1e6;
                if (first.size() != sourceCount + 4 || first.stream().anyMatch(s -> s.state != AL10.AL_PLAYING))
                    throw new AssertionError("Not all network and reserved sources started");
                if (first.getFirst().buffer == 0 || first.stream().map(Sample::buffer).distinct().count() != 1)
                    throw new AssertionError("Wired master and speakers must share one native decoded buffer");
                context.waitTicks(sampleTicks);
                var later = observe(access, handles);
                if (later.size() != first.size()) throw new AssertionError("Sources disappeared during playback");
                double relativeDriftMs = 0;
                for (int i = 0; i < first.size(); i++) {
                    var a = first.get(i); var b = later.get(i);
                    if (b.state != AL10.AL_PLAYING || a.source != b.source || a.buffer != b.buffer || b.offset <= a.offset)
                        throw new AssertionError("Playback did not advance on its original shared buffer");
                    relativeDriftMs = Math.max(relativeDriftMs, Math.abs((b.start - later.getFirst().start) - (a.start - first.getFirst().start)) * 1000);
                }
                Map<String,Object> trial = new LinkedHashMap<>();
                trial.put("mode", cold ? "cold" : "cached"); trial.put("round", round + 1);
                trial.put("initial", first); trial.put("later", later);
                trial.put("spreadMs", spread(first)); trial.put("laterSpreadMs", spread(later));
                trial.put("relativeDriftMs", relativeDriftMs); trial.put("observedReadyMs", observedReadyMs);
                trial.put("observationSeconds", later.getFirst().clock - first.getFirst().clock);
                trials.add(trial); save(Path.of(System.getenv().getOrDefault("COPPER_SMOKE_OUTPUT", "build/audio-smoke.json")));
                if (round == 0 && cold) {
                    // Only a measurement control; no production path changes source clocks.
                    CompletableFuture<Void> adjusted = new CompletableFuture<>();
                    access.executeOnChannels(ignored -> {
                        try {
                            Channel channel = field(handles.getLast(), "channel");
                            int source = field(channel, "source");
                            AL10.alSourcef(source, AL11.AL_SEC_OFFSET, AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET) + .05f);
                            adjusted.complete(null);
                        } catch (Throwable e) { adjusted.completeExceptionally(e); }
                    });
                    adjusted.get(10, TimeUnit.SECONDS);
                    double control = (later.getLast().start - observe(access, handles).getLast().start) * 1000;
                    report.put("deliberate50msControl", control);
                    if (control < 30 || control > 70) throw new AssertionError("Clock observer failed positive control");
                }
                server.runOnServer(s -> {
                    var box = (JukeboxBlockEntity) s.overworld().getBlockEntity(MASTER);
                    var song = s.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).get(songId()).orElseThrow();
                    box.getSongPlayer().setSongWithoutPlaying(song, song.value().lengthInTicks() + 19);
                    box.getSongPlayer().tick(s.overworld(), box.getBlockState());
                    box.getSongPlayer().tick(s.overworld(), box.getBlockState());
                });
                connection.waitForClientboundPackets(); context.waitTicks(2);
                if (observe(access, handles.subList(0, sourceCount)).stream().anyMatch(s -> s.state != AL10.AL_PLAYING))
                    throw new AssertionError("Natural completion truncated buffered playback");
                server.runOnServer(s -> ((JukeboxBlockEntity) s.overworld().getBlockEntity(MASTER)).popOutTheItem());
                connection.waitForClientboundPackets();
                context.waitFor(c -> {
                    Map<BlockPos, SoundInstance> songs = field(field(c.level, "levelEventHandler"), "playingJukeboxSongs");
                    return sources.stream().noneMatch(songs::containsKey);
                });
                context.waitTicks(3);
            }
            report.put("naturalCompletionAndExplicitStops", "passed for all " + trials.size() + " playbacks");
            report.put("screenshot", context.takeScreenshot("copper-speakers-smoke").toString());
        }
    }
    private void save(Path output) {
        try { Files.createDirectories(output.getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static double spread(List<Sample> samples) {
        return (samples.stream().mapToDouble(Sample::start).max().orElseThrow()
                - samples.stream().mapToDouble(Sample::start).min().orElseThrow()) * 1000;
    }
    private static List<Sample> observe(ChannelAccess access, List<ChannelAccess.ChannelHandle> handles) throws Exception {
        CompletableFuture<List<Sample>> done = new CompletableFuture<>();
        access.executeOnChannels(ignored -> {
            try {
                List<Sample> samples = new ArrayList<>();
                for (var handle : handles) {
                    if (handle == null) continue;
                    Channel channel = field(handle, "channel");
                    if (channel == null) continue;
                    int source = field(channel, "source");
                    double[] clock = new double[2];
                    SOFTSourceLatency.alGetSourcedvSOFT(source, SOFTDeviceClock.AL_SEC_OFFSET_CLOCK_SOFT, clock);
                    int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
                    samples.add(new Sample(source, AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE),
                            buffer, buffer == 0 ? 0 : AL10.alGetBufferi(buffer, AL10.AL_SIZE), clock[0], clock[1], clock[1] - clock[0]));
                    if (AL10.alGetError() != AL10.AL_NO_ERROR) throw new AssertionError("OpenAL clock query failed");
                }
                done.complete(samples);
            } catch (Throwable e) { done.completeExceptionally(e); }
        });
        return done.get(10, TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object object, String name) {
        try { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return (T) f.get(object); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }
}
