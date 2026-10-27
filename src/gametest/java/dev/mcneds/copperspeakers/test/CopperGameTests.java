package dev.mcneds.copperspeakers.test;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.block.SpeakerBlock;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;

public final class CopperGameTests {
    private static final String ARENA = "copper_speakers_tests:arena";
    private static final BlockPos MASTER = new BlockPos(2, 2, 5);
    private static final BlockPos WIRE = new BlockPos(3, 2, 5);
    private static final BlockPos SPEAKER = new BlockPos(6, 2, 5);
    private static final BlockPos SECOND = new BlockPos(4, 2, 4);
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JukeboxBlockEntity setup(GameTestHelper h) {
        for (int x = 1; x < 10; x++) for (int z = 1; z < 10; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(MASTER, Blocks.JUKEBOX);
        for (int x = 3; x <= 5; x++) h.setBlock(x, 2, 5, CopperSpeakers.WIRE);
        h.setBlock(SPEAKER, CopperSpeakers.SPEAKER);
        h.setBlock(SECOND, CopperSpeakers.SPEAKER);
        return h.getBlockEntity(MASTER, JukeboxBlockEntity.class);
    }
    private static AudioNetworks network(GameTestHelper h) { return AudioNetworks.get(h.getLevel()); }
    private static BlockPos master(GameTestHelper h) { return h.absolutePos(MASTER); }
    private static void insert(JukeboxBlockEntity box) { box.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT)); }

    @GameTest(structure = ARENA) public void speakerNoteLifecycle(GameTestHelper h) {
        var box = setup(h);
        var net = network(h);
        var root = master(h);
        var speaker = h.absolutePos(SPEAKER);
        net.tick();
        check(net.speakerNotes(root).isEmpty(), "Idle speakers must have no note emitters");
        insert(box); net.tick();
        check(net.speakerNotes(root).size() == 2 && !net.speakerNotes(root).containsKey(root),
                "Only active speakers get note emitters, never the jukebox");
        var old = net.speakerNotes(root).get(speaker);
        h.setBlock(SPEAKER, CopperSpeakers.SPEAKER.defaultBlockState().setValue(SpeakerBlock.FACING, Direction.EAST));
        net.tick();
        check(old.getAttachment() == null && net.speakerNotes(root).get(speaker).facing() == Direction.EAST,
                "Changing facing must replace the old emitter");
        h.setBlock(WIRE, Blocks.AIR); net.tick();
        check(net.speakerNotes(root).isEmpty(), "Disconnected speakers must lose their notes");
        h.setBlock(WIRE, CopperSpeakers.WIRE); net.tick();
        check(net.speakerNotes(root).size() == 2, "Reconnected speakers using spare voices get notes");
        var holders = java.util.List.copyOf(net.speakerNotes(root).values());
        net.finish(root, true);
        check(net.speakerNotes(root).isEmpty() && holders.stream().allMatch(n -> n.getAttachment() == null),
                "Natural completion must remove visual notes while retaining audio tails");
        box.popOutTheItem(); insert(box); net.tick();
        holders = java.util.List.copyOf(net.speakerNotes(root).values());
        net.unload(new ChunkPos(root.getX() >> 4, root.getZ() >> 4));
        check(net.speakerNotes(root).isEmpty() && holders.stream().allMatch(n -> n.getAttachment() == null),
                "Master unload must remove its speakers' note displays");
        box.popOutTheItem();
        h.succeed();
    }

    @GameTest(structure = ARENA) public void insertionEjectionReplacement(GameTestHelper h) {
        var box = setup(h); insert(box);
        check(network(h).sources(master(h)).size() == 3, "One disc must feed master and two speakers");
        check(box.getComparatorOutput() == 2, "Vanilla comparator output preserved");
        box.setTheItem(new ItemStack(Items.MUSIC_DISC_13));
        check(network(h).sources(master(h)).size() == 3, "Replacement must establish a new group");
        check(box.getComparatorOutput() == 1, "Replacement comparator output");
        box.popOutTheItem();
        check(!network(h).hasSession(master(h)), "Ejection must stop the whole group");
        check(box.getTheItem().isEmpty(), "Ejection empties container");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void naturalCompletionThenExplicitEjection(GameTestHelper h) {
        var box = setup(h); insert(box);
        var song = h.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .get(Identifier.withDefaultNamespace("cat")).orElseThrow();
        box.getSongPlayer().setSongWithoutPlaying(song, song.value().lengthInTicks() + 19);
        box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
        box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
        check(!box.getSongPlayer().isPlaying(), "Vanilla song player must finish at declared duration +20");
        check(network(h).isNaturalTail(master(h)), "Natural completion must retain cancellable client tails");
        check(network(h).sources(master(h)).size() == 3, "Natural stop must not cut client decoding delay");
        box.popOutTheItem();
        check(!network(h).hasSession(master(h)), "Explicit removal must still cancel natural tails");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void standaloneUsesCoordinatedPlayback(GameTestHelper h) {
        h.setBlock(MASTER, Blocks.JUKEBOX);
        var box = h.getBlockEntity(MASTER, JukeboxBlockEntity.class); insert(box);
        check(box.getSongPlayer().isPlaying(), "Standalone jukebox must play");
        check(network(h).sources(master(h)).equals(java.util.Set.of(master(h))),
                "Standalone playback must register the jukebox for distant listeners");
        check(network(h).spareVoiceCount(master(h)) == 4, "Standalone playback reserves advancing speaker voices");
        check(box.getComparatorOutput() == 2, "Standalone comparator output is preserved");
        box.popOutTheItem();
        check(!box.getSongPlayer().isPlaying() && !network(h).hasSession(master(h)),
                "Standalone ejection stops the song and coordinated playback");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void standaloneCanGainItsFirstSpeakerDuringPlayback(GameTestHelper h) {
        h.setBlock(MASTER.below(), Blocks.STONE);
        h.setBlock(MASTER, Blocks.JUKEBOX);
        var box = h.getBlockEntity(MASTER, JukeboxBlockEntity.class); insert(box);
        var net = network(h);
        var initialIds = net.spareEntityIds(master(h));
        h.setBlock(MASTER.east().below(), Blocks.STONE);
        h.setBlock(MASTER.east(), CopperSpeakers.SPEAKER);
        net.tick();
        check(net.sources(master(h)).equals(java.util.Set.of(master(h), h.absolutePos(MASTER.east()))),
                "The first speaker joins an already playing standalone jukebox");
        check(net.spareEntityIds(master(h)).equals(initialIds) && net.spareVoiceCount(master(h)) == 3,
                "The first speaker uses an existing voice without restarting the jukebox");
        box.popOutTheItem();
        h.succeed();
    }
    @GameTest(structure = ARENA, maxTicks = 100) public void standaloneLockedHopperInsertionAndExtraction(GameTestHelper h) {
        h.setBlock(MASTER, Blocks.JUKEBOX);
        h.setBlock(MASTER.above().east(), Blocks.REDSTONE_BLOCK);
        h.setBlock(MASTER.above(), Blocks.HOPPER);
        var box = h.getBlockEntity(MASTER, JukeboxBlockEntity.class);
        h.getBlockEntity(MASTER.above(), HopperBlockEntity.class).setItem(0, new ItemStack(Items.MUSIC_DISC_CAT));
        h.runAtTickTime(12, () -> {
            check(box.getTheItem().isEmpty() && !network(h).hasSession(master(h)), "A locked hopper cannot start playback");
            h.setBlock(MASTER.above().east(), Blocks.AIR);
        });
        h.runAtTickTime(30, () -> {
            check(box.getSongPlayer().isPlaying() && network(h).sources(master(h)).equals(java.util.Set.of(master(h))),
                    "Unlocking the hopper starts coordinated playback without speakers");
            h.setBlock(MASTER.below(), Blocks.HOPPER);
            var song = h.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                    .get(Identifier.withDefaultNamespace("cat")).orElseThrow();
            box.getSongPlayer().setSongWithoutPlaying(song, song.value().lengthInTicks() + 19);
            box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
            box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
            check(network(h).isNaturalTail(master(h)), "Standalone completion retains cancellable audio tails");
        });
        h.runAtTickTime(50, () -> {
            check(h.getBlockEntity(MASTER.below(), HopperBlockEntity.class).getItem(0).is(Items.MUSIC_DISC_CAT),
                    "The lower hopper extracts the finished standalone disc");
            check(!network(h).hasSession(master(h)), "Hopper extraction cancels standalone audio tails");
            h.succeed();
        });
    }
    @GameTest(structure = ARENA) public void standaloneAllVanillaDiscsUseCoordinatedPlayback(GameTestHelper h) {
        h.setBlock(MASTER, Blocks.JUKEBOX);
        var box = h.getBlockEntity(MASTER, JukeboxBlockEntity.class);
        int tested = 0;
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals("minecraft") || !id.getPath().startsWith("music_disc_")) continue;
            box.setTheItem(new ItemStack(item));
            check(box.getSongPlayer().isPlaying() && network(h).sources(master(h)).equals(java.util.Set.of(master(h))),
                    "Every vanilla disc coordinates standalone playback: " + id);
            box.popOutTheItem();
            tested++;
        }
        check(tested == 22, "Exercise all 22 standalone vanilla discs");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void disconnectReconnectAndNewSpeakers(GameTestHelper h) {
        var box = setup(h); insert(box);
        h.setBlock(WIRE, Blocks.AIR); network(h).tick();
        check(network(h).sources(master(h)).size() == 1, "Disconnected speakers must stop promptly");
        h.setBlock(WIRE, CopperSpeakers.WIRE);
        h.setBlock(4, 2, 6, CopperSpeakers.SPEAKER); network(h).tick();
        check(network(h).sources(master(h)).size() == 4, "Reconnections and new speakers must take prestarted voices");
        check(network(h).spareVoiceCount(master(h)) == 1, "Three joins consume three spare voices");
        box.popOutTheItem(); insert(box);
        check(network(h).sources(master(h)).size() == 4, "Next playback must include all connected speakers");
        h.setBlock(SPEAKER, Blocks.AIR); network(h).tick();
        check(network(h).sources(master(h)).size() == 3, "Broken speaker must stop individually");
        h.setBlock(MASTER, Blocks.AIR);
        check(!network(h).hasSession(master(h)), "Destroyed master must stop everything");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void spareCapacityRecyclingAndNaturalCleanup(GameTestHelper h) {
        var box = setup(h); insert(box);
        var net = network(h); var root = master(h);
        var initial = net.spareEntityIds(root);
        check(initial.size() == 4, "Four spare voices per playback");
        // Expand the wire route and attach five new speakers without reinserting.
        for (int x = 3; x <= 7; x++) {
            h.setBlock(x, 2, 6, CopperSpeakers.WIRE);
            h.setBlock(x, 2, 7, CopperSpeakers.SPEAKER);
        }
        net.tick();
        check(net.sources(root).size() == 7 && net.spareVoiceCount(root) == 0, "Only four extra speakers can join");
        var bound = net.sources(root).stream().filter(p -> net.speakerVoiceId(root, p) != -1).findFirst().orElseThrow();
        var waiting = net.component(root).speakers().stream().filter(p -> !net.sources(root).contains(p)).findFirst().orElseThrow();
        int reusedId = net.speakerVoiceId(root, bound);
        h.getLevel().setBlockAndUpdate(bound, Blocks.AIR.defaultBlockState()); net.tick();
        check(net.speakerVoiceId(root, waiting) == reusedId, "A waiting speaker must reuse the freed, already-playing entity");
        check(net.spareEntityIds(root).equals(initial), "Reassignment must never allocate another sound anchor");
        h.getLevel().setBlockAndUpdate(waiting, Blocks.AIR.defaultBlockState()); net.tick();
        check(net.spareVoiceCount(root) == 1, "Removing a bound speaker parks its voice");
        net.finish(root, true);
        check(net.spareEntityIds(root).size() == 3 && net.spareVoiceCount(root) == 0,
                "Natural completion retires idle voices but preserves three bound tails");
        h.getLevel().setBlockAndUpdate(waiting, CopperSpeakers.SPEAKER.defaultBlockState()); net.tick();
        check(!net.sources(root).contains(waiting), "No joining after the server declares natural completion");
        box.popOutTheItem();
        check(net.spareEntityIds(root).isEmpty(), "Ejection destroys all spare voices");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void mergedPlayingNetworksAssignNewSpeakersOnce(GameTestHelper h) {
        var first = setup(h); insert(first);
        BlockPos other = new BlockPos(2,2,8), original = new BlockPos(6,2,8), added = new BlockPos(4,2,7);
        h.setBlock(other, Blocks.JUKEBOX);
        for (int x = 3; x <= 5; x++) h.setBlock(x,2,8,CopperSpeakers.WIRE);
        h.setBlock(original, CopperSpeakers.SPEAKER);
        var second = h.getBlockEntity(other, JukeboxBlockEntity.class); insert(second);
        h.setBlock(3,2,6,CopperSpeakers.WIRE); h.setBlock(3,2,7,CopperSpeakers.WIRE);
        h.setBlock(added,CopperSpeakers.SPEAKER); network(h).tick();
        check(network(h).sources(master(h)).contains(h.absolutePos(added)), "Earlier active master owns new speaker on merge");
        check(!network(h).sources(h.absolutePos(other)).contains(h.absolutePos(added)), "New speaker cannot receive conflicting voices");
        check(network(h).sources(h.absolutePos(other)).contains(h.absolutePos(original)), "Independent playback keeps its original speaker");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void brokenInsulationDropsOneResin(GameTestHelper h) {
        setup(h);
        var level = h.getLevel();
        for (int i = 0; i < 3; i++) {
            var pos = h.absolutePos(new BlockPos(2 + 3 * i, 2, 2));
            var state = CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, i != 0)
                    .setValue(CopperWireBlock.WATERLOGGED, i == 2);
            level.setBlockAndUpdate(pos, state);
            level.destroyBlock(pos, true);
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(pos).inflate(.5));
            int wire = drops.stream().filter(e -> e.getItem().is(CopperSpeakers.WIRE.asItem())).mapToInt(e -> e.getItem().getCount()).sum();
            int resin = drops.stream().filter(e -> e.getItem().is(Items.RESIN_CLUMP)).mapToInt(e -> e.getItem().getCount()).sum();
            check(wire == 1 && resin == (i == 0 ? 0 : 1), "Breaking dry/wet insulation returns exactly one resin plus wire; bare wire returns no resin");
        }
        h.succeed();
    }
    @GameTest(structure = ARENA) public void firstActiveOwnershipAndCycles(GameTestHelper h) {
        var first = setup(h);
        for (int x = 3; x <= 5; x++) h.setBlock(x, 2, 6, CopperSpeakers.WIRE);
        BlockPos other = new BlockPos(5, 2, 7);
        h.setBlock(other, Blocks.JUKEBOX);
        var second = h.getBlockEntity(other, JukeboxBlockEntity.class);
        insert(first); insert(second);
        check(network(h).sources(master(h)).size() == 3, "First owner retains speakers through a cycle");
        check(network(h).sources(h.absolutePos(other)).isEmpty(), "Second master must not conflict");
        first.popOutTheItem();
        check(network(h).sources(h.absolutePos(other)).isEmpty(), "Waiting disc must not join automatically");
        second.popOutTheItem(); insert(second);
        check(network(h).sources(h.absolutePos(other)).size() == 3, "Reinsert claims released circuit");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void chunkUnloadAndReloadDoNotRestartAudio(GameTestHelper h) {
        var box = setup(h); insert(box);
        var chunk = ChunkPos.containing(master(h));
        network(h).unload(chunk);
        check(!network(h).hasSession(master(h)), "Master chunk unload must stop session");
        network(h).load(chunk); network(h).tick();
        check(!network(h).hasSession(master(h)), "Loading a chunk must not seek or restart playback");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void savedJukeboxDoesNotRestartSpeakers(GameTestHelper h) {
        var box = setup(h); insert(box);
        var tag = box.saveWithoutMetadata(h.getLevel().registryAccess());
        network(h).stop(master(h));
        JukeboxBlockEntity restored = new JukeboxBlockEntity(master(h), box.getBlockState());
        restored.setLevel(h.getLevel());
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        check(restored.getTheItem().is(Items.MUSIC_DISC_CAT), "Disc must persist across restart");
        check(!network(h).hasSession(master(h)), "Deserialization must not create audio sessions");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void resinConsumptionAndWaterlogging(GameTestHelper h) {
        setup(h);
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        var resin = new ItemStack(Items.RESIN_CLUMP, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, resin);
        var pos = h.absolutePos(WIRE);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        h.getLevel().getBlockState(pos).useItemOn(resin, h.getLevel(), player, InteractionHand.MAIN_HAND, hit);
        var insulated = h.getLevel().getBlockState(pos);
        check(insulated.getValue(CopperWireBlock.INSULATED), "Resin must insulate placed wire");
        check(resin.getCount() == 1, "Survival resin consumes exactly one");
        insulated.useItemOn(resin, h.getLevel(), player, InteractionHand.MAIN_HAND, hit);
        check(resin.getCount() == 1, "Reapplying must not consume resin");
        CopperSpeakers.WIRE.placeLiquid(h.getLevel(), pos, insulated, Fluids.WATER.getSource(false));
        check(h.getLevel().getBlockState(pos).is(CopperSpeakers.WIRE), "Insulated wire survives water");
        check(h.getLevel().getBlockState(pos).getValue(CopperWireBlock.WATERLOGGED), "Water is retained");
        check(!CopperSpeakers.WIRE.pickupBlock(player, h.getLevel(), pos, h.getLevel().getBlockState(pos)).isEmpty(), "Water can be bucketed out");
        check(!h.getLevel().getBlockState(pos).getValue(CopperWireBlock.WATERLOGGED), "Bucket dries the wire");
        var raw = h.absolutePos(new BlockPos(4, 2, 5));
        CopperSpeakers.WIRE.placeLiquid(h.getLevel(), raw, h.getLevel().getBlockState(raw), Fluids.WATER.getSource(false));
        check(h.getLevel().getBlockState(raw).is(Blocks.WATER), "Bare wire washes away");
        var creative = h.makeMockServerPlayer(GameType.CREATIVE);
        var remaining = h.absolutePos(new BlockPos(5, 2, 5));
        var free = new ItemStack(Items.RESIN_CLUMP, 2);
        creative.setItemInHand(InteractionHand.MAIN_HAND, free);
        h.getLevel().getBlockState(remaining).useItemOn(free, h.getLevel(), creative, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(remaining), Direction.UP, remaining, false));
        check(free.getCount() == 2, "Creative resin is not consumed");
        h.succeed();
    }
    @GameTest(structure = ARENA, maxTicks = 100) public void hopperInsertionAndExtraction(GameTestHelper h) {
        var box = setup(h);
        h.setBlock(MASTER.above(), Blocks.HOPPER);
        var input = h.getBlockEntity(MASTER.above(), HopperBlockEntity.class);
        input.setItem(0, new ItemStack(Items.MUSIC_DISC_CAT));
        h.runAtTickTime(12, () -> {
            check(box.getTheItem().is(Items.MUSIC_DISC_CAT), "Hopper inserts disc");
            check(network(h).sources(master(h)).size() == 3, "Hopper insertion starts group");
            h.setBlock(MASTER.below(), Blocks.HOPPER);
        });
        h.runAtTickTime(20, () -> {
            check(box.getTheItem().is(Items.MUSIC_DISC_CAT), "Playing jukebox powers and locks hopper below");
            var song = h.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                    .get(Identifier.withDefaultNamespace("cat")).orElseThrow();
            box.getSongPlayer().setSongWithoutPlaying(song, song.value().lengthInTicks() + 19);
            box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
            box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
        });
        h.runAtTickTime(30, () -> {
            var output = h.getBlockEntity(MASTER.below(), HopperBlockEntity.class);
            check(output.getItem(0).is(Items.MUSIC_DISC_CAT), "Hopper extracts disc using vanilla rules");
            check(!network(h).hasSession(master(h)), "Hopper extraction stops group");
            h.succeed();
        });
    }
    @GameTest(structure = ARENA) public void dryCableClientOutlineStaysNearTheFloor(GameTestHelper h) {
        setup(h);
        var pos = h.absolutePos(WIRE);
        for (boolean insulated : new boolean[]{false, true}) for (int mask = 0; mask < 16; mask++) {
            var state = CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, insulated);
            for (int i = 0; i < 4; i++) state = state.setValue(CopperWireBlock.PORTS[i], (mask & (1 << i)) != 0);
            var client = CopperSpeakers.WIRE.getPolymerBlockState(state, null);
            var shape = client.getShape(h.getLevel(), pos);
            check(shape.max(Direction.Axis.Y) <= 2.5 / 16.0, "Every dry client outline must stay near the floor");
            check(shape.clip(Vec3.atLowerCornerOf(pos).add(-1, 0.5, 0.5),
                    Vec3.atLowerCornerOf(pos).add(2, 0.5, 0.5), pos) == null,
                    "Looking through the air above a cable must not select it");
            check(client.getCollisionShape(h.getLevel(), pos).isEmpty(), "Client carrier must not block walking");
            check(!client.is(BlockTags.CLIMBABLE), "Cable must not act as a client-side vine ladder");
        }
        for (int mask = 0; mask < 16; mask++) {
            var wet = CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, true)
                    .setValue(CopperWireBlock.WATERLOGGED, true);
            for (int i = 0; i < 4; i++) wet = wet.setValue(CopperWireBlock.PORTS[i], (mask & (1 << i)) != 0);
            var client = CopperSpeakers.WIRE.getPolymerBlockState(wet, null);
            check(client.getFluidState().isSourceOfType(Fluids.WATER), "Wet client carrier must retain water");
            check(client.getCollisionShape(h.getLevel(), pos).isEmpty(), "Waterlogged cable must not block swimming");
        }
        h.succeed();
    }
    @GameTest(structure = ARENA) public void resinBesideCableKeepsClientModel(GameTestHelper h) {
        setup(h);
        var pos = h.absolutePos(WIRE);
        var resin = Blocks.RESIN_CLUMP.defaultBlockState().setValue(BlockStateProperties.DOWN, true);
        for (boolean insulated : new boolean[]{false, true}) for (int mask = 0; mask < 16; mask++) {
            var state = CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, insulated);
            for (int i = 0; i < 4; i++) state = state.setValue(CopperWireBlock.PORTS[i], (mask & (1 << i)) != 0);
            var client = CopperSpeakers.WIRE.getPolymerBlockState(state, null);
            for (var direction : CopperWireBlock.DIRECTIONS) {
                var updated = client.updateShape(h.getLevel(), h.getLevel(), pos, direction,
                        pos.relative(direction), resin, h.getLevel().getRandom());
                check(updated == client, "Placing resin beside cable must not change its predicted client model: "
                        + insulated + "/" + mask + "/" + direction + " " + client + " -> " + updated);
            }
        }
        h.succeed();
    }
    @GameTest(structure = ARENA) public void resinFloorSurvivesUpdatesAndFollowsWaterlogging(GameTestHelper h) {
        setup(h);
        var level = h.getLevel();
        var pos = h.absolutePos(WIRE);
        level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(CopperWireBlock.INSULATED, true));
        var holder = BlockBoundAttachment.get(level, pos).holder();
        check(holder.getElements().size() == 1, "Dry insulated cable has one floor display");
        var floor = (ItemDisplayElement) holder.getElements().getFirst();
        level.setBlockAndUpdate(pos.north(), Blocks.RESIN_CLUMP.defaultBlockState().setValue(BlockStateProperties.DOWN, true));
        check(level.getBlockState(pos).getValue(CopperWireBlock.INSULATED), "Nearby resin preserves insulation");
        check(holder.getElements().getFirst() == floor, "Nearby resin must not recreate the floor display");
        level.setBlockAndUpdate(pos.east(), Blocks.AIR.defaultBlockState());
        network(h).tick();
        check(holder.getElements().getFirst() == floor, "Connection changes update the existing floor display");
        check(floor.getItem().get(DataComponents.ITEM_MODEL).equals(CopperSpeakers.id("insulated_wire_"
                + CopperWireBlock.connectionMask(level.getBlockState(pos)))), "Floor model follows updated connections");
        CopperSpeakers.WIRE.placeLiquid(level, pos, level.getBlockState(pos), Fluids.WATER.getSource(false));
        check(holder.getElements().isEmpty(), "Waterlogged cable uses its water-rendering block model without a duplicate floor");
        CopperSpeakers.WIRE.pickupBlock(null, level, pos, level.getBlockState(pos));
        check(holder.getElements().size() == 1, "Draining cable restores its resin floor");
        level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(CopperWireBlock.INSULATED, false));
        check(holder.getElements().isEmpty(), "Stripping resin removes its floor display");
        h.succeed();
    }
    @GameTest(structure = ARENA) public void axeStripsResinWithoutDisconnectingMusic(GameTestHelper h) {
        var box = setup(h); insert(box);
        var pos = h.absolutePos(WIRE);
        var before = h.getLevel().getBlockState(pos).setValue(CopperWireBlock.INSULATED, true);
        h.getLevel().setBlockAndUpdate(pos, before);
        var player = h.makeMockServerPlayer(GameType.SURVIVAL);
        var axe = new ItemStack(Items.IRON_AXE);
        player.setItemInHand(InteractionHand.MAIN_HAND, axe);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        before.useItemOn(axe, h.getLevel(), player, InteractionHand.MAIN_HAND, hit);
        var after = h.getLevel().getBlockState(pos);
        check(after.is(CopperSpeakers.WIRE) && !after.getValue(CopperWireBlock.INSULATED), "Stripping keeps bare wire");
        for (var port : CopperWireBlock.PORTS) check(after.getValue(port).equals(before.getValue(port)), "Stripping preserves connections");
        check(axe.getDamageValue() == 1, "Survival stripping uses one axe durability");
        after.useItemOn(axe, h.getLevel(), player, InteractionHand.MAIN_HAND, hit);
        check(axe.getDamageValue() == 1, "Bare wire does not consume tool durability");
        network(h).tick();
        check(network(h).sources(master(h)).size() == 3 && box.getSongPlayer().isPlaying(), "Stripping must preserve active music");
        var wet = before.setValue(CopperWireBlock.WATERLOGGED, true);
        h.getLevel().setBlockAndUpdate(pos, wet);
        // The basic mock has no network connection; message delivery is a client integration concern.
        var swimmer = h.makeMockPlayer(GameType.SURVIVAL);
        swimmer.setItemInHand(InteractionHand.MAIN_HAND, axe);
        wet.useItemOn(axe, h.getLevel(), swimmer, InteractionHand.MAIN_HAND, hit);
        check(h.getLevel().getBlockState(pos).equals(wet) && axe.getDamageValue() == 1,
                "Wet wire must reject stripping without destroying cable, water or tool durability");
        CopperSpeakers.WIRE.pickupBlock(player, h.getLevel(), pos, wet);
        var creative = h.makeMockServerPlayer(GameType.CREATIVE);
        var free = new ItemStack(Items.IRON_AXE);
        creative.setItemInHand(InteractionHand.MAIN_HAND, free);
        h.getLevel().getBlockState(pos).useItemOn(free, h.getLevel(), creative, InteractionHand.MAIN_HAND, hit);
        check(!h.getLevel().getBlockState(pos).getValue(CopperWireBlock.INSULATED), "Drained wire can be stripped");
        check(free.getDamageValue() == 0, "Creative stripping does not damage tools");
        h.succeed();
    }
    @GameTest(structure = ARENA, maxTicks = 100) public void flowingWaterPreservesOnlyInsulatedWire(GameTestHelper h) {
        setup(h);
        // A closed basin prevents vanilla flow from choosing only a nearer platform edge.
        for (int i = 1; i <= 9; i++) {
            h.setBlock(i, 2, 1, Blocks.STONE); h.setBlock(i, 2, 9, Blocks.STONE);
            h.setBlock(1, 2, i, Blocks.STONE); h.setBlock(9, 2, i, Blocks.STONE);
        }
        h.setBlock(3, 2, 3, CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, true));
        h.setBlock(5, 2, 3, CopperSpeakers.WIRE);
        h.setBlock(4, 2, 3, Blocks.WATER);
        h.runAtTickTime(35, () -> {
            h.assertBlockPresent(CopperSpeakers.WIRE, new BlockPos(3,2,3));
            h.assertBlockProperty(new BlockPos(3,2,3), CopperWireBlock.WATERLOGGED, false);
            h.assertBlockNotPresent(CopperSpeakers.WIRE, new BlockPos(5,2,3));
            h.succeed();
        });
    }
    @GameTest(structure = ARENA) public void flowingWaterCannotBecomeStoredSources(GameTestHelper h) {
        setup(h);
        var level = h.getLevel();
        var pos = h.absolutePos(WIRE);
        var insulated = CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, true);
        for (boolean falling : new boolean[]{false, true}) for (int amount = falling ? 8 : 1; amount <= (falling ? 8 : 7); amount++) {
            var flow = Fluids.FLOWING_WATER.getFlowing(amount, falling);
            level.setBlockAndUpdate(pos, insulated);
            var state = level.getBlockState(pos);
            h.assertTrue(!CopperSpeakers.WIRE.placeLiquid(level, pos, state, flow),
                    "Insulated wire must reject non-source water: " + flow);
            h.assertTrue(level.getFluidState(pos).isEmpty(), "Rejected flow must not leave stored water");
            h.assertTrue(CopperSpeakers.WIRE.pickupBlock(null, level, pos, level.getBlockState(pos)).isEmpty(),
                    "Flowing water must not create a bucketable source");
            h.assertTrue(!CopperSpeakers.WIRE.canPlaceLiquid(null, level, pos, state, flow.getType()),
                    "Insulated wire must not advertise acceptance of flowing water");

            level.setBlockAndUpdate(pos, CopperSpeakers.WIRE.defaultBlockState());
            var bare = level.getBlockState(pos);
            h.assertTrue(CopperSpeakers.WIRE.canPlaceLiquid(null, level, pos, bare, flow.getType()),
                    "Flowing water must still enter bare wire");
            h.assertTrue(CopperSpeakers.WIRE.placeLiquid(level, pos, bare, flow), "Bare wire must wash away");
            h.assertTrue(level.getBlockState(pos).is(Blocks.WATER) && level.getFluidState(pos).equals(flow),
                    "Washed-away bare wire must preserve the incoming flow level and falling state");
        }
        h.succeed();
    }
    @GameTest(structure = ARENA, maxTicks = 150) public void oneBucketOnResinWireDoesNotMultiply(GameTestHelper h) {
        var level = h.getLevel();
        for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++) {
            h.setBlock(x, 1, z, Blocks.STONE);
            if (x == 1 || x == 9 || z == 1 || z == 9) h.setBlock(x, 2, z, Blocks.STONE);
        }
        var insulated = CopperSpeakers.WIRE.defaultBlockState().setValue(CopperWireBlock.INSULATED, true);
        for (int x = 2; x <= 8; x++) h.setBlock(x, 2, 5, insulated);
        var source = h.absolutePos(new BlockPos(2, 2, 5));
        h.assertTrue(((BucketItem) Items.WATER_BUCKET).emptyContents(null, level, source, null),
                "A water bucket must still waterlog insulated wire");
        h.assertTrue(level.getFluidState(source).isSource(), "The poured bucket must create one source");
        h.runAtTickTime(45, () -> {
            int sources = 0;
            boolean hasFlow = false;
            for (int x = 2; x <= 8; x++) for (int z = 2; z <= 8; z++) {
                var fluid = level.getFluidState(h.absolutePos(new BlockPos(x, 2, z)));
                if (fluid.isSource()) sources++;
                if (!fluid.isEmpty() && !fluid.isSource()) hasFlow = true;
            }
            h.assertTrue(sources == 1, "One bucket on a resin wire row must remain one source, found " + sources);
            h.assertTrue(hasFlow, "The original source must still spread water around the cable");
            for (int x = 2; x <= 8; x++) h.assertBlockPresent(CopperSpeakers.WIRE, new BlockPos(x, 2, 5));
            h.assertTrue(CopperSpeakers.WIRE.pickupBlock(null, level, source, level.getBlockState(source)).is(Items.WATER_BUCKET),
                    "The original source must be recoverable with a bucket");
        });
        h.runAtTickTime(130, () -> {
            for (int x = 2; x <= 8; x++) for (int z = 2; z <= 8; z++) {
                h.assertTrue(level.getFluidState(h.absolutePos(new BlockPos(x, 2, z))).isEmpty(),
                        "Removing the original source must drain the basin");
            }
            for (int x = 2; x <= 8; x++) {
                h.assertBlockPresent(CopperSpeakers.WIRE, new BlockPos(x, 2, 5));
                h.assertBlockProperty(new BlockPos(x, 2, 5), CopperWireBlock.WATERLOGGED, false);
            }
            h.succeed();
        });
    }
}
