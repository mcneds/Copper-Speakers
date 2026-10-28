package dev.mcneds.copperspeakers.test;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import dev.mcneds.copperspeakers.network.BufferedSongs;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

public final class CustomDiscGameTests {
    public static final Identifier SONG = Identifier.parse("example_music:disc_a1111111111141118111111111111111");
    public static final Identifier SOUND = Identifier.parse("example_music:buffered." + SONG.getPath());
    private static final String ARENA = "copper_speakers_tests:arena";
    private static final BlockPos MASTER = new BlockPos(2, 2, 5);
    private static final BlockPos WIRE = MASTER.east();
    private static final BlockPos SPEAKER = MASTER.east(2);
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JukeboxBlockEntity setup(GameTestHelper h, boolean connected) {
        for (int x = 1; x < 7; x++) for (int z = 4; z < 7; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(MASTER, Blocks.JUKEBOX);
        if (connected) {
            h.setBlock(WIRE, CopperSpeakers.WIRE);
            h.setBlock(SPEAKER, CopperSpeakers.SPEAKER);
        }
        return h.getBlockEntity(MASTER, JukeboxBlockEntity.class);
    }
    public static ItemStack disc(Holder<JukeboxSong> song) {
        var disc = new ItemStack(Items.MUSIC_DISC_BLOCKS);
        disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song));
        disc.set(DataComponents.ITEM_NAME, song.value().description());
        return disc;
    }
    private static Holder<JukeboxSong> song(GameTestHelper h) {
        return h.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).get(SONG).orElseThrow();
    }

    @GameTest(structure = ARENA) public void customDiscStartsSpeakersAndSupportsReconnection(GameTestHelper h) {
        var box = setup(h, true);
        var network = AudioNetworks.get(h.getLevel());
        var root = h.absolutePos(MASTER);
        var registry = h.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG);
        var song = song(h);
        var alias = BufferedSongs.resolve(registry, song);
        check(alias != null && alias.value().soundEvent().value().location().equals(SOUND), "Resolve custom buffered sound");
        var original = disc(song);
        box.setTheItem(original.copy());
        check(ItemStack.matches(original, box.getTheItem()), "The existing base disc and components must be preserved");
        check(box.getComparatorOutput() == 1, "Custom comparator output is preserved");
        check(network.sources(root).size() == 2 && network.spareVoiceCount(root) == 4, "Custom insertion starts wired sources and spares");
        h.setBlock(WIRE, Blocks.AIR); network.tick();
        check(network.sources(root).size() == 1, "Custom speaker disconnects");
        h.setBlock(WIRE, CopperSpeakers.WIRE); network.tick();
        check(network.speakerVoiceId(root, h.absolutePos(SPEAKER)) != -1, "Reconnection uses an advancing spare");
        box.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
        check(network.sources(root).size() == 2, "A vanilla replacement starts a fresh session");
        box.setTheItem(disc(song));
        check(network.sources(root).size() == 2, "A custom replacement starts a fresh session");
        box.popOutTheItem();
        check(!network.hasSession(root), "Custom ejection stops all sources");
        h.succeed();
    }

    @GameTest(structure = ARENA) public void customNaturalCompletionKeepsCancellableTails(GameTestHelper h) {
        var box = setup(h, true);
        var song = song(h);
        box.setTheItem(disc(song));
        box.getSongPlayer().setSongWithoutPlaying(song, song.value().lengthInTicks() + 19);
        box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
        box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
        var network = AudioNetworks.get(h.getLevel());
        var root = h.absolutePos(MASTER);
        check(!box.getSongPlayer().isPlaying() && network.isNaturalTail(root), "Custom duration finishes with cancellable tails");
        check(network.sources(root).size() == 2, "Natural finish does not truncate buffered custom audio");
        box.popOutTheItem();
        check(!network.hasSession(root), "Ejection cancels custom tails");
        h.succeed();
    }

    @GameTest(structure = ARENA) public void missingAndInvalidAliasesUseOriginalSongsOnSpeakers(GameTestHelper h) {
        var box = setup(h, true);
        var registry = h.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG);
        for (String id : new String[]{"example_music:disc_missing", "example_music:disc_stale",
                "example_music:disc_wrong_alias", "example_music:disc_replaced", "copper_speakers_tests:unknown"}) {
            var song = registry.get(Identifier.parse(id)).orElseThrow();
            check(BufferedSongs.resolve(registry, song) == null, "Unsafe alias rejected: " + id);
            box.setTheItem(disc(song));
            check(box.getSongPlayer().isPlaying(), "Original custom playback remains active: " + id);
            check(AudioNetworks.get(h.getLevel()).sources(h.absolutePos(MASTER)).size() == 2,
                    "The original registered song must reach connected speakers: " + id);
            box.popOutTheItem();
        }
        h.setBlock(WIRE, Blocks.AIR);
        box.setTheItem(disc(song(h)));
        check(box.getSongPlayer().isPlaying()
                        && AudioNetworks.get(h.getLevel()).sources(h.absolutePos(MASTER)).equals(java.util.Set.of(h.absolutePos(MASTER))),
                "Custom songs also coordinate playback without connected speakers");
        box.popOutTheItem();
        h.succeed();
    }

    @GameTest(structure = ARENA) public void anyNamespaceCanProvideAnOptionalBufferedAlias(GameTestHelper h) {
        var box = setup(h, true);
        var registry = h.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG);
        var song = registry.get(Identifier.parse("test_records:evening")).orElseThrow();
        var alias = BufferedSongs.resolve(registry, song);
        check(alias != null && alias.value().soundEvent().value().location().equals(Identifier.parse("test_records:buffered.evening")),
                "A different namespace and song name can opt into buffered playback");
        box.setTheItem(disc(song));
        check(AudioNetworks.get(h.getLevel()).sources(h.absolutePos(MASTER)).size() == 2, "Generic buffered song reaches speakers");
        h.succeed();
    }

    @GameTest(structure = ARENA) public void allVanillaDiscsReachSpeakers(GameTestHelper h) {
        var box = setup(h, true);
        int tested = 0;
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals("minecraft") || !id.getPath().startsWith("music_disc_")) continue;
            box.setTheItem(new ItemStack(item));
            check(box.getSongPlayer().isPlaying(), "Vanilla disc plays: " + id);
            check(AudioNetworks.get(h.getLevel()).sources(h.absolutePos(MASTER)).size() == 2,
                    "Every vanilla disc reaches speakers: " + id);
            box.popOutTheItem();
            tested++;
        }
        check(tested == 22, "Exercise all 22 built-in discs");
        h.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 100) public void hopperPreservesCustomDiscComponents(GameTestHelper h) {
        var box = setup(h, true);
        var original = disc(song(h));
        h.setBlock(MASTER.above(), Blocks.HOPPER);
        h.getBlockEntity(MASTER.above(), HopperBlockEntity.class).setItem(0, original.copy());
        h.runAtTickTime(12, () -> {
            check(ItemStack.matches(original, box.getTheItem()), "Hopper inserts the existing custom disc");
            check(AudioNetworks.get(h.getLevel()).sources(h.absolutePos(MASTER)).size() == 2, "Hopper starts custom speakers");
            h.setBlock(MASTER.below(), Blocks.HOPPER);
            var song = song(h);
            box.getSongPlayer().setSongWithoutPlaying(song, song.value().lengthInTicks() + 19);
            box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
            box.getSongPlayer().tick(h.getLevel(), box.getBlockState());
        });
        h.runAtTickTime(30, () -> {
            check(ItemStack.matches(original, h.getBlockEntity(MASTER.below(), HopperBlockEntity.class).getItem(0)),
                    "Hopper extracts the same base disc, name and custom song");
            check(!AudioNetworks.get(h.getLevel()).hasSession(h.absolutePos(MASTER)), "Hopper extraction stops custom tails");
            h.succeed();
        });
    }
}
