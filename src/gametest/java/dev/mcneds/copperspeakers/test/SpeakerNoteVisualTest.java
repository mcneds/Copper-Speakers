package dev.mcneds.copperspeakers.test;

import com.google.gson.GsonBuilder;
import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.SpeakerBlock;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;

/** Real client reception, horizontal movement, and lifecycle checks; no audio timing claims. */
public final class SpeakerNoteVisualTest {
    private static final BlockPos MASTER = new BlockPos(0, 90, 0);
    private static final BlockPos SPEAKER = new BlockPos(2, 90, 0);
    private static final BlockPos JOINED = new BlockPos(1, 90, 1);
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static List<Display.ItemDisplay> notes(Minecraft client) {
        List<Display.ItemDisplay> result = new ArrayList<>();
        for (var entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Display.ItemDisplay display) || display.itemRenderState() == null) continue;
            var model = display.itemRenderState().itemStack().get(DataComponents.ITEM_MODEL);
            if (model != null && model.getNamespace().equals(CopperSpeakers.ID) && model.getPath().startsWith("speaker_note_"))
                result.add(display);
        }
        return result;
    }

    public void runTest(ClientGameTestContext context) {
        Path output = Path.of(System.getenv().getOrDefault("COPPER_NOTES_OUTPUT", "notes.json"));
        Map<String, Object> report = new LinkedHashMap<>();
        List<Object> directions = new ArrayList<>();
        report.put("status", "incomplete");
        report.put("directions", directions);
        try {
            context.runOnClient(c -> c.options.renderDistance().set(2));
            Properties properties = new Properties();
            properties.setProperty("server-ip", "127.0.0.1");
            properties.setProperty("online-mode", "false");
            properties.setProperty("view-distance", "2");
            try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                properties.setProperty("server-port", "" + socket.getLocalPort());
            }
            properties.setProperty("level-name", Path.of("build/notes-world-" + System.currentTimeMillis()).toAbsolutePath().toString());
            try (var server = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("1")).createServer(properties);
                 var connection = server.connect()) {
                server.runCommand("gamemode spectator @a");
                server.runCommand("tp @a 2 91 -4 0 20");
                server.runCommand("forceload add -16 -16 16 16"); // Disposable fixture only.
                server.runCommand("time set day");
                server.runOnServer(s -> {
                    var level = s.overworld();
                    for (int x = -3; x <= 7; x++) for (int z = -4; z <= 4; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 89, z), Blocks.STONE.defaultBlockState());
                        for (int y = 90; y <= 94; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                    level.setBlockAndUpdate(MASTER, Blocks.JUKEBOX.defaultBlockState());
                    level.setBlockAndUpdate(MASTER.east(), CopperSpeakers.WIRE.defaultBlockState());
                    level.setBlockAndUpdate(SPEAKER, CopperSpeakers.SPEAKER.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(6, 90, 0), CopperSpeakers.SPEAKER.defaultBlockState());
                });
                context.waitTicks(25);
                check(context.computeOnClient(c -> notes(c).isEmpty()), "Idle speakers emit no notes");
                for (var facing : Direction.Plane.HORIZONTAL) {
                    server.runOnServer(s -> {
                        var level = s.overworld();
                        level.setBlockAndUpdate(SPEAKER, CopperSpeakers.SPEAKER.defaultBlockState().setValue(SpeakerBlock.FACING, facing));
                        ((JukeboxBlockEntity)level.getBlockEntity(MASTER)).setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
                    });
                    var origin = new Vec3(2.5, 90.375, .5).relative(facing, .67);
                    context.waitFor(c -> notes(c).stream().anyMatch(n -> n.position().distanceToSqr(origin) < .1 * .1));
                    var initial = context.computeOnClient(c -> notes(c).stream().min(Comparator.comparingDouble(n -> n.position().distanceToSqr(origin))).orElseThrow());
                    int id = initial.getId();
                    Vec3 before = context.computeOnClient(c -> c.level.getEntity(id).position());
                    context.waitTicks(4);
                    Vec3 after = context.computeOnClient(c -> {
                        var display = c.level.getEntity(id);
                        check(display != null, "The same note must remain alive during its movement");
                        return display.position();
                    });
                    var delta = after.subtract(before);
                    check(Math.abs(after.y - 90.375) < .001, "Note must stay at driver height");
                    check(delta.dot(new Vec3(facing.getStepX(), 0, facing.getStepZ())) > .05,
                            "Note must move outward on " + facing);
                    check(Math.abs(delta.x * facing.getStepZ() - delta.z * facing.getStepX()) < .001,
                            "Note must not drift across the driver's face");
                    directions.add(Map.of("facing", facing.getSerializedName(), "entityId", id,
                            "before", before.toString(), "after", after.toString()));
                    if (facing == Direction.NORTH) {
                        server.runCommand("tp @a 3.8 89.3 -2.2 25 8");
                        connection.waitForClientboundPackets(); context.waitTicks(5);
                        context.waitFor(c -> !notes(c).isEmpty());
                        var screenshot = context.takeScreenshot("speaker-notes-north");
                        Files.copy(screenshot, output.toAbsolutePath().getParent().resolve("speaker-notes.png"));
                        report.put("screenshot", "speaker-notes.png");
                    }
                    server.runOnServer(s -> ((JukeboxBlockEntity)s.overworld().getBlockEntity(MASTER)).popOutTheItem());
                    connection.waitForClientboundPackets();
                    context.waitFor(c -> notes(c).isEmpty());
                }
                server.runOnServer(s -> {
                    var level = s.overworld();
                    ((JukeboxBlockEntity)level.getBlockEntity(MASTER)).setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
                    level.setBlockAndUpdate(JOINED, CopperSpeakers.SPEAKER.defaultBlockState());
                    AudioNetworks.get(level).tick();
                    check(AudioNetworks.get(level).speakerNotes(MASTER).containsKey(JOINED), "A speaker using a spare voice must emit notes");
                });
                var joinedOrigin = new Vec3(1.5, 90.375, 1.5).relative(Direction.NORTH, .67);
                context.waitFor(c -> notes(c).stream().anyMatch(n -> Math.abs(n.getX() - joinedOrigin.x) < .01));
                server.runOnServer(s -> {
                    s.overworld().setBlockAndUpdate(JOINED, Blocks.AIR.defaultBlockState());
                    AudioNetworks.get(s.overworld()).tick();
                });
                connection.waitForClientboundPackets();
                context.waitFor(c -> notes(c).stream().noneMatch(n -> Math.abs(n.getX() - joinedOrigin.x) < .01));
                server.runOnServer(s -> AudioNetworks.get(s.overworld()).finish(MASTER, true));
                connection.waitForClientboundPackets();
                context.waitFor(c -> notes(c).isEmpty());
                context.waitTicks(25);
                check(context.computeOnClient(c -> notes(c).isEmpty()), "Natural audio tails must not keep spawning notes");
                server.runOnServer(s -> ((JukeboxBlockEntity)s.overworld().getBlockEntity(MASTER)).popOutTheItem());
                report.put("idleEjectionDisconnectNaturalCompletion", "passed");
                report.put("lateSpeaker", "passed");
            }
            report.put("status", "complete");
        } catch (Throwable error) {
            report.put("status", "failed"); report.put("failure", error.toString());
            throw new RuntimeException(error);
        } finally {
            try { Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
