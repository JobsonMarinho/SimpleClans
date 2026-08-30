package net.sacredlabyrinth.phaed.simpleclans.network;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The role this server plays inside the clan network.
 *
 * @since 2.19.4
 */
public enum ServerMode {

    /**
     * Full SimpleClans. Every feature is available unless explicitly disabled.
     */
    NORMAL,

    /**
     * Secondary server sharing the same clan database, typically running a much
     * newer Minecraft version than the main one. It reads and writes the same
     * clans, but the features that cannot survive the version gap (or that would
     * race with the main server) are turned off.
     */
    GARDEN;

    public static @NotNull ServerMode parse(@NotNull String raw) {
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return NORMAL;
        }
    }
}
