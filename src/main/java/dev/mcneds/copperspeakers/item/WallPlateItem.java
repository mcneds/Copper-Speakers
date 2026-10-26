package dev.mcneds.copperspeakers.item;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.entity.WallPlateEntity;
import dev.mcneds.copperspeakers.network.WallPlates;
import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.gameevent.GameEvent;

public final class WallPlateItem extends SimplePolymerItem {
    public WallPlateItem(Properties properties) { super(properties, Items.ITEM_FRAME, true); }

    @Override public InteractionResult useOn(UseOnContext context) {
        var face = context.getClickedFace();
        var host = context.getClickedPos();
        var player = context.getPlayer();
        var level = context.getLevel();
        if (player == null) return InteractionResult.FAIL;
        // A top/bottom click installs a horizontal pair; it never creates a vertical network port.
        if (face.getAxis().isVertical()) face = context.getHorizontalDirection().getOpposite();
        for (var side : new net.minecraft.core.Direction[]{face, face.getOpposite()}) {
            var pos = host.relative(side);
            if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, side, context.getItemInHand()))
                return InteractionResult.FAIL;
        }
        if (!level.mayInteract(player, host)) return InteractionResult.FAIL;
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        if (!WallPlates.eligible(server, host) || WallPlates.get(server).at(host) != null)
            return InteractionResult.FAIL;
        var plate = new WallPlateEntity(CopperSpeakers.WALL_PLATE_ENTITY, server);
        plate.place(host, face);
        if (!plate.facesLoaded() || !plate.survives() || !server.addFreshEntity(plate)) return InteractionResult.FAIL;
        plate.playPlacementSound();
        server.gameEvent(player, GameEvent.ENTITY_PLACE, host);
        context.getItemInHand().consume(1, player);
        return InteractionResult.SUCCESS;
    }
}
