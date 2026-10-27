import com.google.gson.*;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.sounds.JOrbisAudioStream;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Offline verification using Minecraft's actual decoder; no game or playback changes. */
public final class VerifyJoinAudio {
    static float[] decode(List<ZipFile> packs, String event) throws Exception {
        String path = "assets/copper_speakers/sounds/" + event.split(":", 2)[1] + ".ogg";
        for (var pack : packs) {
            var entry = pack.getEntry(path);
            if (entry == null) continue;
            var samples = new FloatArrayList();
            try (var decoder = new JOrbisAudioStream(pack.getInputStream(entry))) {
                if (decoder.getFormat().getChannels() != 1) throw new AssertionError("Expected mono audio");
                while (decoder.readChunk(value -> samples.add(value))) { }
            }
            return samples.toFloatArray();
        }
        throw new FileNotFoundException(path);
    }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var catalogue = JsonParser.parseString(Files.readString(directory.resolve("catalog.json"))).getAsJsonObject();
        List<ZipFile> packs = new ArrayList<>();
        int entries = 0, maxLeadIn = 0;
        try {
            for (var pack : catalogue.getAsJsonArray("packs"))
                packs.add(new ZipFile(directory.resolve(pack.getAsJsonObject().get("file").getAsString()).toFile()));
            for (var row : catalogue.getAsJsonObject("tracks").entrySet()) {
                var track = row.getValue().getAsJsonObject();
                var list = track.getAsJsonArray("entries");
                var original = decode(packs, list.get(0).getAsJsonObject().get("event").getAsString());
                if (original.length != track.get("frames").getAsInt()) throw new AssertionError("Original frame count");
                for (var value : list) {
                    var entry = value.getAsJsonObject();
                    var audio = decode(packs, entry.get("event").getAsString());
                    int offset = entry.get("offsetFrames").getAsInt();
                    int lead = entry.get("decoderLeadInFrames").getAsInt();
                    if (audio.length != original.length - offset) {
                        int inferred = original.length - audio.length;
                        int matching = -1;
                        for (int delta = -4096; delta <= 4096; delta++) {
                            int candidate = offset + delta;
                            int start = Math.min(2048, audio.length / 2), end = Math.min(start + 256, audio.length);
                            if (candidate + start < 0 || candidate + end > original.length) continue;
                            boolean match = true;
                            for (int i = start; i < end; i++) if (audio[i] != original[candidate + i]) { match = false; break; }
                            if (match) { matching = candidate; break; }
                        }
                        throw new AssertionError("Frame count: actual=" + audio.length + " expected=" + (original.length-offset)
                            + " inferredOffset=" + inferred + " matchingOffset=" + matching + " entry=" + entry);
                    }
                    for (int i = lead; i < audio.length; i++)
                        if (audio[i] != original[offset + i]) throw new AssertionError("Audio differs after lead-in: " + entry + " at " + i);
                    entries++;
                    maxLeadIn = Math.max(maxLeadIn, lead);
                }
                System.out.println(row.getKey() + ": all native-decoder offsets and waveforms verified");
            }
        } finally {
            for (var pack : packs) pack.close();
        }
        var report = new JsonObject();
        report.addProperty("status", "complete");
        report.addProperty("decoder", "Minecraft 26.3 JOrbisAudioStream");
        report.addProperty("verifiedEntries", entries);
        report.addProperty("maxDecoderLeadInFrames", maxLeadIn);
        report.addProperty("scope", "Exact native-decoder frame counts and bit-identical decoded samples after the short Vorbis startup lead-in. No playback timing measurement.");
        Files.writeString(directory.resolve("decoder-verification.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
    }
}
