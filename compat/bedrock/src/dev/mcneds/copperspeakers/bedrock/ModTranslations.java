package dev.mcneds.copperspeakers.bedrock;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.geysermc.geyser.text.MinecraftLocale;

/** Geyser renders Java translatable components before sending Bedrock item NBT. */
final class ModTranslations {
    static void install() throws IOException {
        try (var input = ModTranslations.class.getResourceAsStream("/copper-speakers-en_us.json")) {
            if (input == null) throw new IOException("Missing Copper Speakers translations");
            var names = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            MinecraftLocale.LOCALE_MAPPINGS.compute("en_us", (locale, existing) -> {
                var merged = new HashMap<String, String>();
                if (existing != null) merged.putAll(existing);
                names.entrySet().forEach(entry -> merged.put(entry.getKey(), entry.getValue().getAsString()));
                return merged;
            });
        }
    }
}
