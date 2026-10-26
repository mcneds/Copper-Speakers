package dev.mcneds.copperspeakers.test;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class WireStepGameTests {
    private static final String ARENA = "copper_speakers_tests:arena";
    private static final BlockPos LOW = new BlockPos(4, 2, 5);
    private static final BlockPos HIGH = LOW.east().above();
    private static final BlockPos MASTER = LOW.west();
    private static final BlockPos SPEAKER = HIGH.east();
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void floor(GameTestHelper h) {
        for (int x = 1; x < 10; x++) for (int z = 1; z < 10; z++) h.setBlock(x, 1, z, Blocks.STONE);
    }
    private static AudioNetworks network(GameTestHelper h) { return AudioNetworks.get(h.getLevel()); }
    private static void setup(GameTestHelper h) {
        floor(h);
        h.setBlock(MASTER, Blocks.JUKEBOX);
        h.setBlock(HIGH.below(), Blocks.STONE);
        h.setBlock(SPEAKER.below(), Blocks.STONE);
        h.setBlock(SPEAKER, CopperSpeakers.SPEAKER);
        h.setBlock(LOW, CopperSpeakers.WIRE);
        h.setBlock(HIGH, CopperSpeakers.WIRE);
        network(h).tick();
    }
    private static boolean reaches(GameTestHelper h, BlockPos from, BlockPos to) {
        return network(h).component(h.absolutePos(from)).nodes().contains(h.absolutePos(to));
    }
    private static int risers(GameTestHelper h, BlockPos pos) {
        var holder = BlockBoundAttachment.get(h.getLevel(), h.absolutePos(pos));
        return holder == null ? 0 : holder.holder().getElements().size();
    }
    @GameTest(structure = ARENA) public void oneBlockStepCarriesAudioAndUpdatesBothEnds(GameTestHelper h) {
        setup(h);
        check(reaches(h, MASTER, SPEAKER) && reaches(h, HIGH, LOW), "Audio routes ascend and descend the same ledge");
        h.assertBlockProperty(LOW, CopperWireBlock.CLIMBING, 2);
        h.assertBlockProperty(LOW, CopperWireBlock.PORTS[1], true);
        h.assertBlockProperty(HIGH, CopperWireBlock.PORTS[3], true);
        check(risers(h, LOW) == 1 && risers(h, HIGH) == 0, "Exactly one rising cable face is rendered at the lower wire");
        var box = h.getBlockEntity(MASTER, JukeboxBlockEntity.class);
        box.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
        check(network(h).sources(h.absolutePos(MASTER)).contains(h.absolutePos(SPEAKER)), "Disc insertion starts the upper speaker");
        h.setBlock(HIGH, Blocks.AIR);
        network(h).tick();
        check(risers(h, LOW) == 0, "Removing the upper wire removes its side strip");
        h.assertBlockProperty(LOW, CopperWireBlock.PORTS[1], false);
        check(!network(h).sources(h.absolutePos(MASTER)).contains(h.absolutePos(SPEAKER)), "Removal disconnects active playback");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void solidHeadroomBlocksStepAndRemovingItReconnects(GameTestHelper h) {
        setup(h);
        h.setBlock(LOW.above(), Blocks.STONE);
        network(h).tick();
        check(!reaches(h, LOW, HIGH) && !reaches(h, HIGH, LOW), "A solid ceiling blocks both directions");
        check(risers(h, LOW) == 0, "Obstructed steps have no vertical visual");
        h.setBlock(LOW.above(), Blocks.AIR);
        network(h).tick();
        check(reaches(h, MASTER, SPEAKER) && risers(h, LOW) == 1, "Changing a non-network ceiling block refreshes the route and model");
        h.setBlock(HIGH.below(), Blocks.AIR);
        network(h).tick();
        check(!reaches(h, MASTER, SPEAKER), "Removing the ledge breaks the route");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void fourDirectionsSupportUpperFirstAndLowerFirstPlacement(GameTestHelper h) {
        floor(h);
        for (int i = 0; i < 4; i++) {
            var direction = CopperWireBlock.DIRECTIONS[i];
            var top = LOW.relative(direction).above();
            h.setBlock(top.below(), Blocks.STONE);
            h.setBlock(top, CopperSpeakers.WIRE);
            h.setBlock(LOW, CopperSpeakers.WIRE);
            network(h).tick();
            check(reaches(h, LOW, top) && reaches(h, top, LOW), "Every horizontal direction connects up/down");
            h.assertBlockProperty(LOW, CopperWireBlock.CLIMBING, 1 << i);
            var element = (ItemDisplayElement) BlockBoundAttachment.get(h.getLevel(), h.absolutePos(LOW)).holder().getElements().getFirst();
            check(element.getItem().get(DataComponents.ITEM_MODEL).equals(CopperSpeakers.id("wire_riser_" + direction.getSerializedName())),
                    "The climbing strip must face its ledge");
            h.setBlock(LOW, Blocks.AIR);
            h.setBlock(top, Blocks.AIR);
            h.setBlock(top.below(), Blocks.AIR);
            network(h).tick();
        }
        h.succeed();
    }
    @GameTest(structure = ARENA) public void insulatedAndWaterloggedStepsKeepTheirAppearance(GameTestHelper h) {
        setup(h);
        var resin = CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, true)
                .setValue(CopperWireBlock.WATERLOGGED, true);
        h.setBlock(LOW, resin);
        h.setBlock(HIGH, resin);
        network(h).tick();
        check(reaches(h, MASTER, SPEAKER), "Waterlogged insulated wires still join across a step");
        var element = (ItemDisplayElement) BlockBoundAttachment.get(h.getLevel(), h.absolutePos(LOW)).holder().getElements().getFirst();
        check(element.getItem().get(DataComponents.ITEM_MODEL).equals(CopperSpeakers.id("insulated_wire_riser_east")),
                "Resin must also cover the rising face");
        h.setBlock(LOW, CopperSpeakers.WIRE);
        network(h).tick();
        element = (ItemDisplayElement) BlockBoundAttachment.get(h.getLevel(), h.absolutePos(LOW)).holder().getElements().getFirst();
        check(element.getItem().get(DataComponents.ITEM_MODEL).equals(CopperSpeakers.id("wire_riser_east")),
                "Changing insulation refreshes the existing rising face");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void noUnsupportedVerticalJumpsOrRedstonePower(GameTestHelper h) {
        setup(h);
        h.setBlock(HIGH, Blocks.AIR);
        h.setBlock(HIGH.above(), CopperSpeakers.WIRE);
        network(h).tick();
        check(!reaches(h, LOW, HIGH.above()), "Two-block jumps never connect");
        h.setBlock(HIGH.above(), Blocks.AIR);
        h.setBlock(HIGH, Blocks.REDSTONE_WIRE);
        network(h).tick();
        check(!reaches(h, LOW, HIGH), "Copper audio cable must not join vanilla redstone dust");
        h.setBlock(HIGH, CopperSpeakers.WIRE);
        h.setBlock(LOW.north(), Blocks.REDSTONE_BLOCK);
        network(h).tick();
        var state = h.getLevel().getBlockState(h.absolutePos(LOW));
        check(!state.isSignalSource(), "Copper wire remains independent of redstone power");
        for (var direction : Direction.values())
            check(state.getSignal(h.getLevel(), h.absolutePos(LOW), direction) == 0, "Copper cable never powers a redstone circuit");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void realWireItemsCanBePlacedOnBothStepLevels(GameTestHelper h) {
        floor(h);
        h.setBlock(HIGH.below(), Blocks.STONE);
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        var stack = new ItemStack(CopperSpeakers.WIRE, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        for (var target : new BlockPos[]{LOW, HIGH}) {
            var support = h.absolutePos(target.below());
            var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false);
            check(CopperSpeakers.WIRE.asItem().useOn(new UseOnContext(h.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit))
                    .consumesAction(), "Wire items must place normally on both top faces");
        }
        network(h).tick();
        check(stack.isEmpty() && reaches(h, LOW, HIGH) && risers(h, LOW) == 1, "Normal placement builds a complete step connection");
        h.succeed();
    }
}
