package dev.mcneds.copperspeakers.bedrock;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class BufferedSoundAliasesTest {
    private static final String ORIGINAL = "mineandcraft_music:disc_test";
    private static final String BUFFERED = "mineandcraft_music:buffered.disc_test";
    private static final String DEFINITIONS = """
            {"sound_definitions":{
              "mineandcraft_music:disc_test":{"category":"record","sounds":[{"name":"sounds/music/disc_test","stream":true}]},
              "mineandcraft_music:buffered.disc_test":{"category":"record","sounds":[{"name":"sounds/music/disc_test","stream":false}]},
              "other:evening":{"category":"record","sounds":[{"name":"sounds/music/evening","stream":true}]},
              "other:buffered.evening":{"category":"record","sounds":[{"name":"sounds/music/evening","stream":false}]}
            }}
            """;

    private static void pack(Path file, String definitions) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("sounds/sound_definitions.json"));
            zip.write(definitions.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    static void run() throws Exception {
        Path root = Files.createTempDirectory("copperbedrock-buffered-");
        Path pack = root.resolve("music.mcpack"), override = root.resolve("override.mcpack");
        try {
            pack(pack, DEFINITIONS);
            var warnings = new ArrayList<String>();
            var aliases = BufferedSoundAliases.load(List.of(pack), warnings::add);
            if (!aliases.equals(Map.of(ORIGINAL, BUFFERED, "other:evening", "other:buffered.evening")) || !warnings.isEmpty())
                throw new AssertionError("Wiki and other namespaces must discover the actual buffered definitions: " + aliases);
            if (!BufferedSoundAliases.select(ORIGINAL, Map.of(ORIGINAL, "override:chosen"), aliases).equals("override:chosen"))
                throw new AssertionError("Explicit mappings must override discovery");
            if (BufferedSoundAliases.select("missing:song", Map.of(), aliases) != null
                    || BufferedSoundAliases.select(BUFFERED, Map.of(), aliases) != null)
                throw new AssertionError("Missing and already-buffered sounds must retain their names");

            var extension = new CopperBedrock();
            extension.loadSoundAliases(List.of(pack), warnings::add);
            String event = "copper_speakers:tail/v1/"
                    + HexFormat.of().formatHex(ORIGINAL.getBytes(StandardCharsets.UTF_8))
                    + "/0123456789abcdef01234567/48000/720000";
            var prepared = extension.recording(event);
            if (prepared == null || !prepared.sound().equals(BUFFERED) || prepared.offset() != 15)
                throw new AssertionError("Prepared rejoin must select the buffered recording and preserve its seek offset");
            if (!extension.recording(ORIGINAL).sound().equals(BUFFERED)
                    || !extension.recording(BUFFERED).sound().equals(BUFFERED))
                throw new AssertionError("Normal and already-buffered playback must select the same recording");
            // New songs become available after the packs are reloaded, without an extension rebuild.
            pack(pack, DEFINITIONS.replace("disc_test", "disc_new"));
            extension.loadSoundAliases(List.of(pack), warnings::add);
            String newEvent = event.replace(HexFormat.of().formatHex(ORIGINAL.getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().formatHex("mineandcraft_music:disc_new".getBytes(StandardCharsets.UTF_8)));
            if (!extension.recording(newEvent).sound().equals("mineandcraft_music:buffered.disc_new"))
                throw new AssertionError("New wiki songs require neither manual mappings nor recompilation");
            if (!extension.recording(event).sound().equals(ORIGINAL))
                throw new AssertionError("Removed aliases must fall back to the original recording");

            pack(pack, DEFINITIONS);
            for (String bad : List.of(
                    "{\"category\":\"record\",\"sounds\":[{\"name\":\"sounds/music/disc_test\",\"stream\":true}]}",
                    "{\"category\":\"record\",\"sounds\":[{\"name\":\"sounds/music/different\",\"stream\":false}]}",
                    "{\"category\":\"record\",\"sounds\":[{\"name\":\"sounds/music/disc_test\",\"stream\":\"false\"}]}",
                    "{\"category\":\"record\",\"sounds\":[]}", "null")) {
                pack(override, "{\"sound_definitions\":{\"" + BUFFERED + "\":" + bad + "}}");
                var rejected = BufferedSoundAliases.load(List.of(pack, override), warnings::add);
                if (rejected.containsKey(ORIGINAL) || !rejected.containsKey("other:evening"))
                    throw new AssertionError("An invalid/streamed/different alias override must not be selected: " + bad);
            }
            pack(override, "{broken");
            var recovered = BufferedSoundAliases.load(List.of(pack, override), warnings::add);
            if (!recovered.equals(aliases) || warnings.isEmpty())
                throw new AssertionError("A malformed unrelated pack must not break working aliases");
            System.out.println("PASS: installed pack alias discovery, wiki prepared rejoin offsets, new songs, explicit overrides and safe fallback");
        } finally {
            Files.deleteIfExists(pack); Files.deleteIfExists(override); Files.delete(root);
        }
    }
}
