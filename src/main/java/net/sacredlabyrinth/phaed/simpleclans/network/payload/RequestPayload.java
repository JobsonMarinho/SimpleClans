package net.sacredlabyrinth.phaed.simpleclans.network.payload;

import net.sacredlabyrinth.phaed.simpleclans.ClanRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A pending request replicated to the other servers, so the players sitting on
 * them can see it and answer it.
 * <p>
 * Only the owner (the server that opened the request) ever runs the outcome;
 * the replicas exist to display the prompt and to relay votes back.
 *
 * @since 2.19.4
 */
public final class RequestPayload {

    private final String key;
    private final ClanRequest type;
    private final String clanTag;
    private final String requesterUuid;
    private final String target;
    private final @Nullable String targetUuid;
    private final String message;
    private final List<String> acceptorUuids;

    public RequestPayload(@NotNull String key, @NotNull ClanRequest type, @NotNull String clanTag,
                          @NotNull UUID requesterUuid, @NotNull String target, @Nullable UUID targetUuid,
                          @NotNull String message, @NotNull List<UUID> acceptorUuids) {
        this.key = key;
        this.type = type;
        this.clanTag = clanTag;
        this.requesterUuid = requesterUuid.toString();
        this.target = target;
        this.targetUuid = targetUuid == null ? null : targetUuid.toString();
        this.message = message;
        this.acceptorUuids = new ArrayList<>();
        for (UUID uuid : acceptorUuids) {
            this.acceptorUuids.add(uuid.toString());
        }
    }

    public String getKey() {
        return key;
    }

    public ClanRequest getType() {
        return type;
    }

    public String getClanTag() {
        return clanTag;
    }

    public String getTarget() {
        return target;
    }

    public String getMessage() {
        return message;
    }

    public UUID parseRequesterUniqueId() {
        return parse(requesterUuid);
    }

    public @Nullable UUID parseTargetUniqueId() {
        return parse(targetUuid);
    }

    public @NotNull List<UUID> parseAcceptorUniqueIds() {
        List<UUID> out = new ArrayList<>();
        if (acceptorUuids == null) {
            return out;
        }
        for (String raw : acceptorUuids) {
            UUID uuid = parse(raw);
            if (uuid != null) {
                out.add(uuid);
            }
        }
        return out;
    }

    private static @Nullable UUID parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
