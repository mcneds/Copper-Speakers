package dev.mcneds.copperspeakers.block;

import dev.mcneds.copperspeakers.CopperSpeakers;
import eu.pb4.polymer.blocks.api.*;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import java.util.EnumMap;
import java.util.Map;

public final class SpeakerBlock extends Block implements PolymerTexturedBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    private final Map<Direction, BlockState> models = new EnumMap<>(Direction.class);
    public SpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int rotation = switch (d) { case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0; };
            models.put(d, java.util.Objects.requireNonNull(PolymerBlockResourceUtils.requestBlock(
                    BlockModelType.FULL_BLOCK, PolymerBlockModel.of(CopperSpeakers.id("block/speaker"), 0, rotation)),
                    "No Polymer block models available for speakers"));
        }
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
        return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
    @Override public BlockState getPolymerBlockState(BlockState s, PacketContext c) { return models.get(s.getValue(FACING)); }
}
