package dev.mcneds.copperspeakers.test;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.entity.WallPlateEntity;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import dev.mcneds.copperspeakers.network.WallPlates;
import eu.pb4.polymer.virtualentity.api.elements.EntityElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;

public final class WallPlateGameTests {
    private static final String ARENA = "copper_speakers_tests:arena";
    private static final BlockPos HOST = new BlockPos(4, 2, 5);
    private static final BlockPos MASTER = new BlockPos(2, 2, 5);
    private static final BlockPos SPEAKER = new BlockPos(6, 2, 5);
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void setup(GameTestHelper h) {
        for (int x = 1; x < 10; x++) for (int z = 1; z < 10; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(HOST, Blocks.STONE);
        h.setBlock(MASTER, Blocks.JUKEBOX);
        h.setBlock(3, 2, 5, CopperSpeakers.WIRE);
        h.setBlock(5, 2, 5, CopperSpeakers.WIRE);
        h.setBlock(SPEAKER, CopperSpeakers.SPEAKER);
    }
    private static Player player(GameTestHelper h, GameType mode, int count) {
        var player = h.makeMockServerPlayer(mode);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CopperSpeakers.WALL_PLATE, count));
        return player;
    }
    private static java.util.List<EntityElement<?>> faces(WallPlateEntity plate) {
        return ((HolderAttachmentHolder) (Object) plate).polymerVE$getHolders().stream()
                .flatMap(a -> a.holder().getElements().stream()).filter(e -> e instanceof EntityElement<?>)
                .<EntityElement<?>>map(e -> (EntityElement<?>) e).toList();
    }
    private static java.util.List<ItemDisplayElement> leads(WallPlateEntity plate) {
        return ((HolderAttachmentHolder) (Object) plate).polymerVE$getHolders().stream()
                .flatMap(a -> a.holder().getElements().stream()).filter(e -> e instanceof ItemDisplayElement)
                .map(e -> (ItemDisplayElement) e).toList();
    }
    private static boolean place(GameTestHelper h, Player player, BlockPos pos, Direction face) {
        var absolute = h.absolutePos(pos);
        return CopperSpeakers.WALL_PLATE.useOn(new UseOnContext(h.getLevel(), player, InteractionHand.MAIN_HAND,
                player.getMainHandItem(), new BlockHitResult(Vec3.atCenterOf(absolute).relative(face, .5), face, absolute, false)))
                .consumesAction();
    }
    private static WallPlateEntity plate(GameTestHelper h) { return WallPlates.get(h.getLevel()).at(h.absolutePos(HOST)); }
    private static void reconcile(GameTestHelper h) {
        WallPlates.get(h.getLevel()).tick();
        AudioNetworks.get(h.getLevel()).tick();
    }
    private static int drops(GameTestHelper h) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(HOST)).inflate(3))
                .stream().filter(e -> e.getItem().is(CopperSpeakers.WALL_PLATE)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    @GameTest(structure = ARENA) public void wiresOccupyBothPlateFacesAndCarryAudio(GameTestHelper h) {
        setup(h);
        var player = player(h, GameType.SURVIVAL, 2);
        check(place(h, player, HOST, Direction.WEST), "A pair must fit over existing floor wires on both faces");
        check(player.getMainHandItem().getCount() == 1, "One placement consumes one plate item");
        check(plate(h) != null && plate(h).survives(), "The host must own one surviving pair");
        var faces = faces(plate(h));
        check(faces.size() == 2 && faces.stream().allMatch(e -> e.entity() instanceof ItemFrame f
                        && f.isInvisible() && f.getItem().is(CopperSpeakers.WALL_PLATE)), "Both visuals are invisible item frames containing the plate model");
        check(((ItemFrame) faces.get(0).entity()).getDirection() == Direction.WEST
                && ((ItemFrame) faces.get(1).entity()).getDirection() == Direction.EAST, "The two visible plates face outward");
        check(leads(plate(h)).size() == 2, "Both floor wires must visibly reach their sockets");
        h.assertBlockProperty(new BlockPos(3, 2, 5), CopperWireBlock.PORTS[1], true);
        h.assertBlockProperty(new BlockPos(5, 2, 5), CopperWireBlock.PORTS[3], true);
        var network = AudioNetworks.get(h.getLevel());
        var root = h.absolutePos(MASTER);
        check(network.component(root).nodes().contains(h.absolutePos(HOST)), "The original stone is a conductor node");
        check(network.component(root).speakers().contains(h.absolutePos(SPEAKER)), "The far speaker connects through the wall");
        var box = h.getBlockEntity(MASTER, JukeboxBlockEntity.class);
        box.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
        check(network.sources(root).size() == 2, "Both source and far speaker must start");
        check(!place(h, player, HOST, Direction.EAST), "Opposite-side duplicate placement must fail");
        check(!place(h, player, HOST, Direction.NORTH), "A host accepts only one pair on any axis");
        check(player.getMainHandItem().getCount() == 1, "Failed placement consumes nothing");
        var serverPlayer = (net.minecraft.server.level.ServerPlayer) player;
        serverPlayer.setPos(Vec3.atCenterOf(h.absolutePos(HOST).east(2)).add(0, -1, 0));
        faces.get(1).getInteractionHandler(serverPlayer).attack(serverPlayer);
        reconcile(h);
        check(plate(h) == null && drops(h) == 1, "Breaking the reverse face removes the pair and drops exactly one item");
        h.assertBlockProperty(new BlockPos(3, 2, 5), CopperWireBlock.PORTS[1], false);
        check(network.sources(root).size() == 1, "Disconnecting a plate stops the far speaker");
        box.popOutTheItem();
        check(place(h, player, HOST, Direction.EAST), "Removed pair can be replaced from the opposite side");
        reconcile(h);
        check(!network.hasSession(root), "Replacing a plate must not start an ejected jukebox");
        box.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
        check(network.sources(root).size() == 2, "Reinsertion starts the reconnected speaker");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void wireCanBeAddedAfterPlateAndInsulated(GameTestHelper h) {
        setup(h);
        h.setBlock(3, 2, 5, Blocks.AIR);
        h.setBlock(5, 2, 5, Blocks.AIR);
        check(place(h, player(h, GameType.CREATIVE, 1), HOST, Direction.EAST), "Plate placement in clear faces");
        // Polymer requests packet context for ServerPlayer collision checks; a plain mock needs no connection.
        var cablePlayer = h.makeMockPlayer(GameType.SURVIVAL);
        cablePlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CopperSpeakers.WIRE));
        var floor = h.absolutePos(new BlockPos(3, 1, 5));
        check(CopperSpeakers.WIRE.asItem().useOn(new UseOnContext(h.getLevel(), cablePlayer, InteractionHand.MAIN_HAND,
                cablePlayer.getMainHandItem(), new BlockHitResult(Vec3.atCenterOf(floor).add(0, .5, 0), Direction.UP, floor, false)))
                .consumesAction(), "A real wire item can be placed in the frame's block space");
        h.setBlock(5, 2, 5, CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, true)
                .setValue(CopperWireBlock.WATERLOGGED, true));
        reconcile(h);
        check(plate(h) != null && plate(h).survives(), "Dry and waterlogged insulated cable may occupy the plate faces");
        check(AudioNetworks.get(h.getLevel()).component(h.absolutePos(MASTER)).speakers().contains(h.absolutePos(SPEAKER)),
                "Wires added later connect through the existing pair");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void rejectsInvalidSupportsAndObstructedOppositeFace(GameTestHelper h) {
        setup(h);
        var player = player(h, GameType.SURVIVAL, 8);
        for (var block : new net.minecraft.world.level.block.Block[]{Blocks.OAK_SLAB, Blocks.OAK_STAIRS,
                Blocks.OAK_FENCE, Blocks.CHEST, Blocks.JUKEBOX, Blocks.WATER, CopperSpeakers.SPEAKER}) {
            h.setBlock(HOST, block);
            check(!place(h, player, HOST, Direction.WEST), "Ineligible support must be rejected: " + block);
        }
        h.setBlock(HOST, Blocks.STONE);
        h.setBlock(5, 2, 5, Blocks.FURNACE);
        check(!place(h, player, HOST, Direction.WEST), "Blocked back face must reject the entire placement");
        h.setBlock(5, 2, 5, CopperSpeakers.WIRE);
        var occupied = new ItemFrame(h.getLevel(), h.absolutePos(HOST).east(), Direction.EAST);
        h.getLevel().addFreshEntity(occupied);
        check(!place(h, player, HOST, Direction.WEST), "An existing item frame must not be overwritten");
        check(plate(h) == null && player.getMainHandItem().getCount() == 8, "Failed placements leave no anchor or consumed items");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void hostBreakAndObstructionRemovePairOnce(GameTestHelper h) {
        setup(h);
        var player = player(h, GameType.SURVIVAL, 2);
        check(place(h, player, HOST, Direction.WEST), "Initial placement");
        var old = plate(h);
        h.setBlock(HOST, Blocks.AIR);
        reconcile(h);
        check(old.isRemoved() && plate(h) == null && drops(h) == 1, "Host destruction removes both faces with one drop");
        old.breakPlate(null, Direction.EAST);
        check(drops(h) == 1, "Repeated notifications must not duplicate drops");
        h.setBlock(HOST, Blocks.STONE);
        check(place(h, player, HOST, Direction.EAST), "Replacement placement");
        h.setBlock(3, 2, 5, Blocks.FURNACE);
        reconcile(h);
        check(plate(h) == null && drops(h) == 2, "Obstructing either frame tears down the whole pair");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void creativePlacementAndRemovalDoNotConsumeOrDrop(GameTestHelper h) {
        setup(h);
        var player = player(h, GameType.CREATIVE, 1);
        check(place(h, player, HOST, Direction.NORTH), "North/south plates are supported");
        check(player.getMainHandItem().getCount() == 1, "Creative placement keeps its item");
        // The host is a wire junction, not an axis-limited edge.
        check(AudioNetworks.get(h.getLevel()).component(h.absolutePos(MASTER)).speakers().contains(h.absolutePos(SPEAKER)),
                "A plated block connects all four horizontal neighbors");
        plate(h).breakPlate(player, Direction.SOUTH);
        check(plate(h) == null && drops(h) == 0, "Creative removal drops nothing");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void savedPairRestoresWithoutRestartingPlayback(GameTestHelper h) {
        setup(h);
        check(place(h, player(h, GameType.CREATIVE, 1), HOST, Direction.WEST), "Initial placement");
        var original = plate(h);
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        original.saveWithoutId(output);
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        reconcile(h);
        check(plate(h) == null && drops(h) == 0, "Unloading only unregisters the pair; it must not drop items");
        check(AudioNetworks.get(h.getLevel()).component(h.absolutePos(MASTER)).speakers().isEmpty(), "Unloaded pair cannot conduct");
        var restored = new WallPlateEntity(CopperSpeakers.WALL_PLATE_ENTITY, h.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
        h.getLevel().addFreshEntity(restored);
        reconcile(h);
        check(restored.host().equals(h.absolutePos(HOST)) && restored.facing() == Direction.WEST, "Host and facing persist");
        check(plate(h) == restored && restored.survives(), "Loaded entity reconstructs its pair and conductor index");
        check(faces(restored).size() == 2 && faces(original).isEmpty(), "Reload reconstructs exactly two faces and unload removes the old ones");
        check(leads(restored).size() == 2 && leads(original).isEmpty(), "Reload restores socket leads without leaking old visuals");
        check(!AudioNetworks.get(h.getLevel()).hasSession(h.absolutePos(MASTER)), "Entity reload cannot start audio");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void socketLeadsFollowWireChangesOnEveryHorizontalFace(GameTestHelper h) {
        setup(h);
        var player = player(h, GameType.CREATIVE, 1);
        for (var direction : CopperWireBlock.DIRECTIONS) {
            for (var side : CopperWireBlock.DIRECTIONS) h.setBlock(HOST.relative(side), Blocks.AIR);
            check(place(h, player, HOST, direction), "Plate placement on " + direction);
            var plate = plate(h);
            check(leads(plate).isEmpty(), "An empty face must not show a cable");
            var wire = HOST.relative(direction);
            h.setBlock(wire, CopperSpeakers.WIRE);
            reconcile(h);
            check(leads(plate).size() == 1, "Wire placement must add only its own face's lead");
            var lead = leads(plate).getFirst();
            check(lead.getItem().get(DataComponents.ITEM_MODEL).equals(CopperSpeakers.id("wall_plate_lead_" + direction.getSerializedName())),
                    "Bare lead must face its cable");
            h.setBlock(wire, h.getBlockState(wire).setValue(CopperWireBlock.INSULATED, true));
            reconcile(h);
            check(leads(plate).getFirst() == lead && lead.getItem().get(DataComponents.ITEM_MODEL)
                    .equals(CopperSpeakers.id("insulated_wall_plate_lead_" + direction.getSerializedName())),
                    "Insulating an existing cable must update the lead without duplicating it");
            h.setBlock(wire, Blocks.AIR);
            reconcile(h);
            check(leads(plate).isEmpty() && faces(plate).size() == 2, "Removing cable clears its lead but keeps both plates");
            plate.breakPlate(player, direction);
            check(leads(plate).isEmpty() && faces(plate).isEmpty(), "Removing a pair clears every visual");
        }
        h.succeed();
    }
    @GameTest(structure = ARENA) public void chainsThroughSolidPlaneUsingTopAndBottomClicks(GameTestHelper h) {
        setup(h);
        for (int x = 4; x <= 6; x++) h.setBlock(x, 2, 5, Blocks.STONE);
        h.setBlock(7, 2, 5, CopperSpeakers.WIRE);
        h.setBlock(8, 2, 5, CopperSpeakers.SPEAKER);
        var player = player(h, GameType.SURVIVAL, 3);
        player.setYRot(Direction.WEST.toYRot());
        check(place(h, player, HOST, Direction.UP), "Top click must infer a horizontal pair despite an adjacent solid block");
        check(plate(h).facing() == Direction.EAST, "Top click uses the player's horizontal facing");
        var network = AudioNetworks.get(h.getLevel());
        var root = h.absolutePos(MASTER);
        check(network.component(root).speakers().isEmpty(), "An unplated full block must not conduct");
        check(place(h, player, new BlockPos(5, 2, 5), Direction.DOWN), "Bottom click permits the interior plate in a chain");
        check(place(h, player, new BlockPos(6, 2, 5), Direction.UP), "Top click completes the chain");
        reconcile(h);
        check(network.component(root).speakers().contains(h.absolutePos(new BlockPos(8, 2, 5))), "Adjacent plated blocks relay through the solid plane");
        var middle = WallPlates.get(h.getLevel()).at(h.absolutePos(new BlockPos(5, 2, 5)));
        middle.breakPlate(player, Direction.EAST);
        reconcile(h);
        check(network.component(root).speakers().isEmpty(), "Removing one interior plate disconnects the chain");
        h.succeed();
    }
}
