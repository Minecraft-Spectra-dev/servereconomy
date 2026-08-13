package cn.choosec.economy.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collection;
import java.util.List;

/**
 * Landmark name argument, shared by public warps and personal homes.
 *
 * <p>Brigadier's {@code word()} and unquoted {@code string()} types reject
 * non-ASCII characters, which makes CJK (Chinese) landmark names impossible to
 * type. This type keeps quoted-string support but extends unquoted names to any
 * non-whitespace character, so names such as {@code 主城} work directly.
 */
public final class LandmarkNameArgumentType implements ArgumentType<String> {

    private static final LandmarkNameArgumentType INSTANCE = new LandmarkNameArgumentType();
    private static final Collection<String> EXAMPLES = List.of("spawn", "主城");

    private LandmarkNameArgumentType() {
    }

    /** Singleton argument type allowing CJK and other non-whitespace characters. */
    public static LandmarkNameArgumentType name() {
        return INSTANCE;
    }

    /** Read a previously parsed name from a command context. */
    public static String getName(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) {
            return reader.readString();
        }
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
