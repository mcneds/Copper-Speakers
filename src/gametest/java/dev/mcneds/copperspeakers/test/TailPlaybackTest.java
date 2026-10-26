package dev.mcneds.copperspeakers.test;

import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.*;
import org.lwjgl.openal.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only: a continuous original sound and separately started prepared tails. */
public final class TailPlaybackTest {
    private static final Identifier FULL=Identifier.parse("copper_speakers:buffered.cat");
    private static final Identifier SIX=Identifier.parse("tail_probe:cat_06");
    private static final Identifier TWELVE=Identifier.parse("tail_probe:cat_12");
    private record Sample(String sound, int source, int buffer, double start, double offset) {}
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<AtomicBoolean> callbacks=new ArrayList<>();
    public void runTest(ClientGameTestContext context) {
        Path output=Path.of(System.getenv("COPPER_TAILS_OUTPUT"));
        boolean functionalOnly=Boolean.parseBoolean(System.getenv("COPPER_TAILS_FUNCTIONAL_ONLY"));
        report.put("status","incomplete");
        report.put("functionalOnly",functionalOnly);
        report.put("scope","Same-client native observation; prepared Cat tails at 6 and 12 seconds. No seek, no client scheduling changes, no current-source restarts. Not a multiplayer login validation.");
        report.put("java",System.getProperty("java.version"));
        report.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        report.put("jvmArguments",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        List<Object> trials=new ArrayList<>();report.put("trials",trials);
        try {
            Properties props=new Properties();props.setProperty("server-ip","127.0.0.1");props.setProperty("online-mode","false");
            props.setProperty("view-distance","2");props.setProperty("simulation-distance","2");
            try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {props.setProperty("server-port",""+socket.getLocalPort());}
            props.setProperty("level-name",Path.of("build/tails-world-"+System.currentTimeMillis()).toAbsolutePath().toString());
            context.runOnClient(c -> c.options.renderDistance().set(2));
            try(var server=context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("1")).createServer(props);
                var connection=server.connect()) {
                server.runCommand("gamemode spectator @a");server.runCommand("tp @a 0 91 0 0 20");
                context.runOnClient(c -> {
                    for(SoundSource s:SoundSource.values())c.options.getSoundSourceOptionInstance(s)
                            .set(s==SoundSource.MASTER?1.0:s==SoundSource.RECORDS?.08:0.0);
                    c.getSoundManager().stop();
                });
                context.waitTicks(40);
                Object engine=context.computeOnClient(c -> field(c.getSoundManager(),"soundEngine"));
                ChannelAccess access=field(engine,"channelAccess");SoundBufferLibrary buffers=field(engine,"soundBuffers");
                for(int round=0;round<3;round++) {
                    boolean prime=round>0;
                    context.runOnClient(c -> c.getSoundManager().stop());context.waitTicks(4);
                    CompletableFuture<Void> cleared=new CompletableFuture<>();
                    access.executeOnChannels(unused -> {try{buffers.clear();if(AL10.alGetError()!=AL10.AL_NO_ERROR)throw new AssertionError("Cache clear failed");cleared.complete(null);}catch(Throwable e){cleared.completeExceptionally(e);}});
                    cleared.get(10,TimeUnit.SECONDS);
                    if(prime) {
                        server.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> play(p,FULL,0,-4000)));
                        context.waitTicks(40);
                        server.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> stop(p,FULL)));
                    }
                    var actual=server.computeOnServer(s -> s);int epoch=server.computeOnServer(s -> s.getTickCount()+4);
                    AtomicInteger stage=new AtomicInteger(-1);AtomicBoolean active=new AtomicBoolean(true);callbacks.add(active);
                    ServerTickEvents.END_SERVER_TICK.register(s -> {
                        if(!active.get()||s!=actual)return;
                        int elapsed=s.getTickCount()-epoch;
                        int wanted=elapsed>=240?4:elapsed>=200?3:elapsed>=120?2:elapsed>=80?1:elapsed>=0?0:-1;
                        if(wanted<=stage.get())return;
                        // Normal test timing must observe every stage, never silently skip a preparation.
                        if(wanted!=stage.get()+1)throw new AssertionError("Test server skipped a tail stage");
                        for(var p:s.getPlayerList().getPlayers())switch(wanted) {
                            case 0 -> play(p,FULL,0,90);
                            case 1 -> play(p,SIX,4,-4000);
                            case 2 -> {stop(p,SIX);play(p,SIX,4,90);}
                            case 3 -> play(p,TWELVE,8,-4000);
                            case 4 -> {stop(p,TWELVE);play(p,TWELVE,8,90);}
                        }
                        stage.set(wanted);
                    });
                    Map<String,Object> trial=new LinkedHashMap<>();trial.put("round",round);trial.put("originalPrimedSeconds",prime?2:0);trial.put("tailPrimedSeconds",2);
                    context.waitFor(c -> stage.get()>=0);context.waitTicks(60);
                    var original=observe(context,List.of(FULL));if(!functionalOnly)trial.put("beforeJoining",original);
                    context.waitFor(c -> stage.get()>=2);context.waitTicks(8);
                    var first=observe(context,List.of(FULL,SIX));if(!functionalOnly)trial.put("afterFirstJoin",first);
                    continuity(original.getFirst(),first.getFirst());
                    if(!functionalOnly)trial.put("firstJoinErrorMs",(first.get(1).start-6-first.getFirst().start)*1000);
                    context.waitFor(c -> stage.get()>=4);context.waitTicks(8);
                    var second=observe(context,List.of(FULL,SIX,TWELVE));if(!functionalOnly)trial.put("afterSecondJoin",second);
                    continuity(first.get(0),second.get(0));continuity(first.get(1),second.get(1));
                    if(!functionalOnly)trial.put("secondJoinErrorMs",(second.get(2).start-12-second.getFirst().start)*1000);
                    trial.put("existingSourcesUnchanged",true);trials.add(trial);save(output);
                    active.set(false);context.runOnClient(c -> c.getSoundManager().stop());context.waitTicks(4);
                }
            }
            report.put("status","complete");
        } catch(Throwable e){report.put("status","failed");report.put("failure",e.toString());throw new RuntimeException(e);}
        finally{callbacks.forEach(c -> c.set(false));save(output);}
    }
    private static void play(ServerPlayer p,Identifier id,double x,double y) {
        p.connection.send(new ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(id)),SoundSource.RECORDS,x,y,0,4,1,0));
    }
    private static void stop(ServerPlayer p,Identifier id){p.connection.send(new ClientboundStopSoundPacket(id,SoundSource.RECORDS));}
    private static void continuity(Sample before,Sample after){
        if(before.source!=after.source||before.buffer!=after.buffer||after.offset<=before.offset||Math.abs(before.start-after.start)>.002)
            throw new AssertionError("Existing audio changed when another tail started");
    }
    private static List<Sample> observe(ClientGameTestContext context,List<Identifier> ids)throws Exception {
        CompletableFuture<List<Sample>> done=new CompletableFuture<>();
        context.runOnClient(c -> {
            Object engine=field(c.getSoundManager(),"soundEngine");Map<SoundInstance,ChannelAccess.ChannelHandle> all=field(engine,"instanceToChannel");
            var handles=ids.stream().map(id -> all.entrySet().stream().filter(e -> e.getKey().getIdentifier().equals(id)&&e.getKey().getY()>0).findFirst().orElseThrow().getValue()).toList();
            ChannelAccess access=field(engine,"channelAccess");access.executeOnChannels(unused -> {
                try{
                    List<Sample> result=new ArrayList<>();
                    for(int i=0;i<ids.size();i++){
                        Object channel=field(handles.get(i),"channel");if(channel==null)throw new AssertionError("Audio channel ended");int source=field(channel,"source");
                        if(AL10.alGetSourcei(source,AL10.AL_SOURCE_STATE)!=AL10.AL_PLAYING)throw new AssertionError("Audio source stopped");
                        double[] clock=new double[2];SOFTSourceLatency.alGetSourcedvSOFT(source,SOFTDeviceClock.AL_SEC_OFFSET_CLOCK_SOFT,clock);
                        int buffer=AL10.alGetSourcei(source,AL10.AL_BUFFER);
                        if(AL10.alGetError()!=AL10.AL_NO_ERROR)throw new AssertionError("OpenAL query failed");
                        result.add(new Sample(ids.get(i).toString(),source,buffer,clock[1]-clock[0],clock[0]));
                    }
                    done.complete(result);
                }catch(Throwable e){done.completeExceptionally(e);}
            });
        });return done.get(10,TimeUnit.SECONDS);
    }
    private void save(Path path){try{Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception e){throw new RuntimeException(e);}}
    @SuppressWarnings("unchecked")private static<T>T field(Object o,String name){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return(T)f.get(o);}catch(ReflectiveOperationException e){throw new IllegalStateException(name,e);}}
}
