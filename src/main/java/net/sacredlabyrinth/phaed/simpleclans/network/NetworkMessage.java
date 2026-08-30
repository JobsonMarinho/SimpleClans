package net.sacredlabyrinth.phaed.simpleclans.network;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Envelope wrapping every payload published on the network channel.
 *
 * @since 2.19.4
 */
public final class NetworkMessage {

    /**
     * Wire format version.
     * <p>
     * Both ends copy fields by reflection, so two servers running different
     * builds of the plugin would silently corrupt each other's cache. A message
     * whose protocol does not match ours is dropped and reported instead.
     */
    public static final int PROTOCOL = 1;

    private final int protocol;
    private final String source;
    private final @Nullable String target;
    private final MessageType type;
    private final @Nullable JsonElement payload;

    public NetworkMessage(@NotNull String source, @Nullable String target,
                          @NotNull MessageType type, @Nullable JsonElement payload) {
        this.protocol = PROTOCOL;
        this.source = source;
        this.target = target;
        this.type = type;
        this.payload = payload;
    }

    public int getProtocol() {
        return protocol;
    }

    /**
     * @return the name of the server that published this message
     */
    public @NotNull String getSource() {
        return source;
    }

    /**
     * @return the only server meant to act on this message, or null to address everyone
     */
    public @Nullable String getTarget() {
        return target;
    }

    public @NotNull MessageType getType() {
        return type;
    }

    public @Nullable JsonElement getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return String.format("NetworkMessage{%s from %s to %s}", type, source, target == null ? "*" : target);
    }
}
