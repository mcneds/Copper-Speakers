package dev.mcneds.copperspeakers.network;

import dev.mcneds.copperspeakers.CopperSpeakers;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.JukeboxSong;

/** Optional buffered aliases; standard songs without an alias use their original audio. */
public final class BufferedSongs {
    private BufferedSongs() {}

    public static Holder<JukeboxSong> resolve(Registry<JukeboxSong> registry, Holder<JukeboxSong> song) {
        Identifier original = song.unwrapKey().map(k -> k.identifier()).orElse(null);
        if (original == null) return null;
        Identifier expectedOriginal, alias, expectedBuffered;
        if (original.getNamespace().equals("minecraft")) {
            expectedOriginal = Identifier.withDefaultNamespace("music_disc." + original.getPath());
            alias = CopperSpeakers.id("buffered/" + original.getPath());
            expectedBuffered = CopperSpeakers.id("buffered." + original.getPath());
        } else {
            // Any pack can opt in using this convention, independently of its namespace.
            expectedOriginal = original;
            alias = Identifier.fromNamespaceAndPath(original.getNamespace(), "buffered/" + original.getPath());
            expectedBuffered = Identifier.fromNamespaceAndPath(original.getNamespace(), "buffered." + original.getPath());
        }
        if (!song.value().soundEvent().value().location().equals(expectedOriginal)) return null;
        var buffered = registry.get(alias).orElse(null);
        if (buffered == null || !buffered.value().soundEvent().value().location().equals(expectedBuffered)) return null;
        // An outdated pack must not silently substitute different song metadata.
        if (Float.compare(buffered.value().lengthInSeconds(), song.value().lengthInSeconds()) != 0
                || buffered.value().comparatorOutput() != song.value().comparatorOutput()
                || !buffered.value().description().equals(song.value().description())) return null;
        return buffered;
    }
}
