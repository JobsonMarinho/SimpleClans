package net.sacredlabyrinth.phaed.simpleclans.proxy;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.VoteResult;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.RequestPayload;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The link between this server and the other servers sharing the same clan
 * database.
 *
 * @see net.sacredlabyrinth.phaed.simpleclans.proxy.redis.RedisProxyManager the Redis implementation
 * @see LocalProxyManager the do-nothing implementation used when the network is off
 */
public interface ProxyManager {

    /**
     * Begins listening for messages. Separate from the constructor because the
     * clan cache must be loaded from the database first.
     */
    default void start() {
    }

    /**
     * @return whether this server is actually talking to other servers
     */
    boolean isEnabled();

    /**
     * @return the name identifying this server on the network, never null
     */
    @NotNull String getServerName();

    /**
     * @return true if the player is connected here <i>or</i> to any other server
     */
    boolean isOnline(String playerName);

    /**
     * @return the server hosting the player, or null when they are not online
     * anywhere else (this server included: it answers for remote servers only)
     */
    @Nullable String getServerOf(@NotNull String playerName);

    /**
     * @return the UUID a remote server reported for this name, or null
     */
    @Nullable UUID getRemoteUniqueId(@NotNull String playerName);

    /**
     * @return the names online on the other servers (never includes local players)
     */
    @NotNull List<String> getRemotePlayers();

    void sendMessage(SCMessage message);

    /**
     * Delivers a raw line to one player wherever they are, or to everyone when
     * {@code target} is {@code "ALL"}.
     */
    void sendMessage(String target, String message);

    void sendUpdate(Clan clan);

    void sendUpdate(ClanPlayer cp);

    void sendDelete(Clan clan);

    void sendDelete(ClanPlayer cp);

    /**
     * Replicates a pending request so players on the other servers can answer it.
     */
    void sendRequest(@NotNull RequestPayload payload);

    /**
     * Relays a vote back to the server that owns the request.
     */
    void sendRequestVote(@NotNull String key, @NotNull String voter, @NotNull VoteResult result);

    /**
     * Tells every replica that a request is over.
     */
    void sendRequestCancel(@NotNull String key);

    void announceJoin(@NotNull Player player);

    void announceQuit(@NotNull Player player);

    /**
     * Closes the transport. Called from {@code onDisable}.
     */
    void shutdown();
}
