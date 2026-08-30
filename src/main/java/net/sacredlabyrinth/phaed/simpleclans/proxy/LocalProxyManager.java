package net.sacredlabyrinth.phaed.simpleclans.proxy;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.VoteResult;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.RequestPayload;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Used when {@code network.enabled} is off: everything stays on this server.
 */
public class LocalProxyManager implements ProxyManager {

    private final String serverName;

    public LocalProxyManager(@NotNull String serverName) {
        this.serverName = serverName;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public @NotNull String getServerName() {
        return serverName;
    }

    @Override
    public boolean isOnline(String playerName) {
        return playerName != null && Bukkit.getPlayerExact(playerName) != null;
    }

    @Override
    public @Nullable String getServerOf(@NotNull String playerName) {
        return null;
    }

    @Override
    public @Nullable UUID getRemoteUniqueId(@NotNull String playerName) {
        return null;
    }

    @Override
    public @NotNull List<String> getRemotePlayers() {
        return new ArrayList<>();
    }

    @Override
    public void sendMessage(SCMessage message) {
        // no network: SpigotChatHandler already delivered it locally
    }

    @Override
    public void sendMessage(String target, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if ("ALL".equals(target)) {
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
            return;
        }
        Player player = Bukkit.getPlayerExact(target);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    @Override
    public void sendUpdate(Clan clan) {
        // nothing to replicate
    }

    @Override
    public void sendUpdate(ClanPlayer cp) {
        // nothing to replicate
    }

    @Override
    public void sendDelete(Clan clan) {
        // nothing to replicate
    }

    @Override
    public void sendDelete(ClanPlayer cp) {
        // nothing to replicate
    }

    @Override
    public void sendRequest(@NotNull RequestPayload payload) {
        // nothing to replicate
    }

    @Override
    public void sendRequestVote(@NotNull String key, @NotNull String voter, @NotNull VoteResult result) {
        // nothing to relay
    }

    @Override
    public void sendRequestCancel(@NotNull String key) {
        // nothing to replicate
    }

    @Override
    public void announceJoin(@NotNull Player player) {
        // no presence registry
    }

    @Override
    public void announceQuit(@NotNull Player player) {
        // no presence registry
    }

    @Override
    public void shutdown() {
        // nothing to close
    }
}
