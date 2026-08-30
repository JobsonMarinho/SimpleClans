package net.sacredlabyrinth.phaed.simpleclans.proxy.redis;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.VoteResult;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager;
import net.sacredlabyrinth.phaed.simpleclans.network.MessageType;
import net.sacredlabyrinth.phaed.simpleclans.network.NetworkCodec;
import net.sacredlabyrinth.phaed.simpleclans.network.NetworkMessage;
import net.sacredlabyrinth.phaed.simpleclans.network.NetworkPresence;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.PresencePayload;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.PrivateMessagePayload;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.RequestPayload;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.RequestVotePayload;
import net.sacredlabyrinth.phaed.simpleclans.proxy.ProxyManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_BOOT_SYNC;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_CHANNEL;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_PRESENCE_INTERVAL;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_PRESENCE_TIMEOUT;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_REDIS_DATABASE;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_REDIS_HOST;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_REDIS_PASSWORD;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_REDIS_POOL_SIZE;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_REDIS_PORT;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_REDIS_SSL;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_REDIS_TIMEOUT;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_SERVERS;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_SERVER_NAME;

/**
 * Cross-server transport built on Redis pub/sub.
 * <p>
 * It replaces the old BungeeCord plugin-message transport, which had three
 * problems this one does not: it could only send while at least one player was
 * connected, it travelled through player connections, and it relied on the proxy
 * forwarding to every backend.
 *
 * @since 2.19.4
 */
public class RedisProxyManager implements ProxyManager {

    private static final String THREAD_NAME = "SimpleClans-Redis";
    private static final long RECONNECT_DELAY_MILLIS = 5000L;

    private final SimpleClans plugin;
    private final NetworkCodec codec;
    private final NetworkPresence presence = new NetworkPresence();
    private final NetworkDispatcher dispatcher;

    private final String serverName;
    private final String channel;
    private final Set<String> allowedServers = new HashSet<>();

    private final HostAndPort address;
    private final JedisClientConfig clientConfig;
    private final JedisPool pool;
    /** Single thread on purpose: it is what keeps published messages in order. */
    private final ExecutorService publisher;

    private final AtomicBoolean protocolWarned = new AtomicBoolean();
    private volatile boolean running = true;
    private volatile boolean connected;
    private @Nullable Thread subscriberThread;
    private volatile @Nullable JedisPubSub subscription;
    private @Nullable BukkitTask presenceTask;

    public RedisProxyManager(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
        this.codec = new NetworkCodec(plugin);

        SettingsManager settings = plugin.getSettingsManager();
        String configuredName = settings.getString(NETWORK_SERVER_NAME).trim();
        this.serverName = configuredName.isEmpty() ? "unnamed-" + UUID.randomUUID() : configuredName;
        this.channel = settings.getString(NETWORK_CHANNEL).trim();
        for (String server : settings.getStringList(NETWORK_SERVERS)) {
            allowedServers.add(server.trim());
        }
        this.dispatcher = new NetworkDispatcher(plugin, this, codec, presence);

        String password = settings.getString(NETWORK_REDIS_PASSWORD);
        int timeout = Math.max(500, settings.getInt(NETWORK_REDIS_TIMEOUT));
        this.address = new HostAndPort(settings.getString(NETWORK_REDIS_HOST), settings.getInt(NETWORK_REDIS_PORT));
        this.clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeout)
                .socketTimeoutMillis(timeout)
                .password(password == null || password.isEmpty() ? null : password)
                .database(settings.getInt(NETWORK_REDIS_DATABASE))
                .ssl(settings.is(NETWORK_REDIS_SSL))
                .clientName("SimpleClans")
                .build();

        int poolSize = Math.max(2, settings.getInt(NETWORK_REDIS_POOL_SIZE));
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(poolSize);
        poolConfig.setMaxIdle(Math.max(1, poolSize / 2));
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setBlockWhenExhausted(true);
        this.pool = new JedisPool(poolConfig, address, clientConfig);

        this.publisher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME + "-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Begins listening. Called once the clan cache has been loaded from the
     * database, so an incoming update is never applied over an empty cache that
     * {@code importFromDatabase} is about to wipe anyway.
     * <p>
     * Publishing, on the other hand, works from construction time: the startup
     * purge deletes clans from the shared database and the peers have to hear
     * about it.
     */
    public void start() {
        startSubscriber();
        startPresenceTask();
        if (plugin.getSettingsManager().is(NETWORK_BOOT_SYNC)) {
            // ask the peers for what they changed but have not written to the
            // database yet - that delta is exactly what our fresh read is missing
            publish(MessageType.SYNC_REQUEST, null, null);
        }
    }

    private void startSubscriber() {
        Thread thread = new Thread(() -> {
            long streak = 0;
            while (running) {
                try (Jedis jedis = new Jedis(address, clientConfig)) {
                    JedisPubSub pubSub = new JedisPubSub() {
                        @Override
                        public void onMessage(String incomingChannel, String rawMessage) {
                            receive(rawMessage);
                        }

                        @Override
                        public void onSubscribe(String incomingChannel, int subscribedChannels) {
                            connected = true;
                            plugin.getLogger().info(String.format(
                                    "Clan network connected as '%s' on channel '%s'", serverName, channel));
                        }
                    };
                    subscription = pubSub;
                    streak = 0;
                    jedis.subscribe(pubSub, channel);
                } catch (Exception ex) {
                    if (!running) {
                        break;
                    }
                    // only shout on the first failure of a streak, then stay quiet
                    if (streak == 0) {
                        plugin.getLogger().log(Level.WARNING,
                                "Lost the clan network connection, retrying every "
                                        + (RECONNECT_DELAY_MILLIS / 1000) + "s: " + ex.getMessage());
                    }
                    streak++;
                } finally {
                    connected = false;
                    subscription = null;
                }
                if (!running) {
                    break;
                }
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            connected = false;
        }, THREAD_NAME + "-subscriber");
        thread.setDaemon(true);
        thread.start();
        subscriberThread = thread;
    }

    private void startPresenceTask() {
        int interval = Math.max(5, plugin.getSettingsManager().getInt(NETWORK_PRESENCE_INTERVAL));
        long ticks = interval * 20L;
        presenceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            publishPresenceSnapshot();
            long timeoutSeconds = Math.max(interval * 2L, plugin.getSettingsManager().getInt(NETWORK_PRESENCE_TIMEOUT));
            presence.purgeStale(timeoutSeconds * 1000L);
        }, ticks, ticks);
    }

    private void publishPresenceSnapshot() {
        List<PresencePayload.Entry> entries = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            entries.add(new PresencePayload.Entry(player.getUniqueId(), player.getName()));
        }
        publish(MessageType.PRESENCE_SYNC, null, new PresencePayload(entries));
    }

    // ------------------------------------------------------------------
    // receiving
    // ------------------------------------------------------------------

    private void receive(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        NetworkMessage message = codec.decode(raw);
        if (message == null) {
            SimpleClans.debug("Dropped an unreadable network message");
            return;
        }
        if (message.getProtocol() != NetworkMessage.PROTOCOL) {
            // a peer running a different build: copying its fields by reflection
            // would corrupt our cache, so refuse and say so exactly once
            if (protocolWarned.compareAndSet(false, true)) {
                plugin.getLogger().severe(String.format(
                        "Server '%s' speaks clan-network protocol %d but we speak %d. "
                                + "Its messages are being ignored - deploy the same jar everywhere.",
                        message.getSource(), message.getProtocol(), NetworkMessage.PROTOCOL));
            }
            return;
        }
        if (serverName.equals(message.getSource())) {
            return; // our own echo
        }
        if (!allowedServers.isEmpty() && !allowedServers.contains(message.getSource())) {
            SimpleClans.debug("Server not allowed: " + message.getSource());
            return;
        }
        if (message.getTarget() != null && !serverName.equals(message.getTarget())) {
            return; // addressed to another server
        }
        runOnMainThread(() -> dispatcher.dispatch(message));
    }

    private void runOnMainThread(@NotNull Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            // scheduler already shut down; not worth logging during a stop
            SimpleClans.debug("Dropped a network message during shutdown");
        }
    }

    // ------------------------------------------------------------------
    // publishing
    // ------------------------------------------------------------------

    /**
     * Serializes on the calling thread, so the payload is a snapshot of the state
     * as it is right now, then hands the finished string to the publisher thread.
     */
    private void publish(@NotNull MessageType type, @Nullable String target, @Nullable Object payload) {
        if (!running) {
            return;
        }
        final String encoded;
        try {
            encoded = codec.encode(new NetworkMessage(serverName, target, type, codec.toTree(payload)));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not serialize a " + type + " network message", ex);
            return;
        }
        try {
            publisher.execute(() -> {
                try (Jedis jedis = pool.getResource()) {
                    jedis.publish(channel, encoded);
                } catch (Exception ex) {
                    SimpleClans.debug("Failed to publish " + type + ": " + ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            SimpleClans.debug("Publisher rejected " + type); // executor already shutting down
        }
    }

    /**
     * Used by the dispatcher to answer a {@link MessageType#SYNC_REQUEST}.
     */
    void publishTo(@NotNull String target, @NotNull MessageType type, @Nullable Object payload) {
        publish(type, target, payload);
    }

    // ------------------------------------------------------------------
    // ProxyManager
    // ------------------------------------------------------------------

    @Override
    public boolean isEnabled() {
        return running;
    }

    /**
     * @return whether the subscriber currently holds a live connection
     */
    public boolean isConnected() {
        return connected;
    }

    @Override
    public @NotNull String getServerName() {
        return serverName;
    }

    @Override
    public boolean isOnline(String playerName) {
        if (playerName == null) {
            return false;
        }
        return Bukkit.getPlayerExact(playerName) != null || presence.isOnline(playerName);
    }

    @Override
    public @Nullable String getServerOf(@NotNull String playerName) {
        return presence.getServer(playerName);
    }

    @Override
    public @Nullable UUID getRemoteUniqueId(@NotNull String playerName) {
        return presence.getUniqueId(playerName);
    }

    @Override
    public @NotNull List<String> getRemotePlayers() {
        return presence.getNames();
    }

    @Override
    public void sendMessage(SCMessage message) {
        publish(MessageType.CHAT, null, message);
    }

    @Override
    public void sendMessage(String target, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if ("ALL".equals(target)) {
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
            publish(MessageType.BROADCAST, null, message);
            return;
        }
        Player player = Bukkit.getPlayerExact(target);
        if (player != null) {
            player.sendMessage(message);
            return;
        }
        String server = presence.getServer(target);
        if (server == null) {
            return; // not online anywhere, nothing to deliver
        }
        publish(MessageType.PRIVATE_MESSAGE, server, new PrivateMessagePayload(target, message));
    }

    @Override
    public void sendUpdate(Clan clan) {
        publish(MessageType.UPDATE_CLAN, null, clan);
    }

    @Override
    public void sendUpdate(ClanPlayer cp) {
        publish(MessageType.UPDATE_CLAN_PLAYER, null, cp);
    }

    @Override
    public void sendDelete(Clan clan) {
        publish(MessageType.DELETE_CLAN, null, clan.getTag());
    }

    @Override
    public void sendDelete(ClanPlayer cp) {
        publish(MessageType.DELETE_CLAN_PLAYER, null, cp.getUniqueId().toString());
    }

    @Override
    public void sendRequest(@NotNull RequestPayload payload) {
        publish(MessageType.REQUEST_CREATE, null, payload);
    }

    @Override
    public void sendRequestVote(@NotNull String key, @NotNull String voter, @NotNull VoteResult result) {
        publish(MessageType.REQUEST_VOTE, null, new RequestVotePayload(key, voter, result));
    }

    @Override
    public void sendRequestCancel(@NotNull String key) {
        publish(MessageType.REQUEST_CANCEL, null, key);
    }

    @Override
    public void announceJoin(@NotNull Player player) {
        publish(MessageType.PRESENCE_JOIN, null,
                new PresencePayload.Entry(player.getUniqueId(), player.getName()));
    }

    @Override
    public void announceQuit(@NotNull Player player) {
        publish(MessageType.PRESENCE_QUIT, null,
                new PresencePayload.Entry(player.getUniqueId(), player.getName()));
    }

    @Override
    public void shutdown() {
        running = false;
        if (presenceTask != null) {
            presenceTask.cancel();
        }
        JedisPubSub pubSub = subscription;
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {
                // the connection may already be gone
            }
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        publisher.shutdown();
        try {
            // give the queued updates a moment to reach Redis before tearing the pool down
            if (!publisher.awaitTermination(3, TimeUnit.SECONDS)) {
                publisher.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            publisher.shutdownNow();
        }
        try {
            pool.close();
        } catch (Exception ignored) {
            // nothing useful to do while stopping
        }
        presence.clear();
    }
}
