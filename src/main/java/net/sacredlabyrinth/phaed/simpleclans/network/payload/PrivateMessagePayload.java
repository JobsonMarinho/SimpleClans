package net.sacredlabyrinth.phaed.simpleclans.network.payload;

import org.jetbrains.annotations.NotNull;

/**
 * A line addressed to a single player, wherever they happen to be connected.
 *
 * @since 2.19.4
 */
public final class PrivateMessagePayload {

    private final String player;
    private final String message;

    public PrivateMessagePayload(@NotNull String player, @NotNull String message) {
        this.player = player;
        this.message = message;
    }

    public String getPlayer() {
        return player;
    }

    public String getMessage() {
        return message;
    }
}
