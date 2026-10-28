package dev.mcneds.copperspeakers.bedrock;

import java.util.Map;
import net.kyori.adventure.text.Component;
import org.geysermc.geyser.text.MinecraftLocale;
import org.geysermc.geyser.translator.text.MessageTranslator;

final class ModTranslationsTest {
    static void run() throws Exception {
        var original = MinecraftLocale.LOCALE_MAPPINGS.get("en_us");
        try {
            MinecraftLocale.LOCALE_MAPPINGS.put("en_us", Map.of("item.minecraft.stone", "Stone"));
            ModTranslations.install();
            ModTranslations.install();
            for (String locale : new String[]{"en_us", "de_de", "not_loaded"}) {
                for (var name : Map.of("block.copper_speakers.copper_wire", "Copper Wire",
                        "block.copper_speakers.speaker", "Speaker", "item.copper_speakers.wall_plate", "Wall Plate").entrySet()) {
                    String translated = MessageTranslator.convertMessage(Component.translatable(name.getKey()), locale).replace("\u00a7r", "");
                    if (!translated.equals(name.getValue())) throw new AssertionError("Untranslated item name: " + translated);
                }
                String renamed = "My block.copper_speakers.copper_wire";
                if (!MessageTranslator.convertMessage(Component.text(renamed), locale).replace("\u00a7r", "").equals(renamed))
                    throw new AssertionError("A player's literal custom name was rewritten");
            }
            if (!MinecraftLocale.getLocaleString("item.minecraft.stone", "en_us").equals("Stone"))
                throw new AssertionError("Vanilla translations were replaced");
            System.out.println("PASS: actual Geyser component rendering translates mod names and preserves literal renames");
        } finally {
            if (original == null) MinecraftLocale.LOCALE_MAPPINGS.remove("en_us");
            else MinecraftLocale.LOCALE_MAPPINGS.put("en_us", original);
        }
    }
}
