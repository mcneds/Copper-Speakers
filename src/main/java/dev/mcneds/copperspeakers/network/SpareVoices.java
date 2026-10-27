package dev.mcneds.copperspeakers.network;

import eu.pb4.polymer.virtualentity.api.VirtualEntityUtils;
import eu.pb4.polymer.virtualentity.api.elements.MarkerElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Packet-only audio anchors, shared by original playback and prepared late admissions. */
final class SpareVoices {
    static final int CAPACITY = 4;
    private static final double PARKING_HEIGHT = 4096;
    private final List<Voice> voices = new ArrayList<>();
    private final Map<UUID, Vec3> parking = new HashMap<>();
    private static final class Voice {
        final MarkerElement marker = new MarkerElement();
        BlockPos speaker;
        boolean retired, original;
        Voice() {
            marker.setInvisible(true);
            marker.setNoGravity(true);
            // MarkerElement defaults to silent; entity-bound sounds would reject it.
            marker.setSilent(false);
        }
    }
    SpareVoices() { this(CAPACITY); }
    SpareVoices(int count) { for (int i = 0; i < count; i++) voices.add(new Voice()); }
    SpareVoices(Collection<BlockPos> originals) {
        this();
        for (var pos : originals) {
            var voice = new Voice();
            voice.speaker = pos.immutable();
            voice.original = true;
            voices.add(voice);
        }
    }
    void appendStart(ServerPlayer player, Holder<SoundEvent> sound, List<Packet<? super ClientGamePacketListener>> packets) {
        Vec3 parked = parkingPosition(player);
        parking.put(player.getUUID(), parked);
        for (Voice voice : voices) {
            if (voice.retired) continue;
            voice.marker.setOverridePos(voice.speaker == null ? parked : Vec3.atCenterOf(voice.speaker));
            voice.marker.updateLastSyncedPos();
            voice.marker.startWatching(player, packets::add);
            packets.add(VirtualEntityUtils.createClientboundSoundEntityPacket(
                    voice.marker.getEntityId(), sound, SoundSource.RECORDS, 4.0f, 1.0f, 0));
        }
    }
    boolean bind(BlockPos speaker, Collection<ServerPlayer> listeners) {
        Voice voice = voices.stream().filter(v -> !v.retired && v.speaker == null).findFirst().orElse(null);
        if (voice == null) return false;
        voice.speaker = speaker.immutable();
        for (ServerPlayer player : listeners) move(player, voice, Vec3.atCenterOf(speaker));
        return true;
    }
    boolean release(BlockPos speaker, Collection<ServerPlayer> listeners, boolean ended) {
        Voice voice = voices.stream().filter(v -> speaker.equals(v.speaker)).findFirst().orElse(null);
        if (voice == null) return false;
        voice.speaker = null;
        if (ended || voice.original) retire(voice, listeners);
        else for (ServerPlayer player : listeners) move(player, voice, parking.get(player.getUUID()));
        return true;
    }
    void retireUnused(Collection<ServerPlayer> listeners) {
        for (Voice voice : voices) if (voice.speaker == null) retire(voice, listeners);
    }
    void destroy(Collection<ServerPlayer> listeners) {
        for (Voice voice : voices) retire(voice, listeners);
        parking.clear();
    }
    private void retire(Voice voice, Collection<ServerPlayer> listeners) {
        if (voice.retired) return;
        for (ServerPlayer player : listeners) player.connection.send(new ClientboundRemoveEntitiesPacket(voice.marker.getEntityId()));
        voice.retired = true;
    }
    void forget(ServerPlayer player, boolean sendRemoval) {
        parking.remove(player.getUUID());
        if (sendRemoval) for (Voice voice : voices) if (!voice.retired)
            player.connection.send(new ClientboundRemoveEntitiesPacket(voice.marker.getEntityId()));
    }
    void keepParkedOutOfEarshot(ServerPlayer player) {
        Vec3 old = parking.get(player.getUUID());
        if (old == null) return;
        Vec3 next = parkingPosition(player);
        if (old.distanceToSqr(next) < 32 * 32) return;
        parking.put(player.getUUID(), next);
        for (Voice voice : voices) if (!voice.retired && voice.speaker == null) move(player, voice, next);
    }
    private static Vec3 parkingPosition(ServerPlayer player) {
        return player.position().add(0, player.getY() > 0 ? -PARKING_HEIGHT : PARKING_HEIGHT, 0);
    }
    private static void move(ServerPlayer player, Voice voice, Vec3 pos) {
        if (pos != null) player.connection.send(new ClientboundEntityPositionSyncPacket(voice.marker.getEntityId(),
                PositionPath.of(pos), 0, 0, false));
    }
    int available() { return (int)voices.stream().filter(v -> !v.retired && v.speaker == null).count(); }
    int voiceId(BlockPos speaker) {
        return voices.stream().filter(v -> !v.retired && speaker.equals(v.speaker))
                .mapToInt(v -> v.marker.getEntityId()).findFirst().orElse(-1);
    }
    Set<Integer> entityIds() {
        var ids = new LinkedHashSet<Integer>();
        for (Voice voice : voices) if (!voice.retired) ids.add(voice.marker.getEntityId());
        return Set.copyOf(ids);
    }
}
