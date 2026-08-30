package net.sacredlabyrinth.phaed.simpleclans.network.payload;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The players online on the sending server.
 *
 * @since 2.19.4
 */
public final class PresencePayload {

    private final List<Entry> players;

    public PresencePayload(@NotNull List<Entry> players) {
        this.players = players;
    }

    public @NotNull List<Entry> getPlayers() {
        return players == null ? new ArrayList<>() : players;
    }

    public static final class Entry {

        private final String uuid;
        private final String name;

        public Entry(@NotNull UUID uuid, @NotNull String name) {
            this.uuid = uuid.toString();
            this.name = name;
        }

        public @NotNull String getName() {
            return name;
        }

        /**
         * @return the parsed UUID, or null when the payload is malformed
         */
        public UUID parseUniqueId() {
            try {
                return UUID.fromString(uuid);
            } catch (IllegalArgumentException | NullPointerException ex) {
                return null;
            }
        }
    }
}
