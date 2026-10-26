package dev.mcneds.copperspeakers.test;

import com.google.gson.GsonBuilder;
import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.entity.WallPlateEntity;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import dev.mcneds.copperspeakers.network.WallPlates;
import eu.pb4.polymer.virtualentity.api.elements.EntityElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;

/** Inspect vanilla frame/display packets and capture every horizontal socket in a real renderer. */
public final class WallPlateVisualTest {
    private static final BlockPos HOST = new BlockPos(0, 90, 0);
    private static final BlockPos CROSS = new BlockPos(0, 90, 5);
    private record Expected(int id, BlockPos host, String model) {}
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public void runTest(ClientGameTestContext context) {
        Path output = Path.of(System.getenv().getOrDefault("COPPER_PLATE_OUTPUT", "plate.json"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "incomplete");
        try {
            Properties properties = new Properties();
            properties.setProperty("server-ip", "127.0.0.1");
            properties.setProperty("online-mode", "false");
            properties.setProperty("view-distance", "5");
            try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                properties.setProperty("server-port", "" + socket.getLocalPort());
            }
            properties.setProperty("level-name", Path.of("build/plate-world-" + System.currentTimeMillis()).toAbsolutePath().toString());
            context.runOnClient(c -> c.options.renderDistance().set(5));
            try (var server = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("1")).createServer(properties);
                 var connection = server.connect()) {
                server.runCommand("gamemode spectator @a");
                server.runCommand("tp @a -3 91 3 -135 25");
                server.runCommand("forceload add -16 -16 16 16");
                server.runCommand("time set day");
                var registeredDuringSetup = server.computeOnServer(s -> {
                    var level = s.overworld();
                    for (int x = -5; x <= 6; x++) for (int z = -4; z <= 9; z++) {
                        var floor = new BlockPos(x, 89, z); level.getChunk(floor);
                        level.setBlockAndUpdate(floor, Blocks.GRASS_BLOCK.defaultBlockState());
                        for (int y = 1; y <= 5; y++) level.setBlockAndUpdate(floor.above(y), Blocks.AIR.defaultBlockState());
                    }
                    for (var host : List.of(HOST, CROSS, HOST.east(4))) {
                        level.setBlockAndUpdate(host, Blocks.GRASS_BLOCK.defaultBlockState());
                        var plate = new WallPlateEntity(CopperSpeakers.WALL_PLATE_ENTITY, level);
                        plate.place(host, host.equals(CROSS) ? Direction.NORTH : Direction.WEST);
                        level.addFreshEntity(plate);
                        if (host.equals(HOST.east(4))) continue; // Unconnected plate shows its entire backing.
                        for (var side : new Direction[]{plate.facing(), plate.facing().getOpposite()})
                            level.setBlockAndUpdate(host.relative(side), CopperSpeakers.WIRE.defaultBlockState()
                                    .setValue(CopperWireBlock.INSULATED, side != plate.facing()));
                    }
                    level.setBlockAndUpdate(HOST.west(2), Blocks.JUKEBOX.defaultBlockState());
                    level.setBlockAndUpdate(HOST.east(2), CopperSpeakers.SPEAKER.defaultBlockState());
                    // A normal climbing wire may share the same wall without overlapping front surfaces.
                    level.setBlockAndUpdate(CROSS.above(), CopperSpeakers.WIRE.defaultBlockState());
                    WallPlates.get(level).tick(); AudioNetworks.get(level).tick();
                    return WallPlates.get(level).at(HOST) != null;
                });
                report.put("plateRegisteredDuringSetup", registeredDuringSetup);
                // Freshly generated chunks need a tick to start entity tracking and fire ENTITY_LOAD.
                context.waitTicks(10); connection.waitForClientboundPackets();
                server.runOnServer(s -> {
                    var level = s.overworld();
                    check(WallPlates.get(level).at(HOST) != null, "Plate must register when the chunk starts tracking entities");
                    ((JukeboxBlockEntity) level.getBlockEntity(HOST.west(2))).setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
                    check(AudioNetworks.get(level).sources(HOST.west(2)).size() == 2, "Audio must reach the speaker through the plate");
                });
                var expected = server.computeOnServer(s -> {
                    List<Expected> result = new ArrayList<>();
                    for (var host : List.of(HOST, CROSS, HOST.east(4))) {
                        var plate = WallPlates.get(s.overworld()).at(host);
                        for (var attachment : ((HolderAttachmentHolder) (Object) plate).polymerVE$getHolders())
                            for (var element : attachment.holder().getElements()) {
                                if (element instanceof EntityElement<?> frame)
                                    result.add(new Expected(frame.entity().getId(), host, "frame"));
                                else if (element instanceof ItemDisplayElement lead)
                                    result.add(new Expected(lead.getEntityId(), host, lead.getItem().get(DataComponents.ITEM_MODEL).getPath()));
                            }
                    }
                    return result;
                });
                context.waitFor(c -> expected.stream().allMatch(e -> c.level.getEntity(e.id) != null));
                context.runOnClient(c -> {
                    for (var e : expected) {
                        var entity = c.level.getEntity(e.id);
                        if (e.model.equals("frame")) {
                            check(entity instanceof ItemFrame frame && frame.isInvisible() && !frame.getItem().isEmpty(), "Both vanilla frame faces must arrive");
                        } else {
                            check(entity instanceof Display.ItemDisplay, "A socket lead must arrive as a vanilla display");
                            var display = (Display.ItemDisplay) entity;
                            var side = Arrays.stream(CopperWireBlock.DIRECTIONS)
                                    .filter(d -> e.model.endsWith("_" + d.getSerializedName())).findFirst().orElseThrow();
                            check(display.position().distanceToSqr(Vec3.atCenterOf(e.host.relative(side))) < .000001,
                                    "Lead must sample light outside the opaque host");
                            check(net.minecraft.util.Mth.wrapDegrees(display.getYRot() - 180) == 0,
                                    "Lead orientation must cancel vanilla's half-turn: " + display.getYRot());
                            check(display.itemRenderState().itemStack().get(DataComponents.ITEM_MODEL).getPath().equals(e.model), "Lead material and face must arrive intact");
                        }
                    }
                });
                report.put("frames", expected.stream().filter(e -> e.model.equals("frame")).count());
                report.put("leads", expected.stream().filter(e -> !e.model.equals("frame")).count());
                report.put("audioSourcesThroughWall", 2);
                List<String> screenshots = new ArrayList<>();
                String[] cameras = {"-3 90.8 2.6 -120 24", "4 90.8 -1.8 60 24",
                        "-1.8 90.8 2.0 -35 24", "2.8 90.8 8.2 145 24", "2 90.8 2.6 -135 24",
                        "3.86 89.05 -0.9 0 0", "-0.45 89.7 3.8 -35 10", "1.45 89.7 7.2 145 10"};
                String[] names = {"west-bare", "east-insulated", "north-bare-riser", "south-insulated-riser", "unconnected",
                        "mount-side", "bare-ledge-close", "mixed-ledge-close"};
                for (int i = 0; i < cameras.length; i++) {
                    server.runCommand("tp @a " + cameras[i]);
                    connection.waitForClientboundPackets(); context.waitTicks(15);
                    var captured = context.takeScreenshot("wall-plate-" + names[i]);
                    var retained = output.toAbsolutePath().getParent().resolve(names[i] + ".png");
                    Files.createDirectories(retained.getParent());
                    Files.copy(captured, retained);
                    screenshots.add(retained.toString());
                }
                report.put("screenshots", screenshots);
                var westLead = expected.stream().filter(e -> e.host.equals(HOST) && e.model.equals("wall_plate_lead_west")).findFirst().orElseThrow();
                server.runOnServer(s -> {
                    var level = s.overworld();
                    level.setBlockAndUpdate(HOST.west(), level.getBlockState(HOST.west()).setValue(CopperWireBlock.INSULATED, true));
                    WallPlates.get(level).tick();
                });
                connection.waitForClientboundPackets();
                context.waitFor(c -> c.level.getEntity(westLead.id) instanceof Display.ItemDisplay d
                        && d.itemRenderState().itemStack().get(DataComponents.ITEM_MODEL).getPath().equals("insulated_wall_plate_lead_west"));
                report.put("insulationUpdatedExistingLead", true);
                server.runOnServer(s -> {
                    s.overworld().setBlockAndUpdate(HOST.west(), Blocks.AIR.defaultBlockState());
                    WallPlates.get(s.overworld()).tick(); AudioNetworks.get(s.overworld()).tick();
                    check(AudioNetworks.get(s.overworld()).sources(HOST.west(2)).size() == 1, "Cable removal must disconnect the far speaker");
                });
                connection.waitForClientboundPackets();
                context.waitFor(c -> c.level.getEntity(westLead.id) == null);
                report.put("wireRemovalClearsLead", true);
            }
            report.put("status", "complete");
        } catch (Throwable e) {
            report.put("status", "failed"); report.put("failure", e.toString()); throw new RuntimeException(e);
        } finally {
            try { Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
