package cn.choosec.economy.command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

/**
 * Registers all mod commands (preserved ServerRules + new economy).
 *
 * <p>This mod is server-only, so the command tree must only use vanilla
 * argument types; custom Brigadier argument types would have to exist on every
 * client as well and otherwise break Fabric registry sync on join.
 */
public final class CommandRegistry {

    private CommandRegistry() {
    }

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LegacyCommands.register(dispatcher);
            EconomyCommands.register(dispatcher);
        });
    }
}
