package cn.choosec.economy.command;

import cn.choosec.economy.util.MessageUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/** Shared command helpers (feedback, player access, admin checks). */
public final class CommandUtil {

    private CommandUtil() {
    }

    public static void success(CommandSourceStack src, String text) {
        src.sendSuccess(() -> MessageUtil.parse(text), true);
    }

    public static void successQuiet(CommandSourceStack src, String text) {
        src.sendSuccess(() -> MessageUtil.parse(text), false);
    }

    public static void failure(CommandSourceStack src, String text) {
        src.sendFailure(MessageUtil.parse(text));
    }

    /**
     * {@code StringArgumentType.greedyString()} keeps surrounding quotes in the
     * value (unlike the quoted string parser). Strip one matching pair of
     * double quotes so trailing landmark/home names such as {@code "my home"}
     * keep working alongside unquoted CJK names.
     */
    public static String unquoteGreedy(String value) {
        if (value != null && value.length() >= 2
                && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static boolean requirePlayer(CommandSourceStack src) {
        return src.getPlayer() != null;
    }

    /** True if the executing player (or console with level 4) is an operator. */
    public static boolean isOp(CommandSourceStack src) {
        if (!src.isPlayer()) {
            return true; // console is always an operator
        }
        ServerPlayer p = src.getPlayer();
        return src.getServer().getPlayerList().isOp(new NameAndId(p.getUUID(), p.getName().getString()));
    }
}
