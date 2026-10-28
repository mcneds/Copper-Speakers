package dev.mcneds.copperspeakers.network;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class PreparedSongsTest {
    @TempDir Path root;
    private static final String SOUND = "example:evening";
    private static final class Backend implements PreparedSongs.Backend {
        Map<String, PreparedSongs.Setting> settings = Map.of();
        final Map<String, Path> installed = new HashMap<>();
        int installs;
        boolean playing, downloading;
        public void settings(Map<String, PreparedSongs.Setting> value) { settings = value; }
        public boolean available(String sound) { return installed.containsKey(sound); }
        public void install(String sound, Path path) { installed.put(sound, path); installs++; }
        public boolean retire(String sound, Path path) {
            if (playing || downloading) return false;
            installed.remove(sound, path); return true;
        }
        public Map<String, Object> details(String sound) { return Map.of(); }
        public Map<String, Object> events() { return Map.of(); }
    }
    private PreparedSongs.Setting setting(String revision, boolean enabled, double spacing, boolean delete) throws Exception {
        Path source = root.resolve("original.ogg");
        if (!Files.exists(source)) Files.writeString(source, "published original");
        return new PreparedSongs.Setting(revision, enabled, source.toString(), spacing, delete);
    }
    private void write(PreparedSongs.Setting setting) throws Exception {
        PreparedSongs.atomicJson(root.resolve("settings.json"), Map.of("format", 1, "tracks", Map.of(SOUND, setting)));
    }
    private String state() throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve("status.json"))).getAsJsonObject()
                .getAsJsonObject("tracks").getAsJsonObject(SOUND).get("state").getAsString();
    }
    private static void output(Path output) throws Exception {
        Files.createDirectories(output);
        Files.writeString(output.resolve("catalog.json"), "{}");
    }
    private static void until(PreparedSongs control, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            control.poll();
            Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), "Background preparation did not finish");
    }
    @Test void enablingPreparesOnlySelectedSongOffTheControlThread() throws Exception {
        var backend = new Backend();
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> {
            assertEquals(SOUND, sound);
            assertEquals(15, setting.spacingSeconds());
            entered.countDown(); finish.await(); output(out);
        })) {
            write(setting("a", true, 15, false)); control.poll();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals("preparing", state());
            assertTrue(backend.installed.isEmpty());
            finish.countDown(); until(control, () -> backend.installs == 1);
            assertEquals("ready", state());
            assertEquals(Set.of(SOUND), backend.installed.keySet());
        } finally { finish.countDown(); }
    }
    @Test void staleSuccessfulCompletionCannotActivateAfterDisable() throws Exception {
        var backend = new Backend();
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> {
            entered.countDown();
            // Simulate a subprocess completing just as cancellation arrives.
            boolean done = false;
            while (!done) try { finish.await(); done = true; } catch (InterruptedException ignored) { }
            output(out);
        })) {
            write(setting("a", true, 15, false)); control.poll(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            write(setting("b", false, 15, false)); control.poll(); finish.countDown();
            until(control, () -> {
                // Observe the consumed completion, not the worker's changing temporary files.
                try { return JsonParser.parseString(Files.readString(root.resolve("status.json"))).getAsJsonObject()
                        .getAsJsonObject("tracks").getAsJsonObject(SOUND).get("preparedVersions").getAsInt() == 1; }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            control.poll();
            assertEquals(0, backend.installs);
            assertFalse(backend.settings.get(SOUND).enabled());
            assertEquals("disabled", state());
        } finally { finish.countDown(); }
    }
    @Test void failureKeepsExistingAudioAndRetriesOnlyWithNewRevision() throws Exception {
        var backend = new Backend(); backend.installed.put(SOUND, root.resolve("legacy"));
        var count = new java.util.concurrent.atomic.AtomicInteger();
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> {
            count.incrementAndGet(); throw new java.io.IOException("codec failed");
        })) {
            write(setting("a", true, 15, false)); control.poll();
            until(control, () -> {
                try { return state().equals("failed"); } catch (Exception e) { return false; }
            });
            control.poll(); assertEquals(1, count.get());
            assertEquals(root.resolve("legacy"), backend.installed.get(SOUND));
            write(setting("b", true, 15, false)); control.poll();
            until(control, () -> count.get() == 2);
        }
    }
    @Test void deletionWaitsForBothPlaybackAndDownloadsAndCanBeCancelled() throws Exception {
        var backend = new Backend();
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> output(out))) {
            write(setting("a", true, 15, false)); control.poll(); until(control, () -> backend.installs == 1);
            Path prepared = backend.installed.get(SOUND);
            backend.playing = true; backend.downloading = true;
            write(setting("b", false, 15, true)); control.poll();
            assertEquals("deletion_pending", state()); assertTrue(Files.exists(prepared));
            backend.playing = false; control.poll(); assertTrue(Files.exists(prepared));
            write(setting("c", true, 15, false)); control.poll();
            assertEquals("ready", state()); assertTrue(Files.exists(prepared));
            write(setting("d", false, 15, true)); control.poll();
            backend.downloading = false; control.poll();
            assertFalse(Files.exists(prepared)); assertEquals("disabled", state());
        }
    }
    @Test void completedAudioSurvivesRestartAndSourceChangesRequirePreparation() throws Exception {
        var backend = new Backend();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        PreparedSongs.Preparer prepare = (sound, setting, out) -> { count.incrementAndGet(); output(out); };
        write(setting("a", true, 15, false));
        try (var control = new PreparedSongs(root, backend, prepare)) {
            control.poll(); until(control, () -> backend.installs == 1);
        }
        backend.installed.clear();
        try (var control = new PreparedSongs(root, backend, prepare)) {
            control.poll(); assertTrue(backend.available(SOUND)); assertEquals(1, count.get());
            Files.writeString(root.resolve("original.ogg"), "new published recording");
            write(setting("b", true, 15, false)); control.poll();
            until(control, () -> count.get() == 2);
        }
    }
    @Test void malformedSettingsKeepTheLastAcceptedConfiguration() throws Exception {
        var backend = new Backend();
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> output(out))) {
            var disabled = setting("a", false, 15, false);
            write(disabled); control.poll();
            Files.writeString(root.resolve("settings.json"), "{"); control.poll();
            assertEquals(disabled, backend.settings.get(SOUND));
            assertTrue(Files.readString(root.resolve("status.json")).contains("settingsError"));
        }
    }
    @Test void changingSpacingDuringPreparationActivatesOnlyTheLatestRequest() throws Exception {
        var backend = new Backend();
        var entered = new CountDownLatch(1);
        var oldFinished = new CountDownLatch(1);
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> {
            if (setting.spacingSeconds() == 15) {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                finally { oldFinished.countDown(); }
            }
            output(out);
        })) {
            write(setting("a", true, 15, false)); control.poll(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            write(setting("b", true, 5, false)); control.poll();
            assertTrue(oldFinished.await(3, TimeUnit.SECONDS));
            until(control, () -> backend.installs == 1);
            assertEquals(5, backend.settings.get(SOUND).spacingSeconds());
            assertEquals("ready", state());
            try (var files = Files.list(root.resolve("songs"))) { assertEquals(1, files.count()); }
        }
    }
    @Test void replacingTheSourceDuringPreparationCannotPublishMismatchedAudio() throws Exception {
        var backend = new Backend();
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> {
            output(out);
            Files.writeString(Path.of(setting.source()), "replacement published during conversion");
        })) {
            write(setting("a", true, 15, false)); control.poll();
            until(control, () -> {
                try { return state().equals("failed"); } catch (Exception e) { return false; }
            });
            assertEquals(0, backend.installs);
            assertTrue(Files.readString(root.resolve("status.json")).contains("Source changed during preparation"));
        }
    }
    @Test void missingSpacingDefaultsToFifteenAndConflictingDeleteIsRejected() throws Exception {
        Files.writeString(root.resolve("settings.json"), "{\"format\":1,\"tracks\":{\"example:evening\":{\"revision\":\"a\",\"enabled\":false}}}");
        assertEquals(15, PreparedSongs.readSettings(root.resolve("settings.json")).get(SOUND).spacingSeconds());
        assertThrows(IllegalArgumentException.class, () -> new PreparedSongs.Setting("b", true, null, 15, true));
    }
    @Test void restartDiscardsIncompleteOwnedOutputs() throws Exception {
        Path partial = root.resolve("songs/interrupted-job");
        Files.createDirectories(partial);
        Files.writeString(partial.resolve("audio.zip"), "partial output");
        try (var control = new PreparedSongs(root, new Backend(), (sound, setting, out) -> output(out))) {
            assertFalse(Files.exists(partial));
        }
    }
    @Test void commandsPreserveOtherSongsAndReuseSavedSourceAndSpacing() throws Exception {
        var backend = new Backend();
        var original = setting("original", false, 5, true);
        var other = new PreparedSongs.Setting("other", false, null, 30, false);
        PreparedSongs.atomicJson(root.resolve("settings.json"), Map.of("format", 1, "tracks",
                Map.of(SOUND, original, "example:other", other)));
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> output(out))) {
            var enabled = control.setEnabled(SOUND, true, null).get(5, TimeUnit.SECONDS);
            assertTrue(enabled.enabled());
            assertFalse(enabled.deletePrepared());
            assertEquals(original.source(), enabled.source());
            assertEquals(5, enabled.spacingSeconds());
            assertNotEquals(original.revision(), enabled.revision());
            until(control, () -> backend.installs == 1);
            assertEquals("ready", control.status(SOUND).get(5, TimeUnit.SECONDS).state());
            var disabled = control.setEnabled(SOUND, false, null).get(5, TimeUnit.SECONDS);
            assertFalse(disabled.enabled());
            assertEquals(enabled.source(), disabled.source());
            assertEquals("disabled", control.status(SOUND).get(5, TimeUnit.SECONDS).state());
            assertEquals(other, PreparedSongs.readSettings(root.resolve("settings.json")).get("example:other"));
            control.setEnabled(SOUND, true, null).get(5, TimeUnit.SECONDS);
            assertEquals(2, backend.installs, "Re-enabling installs the reusable generation");
            assertEquals(1, backend.installed.size());
        }
    }
    @Test void commandRejectsMissingAudioAndMalformedSettingsWithoutOverwritingThem() throws Exception {
        try (var control = new PreparedSongs(root, new Backend(), (sound, setting, out) -> output(out))) {
            var missing = assertThrows(ExecutionException.class,
                    () -> control.setEnabled(SOUND, true, null).get(5, TimeUnit.SECONDS));
            assertTrue(missing.getCause().getMessage().contains("No prepared audio"));
            assertFalse(Files.exists(root.resolve("settings.json")));
            assertThrows(ExecutionException.class,
                    () -> control.setEnabled(SOUND, true, "relative.ogg").get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(root.resolve("settings.json")));
            Files.writeString(root.resolve("settings.json"), "{bad input");
            assertThrows(ExecutionException.class,
                    () -> control.setEnabled(SOUND, false, null).get(5, TimeUnit.SECONDS));
            assertEquals("{bad input", Files.readString(root.resolve("settings.json")));
        }
    }
    @Test void commandsSerializeSongUpdatesAndUseExistingCatalogueWithoutSource() throws Exception {
        var backend = new Backend();
        backend.installed.put(SOUND, root.resolve("legacy"));
        try (var control = new PreparedSongs(root, backend, (sound, setting, out) -> fail("Legacy audio needs no preparation"))) {
            var enable = control.setEnabled(SOUND, true, null);
            var disableOther = control.setEnabled("example:other", false, null);
            CompletableFuture.allOf(enable, disableOther).get(5, TimeUnit.SECONDS);
            var settings = PreparedSongs.readSettings(root.resolve("settings.json"));
            assertEquals(Set.of(SOUND, "example:other"), settings.keySet());
            assertTrue(settings.get(SOUND).enabled());
            assertFalse(settings.get("example:other").enabled());
            assertEquals("ready", control.status(SOUND).get(5, TimeUnit.SECONDS).state());
            assertEquals("unprepared", control.status("example:unknown").get(5, TimeUnit.SECONDS).state());
        }
    }
    @Test void commandPreparesNewSourceAndReportsConversionFailure() throws Exception {
        var source = setting("unused", true, 15, false).source();
        try (var control = new PreparedSongs(root, new Backend(), (sound, setting, out) -> {
            throw new java.io.IOException("unsupported recording");
        })) {
            control.setEnabled(SOUND, true, source).get(5, TimeUnit.SECONDS);
            until(control, () -> {
                try { return state().equals("failed"); } catch (Exception e) { return false; }
            });
            var status = control.status(SOUND).get(5, TimeUnit.SECONDS);
            assertEquals("failed", status.state());
            assertTrue(status.error().contains("unsupported recording"));
        }
    }
}
