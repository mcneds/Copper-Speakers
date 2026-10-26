package dev.mcneds.copperspeakers.test;

import com.google.gson.GsonBuilder;
import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;

/** Real client packets for every direction mask, including the opposite-pair symmetry trap. */
public final class WireVisualTest {
    private record Expected(int entityId, BlockPos lower, Direction direction, boolean resin) {}
    private record ExpectedFloor(int entityId, BlockPos pos, int mask) {}
    private static BlockPos lower(int index) { return new BlockPos(4 * (index % 6), 90, 4 * (index / 6)); }
    private static int mask(int index) { return index % 16; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public void runTest(ClientGameTestContext context) {
        Path output = Path.of(System.getenv().getOrDefault("COPPER_WIRE_OUTPUT", "wire.json"));
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("status", "incomplete");
        report.put("axiomLoaded", FabricLoader.getInstance().isModLoaded("axiom"));
        try {
            Properties properties = new Properties();
            properties.setProperty("server-ip", "127.0.0.1"); properties.setProperty("online-mode", "false");
            try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                properties.setProperty("server-port", "" + socket.getLocalPort());
            }
            properties.setProperty("level-name", Path.of("build/wire-world-" + System.currentTimeMillis()).toAbsolutePath().toString());
            try (var server = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("1")).createServer(properties);
                 var connection = server.connect()) {
                server.runCommand("gamemode spectator @a");
                server.runCommand("tp @a 8 94 12 180 30");
                server.runCommand("forceload add -16 -16 32 32"); // Test fixture only.
                server.runCommand("time set day");
                server.runOnServer(s -> {
                    var level = s.overworld();
                    for (int x = -2; x <= 23; x++) for (int z = -2; z <= 23; z++) {
                        var p = new BlockPos(x, 89, z); level.getChunk(p);
                        level.setBlockAndUpdate(p, Blocks.GRASS_BLOCK.defaultBlockState());
                        for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(p.above(y), Blocks.AIR.defaultBlockState());
                    }
                    for (int index = 0; index < 32; index++) {
                        var low = lower(index);
                        var wire = CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, index >= 16);
                        for (int i = 0; i < 4; i++) if ((mask(index) & (1 << i)) != 0) {
                            var support = low.relative(CopperWireBlock.DIRECTIONS[i]);
                            level.setBlockAndUpdate(support, Blocks.GRASS_BLOCK.defaultBlockState());
                            level.setBlockAndUpdate(support.above(), wire);
                        }
                        level.setBlockAndUpdate(low, wire);
                    }
                    AudioNetworks.get(level).tick();
                });
                context.waitTicks(10); connection.waitForClientboundPackets();
                var expected = server.computeOnServer(s -> {
                    List<Expected> result = new ArrayList<>();
                    for (int index = 0; index < 32; index++) {
                        var low = lower(index);
                        var holder = BlockBoundAttachment.get(s.overworld(), low).holder();
                        check(holder.getElements().size() == Integer.bitCount(mask(index)) + (index >= 16 ? 1 : 0),
                                "Each ledge has one riser and each dry resin wire has one floor");
                        for (var element : holder.getElements()) {
                            var display = (ItemDisplayElement)element;
                            String model = display.getItem().get(DataComponents.ITEM_MODEL).getPath();
                            if (!model.contains("_riser_")) continue;
                            Direction side = Arrays.stream(CopperWireBlock.DIRECTIONS)
                                    .filter(d -> model.endsWith("_" + d.getSerializedName())).findFirst().orElseThrow();
                            result.add(new Expected(display.getEntityId(), low, side, index >= 16));
                        }
                    }
                    return result;
                });
                var floors = server.computeOnServer(s -> {
                    List<ExpectedFloor> result = new ArrayList<>();
                    for (int index = 16; index < 32; index++) {
                        var pos = lower(index);
                        int floorMask = mask(index);
                        var holder = BlockBoundAttachment.get(s.overworld(), pos).holder();
                        var floor = holder.getElements().stream().map(e -> (ItemDisplayElement)e)
                                .filter(e -> e.getItem().get(DataComponents.ITEM_MODEL).getPath().equals("insulated_wire_" + floorMask))
                                .findFirst().orElseThrow();
                        result.add(new ExpectedFloor(floor.getEntityId(), pos, mask(index)));
                    }
                    return result;
                });
                context.waitFor(c -> expected.stream().allMatch(e -> c.level.getEntity(e.entityId) instanceof Display.ItemDisplay d && d.itemRenderState() != null));
                context.waitFor(c -> floors.stream().allMatch(e -> c.level.getEntity(e.entityId) instanceof Display.ItemDisplay d && d.itemRenderState() != null));
                var observed = context.computeOnClient(c -> {
                    List<Object> values = new ArrayList<>();
                    for (var e : expected) {
                        var display = (Display.ItemDisplay)c.level.getEntity(e.entityId);
                        String model = display.itemRenderState().itemStack().get(DataComponents.ITEM_MODEL).getPath();
                        check(model.equals((e.resin ? "insulated_" : "") + "wire_riser_" + e.direction.getSerializedName()), "Received model must match its actual ledge");
                        check(display.position().distanceToSqr(Vec3.atCenterOf(e.lower)) < .000001, "Riser must be centered on its lower wire");
                        // Display orientation rotates by -yaw; vanilla's item renderer adds +PI.
                        // A model's outward normal must still point toward its actual supporting block.
                        Vec3 normal = new Vec3(e.direction.getStepX(), 0, e.direction.getStepZ());
                        Vec3 renderedNormal = normal.yRot((float)Math.toRadians(180 - display.getYRot()));
                        check(renderedNormal.dot(normal) > .99999, "Client riser faces away from its support: " + e.direction + " yaw=" + display.getYRot());
                        values.add(Map.of("entityId",e.entityId,"lower",e.lower.toShortString(),"face",e.direction.getSerializedName(),"yaw",display.getYRot(),"model",model));
                    }
                    return values;
                });
                report.put("receivedRisers", observed); report.put("layouts", 32);
                context.runOnClient(c -> {
                    for (var floor : floors) {
                        var display = (Display.ItemDisplay)c.level.getEntity(floor.entityId);
                        check(display.itemRenderState().itemStack().get(DataComponents.ITEM_MODEL)
                                .equals(CopperSpeakers.id("insulated_wire_" + floor.mask)), "Client receives the correct resin floor model");
                        check(Math.abs(display.getYRot()) == 180 && display.position().distanceToSqr(Vec3.atCenterOf(floor.pos)) < .000001,
                                "Resin floor retains its world position and orientation");
                        check(display.shouldRenderAtSqrDistance(128 * 128), "Resin floors remain visible beyond the default display range");
                        var before = c.level.getBlockState(floor.pos);
                        for (var direction : CopperWireBlock.DIRECTIONS) {
                            var neighbor = floor.pos.relative(direction);
                            var previous = c.level.getBlockState(neighbor);
                            // Exercise vanilla client neighbor prediction before any server correction.
                            c.level.setBlock(neighbor, Blocks.RESIN_CLUMP.defaultBlockState()
                                    .setValue(BlockStateProperties.DOWN, true), Block.UPDATE_ALL);
                            check(c.level.getBlockState(floor.pos) == before, "Predicted resin placement must not change the cable carrier");
                            check(c.level.getEntity(floor.entityId) == display, "Predicted placement must preserve the resin display");
                            c.level.setBlock(neighbor, previous, Block.UPDATE_ALL);
                        }
                    }
                });
                server.runCommand("gamemode creative @a");
                var ground = lower(16).north().below();
                server.runCommand("tp @a " + (ground.getX() + .5) + " 90 " + (ground.getZ() - 1.5));
                server.runCommand("item replace entity @a weapon.mainhand with minecraft:resin_clump 64");
                connection.waitForClientboundPackets();
                context.runOnClient(c -> {
                    var before = c.level.getBlockState(lower(16));
                    var result = c.gameMode.useItemOn(c.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(ground).add(0, .5, 0), Direction.UP, ground, false));
                    check(result.consumesAction(), "Real resin placement beside wire must succeed");
                    check(c.level.getBlockState(ground.above()).is(Blocks.RESIN_CLUMP), "Client predicts resin on the ground");
                    check(c.level.getBlockState(lower(16)) == before, "Real item prediction preserves the insulated cable carrier");
                });
                context.waitTicks(5); connection.waitForClientboundPackets();
                server.runOnServer(s -> {
                    check(s.overworld().getBlockState(ground.above()).is(Blocks.RESIN_CLUMP), "Server accepts the ground resin placement");
                    check(s.overworld().getBlockState(lower(16)).getValue(CopperWireBlock.INSULATED), "Server keeps wire insulated");
                });
                report.put("resinPredictionChecks", 64); report.put("realResinPlacement", true);
                server.runCommand("gamemode spectator @a");
                List<String> screenshots = new ArrayList<>();
                for (int index : new int[]{2,3,10,19}) {
                    var low = lower(index);
                    server.runCommand("tp @a " + (low.getX()-3.0) + " 92.5 " + (low.getZ()+4.0) + " -140 28");
                    connection.waitForClientboundPackets(); context.waitTicks(12);
                    screenshots.add(context.takeScreenshot("wire-mask-" + mask(index) + (index >= 16 ? "-resin" : "-bare")).toString());
                }
                report.put("screenshots", screenshots);
                server.runOnServer(s -> {
                    for (int index = 0; index < 32; index++) s.overworld().setBlockAndUpdate(lower(index), Blocks.AIR.defaultBlockState());
                    AudioNetworks.get(s.overworld()).tick();
                });
                connection.waitForClientboundPackets();
                context.waitFor(c -> expected.stream().allMatch(e -> c.level.getEntity(e.entityId) == null));
                context.waitFor(c -> floors.stream().allMatch(e -> c.level.getEntity(e.entityId) == null));
                report.put("removalClearedAllRisers", true);
                report.put("removalClearedAllFloors", true);
            }
            report.put("status", "complete");
        } catch (Throwable e) {
            report.put("status", "failed"); report.put("failure",e.toString()); throw new RuntimeException(e);
        } finally {
            try { Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
