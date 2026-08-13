package cn.choosec.economy.command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

/** Registers all mod commands (preserved ServerRules + new economy). */
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
