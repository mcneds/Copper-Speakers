package dev.mcneds.copperspeakers.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.mcneds.copperspeakers.network.PreparedSongs;
import dev.mcneds.copperspeakers.network.TailCatalog;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Stream;

/** Operator controls for prepared joining, usable from chat or the server console. */
public final class JoiningCommands {
    private JoiningCommands() {}

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("copperspeakers").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("sync")
                                .then(Commands.literal("enable").then(soundArgument()
                                        .executes(c -> change(c.getSource(), IdentifierArgument.getId(c, "sound"), true, null))
                                        .then(Commands.argument("source", StringArgumentType.string())
                                                .executes(c -> change(c.getSource(), IdentifierArgument.getId(c, "sound"), true,
                                                        StringArgumentType.getString(c, "source"))))))
                                .then(Commands.literal("disable").then(soundArgument()
                                        .executes(c -> change(c.getSource(), IdentifierArgument.getId(c, "sound"), false, null))))
                                .then(Commands.literal("status").then(soundArgument()
                                        .executes(c -> status(c.getSource(), IdentifierArgument.getId(c, "sound"))))))));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, Identifier> soundArgument() {
        return Commands.argument("sound", IdentifierArgument.id()).suggests((context, builder) -> {
            var source = context.getSource();
            var songs = source.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).listElements()
                    .map(song -> song.value().soundEvent().value().location());
            return SharedSuggestionProvider.suggestResource(Stream.concat(songs,
                    TailCatalog.knownSounds(source.getServer()).stream()).distinct(), builder);
        });
    }
    private static int change(CommandSourceStack source, Identifier sound, boolean enabled, String recording) {
        return request(source, control -> control.setEnabled(sound.toString(), enabled, recording).thenApply(setting ->
                "Song rejoin sync " + (enabled ? "enabled" : "disabled") + " for " + sound + ". "
                        + (enabled ? "Check /copperspeakers sync status " + sound + "; reinsert the disc once ready."
                        : "Current listeners keep playing.")));
    }
    private static int status(CommandSourceStack source, Identifier sound) {
        return request(source, control -> control.status(sound.toString()).thenApply(status ->
                sound + ": " + status.state() + (status.error() == null ? "" : ". " + status.error())
                        + (status.state().equals("ready") ? ". Rejoin sync takes effect on the next disc insertion." : "")));
    }
    private static int request(CommandSourceStack source, Function<PreparedSongs, CompletableFuture<String>> action) {
        var control = TailCatalog.control(source.getServer());
        if (control == null) {
            source.sendFailure(Component.literal("Song rejoin sync requires a dedicated server with song sync control running."));
            return 0;
        }
        action.apply(control).whenComplete((message, error) -> source.getServer().execute(() -> {
            if (error == null) source.sendSuccess(() -> Component.literal(message), false);
            else {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                source.sendFailure(Component.literal("Song sync request failed: " + cause.getMessage()));
            }
        }));
        return 1;
    }
}
