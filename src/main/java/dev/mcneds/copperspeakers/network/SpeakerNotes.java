package dev.mcneds.copperspeakers.network;

import dev.mcneds.copperspeakers.CopperSpeakers;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/** Vanilla NOTE ignores packet velocity and rises. A short-lived vanilla display can travel sideways. */
public final class SpeakerNotes extends ElementHolder {
    private static final int INTERVAL = 20;
    private static final int LIFETIME = 16;
    private final ServerLevel level;
    private final Vec3 origin;
    private final Direction facing;
    private long lastTick = Long.MIN_VALUE;
    private long lastEmission;
    private ItemDisplayElement note;

    private SpeakerNotes(ServerLevel level, BlockPos pos, Direction facing) {
        this.level = level;
        this.facing = facing;
        // The large driver is centered six pixels above the bottom of speaker_front.png.
        this.origin = new Vec3(pos.getX() + .5, pos.getY() + 6.0 / 16, pos.getZ() + .5).relative(facing, .67);
        this.lastEmission = level.getGameTime() - INTERVAL + Math.floorMod(pos.hashCode(), INTERVAL);
    }

    public static SpeakerNotes create(ServerLevel level, BlockPos pos, Direction facing) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        var notes = new SpeakerNotes(level, pos, facing);
        // Use the already loaded chunk directly; never wait for a chunk or add tickets.
        new ChunkAttachment(notes, chunk, Vec3.atCenterOf(pos), false);
        return notes;
    }

    public Direction facing() { return facing; }

    @Override protected void onTick() {
        long now = level.getGameTime();
        if (now == lastTick) return; // Audio reconciliation may also run inside unload callbacks.
        lastTick = now;
        if (note != null) {
            long age = now - lastEmission;
            if (age >= LIFETIME) {
                removeElement(note);
                note = null;
            } else {
                note.setOverridePos(origin.relative(facing, age * .055));
                note.setScale(new Vector3f(.32f * Math.min(1, (LIFETIME - age) / 4f)));
                note.startInterpolationIfDirty();
            }
        }
        if (now - lastEmission < INTERVAL || getWatchingPlayers().stream().noneMatch(
                watcher -> watcher.getPlayer().position().distanceToSqr(origin) <= 32 * 32)) return;
        lastEmission = now;
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, CopperSpeakers.id("speaker_note_" + Math.floorMod(now / INTERVAL, 8)));
        note = new ItemDisplayElement(stack);
        note.setItemDisplayContext(ItemDisplayContext.NONE);
        note.setBillboardMode(Display.BillboardConstraints.CENTER);
        // Cancel ItemDisplayRenderer's Y half-turn so the camera-facing note is not mirrored.
        note.setLeftRotation(new Quaternionf().rotateY((float)Math.PI));
        note.setBrightness(new Brightness(15, 15));
        note.setViewRange(.5f);
        note.setScale(new Vector3f(.32f));
        note.setTeleportDuration(1);
        note.setInterpolationDuration(1);
        note.setOverridePos(origin);
        addElement(note);
    }
}
