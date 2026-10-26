package dev.mcneds.copperspeakers.test;

import com.google.gson.GsonBuilder;
import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.lwjgl.openal.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only native clock observation. Production never changes playback offsets. */
public final class LateJoinTest {
    private final boolean custom;
    public LateJoinTest() { this(false); }
    public LateJoinTest(boolean custom) { this.custom = custom; }
    private Identifier songId() { return Identifier.parse(custom ? "test_records:radio" : "minecraft:cat"); }
    private Identifier soundId() { return custom ? Identifier.parse("test_records:station") : CopperSpeakers.id("buffered.cat"); }
    private static final BlockPos MASTER = new BlockPos(0,90,0), INITIAL = new BlockPos(7,90,0), ADDED = new BlockPos(2,90,1);
    private record Sample(int source, int state, int buffer, double offset, double clock, double start) {}
    private final Map<String,Object> report = new LinkedHashMap<>();
    public void runTest(ClientGameTestContext context) {
        Path output = Path.of(System.getenv().getOrDefault(custom ? "COPPER_GENERIC_OUTPUT" : "COPPER_LATE_OUTPUT", "late.json"));
        report.put("status", "incomplete");
        report.put("method", "Production packets, vanilla EntityBoundSoundInstance, AL_SEC_OFFSET_CLOCK_SOFT; no seeking or replacement start");
        report.put("vmArguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("scope", "Instrumented client; not acoustic capture or unmodified-client pack acceptance");
        report.put("song", songId().toString());
        report.put("streamed", custom);
        try {
            Properties properties = new Properties();
            properties.setProperty("server-ip", "127.0.0.1"); properties.setProperty("online-mode", "false");
            try (var socket = new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
                properties.setProperty("server-port", "" + socket.getLocalPort());
            }
            properties.setProperty("level-name", Path.of("build/late-world-" + System.currentTimeMillis()).toAbsolutePath().toString());
            if (custom) context.runOnClient(c -> c.options.renderDistance().set(2));
            try (var server = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("1")).createServer(properties);
                 var connection = server.connect()) {
                server.runCommand("gamemode spectator @a"); server.runCommand("tp @a 4 91 -4 0 10");
                server.runCommand("forceload add 0 0 7 1"); // Disposable fixture only; no production chunk tickets.
                server.runOnServer(s -> {
                    var level = s.overworld();
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 1; z++) {
                        var pos = new BlockPos(x,90,z); level.getChunk(pos);
                        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                    level.setBlockAndUpdate(MASTER, Blocks.JUKEBOX.defaultBlockState());
                    for (int x = 1; x < 7; x++) level.setBlockAndUpdate(new BlockPos(x,90,0), CopperSpeakers.WIRE.defaultBlockState());
                    level.setBlockAndUpdate(INITIAL, CopperSpeakers.SPEAKER.defaultBlockState());
                });
                connection.waitForClientboundPackets();
                context.runOnClient(c -> {
                    for (SoundSource category : SoundSource.values()) c.options.getSoundSourceOptionInstance(category)
                            .set(category == SoundSource.MASTER ? 1.0 : category == SoundSource.RECORDS ? .08 : 0.0);
                    c.getSoundManager().stop();
                });
                List<Object> trials = new ArrayList<>(); report.put("trials", trials);
                for (int round = 0; round < 2; round++) {
                    Map<String,Object> trial = new LinkedHashMap<>(); trials.add(trial);
                    trial.put("round", round + 1);
                    server.runOnServer(s -> {
                        var song = s.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).get(songId()).orElseThrow();
                        var disc = custom ? CustomDiscGameTests.disc(song) : new ItemStack(Items.MUSIC_DISC_CAT);
                        ((JukeboxBlockEntity)s.overworld().getBlockEntity(MASTER)).setTheItem(disc);
                    });
                    var ids = server.computeOnServer(s -> AudioNetworks.get(s.overworld()).spareEntityIds(MASTER));
                    check(ids.size() == 4, "Four prestarted anchors required");
                    connection.waitForClientboundPackets();
                    context.waitFor(c -> entitySounds(c, ids).size() == 4 && songs(c).size() == 2);
                    var spares = context.computeOnClient(c -> entitySounds(c, ids));
                    var originals = context.computeOnClient(c -> List.of(songs(c).get(MASTER), songs(c).get(INITIAL)));
                    var all = new ArrayList<SoundInstance>(originals); all.addAll(spares.values());
                    context.waitTicks(60); // Observation only, never a production readiness assumption.
                    var before = sample(context, all);
                    check(before.stream().allMatch(v -> v.state == AL10.AL_PLAYING), "All original and parked sources must already play");
                    if (!custom) check(before.getFirst().buffer != 0 && before.stream().map(Sample::buffer).distinct().count() == 1, "All voices must share one decoded buffer");
                    check(all.stream().allMatch(v -> v.getIdentifier().equals(soundId()) && v.getSound().shouldStream() == custom), "Every source uses the selected song's audio settings");
                    trial.put("initial", before);
                    if (!custom) trial.put("startSpreadMs", spread(before));
                    check(context.computeOnClient(c -> spares.values().stream().allMatch(v -> c.player.distanceToSqr(v.getX(),v.getY(),v.getZ()) > 64*64)), "Idle voices must remain inaudible");
                    server.runOnServer(s -> s.overworld().setBlockAndUpdate(ADDED, CopperSpeakers.SPEAKER.defaultBlockState()));
                    context.waitTicks(5); connection.waitForClientboundPackets();
                    int bound = server.computeOnServer(s -> AudioNetworks.get(s.overworld()).speakerVoiceId(MASTER, ADDED));
                    check(spares.containsKey(bound), "New speaker must claim an existing prestarted voice");
                    SoundInstance sound = spares.get(bound);
                    context.waitFor(c -> Math.abs(sound.getX() - ADDED.getX() - .5) < .01 && Math.abs(sound.getY() - ADDED.getY() - .5) < .01);
                    var after = sample(context, all); assertContinuity(before, after);
                    trial.put("afterJoin", after); trial.put("joinedEntity", bound);
                    server.runOnServer(s -> s.overworld().setBlockAndUpdate(ADDED, Blocks.AIR.defaultBlockState()));
                    context.waitFor(c -> c.player.distanceToSqr(sound.getX(),sound.getY(),sound.getZ()) > 64*64);
                    var parked = sample(context, all); assertContinuity(after, parked);
                    trial.put("afterDisconnect", parked);
                    server.runOnServer(s -> s.overworld().setBlockAndUpdate(ADDED, CopperSpeakers.SPEAKER.defaultBlockState()));
                    context.waitTicks(5); connection.waitForClientboundPackets();
                    check(server.computeOnServer(s -> AudioNetworks.get(s.overworld()).speakerVoiceId(MASTER, ADDED)) == bound, "Reconnection must reuse the parked voice");
                    context.waitFor(c -> Math.abs(sound.getY() - ADDED.getY() - .5) < .01);
                    var rejoined = sample(context, all); assertContinuity(parked, rejoined); trial.put("afterReconnect", rejoined);
                    server.runOnServer(s -> {
                        var box = (JukeboxBlockEntity)s.overworld().getBlockEntity(MASTER);
                        var song = s.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).get(songId()).orElseThrow();
                        box.getSongPlayer().setSongWithoutPlaying(song, song.value().lengthInTicks() + 19);
                        box.getSongPlayer().tick(s.overworld(), box.getBlockState()); box.getSongPlayer().tick(s.overworld(), box.getBlockState());
                    });
                    connection.waitForClientboundPackets();
                    context.waitFor(c -> ids.stream().filter(id -> id != bound).allMatch(id -> c.level.getEntity(id) == null));
                    var tail = sample(context, List.of(originals.getFirst(), sound));
                    check(tail.stream().allMatch(v -> v.state == AL10.AL_PLAYING), "Natural completion preserves bound voice tail and master");
                    trial.put("naturalTail", tail);
                    server.runOnServer(s -> ((JukeboxBlockEntity)s.overworld().getBlockEntity(MASTER)).popOutTheItem());
                    connection.waitForClientboundPackets();
                    context.waitFor(c -> ids.stream().allMatch(id -> c.level.getEntity(id) == null) && all.stream().noneMatch(c.getSoundManager()::isActive));
                    trial.put("ejectionRemovedAllVoices", true);
                    server.runOnServer(s -> s.overworld().setBlockAndUpdate(ADDED, Blocks.AIR.defaultBlockState()));
                }
                report.put("screenshot", context.takeScreenshot("speaker-driver-front").toString());
            }
            report.put("status", "complete");
        } catch (Throwable e) {
            report.put("status", "failed"); report.put("failure", e.toString()); throw new RuntimeException(e);
        } finally {
            try { Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void assertContinuity(List<Sample> before, List<Sample> after) {
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i); var b = after.get(i);
            check(b.state == AL10.AL_PLAYING && a.source == b.source,
                    "Every native source must keep playing on its original channel");
            if (custom) continue; // Stream queue offsets wrap; they are not whole-track clocks.
            check(a.buffer == b.buffer && b.offset > a.offset,
                    "Every native source must continue advancing on its original channel");
            check(Math.abs(a.start - b.start) < .002, "Moving a source must preserve its original audio clock (2 ms observation tolerance)");
        }
    }
    private static double spread(List<Sample> values) {
        return 1000 * (values.stream().mapToDouble(Sample::start).max().orElseThrow() - values.stream().mapToDouble(Sample::start).min().orElseThrow());
    }
    private static Map<BlockPos,SoundInstance> songs(Minecraft c) { return field(field(c.level,"levelEventHandler"),"playingJukeboxSongs"); }
    private static Map<SoundInstance,ChannelAccess.ChannelHandle> channels(Minecraft c) { return field(field(c.getSoundManager(),"soundEngine"),"instanceToChannel"); }
    private static Map<Integer,SoundInstance> entitySounds(Minecraft c, Set<Integer> ids) {
        Map<Integer,SoundInstance> result = new TreeMap<>();
        for (SoundInstance sound : channels(c).keySet()) if (sound instanceof EntityBoundSoundInstance) {
            Entity entity = field(sound,"entity"); if (ids.contains(entity.getId())) result.put(entity.getId(), sound);
        }
        return result;
    }
    private static List<Sample> sample(ClientGameTestContext context, List<SoundInstance> sounds) throws Exception {
        var done = new CompletableFuture<List<Sample>>();
        context.runOnClient(c -> {
            var handles = sounds.stream().map(channels(c)::get).toList();
            ChannelAccess access = field(field(c.getSoundManager(),"soundEngine"),"channelAccess");
            access.executeOnChannels(ignored -> {
                try {
                    List<Sample> result = new ArrayList<>();
                    for (var handle : handles) {
                        check(handle != null, "Sound has no native channel");
                        Object channel = field(handle,"channel"); check(channel != null,"Native channel released unexpectedly");
                        int source = field(channel,"source"); double[] clock = new double[2];
                        SOFTSourceLatency.alGetSourcedvSOFT(source,SOFTDeviceClock.AL_SEC_OFFSET_CLOCK_SOFT,clock);
                        check(AL10.alGetError() == AL10.AL_NO_ERROR,"Clock query failed");
                        result.add(new Sample(source,AL10.alGetSourcei(source,AL10.AL_SOURCE_STATE),AL10.alGetSourcei(source,AL10.AL_BUFFER),clock[0],clock[1],clock[1]-clock[0]));
                    }
                    done.complete(result);
                } catch (Throwable e) { done.completeExceptionally(e); }
            });
        });
        return done.get(10,TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object object,String name) {
        try { var f=object.getClass().getDeclaredField(name); f.setAccessible(true); return (T)f.get(object); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(name,e); }
    }
}
