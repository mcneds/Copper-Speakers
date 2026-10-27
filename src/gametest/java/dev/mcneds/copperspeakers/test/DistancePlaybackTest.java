package dev.mcneds.copperspeakers.test;

import com.google.gson.GsonBuilder;
import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Functional range/continuity regression, not a synchronization timing measurement. */
public final class DistancePlaybackTest {
    private static final BlockPos MASTER = new BlockPos(0, 90, 0), FAR = new BlockPos(96, 90, 0);
    private final Map<String, Object> report = new LinkedHashMap<>();
    public void runTest(ClientGameTestContext context) {
        Path output = Path.of(System.getenv().getOrDefault("COPPER_DISTANCE_OUTPUT", "distance.json"));
        report.put("status", "incomplete");
        report.put("scope", "Player starts outside every source's range, then enters the network; no cross-client synchronization timing claim");
        try {
            // The 96-block route spans seven chunks. A five-chunk client view would
            // legitimately unload its far endpoint and exercise a different lifecycle.
            context.runOnClient(c -> c.options.renderDistance().set(10));
            Properties properties = new Properties();
            properties.setProperty("server-ip", "127.0.0.1");
            properties.setProperty("online-mode", "false");
            properties.setProperty("view-distance", "10");
            properties.setProperty("simulation-distance", "10");
            try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                properties.setProperty("server-port", "" + socket.getLocalPort());
            }
            properties.setProperty("level-name", Path.of("build/distance-world-" + System.currentTimeMillis()).toAbsolutePath().toString());
            try (var server = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("1")).createServer(properties);
                 var connection = server.connect()) {
                server.runCommand("gamemode spectator @a");
                server.runCommand("tp @a 256 91 -2 0 20");
                // Isolate audio range from asynchronous chunk-ticket transitions in
                // this temporary test world. Production code never adds chunk tickets.
                server.runCommand("forceload add 0 0 96 0");
                server.runOnServer(s -> {
                    var level = s.overworld();
                    // Test setup alone loads these nearby chunks; production traversal never loads them.
                    for (int x = 0; x <= 96; x++) {
                        var pos = new BlockPos(x, 90, 0);
                        level.getChunk(pos);
                        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(pos, x == 0 ? Blocks.JUKEBOX.defaultBlockState()
                                : x == 96 ? CopperSpeakers.SPEAKER.defaultBlockState() : CopperSpeakers.WIRE.defaultBlockState());
                    }
                });
                connection.waitForClientboundPackets();
                context.runOnClient(c -> {
                    for (SoundSource category : SoundSource.values()) c.options.getSoundSourceOptionInstance(category)
                            .set(category == SoundSource.MASTER ? 1.0 : category == SoundSource.RECORDS ? .08 : 0.0);
                    c.getSoundManager().stop();
                });
                server.runOnServer(s -> {
                    ((JukeboxBlockEntity)s.overworld().getBlockEntity(MASTER)).setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
                    report.put("serverSources", AudioNetworks.get(s.overworld()).sources(MASTER).stream().map(BlockPos::toShortString).sorted().toList());
                });
                connection.waitForClientboundPackets();
                context.waitTicks(20);
                server.runOnServer(s -> {
                    report.put("farChunkLoadedAtObservation", s.overworld().hasChunkAt(FAR));
                    report.put("serverSourcesAtObservation", AudioNetworks.get(s.overworld()).sources(MASTER).stream().map(BlockPos::toShortString).sorted().toList());
                    if (!s.overworld().hasChunkAt(FAR)) throw new AssertionError("Distance fixture must keep the wired route loaded");
                });
                var sources = context.computeOnClient(c -> songs(c.level).keySet().stream().map(BlockPos::toShortString).sorted().toList());
                report.put("clientSourcesAtInsertion", sources);
                var sound = context.computeOnClient(c -> songs(c.level).get(FAR));
                if (sound == null) throw new AssertionError("Player outside the entire network received no advancing source");
                context.waitFor(c -> c.getSoundManager().isActive(sound));
                context.waitTicks(30);
                double before = offset(context, sound);
                report.put("offsetBeforeMoving", before);
                if (before <= 0) throw new AssertionError("Far source must already be advancing before listener moves");
                server.runCommand("tp @a 96 91 -2 0 20");
                connection.waitForClientboundPackets();
                context.waitTicks(10);
                boolean same = context.computeOnClient(c -> songs(c.level).get(FAR) == sound && c.getSoundManager().isActive(sound));
                double after = offset(context, sound);
                report.put("sameSourceAfterMoving", same);
                report.put("offsetAfterMoving", after);
                double distance = context.computeOnClient(c -> c.player.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()));
                float radius = sound.getSound().getAttenuationDistance(sound.getVolume());
                report.put("distanceAtSpeaker", Math.sqrt(distance)); report.put("audibleRadius", radius);
                if (!same || after <= before || distance >= radius * radius) throw new AssertionError("Walking to the speaker must enter range of the already-playing source");
                server.runOnServer(s -> ((JukeboxBlockEntity)s.overworld().getBlockEntity(MASTER)).popOutTheItem());
                connection.waitForClientboundPackets();
                context.waitFor(c -> !songs(c.level).containsKey(FAR));
                report.put("ejectionStopsFarSource", true);
            }
            report.put("status", "complete");
        } catch (Throwable e) {
            report.put("status", "failed"); report.put("failure", e.toString());
            throw new RuntimeException(e);
        } finally {
            try { Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
    private static Map<BlockPos, SoundInstance> songs(Object level) { return field(field(level, "levelEventHandler"), "playingJukeboxSongs"); }
    private static double offset(ClientGameTestContext context, SoundInstance sound) throws Exception {
        var done = new CompletableFuture<Double>();
        context.runOnClient(c -> {
            Object engine = field(c.getSoundManager(), "soundEngine");
            Map<SoundInstance, ChannelAccess.ChannelHandle> channels = field(engine, "instanceToChannel");
            var handle = channels.get(sound);
            if (handle == null) { done.completeExceptionally(new AssertionError("No native channel for far speaker")); return; }
            handle.execute(channel -> {
                int source = field(channel, "source");
                if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING)
                    done.completeExceptionally(new AssertionError("Far source is not playing"));
                else done.complete((double)AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET));
            });
        });
        return done.get(10, TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object object, String name) {
        try { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return (T)f.get(object); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }
}
