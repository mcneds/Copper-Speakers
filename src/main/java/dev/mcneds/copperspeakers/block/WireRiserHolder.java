package dev.mcneds.copperspeakers.block;

import dev.mcneds.copperspeakers.CopperSpeakers;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

/** Ascending faces and dry resin floors, independent of client-side block prediction. */
public final class WireRiserHolder extends ElementHolder {
    private int mask = -1;
    private boolean insulated;
    private int floorMask = -1;
    private ItemDisplayElement floor;

    public WireRiserHolder(BlockState state) { update(state); }
    private void update(BlockState state) {
        int next = state.getValue(CopperWireBlock.CLIMBING);
        boolean resin = state.getValue(CopperWireBlock.INSULATED);
        int nextFloor = resin && !state.getValue(CopperWireBlock.WATERLOGGED)
                ? CopperWireBlock.connectionMask(state) : -1;
        if (nextFloor != floorMask) {
            floorMask = nextFloor;
            if (nextFloor < 0) {
                removeElement(floor);
                floor = null;
            } else {
                var stack = model("insulated_wire_" + nextFloor);
                if (floor == null) {
                    floor = display(stack);
                    // Chunk tracking limits visibility; avoid the display's shorter default cutoff.
                    floor.setViewRange(16);
                    addElement(floor);
                } else {
                    floor.setItem(stack);
                    floor.tick();
                }
            }
        }
        if (next == mask && resin == insulated) return;
        mask = next; insulated = resin;
        for (var element : List.copyOf(getElements())) if (element != floor) removeElement(element);
        for (int i = 0; i < 4; i++) if ((mask & (1 << i)) != 0) {
            addElement(display(model((resin ? "insulated_" : "")
                    + "wire_riser_" + CopperWireBlock.DIRECTIONS[i].getSerializedName())));
        }
    }
    private static ItemStack model(String name) {
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, CopperSpeakers.id(name));
        return stack;
    }
    private static ItemDisplayElement display(ItemStack stack) {
        var element = new ItemDisplayElement(stack);
        element.setItemDisplayContext(ItemDisplayContext.NONE);
        // Vanilla ItemDisplayRenderer adds a half-turn around Y. Cancel it so
        // the floor connections and rising faces match their world directions.
        element.setYaw(180);
        return element;
    }
    @Override public void notifyUpdate(HolderAttachment.UpdateType type) {
        if (type == BlockBoundAttachment.BLOCK_STATE_UPDATE && getAttachment() instanceof BlockBoundAttachment block)
            update(block.getBlockState());
    }
}
