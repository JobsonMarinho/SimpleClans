package net.sacredlabyrinth.phaed.simpleclans.proxy.redis;

import com.google.gson.JsonElement;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager;
import net.sacredlabyrinth.phaed.simpleclans.managers.StorageManager;
import net.sacredlabyrinth.phaed.simpleclans.network.MessageType;
import net.sacredlabyrinth.phaed.simpleclans.network.NetworkCodec;
import net.sacredlabyrinth.phaed.simpleclans.network.NetworkMessage;
import net.sacredlabyrinth.phaed.simpleclans.network.NetworkPresence;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.PresencePayload;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.PrivateMessagePayload;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.RequestPayload;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.RequestVotePayload;
import net.sacredlabyrinth.phaed.simpleclans.utils.ObjectUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.debug;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_PERSIST_RECEIVED;

/**
 * Applies an incoming {@link NetworkMessage} to this server's model.
 * <p>
 * Always invoked on the main thread: it touches the clan cache, permissions and
 * player display names.
 *
 * @since 2.19.4
 */
final class NetworkDispatcher {

    private final SimpleClans plugin;
    private final RedisProxyManager manager;
    private final NetworkCodec codec;
    private final NetworkPresence presence;

    NetworkDispatcher(@NotNull SimpleClans plugin, @NotNull RedisProxyManager manager,
                      @NotNull NetworkCodec codec, @NotNull NetworkPresence presence) {
        this.plugin = plugin;
        this.manager = manager;
        this.codec = codec;
        this.presence = presence;
    }

    void dispatch(@NotNull NetworkMessage message) {
        try {
            handle(message);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Error handling " + message, ex);
        }
    }

    private void handle(@NotNull NetworkMessage message) {
        JsonElement payload = message.getPayload();
        switch (message.getType()) {
            case UPDATE_CLAN:
                updateClan(payload);
                break;
            case UPDATE_CLAN_PLAYER:
                updateClanPlayer(payload);
                break;
            case DELETE_CLAN:
                deleteClan(payload);
                break;
            case DELETE_CLAN_PLAYER:
                deleteClanPlayer(payload);
                break;
            case CHAT:
                chat(payload);
                break;
            case BROADCAST:
                broadcast(payload);
                break;
            case PRIVATE_MESSAGE:
                privateMessage(payload);
                break;
            case PRESENCE_SYNC:
                presenceSync(message.getSource(), payload);
                break;
            case PRESENCE_JOIN:
                presenceJoin(message.getSource(), payload);
                break;
            case PRESENCE_QUIT:
                presenceQuit(message.getSource(), payload);
                break;
            case REQUEST_CREATE:
                requestCreate(message.getSource(), payload);
                break;
            case REQUEST_VOTE:
                requestVote(payload);
                break;
            case REQUEST_CANCEL:
                requestCancel(payload);
                break;
            case SYNC_REQUEST:
                answerSyncRequest(message.getSource());
                break;
            case SYNC_RESPONSE:
                Integer count = codec.fromTree(payload, Integer.class);
                plugin.getLogger().info(String.format("Received %d pending change(s) from %s",
                        count == null ? 0 : count, message.getSource()));
                break;
            default:
                debug("Unhandled network message type: " + message.getType());
        }
    }

    // ------------------------------------------------------------------
    // model
    // ------------------------------------------------------------------

    private void updateClan(@Nullable JsonElement payload) {
        Clan incoming = codec.fromTree(payload, Clan.class);
        if (incoming == null) {
            return;
        }
        ClanManager clanManager = plugin.getClanManager();
        Clan current = clanManager.getClan(incoming.getTag());
        if (current == null) {
            clanManager.importClan(incoming);
            incoming.validateWarring();
            markDirty(incoming);
            debug("Imported clan " + incoming.getTag());
            return;
        }
        try {
            ObjectUtils.updateFields(incoming, current);
        } catch (IllegalAccessException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not update clan " + incoming.getTag(), ex);
            return;
        }
        markDirty(current);
        debug("Updated clan " + current.getTag());
    }

    private void updateClanPlayer(@Nullable JsonElement payload) {
        ClanPlayer incoming = codec.fromTree(payload, ClanPlayer.class);
        if (incoming == null || incoming.getUniqueId() == null) {
            return;
        }
        ClanManager clanManager = plugin.getClanManager();
        ClanPlayer current = clanManager.getAnyClanPlayer(incoming.getUniqueId());

        if (current == null) {
            clanManager.importClanPlayer(incoming);
            attachToClan(incoming);
            markDirty(incoming);
            debug("Imported clan player " + incoming.getName());
            return;
        }

        Clan previousClan = current.getClan();
        Clan incomingClan = incoming.getClan();
        boolean clanChanged = previousClan != incomingClan;

        if (clanChanged && previousClan != null) {
            // done BEFORE the field copy: removeClanPermissions reads cp.getTag()
            // to build the auto-group name, so it has to still point at the old clan
            plugin.getPermissionsManager().removeClanPermissions(current);
            plugin.getPermissionsManager().removeClanPlayerPermissions(current);
            previousClan.removeMember(current.getUniqueId());
        }

        try {
            ObjectUtils.updateFields(incoming, current);
        } catch (IllegalAccessException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not update clan player " + incoming.getName(), ex);
            return;
        }

        if (clanChanged) {
            if (incomingClan != null) {
                attachToClan(current);
            }
            Player player = Bukkit.getPlayer(current.getUniqueId());
            if (player != null) {
                plugin.getClanManager().updateDisplayName(player);
            }
        }
        markDirty(current);
        debug("Updated clan player " + current.getName());
    }

    /**
     * Keeps the clan member list consistent even when the matching UPDATE_CLAN
     * never arrives, and grants what the membership implies locally.
     */
    private void attachToClan(@NotNull ClanPlayer cp) {
        Clan clan = cp.getClan();
        if (clan == null) {
            return;
        }
        clan.importMember(cp);
        plugin.getPermissionsManager().addClanPermissions(cp);
        plugin.getPermissionsManager().addPlayerPermissions(cp);
    }

    private void deleteClan(@Nullable JsonElement payload) {
        String tag = codec.fromTree(payload, String.class);
        if (tag == null) {
            return;
        }
        ClanManager clanManager = plugin.getClanManager();
        clanManager.removeClan(tag);
        for (ClanPlayer cp : clanManager.getAllClanPlayers()) {
            if (tag.equals(cp.getTag())) {
                plugin.getPermissionsManager().removeClanPermissions(cp);
                plugin.getPermissionsManager().removeClanPlayerPermissions(cp);
                cp.setClan(null);
                cp.setJoinDate(0);
                cp.setRank(null);
                cp.setLeader(false);
                Player player = Bukkit.getPlayer(cp.getUniqueId());
                if (player != null) {
                    clanManager.updateDisplayName(player);
                }
            }
        }
        debug("Deleted clan " + tag);
    }

    private void deleteClanPlayer(@Nullable JsonElement payload) {
        String raw = codec.fromTree(payload, String.class);
        if (raw == null) {
            return;
        }
        try {
            plugin.getClanManager().deleteClanPlayerFromMemory(UUID.fromString(raw));
            debug("Deleted clan player " + raw);
        } catch (IllegalArgumentException ex) {
            debug("Malformed UUID in DELETE_CLAN_PLAYER: " + raw);
        }
    }

    /**
     * Re-marks an object the peer changed so this server can flush it too.
     * <p>
     * Without it, a change made on the other server lives only in our memory and
     * dies with the peer if it crashes before its own periodic save. Both servers
     * write the same synchronized state, so the duplicate write is harmless.
     */
    private void markDirty(@NotNull Object object) {
        if (!plugin.getSettingsManager().is(NETWORK_PERSIST_RECEIVED)) {
            return;
        }
        StorageManager storage = plugin.getStorageManager();
        if (storage == null) {
            return; // still booting
        }
        if (object instanceof Clan) {
            storage.markPendingSave((Clan) object);
        } else if (object instanceof ClanPlayer) {
            storage.markPendingSave((ClanPlayer) object);
        }
    }

    // ------------------------------------------------------------------
    // chat
    // ------------------------------------------------------------------

    private void chat(@Nullable JsonElement payload) {
        SCMessage message = codec.fromTree(payload, SCMessage.class);
        if (message == null || message.getSender().getClan() == null) {
            return;
        }
        plugin.getChatManager().processChat(message);
    }

    private void broadcast(@Nullable JsonElement payload) {
        String message = codec.fromTree(payload, String.class);
        if (message == null || message.isEmpty()) {
            return;
        }
        Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(message));
    }

    private void privateMessage(@Nullable JsonElement payload) {
        PrivateMessagePayload data = codec.fromTree(payload, PrivateMessagePayload.class);
        if (data == null) {
            return;
        }
        Player player = Bukkit.getPlayerExact(data.getPlayer());
        if (player != null) {
            player.sendMessage(data.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // presence
    // ------------------------------------------------------------------

    private void presenceSync(@NotNull String source, @Nullable JsonElement payload) {
        PresencePayload data = codec.fromTree(payload, PresencePayload.class);
        if (data == null) {
            return;
        }
        List<NetworkPresence.Entry> entries = new ArrayList<>();
        for (PresencePayload.Entry entry : data.getPlayers()) {
            UUID uuid = entry.parseUniqueId();
            if (uuid != null) {
                entries.add(new NetworkPresence.Entry(source, uuid, entry.getName()));
            }
        }
        presence.sync(source, entries);
    }

    private void presenceJoin(@NotNull String source, @Nullable JsonElement payload) {
        PresencePayload.Entry entry = codec.fromTree(payload, PresencePayload.Entry.class);
        UUID uuid = entry == null ? null : entry.parseUniqueId();
        if (uuid != null) {
            presence.join(source, uuid, entry.getName());
        }
    }

    private void presenceQuit(@NotNull String source, @Nullable JsonElement payload) {
        PresencePayload.Entry entry = codec.fromTree(payload, PresencePayload.Entry.class);
        UUID uuid = entry == null ? null : entry.parseUniqueId();
        if (uuid != null) {
            presence.quit(source, uuid, entry.getName());
        }
    }

    // ------------------------------------------------------------------
    // requests
    // ------------------------------------------------------------------

    private void requestCreate(@NotNull String source, @Nullable JsonElement payload) {
        RequestPayload data = codec.fromTree(payload, RequestPayload.class);
        if (data != null) {
            plugin.getRequestManager().importRemoteRequest(source, data);
        }
    }

    private void requestVote(@Nullable JsonElement payload) {
        RequestVotePayload data = codec.fromTree(payload, RequestVotePayload.class);
        if (data != null) {
            plugin.getRequestManager().applyRemoteVote(data.getKey(), data.getVoter(), data.getResult());
        }
    }

    private void requestCancel(@Nullable JsonElement payload) {
        String key = codec.fromTree(payload, String.class);
        if (key != null) {
            plugin.getRequestManager().cancelReplica(key);
        }
    }

    // ------------------------------------------------------------------
    // boot handshake
    // ------------------------------------------------------------------

    /**
     * A peer just booted: it read the database, but everything we changed since
     * our last periodic save is missing from that read. Push exactly that delta.
     */
    private void answerSyncRequest(@NotNull String requester) {
        StorageManager storage = plugin.getStorageManager();
        if (storage == null) {
            return;
        }
        List<ClanPlayer> players = storage.getPendingClanPlayers();
        List<Clan> clans = storage.getPendingClans();

        // players first: a clan payload only keeps the members this server already knows
        for (ClanPlayer cp : players) {
            manager.publishTo(requester, MessageType.UPDATE_CLAN_PLAYER, cp);
        }
        for (Clan clan : clans) {
            manager.publishTo(requester, MessageType.UPDATE_CLAN, clan);
        }
        manager.publishTo(requester, MessageType.SYNC_RESPONSE, players.size() + clans.size());
        plugin.getLogger().info(String.format("Sent %d pending change(s) to %s",
                players.size() + clans.size(), requester));
    }
}
