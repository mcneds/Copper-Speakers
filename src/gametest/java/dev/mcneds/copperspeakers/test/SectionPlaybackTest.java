package dev.mcneds.copperspeakers.test;

import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.util.concurrent.atomic.*;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.*;
import org.lwjgl.openal.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Feasibility measurement only. This is not a production playback implementation. */
public final class SectionPlaybackTest {
    private record Sample(double x, int source, int buffer, double start, double offset, double seconds) {}
    private final Map<String,Object> report = new LinkedHashMap<>();
    private final List<AtomicBoolean> callbacks = new ArrayList<>();
    public void runTest(ClientGameTestContext context) {
        Path output = Path.of(System.getenv("COPPER_SECTIONS_OUTPUT"));
        report.put("status", "incomplete");
        report.put("scope", "Unchanged vanilla audio engine; two-second sections through server sound packets. Native clock observation, not acoustic capture.");
        report.put("java", System.getProperty("java.version"));
        report.put("scheduler", "One section every 40 server ticks; observer does not schedule packets");
        List<Object> trials = new ArrayList<>(); report.put("trials", trials);
        try {
            Properties props = new Properties();
            props.setProperty("server-ip", "127.0.0.1"); props.setProperty("online-mode", "false");
            props.setProperty("view-distance", "2"); props.setProperty("simulation-distance", "2");
            try (var socket = new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
                props.setProperty("server-port", ""+socket.getLocalPort());
            }
            props.setProperty("level-name", Path.of("build/sections-world-"+System.currentTimeMillis()).toAbsolutePath().toString());
            context.runOnClient(c -> c.options.renderDistance().set(2));
            try (var server = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("1")).createServer(props);
                 var connection = server.connect()) {
                server.runCommand("gamemode spectator @a"); server.runCommand("tp @a 0 91 0 0 20");
                context.runOnClient(c -> {
                    for (SoundSource s : SoundSource.values()) c.options.getSoundSourceOptionInstance(s)
                            .set(s == SoundSource.MASTER ? 1.0 : s == SoundSource.RECORDS ? .08 : 0.0);
                    c.getSoundManager().stop();
                });
                context.waitTicks(40);
                for (int pass=0; pass<2; pass++) {
                    var actualServer=server.computeOnServer(s -> s);
                    int startTick=server.computeOnServer(s -> s.getTickCount()+4);
                    AtomicInteger sent=new AtomicInteger(-1);
                    AtomicBoolean active=new AtomicBoolean(true); callbacks.add(active);
                    ServerTickEvents.END_SERVER_TICK.register(s -> {
                        if (!active.get() || s!=actualServer || sent.get()>=11 || s.getTickCount()<startTick+(sent.get()+1)*40) return;
                        int part=sent.get()+1;
                        Identifier id=Identifier.parse("section_probe:part_"+String.format(Locale.ROOT,"%02d",part));
                        for (var player : s.getPlayerList().getPlayers()) {
                            List<Packet<? super ClientGamePacketListener>> packets=new ArrayList<>();
                            var sound=Holder.direct(SoundEvent.createVariableRangeEvent(id));
                            packets.add(new ClientboundSoundPacket(sound,SoundSource.RECORDS,0,90,0,4,1,0));
                            if (part>=6) packets.add(new ClientboundSoundPacket(sound,SoundSource.RECORDS,4,90,0,4,1,0));
                            player.connection.send(new ClientboundBundlePacket(packets));
                        }
                        sent.set(part);
                    });
                    double previousStart=Double.NaN, previousLength=Double.NaN;
                    for (int part=0; part<12; part++) {
                        int awaited=part;
                        context.waitFor(c -> sent.get()>=awaited);
                        Identifier id=Identifier.parse("section_probe:part_"+String.format(Locale.ROOT,"%02d",part));
                        boolean joined=part>=6;
                        context.waitTicks(4);
                        var samples=observe(context,id);
                        if (samples.size()!=(joined?2:1)) throw new AssertionError("Missing section playback "+id);
                        var master=samples.getFirst();
                        Map<String,Object> trial=new LinkedHashMap<>();
                        trial.put("pass",pass);trial.put("part",part);trial.put("joined",joined);trial.put("samples",samples);
                        if (!Double.isNaN(previousStart)) trial.put("boundaryGapMs",1000*(master.start-previousStart-previousLength));
                        trial.put("joinSpreadMs",1000*(samples.getLast().start-master.start));
                        previousStart=master.start;previousLength=master.seconds;
                        trials.add(trial); save(output);
                    }
                    active.set(false); context.waitTicks(42);
                }
                context.runOnClient(c -> c.getSoundManager().stop());
            }
            report.put("status","complete");
        } catch (Throwable e) { report.put("status","failed");report.put("failure",e.toString());throw new RuntimeException(e); }
        finally { callbacks.forEach(c -> c.set(false)); save(output); }
    }
    private void save(Path path) {
        try { Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (Exception e) {throw new RuntimeException(e);}
    }
    private static List<Sample> observe(ClientGameTestContext context, Identifier id) throws Exception {
        CompletableFuture<List<Sample>> done=new CompletableFuture<>();
        context.runOnClient(c -> {
            Object engine=field(c.getSoundManager(),"soundEngine");
            Map<SoundInstance,ChannelAccess.ChannelHandle> all=field(engine,"instanceToChannel");
            var matches=all.entrySet().stream().filter(e -> e.getKey().getIdentifier().equals(id))
                    .sorted(Comparator.comparingDouble(e -> e.getKey().getX())).toList();
            ChannelAccess access=field(engine,"channelAccess");
            access.executeOnChannels(unused -> {
                try {
                    List<Sample> samples=new ArrayList<>();
                    for (var entry : matches) {
                        Object channel=field(entry.getValue(),"channel");
                        if (channel==null) continue;
                        int source=field(channel,"source");int buffer=AL10.alGetSourcei(source,AL10.AL_BUFFER);
                        if (AL10.alGetSourcei(source,AL10.AL_SOURCE_STATE)!=AL10.AL_PLAYING) continue;
                        double[] clock=new double[2];SOFTSourceLatency.alGetSourcedvSOFT(source,SOFTDeviceClock.AL_SEC_OFFSET_CLOCK_SOFT,clock);
                        double seconds=(double)AL10.alGetBufferi(buffer,AL10.AL_SIZE)/
                                (AL10.alGetBufferi(buffer,AL10.AL_FREQUENCY)*AL10.alGetBufferi(buffer,AL10.AL_CHANNELS)*AL10.alGetBufferi(buffer,AL10.AL_BITS)/8);
                        if(AL10.alGetError()!=AL10.AL_NO_ERROR)throw new AssertionError("OpenAL observation failed");
                        samples.add(new Sample(entry.getKey().getX(),source,buffer,clock[1]-clock[0],clock[0],seconds));
                    }
                    done.complete(samples);
                } catch(Throwable e){done.completeExceptionally(e);}
            });
        });
        return done.get(10,TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object object,String name) {
        try {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(object);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(name,e);}
    }
}
