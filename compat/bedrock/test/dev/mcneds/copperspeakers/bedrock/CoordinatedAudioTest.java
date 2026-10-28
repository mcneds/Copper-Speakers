package dev.mcneds.copperspeakers.bedrock;

import java.util.*;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.shaded.org.cloudburstmc.protocol.bedrock.data.sound.*;

/** Deterministic lifecycle tests; no sleeps, Minecraft process, or audio engine. */
final class CoordinatedAudioTest {
    private record Task(long due, long order, Runnable action) {}
    private record Update(long handle, SoundData event, long at) {}
    private static final class Playback {
        float seconds;
        long changed;
        boolean running = true;
        float volume;
        Playback(float seconds, long changed) { this.seconds = seconds; this.changed = changed; }
        float position(long now) { return seconds + (running ? (now - changed) / 1_000_000_000f : 0); }
    }
    private static final class Fixture {
        long now, order;
        final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparingLong(Task::due).thenComparingLong(Task::order));
        final List<Update> updates = new ArrayList<>();
        final List<CoordinatedAudio.Entry> prepared = new ArrayList<>();
        final List<Update> restarted = new ArrayList<>();
        boolean ignoreSeeks;
        boolean ignoreRestartOffsets;
        final List<List<CoordinatedAudio.Update>> groups = new ArrayList<>();
        final Map<Long, Playback> playback = new HashMap<>();
        final CoordinatedAudio audio = new CoordinatedAudio(() -> now,
                (delay, action) -> tasks.add(new Task(now + delay * 1_000_000L, order++, action)),
                new CoordinatedAudio.Output() {
                    public void prepare(CoordinatedAudio.Entry entry, float offset) {
                        prepared.add(entry); playback.put(entry.handle, new Playback(offset, now));
                    }
                    public void restartGroup(List<CoordinatedAudio.Entry> entries, long releaseTime, boolean muted) {
                        for (var entry : entries) update(entry.handle, new StopSoundData());
                        for (var entry : entries) {
                            float offset = entry.clock.position(releaseTime);
                            var voice = new Playback(ignoreRestartOffsets ? 0 : offset, now);
                            voice.volume = muted ? 0 : entry.volume;
                            playback.put(entry.handle, voice);
                            restarted.add(new Update(entry.handle, new SeekToSoundData(offset), now));
                        }
                    }
                    public void update(long handle, SoundData event) {
                        updates.add(new Update(handle, event, now));
                        var voice = playback.get(handle);
                        if (event instanceof StopSoundData) { playback.remove(handle); return; }
                        if (voice == null) return;
                        voice.seconds = voice.position(now); voice.changed = now;
                        if (event instanceof PauseSoundData) voice.running = false;
                        if (event instanceof ResumeSoundData) voice.running = true;
                        if (event instanceof SetVolumeSoundData volume) voice.volume = volume.getVolume();
                        if ((!ignoreSeeks || voice.running) && event instanceof SeekToSoundData seek) voice.seconds = seek.getSeconds();
                    }
                    public void updateGroup(List<CoordinatedAudio.Update> batch) {
                        groups.add(List.copyOf(batch));
                        for (var update : batch) update(update.handle(), update.event());
                    }
                });
        CoordinatedAudio.Clock start(long handle, float offset, boolean audible) {
            var clock = new CoordinatedAudio.Clock(offset, 1, now);
            audio.start(handle, "record.cat", Vector3f.ZERO, 4, 1, clock, audible);
            return clock;
        }
        void until(long millis) {
            long target = millis * 1_000_000L;
            while (!tasks.isEmpty() && tasks.peek().due <= target) { var task = tasks.remove(); now = Math.max(now, task.due); task.action.run(); }
            now = target;
        }
        List<Update> type(Class<?> type) { return updates.stream().filter(u -> type.isInstance(u.event)).toList(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void run() {
        long release = CoordinatedAudio.BATCH_MS + CoordinatedAudio.PREPARATION_MS + CoordinatedAudio.SETTLE_MS;
        var f = new Fixture();
        check(!f.audio.stop(999), "Unmanaged Geyser records must use the caller's native stop fallback");
        var a = f.start(1, 15, true); var b = f.start(2, 15, true); var spare = f.start(3, 15, false);
        f.until(CoordinatedAudio.BATCH_MS);
        check(f.prepared.size() == 2 && f.type(PauseSoundData.class).size() == 2, "All audible sources prepare and pause");
        check(f.type(ResumeSoundData.class).isEmpty(), "Nothing resumes before preparation ends");
        f.until(release);
        check(a.epoch == b.epoch && a.epoch == spare.epoch, "Audible and parked sources share one epoch");
        check(f.type(SeekToSoundData.class).stream().allMatch(u -> ((SeekToSoundData)u.event).getSeconds() == 15),
                "Initial release preserves the requested opening position");
        check(f.restarted.size() == 2 && f.restarted.stream().allMatch(u -> u.at == f.now),
                "All prepared sources are recreated on one scheduler turn");
        check(f.type(ResumeSoundData.class).isEmpty(), "Fresh starts must not depend on resuming paused instances");
        f.until(release + 5000);
        f.audio.start(3, "record.cat", Vector3f.ZERO, 4, 1, spare, true);
        f.until(release + 5000 + release);
        var late = f.type(SeekToSoundData.class).stream().filter(u -> u.handle == 3).findFirst().orElseThrow();
        check(Math.abs(((SeekToSoundData)late.event).getSeconds() - (20 + (release - CoordinatedAudio.SETTLE_MS) / 1000f)) < .001,
                "A newly attached speaker joins the current clock before settling silently");
        check(f.type(ResumeSoundData.class).stream().filter(u -> u.handle == 1 || u.handle == 2).count() == 0,
                "Attaching a speaker must never pause or resume existing sources");
        check(f.updates.stream().noneMatch(u -> u.handle != 3 && u.at > (release + 5000) * 1_000_000L),
                "Attaching a speaker must not send any updates to existing handles");
        check(Math.abs(a.position(f.now) - f.playback.get(3L).position(f.now)) < .001,
                "Existing song clocks continue uninterrupted during attachment");

        var stopped = new Fixture(); stopped.start(1, 0, true); stopped.until(100); stopped.audio.stop(1); stopped.until(release + 10);
        check(stopped.type(StopSoundData.class).size() == 1 && stopped.restarted.isEmpty(),
                "Ejection during preparation cancels release");
        var seeking = new Fixture(); seeking.start(1, 0, true);
        seeking.until(CoordinatedAudio.BATCH_MS + CoordinatedAudio.PREPARATION_MS);
        check(seeking.type(SeekToSoundData.class).size() == 1 && seeking.type(ResumeSoundData.class).isEmpty(),
                "Seeking happens while the source is still paused");
        seeking.audio.stop(1); seeking.until(release + 10);
        check(seeking.restarted.isEmpty(), "Ejection while seeking must not revive the source");
        var replaced = new Fixture(); replaced.start(1, 0, true); replaced.until(100); replaced.start(1, 10, true);
        replaced.until(release); check(replaced.restarted.isEmpty(), "Stale callback cannot release a replacement");
        replaced.until(release + 100);
        check(replaced.restarted.size() == 1 && replaced.type(SeekToSoundData.class).stream()
                .allMatch(u -> ((SeekToSoundData)u.event).getSeconds() == 10), "Only the replacement begins");
        var disconnected = new Fixture(); disconnected.start(1, 0, true); disconnected.until(100);
        int before = disconnected.updates.size(); disconnected.audio.close(); disconnected.until(release + 10);
        check(disconnected.updates.size() == before, "Disconnect cancels delayed audio actions");
        var rejoined = new Fixture(); rejoined.start(1, 0, true); rejoined.until(release);
        check(rejoined.restarted.size() == 1, "A new connection receives preparation normally");
        var overlap = new Fixture(); var shared = overlap.start(1, 0, true); overlap.until(release + 1000);
        overlap.audio.start(2, "record.cat", Vector3f.ZERO, 4, 1, shared, true);
        overlap.until(release + 1100);
        overlap.audio.start(3, "record.cat", Vector3f.ZERO, 4, 1, shared, true);
        overlap.until(2 * release + 1100);
        check(overlap.restarted.size() == 1 && overlap.type(ResumeSoundData.class).size() == 2,
                "Overlapping late preparations release only their new sources");
        check(overlap.type(PauseSoundData.class).stream().filter(u -> u.handle == 1).count() == 1,
                "Overlapping attachments cannot pause an existing speaker");
        check(overlap.audio.phase(1).equals("playing") && overlap.audio.phase(2).equals("playing")
                && overlap.audio.phase(3).equals("playing"), "All overlapping attachments become audible");
        var different = new Fixture(); var cat = different.start(1, 0, true);
        var other = new CoordinatedAudio.Clock(0, 1, 0);
        different.audio.start(2, "record.13", Vector3f.ZERO, 4, 1, other, true); different.until(release);
        check(cat.epoch != other.epoch, "Simultaneous unrelated songs must not share a resynchronization clock");
        var independent = new Fixture(); var first = independent.start(1, 0, true); independent.until(release + 1000);
        var second = independent.start(2, 0, true); independent.until(2 * release + 1000);
        check(first.epoch != second.epoch && first.position(independent.now) > 3 && second.position(independent.now) == 0,
                "Independent starts retain separate timelines");
        unreliablePausedVoices(release);
        delayedAttachmentRelease(release);
        stoppedSilentAttachment(release);
        nonzeroRunningSeek(release);
        cancelledRunningSeek(release);
        System.out.println("PASS: coordinated starts, running nonzero seeks, delayed release, cancellation, late attachment and independent clocks");
    }

    private static void nonzeroRunningSeek(long release) {
        var f = new Fixture();
        f.ignoreSeeks = true;
        f.ignoreRestartOffsets = true;
        var clocks = new ArrayList<CoordinatedAudio.Clock>();
        for (int i = 1; i <= 24; i++) clocks.add(f.start(i, 30.032108f, true));
        f.until(release);
        var epoch = clocks.getFirst().epoch;
        check(f.playback.values().stream().allMatch(v -> v.volume == 0 && v.running && v.position(f.now) == 0),
                "A native engine that loses the opening offset must remain inaudible");
        check(f.audio.phase(1).equals("preparing"), "Nonzero playback is not released before the running seek");
        f.until(release + CoordinatedAudio.RUNNING_SEEK_MS - 1);
        check(f.groups.isEmpty(), "The running sources receive their loading window before seeking");
        // Exercise delayed callbacks without changing the session's epoch.
        f.now = (release + CoordinatedAudio.RUNNING_SEEK_MS + 200) * 1_000_000L;
        f.until(f.now / 1_000_000L);
        check(f.groups.size() == 1 && f.groups.getFirst().size() == 24,
                "One ordered update group seeks all current sources");
        check(f.playback.values().stream().allMatch(v -> v.volume == 0), "Seeking cannot expose an incorrect intro");
        f.now += (CoordinatedAudio.SETTLE_MS + 180) * 1_000_000L;
        f.until(f.now / 1_000_000L);
        check(f.groups.size() == 2 && f.groups.getLast().size() == 24,
                "One ordered update group unmutes the settled sources");
        for (int i = 1; i <= 24; i++) {
            var voice = f.playback.get((long) i);
            check(clocks.get(i - 1).epoch == epoch && Math.abs(voice.position(f.now) - clocks.get(i - 1).position(f.now)) < .001,
                    "A delayed running seek/unmute must retain the original song position for voice " + i);
            check(voice.volume == 4 && f.audio.phase(i).equals("playing"), "The corrected group becomes audible");
        }
    }

    private static void cancelledRunningSeek(long release) {
        for (String scenario : List.of("ejectBeforeSeek", "ejectBeforeUnmute", "replacement", "disconnect")) {
            var f = new Fixture(); f.start(1, 15, true); f.until(release);
            if (scenario.equals("ejectBeforeUnmute")) f.until(release + CoordinatedAudio.RUNNING_SEEK_MS);
            int before = f.updates.size();
            if (scenario.equals("disconnect")) f.audio.close();
            else {
                f.audio.stop(1);
                if (scenario.equals("replacement")) f.start(1, 0, true);
            }
            f.until(release + CoordinatedAudio.RUNNING_SEEK_MS + CoordinatedAudio.SETTLE_MS + 10);
            check(f.updates.stream().skip(before).noneMatch(u -> u.event instanceof SeekToSoundData
                            || u.event instanceof SetVolumeSoundData),
                    "A stale running-seek callback cannot alter audio after " + scenario);
            if (scenario.startsWith("eject")) check(!f.playback.containsKey(1L), "Ejected playback must stay stopped");
            if (scenario.equals("replacement")) check(f.audio.phase(1).equals("preparing"),
                    "A replacement retains its own preparation instead of inheriting an old unmute");
            if (scenario.equals("disconnect")) check(f.updates.size() == before, "Disconnect cancels all delayed output");
        }
    }

    private static void unreliablePausedVoices(long release) {
        var f = new Fixture();
        f.ignoreSeeks = true;
        for (int i = 1; i <= 24; i++) f.start(i, 0, true);
        f.until(CoordinatedAudio.BATCH_MS);
        // Model the measured failure: prepared native voices have different
        // cursors, and seeking those paused instances does not correct them.
        for (var entry : f.playback.entrySet()) entry.getValue().seconds += entry.getKey() / 10f;
        f.audio.stop(24);
        f.until(release);
        check(f.restarted.size() == 23 && !f.playback.containsKey(24L),
                "Recreation excludes an ejected source");
        check(f.playback.values().stream().allMatch(v -> v.running && v.position(f.now) == 0 && v.volume == 4),
                "A fresh cohort starts at its requested opening even when paused seeks fail");
        check(f.type(ResumeSoundData.class).isEmpty(), "Broken paused instances are never resumed");
    }

    private static void delayedAttachmentRelease(long release) {
        var f = new Fixture();
        var original = f.start(1, 0, true); var spare = f.start(2, 0, false);
        f.until(release + 5000);
        f.audio.start(2, "record.cat", Vector3f.ZERO, 4, 1, spare, true);
        long seekAt = release + 5000 + CoordinatedAudio.BATCH_MS + CoordinatedAudio.PREPARATION_MS;
        f.until(seekAt);
        check(f.playback.get(2L).running && f.audio.phase(2).equals("preparing"),
                "A newcomer advances silently while its audio settles");
        check(f.type(SetVolumeSoundData.class).stream().noneMatch(u -> u.handle == 2),
                "A newcomer must remain muted until settling completes");
        // The event loop stalls between seeking a newcomer and making it audible.
        // A paused source would retain its predicted position throughout this stall.
        f.now = (seekAt + CoordinatedAudio.SETTLE_MS + 180) * 1_000_000L;
        f.until(f.now / 1_000_000L);
        float existingPosition = f.playback.get(1L).position(f.now);
        float attachedPosition = f.playback.get(2L).position(f.now);
        check(Math.abs(existingPosition - attachedPosition) < .001,
                "A delayed attachment release must not leave the new speaker behind: "
                        + existingPosition + " versus " + attachedPosition);
        check(Math.abs(original.position(f.now) - existingPosition) < .001,
                "Attachment must preserve the existing listener's timeline");
        check(f.audio.phase(2).equals("playing"), "The delayed newcomer becomes audible");
        check(f.type(ResumeSoundData.class).stream().filter(u -> u.handle == 2).count() == 1,
                "Unmuting must not resume a newcomer a second time");
    }

    private static void stoppedSilentAttachment(long release) {
        var f = new Fixture();
        f.start(1, 0, true); var spare = f.start(2, 0, false);
        f.until(release + 5000);
        f.audio.start(2, "record.cat", Vector3f.ZERO, 4, 1, spare, true);
        f.until(release + 5000 + CoordinatedAudio.BATCH_MS + CoordinatedAudio.PREPARATION_MS);
        check(f.playback.get(2L).running, "The late voice is already advancing silently");
        f.audio.stop(2);
        f.until(2 * release + 5000);
        check(!f.playback.containsKey(2L) && f.audio.phase(2).equals("stopped"),
                "Ejection during silent settling must stop the new voice");
        check(f.type(SetVolumeSoundData.class).stream().noneMatch(u -> u.handle == 2),
                "A stale unmute callback must never make a stopped voice audible");
        check(f.audio.phase(1).equals("playing"), "Stopping a newcomer preserves existing playback");
    }
}
