package cn.choosec.economy.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.mixin.ConnectionAccessor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legacy color / gradient parsing and tab-list variable substitution.
 * Replicates the exact rendering behaviour of the original ServerRules mod so
 * that titles, tab lists and chat prefixes look identical.
 */
public final class MessageUtil {

    private static final Pattern GRADIENT_PATTERN =
            Pattern.compile("<(gradient|g):#([0-9a-fA-F]{6}):#([0-9a-fA-F]{6})>(.*?)</\\1>");

    private MessageUtil() {
    }

    /** Parse a string with '&' (and section) codes plus &gt;gradient&lt; tags into a component. */
    public static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return parseFormatCodes(processGradientTags(text), '\u00a7', '&');
    }

    private static String processGradientTags(String text) {
        Matcher matcher = GRADIENT_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String startHex = matcher.group(2);
            String endHex = matcher.group(3);
            String content = matcher.group(4);
            int startColor = Integer.parseInt(startHex, 16);
            int endColor = Integer.parseInt(endHex, 16);
            StringBuilder gradient = new StringBuilder();
            int len = content.length();
            for (int i = 0; i < len; i++) {
                float t = len > 1 ? (float) i / (float) (len - 1) : 0.0f;
                int color = lerpColor(startColor, endColor, t);
                gradient.append("&#").append(String.format("%06x", color)).append(content.charAt(i));
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(gradient.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static int lerpColor(int from, int to, float t) {
        int r = clamp((int) ((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t));
        int g = clamp((int) ((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t));
        int b = clamp((int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t));
        return r << 16 | g << 8 | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static Component parseFormatCodes(String text, char... prefixes) {
        MutableComponent result = Component.empty();
        StringBuilder current = new StringBuilder();
        ChatFormatting colorFormat = null;
        TextColor hexColor = null;
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        boolean obfuscated = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isPrefix = false;
            for (char p : prefixes) {
                if (c == p) {
                    isPrefix = true;
                    break;
                }
            }
            if (isPrefix && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '#' && i + 7 < text.length()) {
                    String hex = text.substring(i + 2, i + 8);
                    try {
                        int rgb = Integer.parseInt(hex, 16);
                        if (current.length() > 0) {
                            result.append(buildStyled(current.toString(), colorFormat, hexColor, bold, italic, underlined, strikethrough, obfuscated));
                            current.setLength(0);
                        }
                        hexColor = TextColor.fromRgb(rgb);
                        colorFormat = null;
                        i += 7;
                        continue;
                    } catch (NumberFormatException ignored) {
                        // not a hex colour; fall through
                    }
                }
                if (current.length() > 0) {
                    result.append(buildStyled(current.toString(), colorFormat, hexColor, bold, italic, underlined, strikethrough, obfuscated));
                    current.setLength(0);
                }
                char code = text.charAt(++i);
                ChatFormatting cf = ChatFormatting.getByCode(code);
                if (cf == null) {
                    continue;
                }
                if (cf == ChatFormatting.RESET) {
                    colorFormat = null;
                    hexColor = null;
                    obfuscated = false;
                    strikethrough = false;
                    underlined = false;
                    italic = false;
                    bold = false;
                    continue;
                }
                if (cf.ordinal() <= 15) {
                    colorFormat = cf;
                    hexColor = null;
                    obfuscated = false;
                    strikethrough = false;
                    underlined = false;
                    italic = false;
                    bold = false;
                    continue;
                }
                if (cf == ChatFormatting.BOLD) {
                    bold = true;
                    continue;
                }
                if (cf == ChatFormatting.ITALIC) {
                    italic = true;
                    continue;
                }
                if (cf == ChatFormatting.UNDERLINE) {
                    underlined = true;
                    continue;
                }
                if (cf == ChatFormatting.STRIKETHROUGH) {
                    strikethrough = true;
                    continue;
                }
                if (cf == ChatFormatting.OBFUSCATED) {
                    obfuscated = true;
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            result.append(buildStyled(current.toString(), colorFormat, hexColor, bold, italic, underlined, strikethrough, obfuscated));
        }
        return result;
    }

    private static MutableComponent buildStyled(String text, ChatFormatting colorFormat, TextColor hexColor,
                                                boolean bold, boolean italic, boolean underlined,
                                                boolean strikethrough, boolean obfuscated) {
        Style style = Style.EMPTY;
        if (hexColor != null) {
            style = style.withColor(hexColor);
        } else if (colorFormat != null) {
            style = style.applyFormat(colorFormat);
        }
        if (bold) {
            style = style.applyFormat(ChatFormatting.BOLD);
        }
        if (italic) {
            style = style.applyFormat(ChatFormatting.ITALIC);
        }
        if (underlined) {
            style = style.applyFormat(ChatFormatting.UNDERLINE);
        }
        if (strikethrough) {
            style = style.applyFormat(ChatFormatting.STRIKETHROUGH);
        }
        if (obfuscated) {
            style = style.applyFormat(ChatFormatting.OBFUSCATED);
        }
        return Component.literal(text).withStyle(style);
    }

    /**
     * Resolve tab-list variables (%player%, %ping%, %online%, %max%, %health%,
     * %maxhealth%, %x%, %y%, %z%, %xp%, %balance%, %currency%) and parse colour codes.
     */
    public static Component resolveVariables(String text, ServerPlayer player) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        MinecraftServer server = null;
        ServerLevel serverLevel = player.level();
        if (serverLevel instanceof ServerLevel) {
            server = ((ServerLevel) serverLevel).getServer();
        }
        String result = text
                .replace("%player%", player.getName().getString())
                .replace("%ping%", String.valueOf(((ConnectionAccessor) player.connection).getLatency()))
                .replace("%online%", server != null ? String.valueOf(server.getPlayerCount()) : "?")
                .replace("%max%", server != null ? String.valueOf(server.getMaxPlayers()) : "?")
                .replace("%health%", String.valueOf((int) player.getHealth()))
                .replace("%maxhealth%", String.valueOf((int) player.getMaxHealth()))
                .replace("%x%", String.valueOf((int) player.getX()))
                .replace("%y%", String.valueOf((int) player.getY()))
                .replace("%z%", String.valueOf((int) player.getZ()))
                .replace("%xp%", String.valueOf(player.experienceLevel))
                .replace("%balance%", MoneyUtil.format(EconomyService.getBalance(player.getUUID(), player.getName().getString())))
                .replace("%currency%", ConfigManager.get().currencyAbbreviation);
        return parse(result);
    }
}
