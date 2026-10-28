package dev.mcneds.copperspeakers.bedrock;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Discovers real non-streaming counterparts before packet translation starts. */
final class BufferedSoundAliases {
    private BufferedSoundAliases() {}

    static Map<String, String> load(List<Path> packs, Consumer<String> warning) {
        Map<String, JsonElement> definitions = new HashMap<>();
        for (var pack : packs) {
            try (var archive = new ZipFile(pack.toFile())) {
                var entry = archive.getEntry("sounds/sound_definitions.json");
                if (entry == null) continue;
                try (var reader = new InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8)) {
                    var sounds = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("sound_definitions");
                    // Later packs override earlier definitions, including streamed overrides.
                    if (sounds != null) definitions.putAll(sounds.asMap());
                }
            } catch (Exception e) {
                warning.accept("Cannot discover buffered sound aliases in " + pack.getFileName() + ": " + e.getMessage());
            }
        }
        Map<String, String> aliases = new HashMap<>();
        for (var entry : definitions.entrySet()) {
            String original = entry.getKey();
            if (!original.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || original.contains(":buffered.")) continue;
            int colon = original.indexOf(':');
            String alias = original.substring(0, colon + 1) + "buffered." + original.substring(colon + 1);
            if (sameBufferedRecording(entry.getValue(), definitions.get(alias))) aliases.put(original, alias);
        }
        return Map.copyOf(aliases);
    }

    private static boolean sameBufferedRecording(JsonElement original, JsonElement alias) {
        if (alias == null || !original.isJsonObject() || !alias.isJsonObject()) return false;
        var normal = original.getAsJsonObject().deepCopy();
        var buffered = alias.getAsJsonObject().deepCopy();
        if (!normal.has("sounds") || !normal.get("sounds").isJsonArray()
                || !buffered.has("sounds") || !buffered.get("sounds").isJsonArray()) return false;
        var sounds = buffered.getAsJsonArray("sounds");
        if (sounds.isEmpty()) return false;
        for (var sound : sounds) {
            if (!sound.isJsonObject()) return false;
            var stream = sound.getAsJsonObject().get("stream");
            if (stream == null || !stream.isJsonPrimitive() || !stream.getAsJsonPrimitive().isBoolean()
                    || stream.getAsBoolean()) return false;
        }
        // Keep file names, variants, gain, pitch and category identical. Only the
        // decoding settings may differ between the original and buffered event.
        for (var definition : List.of(normal, buffered)) {
            for (var sound : definition.getAsJsonArray("sounds")) {
                if (!sound.isJsonObject()) return false;
                var file = sound.getAsJsonObject().get("name");
                if (file == null || !file.isJsonPrimitive() || !file.getAsJsonPrimitive().isString()) return false;
                sound.getAsJsonObject().remove("stream");
                sound.getAsJsonObject().remove("preload");
            }
        }
        return normal.equals(buffered);
    }

    static String select(String name, Map<String, String> explicit, Map<String, String> discovered) {
        return explicit.containsKey(name) ? explicit.get(name) : discovered.get(name);
    }
}
