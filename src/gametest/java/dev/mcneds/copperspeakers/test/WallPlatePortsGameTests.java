package dev.mcneds.copperspeakers.test;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import dev.mcneds.copperspeakers.network.WallPlates;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class WallPlatePortsGameTests {
    private static final BlockPos HOST = new BlockPos(5, 2, 5);
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void floor(GameTestHelper h) {
        for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
    }
    private static Player player(GameTestHelper h) {
        var player = h.makeMockServerPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CopperSpeakers.WALL_PLATE));
        return player;
    }
    private static void place(GameTestHelper h, Player player, BlockPos host, Direction face) {
        var absolute = h.absolutePos(host);
        check(CopperSpeakers.WALL_PLATE.useOn(new UseOnContext(h.getLevel(), player, InteractionHand.MAIN_HAND,
                player.getMainHandItem(), new BlockHitResult(Vec3.atCenterOf(absolute).relative(face, .5), face, absolute, false)))
                .consumesAction(), "Plate placement failed on " + face);
    }
    private static void reconcile(GameTestHelper h) {
        WallPlates.get(h.getLevel()).tick(); AudioNetworks.get(h.getLevel()).tick();
    }
    private static int port(Direction direction) {
        return switch (direction) { case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; case WEST -> 3;
            default -> throw new IllegalArgumentException(); };
    }

    @GameTest(structure = "copper_speakers_tests:arena")
    public void onlySocketFacesConnectInEveryOrientationAndInsulation(GameTestHelper h) {
        floor(h); var player = player(h); var network = AudioNetworks.get(h.getLevel());
        for (boolean insulated : new boolean[]{false, true}) for (var facing : CopperWireBlock.DIRECTIONS) {
            h.setBlock(HOST, Blocks.STONE);
            for (var side : CopperWireBlock.DIRECTIONS) {
                h.setBlock(HOST.relative(side), CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, insulated));
                h.setBlock(HOST.relative(side, 2), side == facing ? Blocks.JUKEBOX : CopperSpeakers.SPEAKER);
            }
            place(h, player, HOST, facing); reconcile(h);
            var master = HOST.relative(facing, 2);
            var opposite = HOST.relative(facing.getOpposite(), 2);
            var component = network.component(h.absolutePos(master));
            check(component.speakers().equals(java.util.Set.of(h.absolutePos(opposite))),
                    "Only the speaker across the sockets may join: " + facing + " insulated=" + insulated);
            for (var side : CopperWireBlock.DIRECTIONS) {
                boolean connected = side.getAxis() == facing.getAxis();
                h.assertBlockProperty(HOST.relative(side), CopperWireBlock.PORTS[port(side.getOpposite())], connected);
                if (!connected) check(!network.component(h.absolutePos(HOST.relative(side))).nodes().contains(h.absolutePos(HOST)),
                        "A perpendicular wire must not enter the host in either direction");
            }
            var box = h.getBlockEntity(master, JukeboxBlockEntity.class);
            box.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
            check(network.sources(h.absolutePos(master)).equals(java.util.Set.of(h.absolutePos(master), h.absolutePos(opposite))),
                    "Actual playback must exclude perpendicular speakers");
            box.popOutTheItem();
            WallPlates.get(h.getLevel()).at(h.absolutePos(HOST)).breakPlate(player, facing);
            for (var side : CopperWireBlock.DIRECTIONS) {
                h.setBlock(HOST.relative(side), Blocks.AIR); h.setBlock(HOST.relative(side, 2), Blocks.AIR);
            }
            h.setBlock(HOST, Blocks.AIR); reconcile(h);
        }
        h.succeed();
    }

    @GameTest(structure = "copper_speakers_tests:arena")
    public void adjacentPlatesRequireBothSocketPairsToAlign(GameTestHelper h) {
        floor(h); var player = player(h); var network = AudioNetworks.get(h.getLevel());
        var first = HOST.west(); var second = HOST;
        var master = first.west(2); var speaker = second.east(2);
        h.setBlock(master, Blocks.JUKEBOX); h.setBlock(first.west(), CopperSpeakers.WIRE);
        h.setBlock(first, Blocks.STONE); h.setBlock(second, Blocks.STONE);
        h.setBlock(second.east(), CopperSpeakers.WIRE); h.setBlock(speaker, CopperSpeakers.SPEAKER);
        place(h, player, first, Direction.EAST); place(h, player, second, Direction.NORTH); reconcile(h);
        check(network.component(h.absolutePos(master)).speakers().isEmpty(), "A mismatched second pair must block the chain");
        h.assertBlockProperty(second.east(), CopperWireBlock.PORTS[3], false);
        WallPlates.get(h.getLevel()).at(h.absolutePos(second)).breakPlate(player, Direction.NORTH);
        place(h, player, second, Direction.EAST); reconcile(h);
        check(network.component(h.absolutePos(master)).speakers().contains(h.absolutePos(speaker)), "Aligned socket pairs must relay");
        h.assertBlockProperty(second.east(), CopperWireBlock.PORTS[3], true);
        WallPlates.get(h.getLevel()).at(h.absolutePos(first)).breakPlate(player, Direction.EAST);
        place(h, player, first, Direction.NORTH); reconcile(h);
        check(network.component(h.absolutePos(master)).speakers().isEmpty(), "A mismatched first pair must also block the chain");
        h.assertBlockProperty(first.west(), CopperWireBlock.PORTS[1], false);
        h.succeed();
    }
}
