package dev.mcneds.copperspeakers.block;

import dev.mcneds.copperspeakers.CopperSpeakers;
import dev.mcneds.copperspeakers.network.WallPlates;
import dev.mcneds.copperspeakers.network.WireConnections;
import dev.mcneds.copperspeakers.network.LoadedBlocks;
import eu.pb4.polymer.blocks.api.*;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

/** Copper cable with redstone-style ledge connections, independent of redstone power. */
public final class CopperWireBlock extends Block implements SimpleWaterloggedBlock, PolymerTexturedBlock, BlockWithElementHolder {
    public static final BooleanProperty INSULATED = BooleanProperty.create("insulated");
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    // Preserve the boolean ports of existing worlds; this mask adds the rising faces.
    public static final IntegerProperty CLIMBING = IntegerProperty.create("climbing", 0, 15);
    public static final BooleanProperty[] PORTS = {BlockStateProperties.NORTH, BlockStateProperties.EAST,
            BlockStateProperties.SOUTH, BlockStateProperties.WEST};
    public static final Direction[] DIRECTIONS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private final BlockState[][] models = new BlockState[3][16];
    public CopperWireBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(INSULATED, false).setValue(WATERLOGGED, false).setValue(CLIMBING, 0);
        for (var port : PORTS) state = state.setValue(port, false);
        registerDefaultState(state);
        // Tripwire rewrites its connection bits during client-side neighbor updates.
        // Dry resin uses a stable empty carrier and a display for its floor model.
        java.util.Arrays.fill(models[1], java.util.Objects.requireNonNull(
                PolymerBlockResourceUtils.requestEmpty(BlockModelType.PRESSURE_PLATE_ACTIVE),
                "No Polymer carrier available for insulated copper wire"));
        for (int kind : new int[]{0, 2}) for (int mask = 0; mask < 16; mask++)
            models[kind][mask] = java.util.Objects.requireNonNull(PolymerBlockResourceUtils.requestBlock(
                    // Models cannot change vanilla client outlines. Dry cable needs a flat,
                    // non-colliding carrier; kelp retains actual water rendering when submerged.
                    kind == 2 ? BlockModelType.KELP : BlockModelType.PRESSURE_PLATE_ACTIVE,
                    PolymerBlockModel.of(CopperSpeakers.id("block/" + (kind == 0 ? "wire_" : "insulated_wire_") + mask))),
                    "No Polymer models available for copper wire");
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(INSULATED, WATERLOGGED, CLIMBING); b.add(PORTS);
    }
    public static boolean isNode(BlockState s) {
        return s.getBlock() instanceof CopperWireBlock || s.getBlock() instanceof SpeakerBlock || s.is(Blocks.JUKEBOX);
    }
    public BlockState connections(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState self = state;
        java.util.function.Function<BlockPos, BlockState> read = p -> p.equals(pos) ? self : LoadedBlocks.state(level, p);
        int climbing = 0;
        for (int i = 0; i < 4; i++) {
            BlockPos neighbor = pos.relative(DIRECTIONS[i]);
            boolean loaded = LoadedBlocks.available(level, neighbor);
            boolean up = loaded && WireConnections.step(level, read, pos, neighbor.above());
            boolean down = loaded && WireConnections.step(level, read, pos, neighbor.below());
            if (up) climbing |= 1 << i;
            state = state.setValue(PORTS[i], loaded && (up || down || isNode(read.apply(neighbor))
                    || level instanceof ServerLevel server && WallPlates.get(server).connects(neighbor, pos)));
        }
        return state.setValue(CLIMBING, climbing);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
        if (!c.getLevel().getFluidState(c.getClickedPos()).isEmpty()) return null;
        return connections(defaultBlockState(), c.getLevel(), c.getClickedPos());
    }
    @Override protected boolean canSurvive(BlockState s, LevelReader l, BlockPos p) {
        return supportsWire(l.getBlockState(p.below()), l, p.below());
    }
    public static boolean supportsWire(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isFaceSturdy(level, pos, Direction.UP) || state.is(Blocks.HOPPER);
    }
    @Override public ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState state) {
        return new WireRiserHolder(state);
    }
    @Override protected BlockState updateShape(BlockState s, LevelReader l, ScheduledTickAccess ticks,
            BlockPos p, Direction d, BlockPos other, BlockState neighbor, RandomSource random) {
        if (!canSurvive(s, l, p)) return s.getValue(WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        if (s.getValue(WATERLOGGED)) ticks.scheduleTick(p, Fluids.WATER, Fluids.WATER.getTickDelay(l));
        return connections(s, l, p);
    }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return s.getValue(INSULATED)
                ? Blocks.SNOW.defaultBlockState().getShape(l, p, c)
                : Block.box(1, 0, 1, 15, 0.5, 15);
    }
    @Override protected InteractionResult useItemOn(ItemStack stack, BlockState s, Level l, BlockPos p,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(Items.RESIN_CLUMP) && !s.getValue(INSULATED)) {
            if (!l.isClientSide()) {
                l.setBlockAndUpdate(p, s.setValue(INSULATED, true));
                stack.consume(1, player);
            }
            return InteractionResult.SUCCESS;
        }
        if (stack.is(ItemTags.AXES) && s.getValue(INSULATED)) {
            if (s.getValue(WATERLOGGED)) {
                if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(
                        Component.translatable("message.copper_speakers.drain_before_stripping"), true);
                return InteractionResult.FAIL;
            }
            if (!l.isClientSide()) {
                l.setBlockAndUpdate(p, s.setValue(INSULATED, false));
                l.playSound(null, p, SoundEvents.AXE_WAX_OFF.value(), SoundSource.BLOCKS, 1.0f, 1.0f);
                l.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.BLOCK_CHANGE, p);
                stack.hurtAndBreak(1, player, hand);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
    @Override protected FluidState getFluidState(BlockState s) {
        return s.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(s);
    }
    @Override public boolean canPlaceLiquid(LivingEntity e, BlockGetter l, BlockPos p, BlockState s, Fluid f) {
        return !s.getValue(WATERLOGGED) && (f == Fluids.WATER
                || f == Fluids.FLOWING_WATER && !s.getValue(INSULATED));
    }
    @Override public boolean placeLiquid(LevelAccessor l, BlockPos p, BlockState s, FluidState fluid) {
        if (fluid.getType() != Fluids.WATER && fluid.getType() != Fluids.FLOWING_WATER) return false;
        if (!s.getValue(INSULATED)) {
            if (l instanceof Level level) Block.dropResources(s, level, p);
            l.setBlock(p, fluid.createLegacyBlock(), Block.UPDATE_ALL);
            return true;
        }
        // Keep vanilla source-only waterlogging; converting flow here multiplies water sources.
        return SimpleWaterloggedBlock.super.placeLiquid(l, p, s, fluid);
    }
    public static int connectionMask(BlockState s) {
        int mask = 0;
        for (int i = 0; i < 4; i++) if (s.getValue(PORTS[i])) mask |= 1 << i;
        return mask;
    }
    @Override public BlockState getPolymerBlockState(BlockState s, PacketContext c) {
        return models[s.getValue(WATERLOGGED) ? 2 : s.getValue(INSULATED) ? 1 : 0][connectionMask(s)];
    }
    @Override public BlockState getPolymerBreakEventBlockState(BlockState s, PacketContext c) {
        return s.getValue(INSULATED) ? Blocks.RESIN_BLOCK.defaultBlockState() : getPolymerBlockState(s, c);
    }
}
