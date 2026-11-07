package dev.mcneds.copperspeakers;

import dev.mcneds.copperspeakers.block.CopperWireBlock;
import dev.mcneds.copperspeakers.block.SpeakerBlock;
import dev.mcneds.copperspeakers.network.AudioNetworks;
import dev.mcneds.copperspeakers.network.WallPlates;
import dev.mcneds.copperspeakers.entity.WallPlateEntity;
import dev.mcneds.copperspeakers.item.WallPlateItem;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class CopperSpeakers implements ModInitializer {
    public static final String ID = "copper_speakers";
    public static CopperWireBlock WIRE;
    public static SpeakerBlock SPEAKER;
    public static WallPlateItem WALL_PLATE;
    public static EntityType<WallPlateEntity> WALL_PLATE_ENTITY;
    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ID, path); }

    @Override public void onInitialize() {
        WIRE = register("copper_wire", new CopperWireBlock(properties("copper_wire")
                .strength(0.2f).noCollision().noOcclusion().sound(SoundType.METAL)), Items.REDSTONE);
        SPEAKER = register("speaker", new SpeakerBlock(properties("speaker")
                .strength(1.0f).sound(SoundType.WOOD)), Items.NOTE_BLOCK);
        WALL_PLATE = Registry.register(BuiltInRegistries.ITEM, id("wall_plate"), new WallPlateItem(
                new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id("wall_plate")))));
        var plateKey = ResourceKey.create(Registries.ENTITY_TYPE, id("wall_plate"));
        WALL_PLATE_ENTITY = Registry.register(BuiltInRegistries.ENTITY_TYPE, plateKey,
                EntityType.Builder.<WallPlateEntity>of(WallPlateEntity::new, MobCategory.MISC)
                        .sized(.5f, .5f).clientTrackingRange(10).noUpdateInterval().noSummon().build(plateKey));
        PolymerEntityUtils.registerType(WALL_PLATE_ENTITY);
        PolymerResourcePackUtils.addModAssets(ID);
        PolymerResourcePackUtils.markAsRequired();
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof WallPlateEntity plate) WallPlates.get(level).add(plate);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof WallPlateEntity plate) WallPlates.get(level).remove(plate);
        });
        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            WallPlates.get(level).tick();
            AudioNetworks.get(level).tick();
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            AudioNetworks.get(level).unload(chunk.getPos());
            AudioNetworks.get(level).refreshChunk(chunk);
            WallPlates.get(level).chunkChanged(chunk.getPos());
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            AudioNetworks.get(level).load(chunk.getPos());
            AudioNetworks.get(level).refreshChunk(chunk);
            WallPlates.get(level).chunkChanged(chunk.getPos());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            AudioNetworks.clear(server);
            WallPlates.clear(server);
        });
    }

    private static BlockBehaviour.Properties properties(String name) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(name)));
    }
    private static <T extends Block> T register(String name, T block, Item fallback) {
        Registry.register(BuiltInRegistries.BLOCK, id(name), block);
        Registry.register(BuiltInRegistries.ITEM, id(name), new PolymerBlockItem(block,
                new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(name))).useBlockDescriptionPrefix(), fallback, true));
        return block;
    }
}
