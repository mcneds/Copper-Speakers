package dev.mcneds.copperspeakers.entity;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.network.WallPlates;
import dev.mcneds.copperspeakers.network.LoadedBlocks;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.EntityElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import java.util.EnumMap;

/** One saved anchor inside the host owns two Polymer item frames, including across chunk borders. */
public final class WallPlateEntity extends Entity implements PolymerEntity {
    private Direction facing = Direction.NORTH;
    private ElementHolder visuals;
    private final EnumMap<Direction, ItemDisplayElement> leads = new EnumMap<>(Direction.class);

    public WallPlateEntity(EntityType<? extends WallPlateEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }
    public void place(BlockPos host, Direction facing) {
        this.facing = facing;
        setPos(Vec3.atCenterOf(host));
    }
    public BlockPos host() { return blockPosition(); }
    public Direction facing() { return facing; }
    public boolean facesLoaded() {
        return LoadedBlocks.available(level(), host()) && LoadedBlocks.available(level(), host().relative(facing))
                && LoadedBlocks.available(level(), host().relative(facing.getOpposite()));
    }
    public ItemFrame frame(Direction side) {
        var frame = new ItemFrame(level(), host().relative(side), side);
        frame.setInvisible(true);
        frame.setSilent(true);
        return frame;
    }
    public boolean survives() {
        return facing.getAxis().isHorizontal() && WallPlates.eligible(level(), host())
                && faceFits(facing) && faceFits(facing.getOpposite());
    }
    private boolean faceFits(Direction side) {
        // Interior faces of a chain are occluded by the next full block. Its own plate is still
        // required to conduct; simply placing a plate against ordinary stone cannot extend the network.
        return WallPlates.eligible(level(), host().relative(side)) || frame(side).survives();
    }
    public void showFrames() {
        if (visuals != null || !(level() instanceof ServerLevel server)) return;
        visuals = new ElementHolder();
        for (var side : new Direction[]{facing, facing.getOpposite()}) {
            var frame = frame(side);
            frame.setItem(new ItemStack(CopperSpeakers.WALL_PLATE), false);
            var element = new EntityElement<>(frame, server, new VirtualElement.InteractionHandler() {
                @Override public void attack(ServerPlayer player) {
                    // Virtual entities bypass vanilla's entity lookup; enforce reach and build permissions here.
                    if (player.isSpectator() || !player.getAbilities().mayBuild || player.level() != level()
                            || !server.mayInteract(player, host())
                            || !player.isWithinEntityInteractionRange(frame.getBoundingBox(), 0)) return;
                    breakPlate(player, side);
                }
                @Override public void pickItem(ServerPlayer player, boolean includeData) {
                    VirtualElement.InteractionHandler.redirect(WallPlateEntity.this).pickItem(player, false);
                }
            });
            // ItemFrame's spawn packet uses its attachment block; retain that position when attaching the holder.
            element.setOverridePos(frame.position());
            visuals.addElement(element);
        }
        EntityAttachment.of(visuals, this);
    }
    /** Bridge the floor cable to the socket, matching insulation on each exposed face. */
    public void refreshConnections() {
        if (visuals == null) return;
        boolean changed = false;
        for (var side : new Direction[]{facing, facing.getOpposite()}) {
            var state = LoadedBlocks.state(level(), host().relative(side));
            var lead = leads.get(side);
            if (!state.is(CopperSpeakers.WIRE)) {
                if (lead != null) {
                    visuals.removeElement(lead);
                    leads.remove(side);
                    changed = true;
                }
                continue;
            }
            var model = CopperSpeakers.id((state.getValue(CopperWireBlock.INSULATED) ? "insulated_" : "")
                    + "wall_plate_lead_" + side.getSerializedName());
            if (lead != null && model.equals(lead.getItem().get(DataComponents.ITEM_MODEL))) continue;
            var stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.ITEM_MODEL, model);
            if (lead == null) {
                lead = new ItemDisplayElement(stack);
                lead.setItemDisplayContext(ItemDisplayContext.NONE);
                // Cancel vanilla ItemDisplayRenderer's half-turn; models use world-facing directions.
                lead.setYaw(180);
                // Sample light in the cable's air space, not inside the opaque host block.
                lead.setOverridePos(Vec3.atCenterOf(host().relative(side)));
                leads.put(side, lead);
                visuals.addElement(lead);
            } else lead.setItem(stack);
            changed = true;
        }
        // The stationary holder does not tick continuously. Flush changed item metadata now.
        if (changed) visuals.tick();
    }
    public void hideFrames() {
        if (visuals != null) { visuals.destroy(); visuals = null; }
        leads.clear();
    }
    public void playPlacementSound() {
        level().playSound(null, host(), SoundEvents.ITEM_FRAME_PLACE, SoundSource.BLOCKS, 1, 1);
    }
    public void breakPlate(Entity causedBy, Direction side) {
        if (isRemoved() || !(level() instanceof ServerLevel server)) return;
        discard(); // unregisters the conductor and both faces before issuing a single drop
        server.playSound(null, host(), SoundEvents.ITEM_FRAME_BREAK, SoundSource.BLOCKS, 1, 1);
        server.gameEvent(causedBy, GameEvent.BLOCK_CHANGE, host());
        if (server.getGameRules().get(GameRules.ENTITY_DROPS)
                && !(causedBy instanceof Player player && player.hasInfiniteMaterials())) {
            Vec3 drop = Vec3.atCenterOf(host()).relative(side, .75);
            var item = new ItemEntity(server, drop.x, drop.y, drop.z, getPickResult());
            item.setDefaultPickUpDelay();
            server.addFreshEntity(item);
        }
    }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (isInvulnerableToBase(source) || isRemoved()
                || (!level.getGameRules().get(GameRules.MOB_GRIEFING) && source.getEntity() instanceof Mob)) return false;
        breakPlate(source.getEntity(), facing);
        return true;
    }
    @Override public EntityType<?> getPolymerEntityType(PacketContext context) { return EntityTypes.MARKER; }
    @Override public ItemStack getPickResult() { return new ItemStack(CopperSpeakers.WALL_PLATE); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
    @Override protected void addAdditionalSaveData(ValueOutput output) { output.store("Facing", Direction.CODEC, facing); }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        facing = input.read("Facing", Direction.CODEC).filter(d -> d.getAxis().isHorizontal()).orElse(Direction.NORTH);
    }
    @Override public void tick() {} // stationary; indexed block-change notifications validate the support
    @Override public PushReaction getPistonPushReaction() { return PushReaction.IGNORE_ENTITY; }
    @Override public boolean isIgnoringBlockTriggers() { return true; }
}
