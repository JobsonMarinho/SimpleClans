package net.sacredlabyrinth.phaed.simpleclans.network;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is connected to the <i>other</i> servers of the clan network.
 * <p>
 * Fed by {@link MessageType#PRESENCE_SYNC} snapshots plus
 * {@link MessageType#PRESENCE_JOIN}/{@link MessageType#PRESENCE_QUIT} deltas. The
 * periodic snapshot is what makes this self-healing: a lost delta, or a server
 * that died without saying goodbye, is corrected on the next round.
 *
 * @since 2.19.4
 */
public final class NetworkPresence {

    private final Map<String, Entry> byName = new ConcurrentHashMap<>();
    private final Map<UUID, String> namesByUniqueId = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenByServer = new ConcurrentHashMap<>();

    /**
     * Replaces everything we knew about {@code server} with a fresh snapshot.
     */
    public void sync(@NotNull String server, @NotNull Collection<Entry> players) {
        lastSeenByServer.put(server, System.currentTimeMillis());
        Set<String> incoming = new HashSet<>();
        for (Entry entry : players) {
            incoming.add(entry.getCleanName());
            put(entry);
        }
        // drop the players this server no longer reports
        forget(server, name -> !incoming.contains(name));
    }

    public void join(@NotNull String server, @NotNull UUID uniqueId, @NotNull String name) {
        lastSeenByServer.put(server, System.currentTimeMillis());
        put(new Entry(server, uniqueId, name));
    }

    public void quit(@NotNull String server, @NotNull UUID uniqueId, @NotNull String name) {
        lastSeenByServer.put(server, System.currentTimeMillis());
        String clean = name.toLowerCase(Locale.ROOT);
        Entry current = byName.get(clean);
        // ignore a late QUIT for a player who already reappeared somewhere else
        if (current != null && current.getServer().equals(server)) {
            byName.remove(clean);
        }
        String mapped = namesByUniqueId.get(uniqueId);
        if (mapped != null && mapped.equals(clean)) {
            namesByUniqueId.remove(uniqueId);
        }
    }

    /**
     * Forgets every player of a server that stopped announcing itself.
     *
     * @param staleMillis how long a server may stay silent before being dropped
     */
    public void purgeStale(long staleMillis) {
        long deadline = System.currentTimeMillis() - staleMillis;
        for (Map.Entry<String, Long> entry : new ArrayList<>(lastSeenByServer.entrySet())) {
            if (entry.getValue() < deadline) {
                lastSeenByServer.remove(entry.getKey());
                forget(entry.getKey(), name -> true);
            }
        }
    }

    public void clear() {
        byName.clear();
        namesByUniqueId.clear();
        lastSeenByServer.clear();
    }

    public boolean isOnline(@NotNull String name) {
        return byName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * @return the server hosting the player, or null if they are not on a remote server
     */
    public @Nullable String getServer(@NotNull String name) {
        Entry entry = byName.get(name.toLowerCase(Locale.ROOT));
        return entry == null ? null : entry.getServer();
    }

    public @Nullable UUID getUniqueId(@NotNull String name) {
        Entry entry = byName.get(name.toLowerCase(Locale.ROOT));
        return entry == null ? null : entry.getUniqueId();
    }

    public boolean isOnline(@NotNull UUID uniqueId) {
        return namesByUniqueId.containsKey(uniqueId);
    }

    public @Nullable String getName(@NotNull UUID uniqueId) {
        String clean = namesByUniqueId.get(uniqueId);
        Entry entry = clean == null ? null : byName.get(clean);
        return entry == null ? null : entry.getName();
    }

    public @NotNull List<String> getNames() {
        List<String> out = new ArrayList<>();
        for (Entry entry : byName.values()) {
            out.add(entry.getName());
        }
        return out;
    }

    private void put(@NotNull Entry entry) {
        byName.put(entry.getCleanName(), entry);
        namesByUniqueId.put(entry.getUniqueId(), entry.getCleanName());
    }

    private void forget(@NotNull String server, @NotNull NamePredicate predicate) {
        for (Map.Entry<String, Entry> e : new ArrayList<>(byName.entrySet())) {
            Entry entry = e.getValue();
            if (entry.getServer().equals(server) && predicate.test(e.getKey())) {
                byName.remove(e.getKey());
                namesByUniqueId.remove(entry.getUniqueId());
            }
        }
    }

    private interface NamePredicate {
        boolean test(String cleanName);
    }

    public static final class Entry {

        private final String server;
        private final UUID uniqueId;
        private final String name;

        public Entry(@NotNull String server, @NotNull UUID uniqueId, @NotNull String name) {
            this.server = server;
            this.uniqueId = uniqueId;
            this.name = name;
        }

        public @NotNull String getServer() {
            return server;
        }

        public @NotNull UUID getUniqueId() {
            return uniqueId;
        }

        public @NotNull String getName() {
            return name;
        }

        public @NotNull String getCleanName() {
            return name.toLowerCase(Locale.ROOT);
        }
    }
}
