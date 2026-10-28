package dev.mcneds.copperspeakers.bedrock;

import java.util.*;
import java.util.function.LongSupplier;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.shaded.org.cloudburstmc.protocol.bedrock.data.sound.*;

/** Per-connection preparation and release of buffered sources. Call on the session event loop. */
final class CoordinatedAudio {
    static final long PREPARATION_MS = 2000;
    static final long SETTLE_MS = 250;
    static final long BATCH_MS = 25;
    interface Scheduler { void later(long milliseconds, Runnable action); }
    interface Output {
        void prepare(Entry entry, float offset);
        void restartGroup(List<Entry> entries, long now);
        void update(long handle, SoundData event);
    }
    static final class Epoch {
        long started;
        Epoch(long started) { this.started = started; }
    }
    static final class Clock {
        final float offset, pitch;
        final long created;
        Epoch epoch;
        Clock(float offset, float pitch, long created) { this.offset = offset; this.pitch = pitch; this.created = created; }
        float position(long now) {
            long started = epoch == null ? created : epoch.started;
            return offset + Math.max(0, now - started) / 1_000_000_000f * pitch;
        }
    }
    static final class Entry {
        final long handle;
        final String sound;
        final Vector3f position;
        final float volume, pitch;
        final Clock clock;
        final boolean audible;
        boolean prepared, released;
        Entry(long handle, String sound, Vector3f position, float volume, float pitch, Clock clock, boolean audible) {
            this.handle = handle; this.sound = sound; this.position = position;
            this.volume = volume; this.pitch = pitch; this.clock = clock; this.audible = audible;
        }
    }
    private final LongSupplier now;
    private final Scheduler scheduler;
    private final Output output;
    private final Map<Long, Entry> active = new HashMap<>(), queued = new LinkedHashMap<>();
    private boolean flushScheduled;
    private volatile boolean closed;
    CoordinatedAudio(LongSupplier now, Scheduler scheduler, Output output) {
        this.now = now; this.scheduler = scheduler; this.output = output;
    }
    void start(long handle, String sound, Vector3f position, float volume, float pitch, Clock clock, boolean audible) {
        if (closed) return;
        stop(handle);
        var entry = new Entry(handle, sound, position, volume, pitch, clock, audible);
        active.put(handle, entry); queued.put(handle, entry);
        if (!flushScheduled) {
            flushScheduled = true;
            scheduler.later(BATCH_MS, this::flush);
        }
    }
    boolean stop(long handle) {
        var previous = active.remove(handle); queued.remove(handle);
        if (previous != null && previous.prepared) output.update(handle, new StopSoundData());
        return previous != null;
    }
    private void flush() {
        flushScheduled = false;
        if (closed || queued.isEmpty()) return;
        var batch = List.copyOf(queued.values()); queued.clear();
        long prepareTime = now.getAsLong();
        record Group(String sound, float offset, float pitch) {}
        var fresh = new HashMap<Group, Epoch>();
        var groups = new IdentityHashMap<Epoch, List<Entry>>();
        for (var entry : batch) {
            // Parked spare voices receive this same clock even though they emit no audio.
            if (entry.clock.epoch == null) entry.clock.epoch = fresh.computeIfAbsent(
                    new Group(entry.sound, entry.clock.offset, entry.pitch),
                    ignored -> new Epoch(prepareTime + (PREPARATION_MS + SETTLE_MS) * 1_000_000L));
            groups.computeIfAbsent(entry.clock.epoch, ignored -> new ArrayList<>()).add(entry);
            if (!entry.audible) continue;
            output.prepare(entry, entry.clock.position(prepareTime)); entry.prepared = true;
        }
        for (var entry : batch) if (entry.prepared) output.update(entry.handle, new PauseSoundData());
        groups.forEach((epoch, entries) -> scheduler.later(PREPARATION_MS,
                () -> seek(entries, epoch, fresh.containsValue(epoch))));
    }
    private void seek(List<Entry> batch, Epoch epoch, boolean fresh) {
        if (closed) return;
        long seekTime = now.getAsLong();
        long releaseTime = seekTime + SETTLE_MS * 1_000_000L;
        // Only the creating batch may establish an epoch. Late attachments keep
        // their existing epoch and never change any already-playing handle.
        if (fresh) epoch.started = releaseTime;
        var ready = batch.stream().filter(e -> e.prepared && active.get(e.handle) == e).toList();
        // Fresh clocks stay paused at the opening until their coordinated release.
        // New speakers on a running clock advance silently during settling. Their
        // position then stays current even if the eventual unmute callback is late.
        long targetTime = fresh ? releaseTime : seekTime;
        for (var entry : ready) output.update(entry.handle, new SeekToSoundData(entry.clock.position(targetTime)));
        if (!fresh) for (var entry : ready) output.update(entry.handle, new ResumeSoundData());
        scheduler.later(SETTLE_MS, () -> release(ready, epoch, fresh));
    }
    private void release(List<Entry> batch, Epoch epoch, boolean fresh) {
        if (closed) return;
        long releaseTime = now.getAsLong();
        if (fresh) epoch.started = releaseTime;
        var ready = batch.stream().filter(e -> e.prepared && active.get(e.handle) == e).toList();
        // Native paused voices can retain different cursors despite receiving the
        // same seek. Keep the loaded audio, but replace those instances together
        // at the requested opening instead of relying on their paused state.
        if (fresh) output.restartGroup(ready, releaseTime);
        else for (var entry : ready) output.update(entry.handle, new SetVolumeSoundData(entry.volume));
        for (var entry : ready) entry.released = true;
    }
    String phase(long handle) {
        var entry = active.get(handle);
        return entry == null ? "stopped" : !entry.audible ? "parked" : entry.released ? "playing" : "preparing";
    }
    // Disconnect may be delivered outside the session loop. The flag cancels all
    // callbacks; the removed listener and its maps are collected after they expire.
    void close() { closed = true; }
}
