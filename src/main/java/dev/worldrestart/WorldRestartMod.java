package dev.worldrestart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * /restart  ->  speichert & verlässt die Welt, löscht sie, setzt einen neuen Seed
 *               (alle anderen Einstellungen bleiben) und lädt sie neu.
 * Client-Mod: Nur der Host (Singleplayer / selbst gehostete Welt) braucht sie.
 */
public class WorldRestartMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("worldrestart");

    private enum State { IDLE, REQUESTED, DISCONNECTING, WIPING, OPENING, WAIT_JOIN }

    private static State state = State.IDLE;
    private static Path worldDir;
    private static String levelId;
    private static long newSeed;
    private static int counter;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("restart").executes(context -> {
                    Minecraft mc = context.getSource().getClient();

                    if (state != State.IDLE && state != State.WAIT_JOIN) {
                        context.getSource().sendError(Component.literal("[Restart] Already restarting..."));
                        return 0;
                    }
                    var server = mc.getSingleplayerServer();
                    if (server == null) {
                        context.getSource().sendError(Component.literal(
                                "[Restart] Only works in a singleplayer world or a world you host yourself."));
                        return 0;
                    }

                    Path dir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                    if (dir.getFileName() == null || !Files.isRegularFile(dir.resolve("level.dat"))) {
                        context.getSource().sendError(Component.literal("[Restart] Could not find the world folder."));
                        return 0;
                    }

                    worldDir = dir;
                    levelId = dir.getFileName().toString();
                    newSeed = ThreadLocalRandom.current().nextLong();
                    state = State.REQUESTED;
                    context.getSource().sendFeedback(Component.literal("[Restart] Restarting with a new seed..."));
                    return 1;
                })));

        ClientTickEvents.END_CLIENT_TICK.register(WorldRestartMod::tick);
    }

    private static void tick(Minecraft mc) {
        switch (state) {
            case REQUESTED -> {
                if (mc.level == null) {
                    state = State.IDLE;
                    return;
                }
                // Wie "Save and Quit to Title"
                mc.level.disconnect(Component.translatable("menu.savingLevel"));
                mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
                state = State.DISCONNECTING;
                counter = 0;
            }
            case DISCONNECTING -> {
                // Warten, bis der integrierte Server komplett gespeichert und beendet ist
                if (mc.level == null && mc.getSingleplayerServer() == null && ++counter > 10) {
                    state = State.WIPING;
                    counter = 0;
                }
            }
            case WIPING -> {
                try {
                    LevelDatEditor.wipeWorldFolder(worldDir);
                    LevelDatEditor.applyNewSeed(worldDir.resolve("level.dat"), newSeed);
                    state = State.OPENING;
                } catch (IOException e) {
                    // Dateien können kurz noch gesperrt sein: ~2 Sekunden lang jeden Tick erneut versuchen
                    if (++counter > 40) {
                        LOGGER.error("Restart failed", e);
                        state = State.IDLE;
                        mc.setScreen(new TitleScreen());
                    }
                }
            }
            case OPENING -> {
                state = State.WAIT_JOIN;
                counter = 0;
                mc.createWorldOpenFlows().openWorld(levelId, () -> {
                    state = State.IDLE;
                    mc.setScreen(new TitleScreen());
                });
            }
            case WAIT_JOIN -> {
                if (mc.player != null && mc.level != null && ++counter > 20) {
                    mc.player.displayClientMessage(Component.literal("[Restart] New world! Seed: " + newSeed), false);
                    state = State.IDLE;
                }
            }
            default -> { }
        }
    }
}
