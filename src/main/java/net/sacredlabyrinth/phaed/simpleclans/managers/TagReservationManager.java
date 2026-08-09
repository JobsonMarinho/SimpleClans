package net.sacredlabyrinth.phaed.simpleclans.managers;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.TAG_RESERVATION_DURATION_MINUTES;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.TAG_RESERVATION_ENABLED;

/**
 * Keeps recently deleted clan tags reserved for the player who deleted the clan,
 * so nobody else can immediately recreate a clan with the same tag.
 * <p>
 * Reservations live in memory for fast main-thread checks and are persisted to the
 * database so they survive restarts. Expired entries are removed lazily on access,
 * on every new reservation and on startup load.
 * </p>
 */
public final class TagReservationManager {

    private final SimpleClans plugin;
    private final ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();

    public TagReservationManager(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
        load();
    }

    /**
     * Loads reservations from the database (expired rows are purged in the same pass)
     */
    private void load() {
        reservations.clear();
        for (Map.Entry<String, Map.Entry<UUID, Long>> entry : plugin.getStorageManager().retrieveTagReservations().entrySet()) {
            reservations.put(entry.getKey(), new Reservation(entry.getValue().getKey(), entry.getValue().getValue()));
        }
        if (!reservations.isEmpty()) {
            plugin.getLogger().info(String.format("Loaded %d tag reservation(s)", reservations.size()));
        }
    }

    public boolean isEnabled() {
        return plugin.getSettingsManager().is(TAG_RESERVATION_ENABLED);
    }

    /**
     * @return the configured reservation duration, in minutes (never below 1)
     */
    public int getDurationMinutes() {
        return Math.max(1, plugin.getSettingsManager().getInt(TAG_RESERVATION_DURATION_MINUTES));
    }

    /**
     * Reserves a tag for the given player. Persists asynchronously.
     *
     * @param cleanTag the clean (colorless, lowercase) tag
     * @param owner    the player entitled to reuse the tag while reserved
     */
    public void reserve(@NotNull String cleanTag, @NotNull UUID owner) {
        if (!isEnabled() || cleanTag.isEmpty()) {
            return;
        }
        pruneExpired();
        long expiresAt = System.currentTimeMillis() + getDurationMinutes() * 60_000L;
        reservations.put(cleanTag, new Reservation(owner, expiresAt));
        runAsync(() -> plugin.getStorageManager().insertTagReservation(cleanTag, owner, expiresAt));
    }

    /**
     * Releases the reservation on a tag, if any. Persists asynchronously.
     */
    public void release(@NotNull String cleanTag) {
        if (reservations.remove(cleanTag) != null) {
            runAsync(() -> plugin.getStorageManager().deleteTagReservation(cleanTag));
        }
    }

    /**
     * Returns the active reservation for a tag, removing it transparently if expired
     */
    public @Nullable Reservation getReservation(@NotNull String cleanTag) {
        Reservation reservation = reservations.get(cleanTag);
        if (reservation == null) {
            return null;
        }
        if (reservation.isExpired()) {
            release(cleanTag);
            return null;
        }
        return reservation;
    }

    /**
     * Whether the tag is actively reserved for someone else than the given player
     *
     * @param cleanTag the clean tag to check
     * @param player   the player attempting to use the tag (null = no one, e.g. staff checks)
     */
    public boolean isReservedForOther(@NotNull String cleanTag, @Nullable UUID player) {
        if (!isEnabled()) {
            return false;
        }
        Reservation reservation = getReservation(cleanTag);
        return reservation != null && !reservation.getOwner().equals(player);
    }

    /**
     * Consumes the reservation if it belongs to the given player (called when the
     * former owner recreates the clan before the reservation expires)
     */
    public void consumeIfOwner(@NotNull String cleanTag, @NotNull UUID player) {
        Reservation reservation = getReservation(cleanTag);
        if (reservation != null && reservation.getOwner().equals(player)) {
            release(cleanTag);
        }
    }

    /**
     * Drops every expired entry from memory (cheap; the map holds few entries)
     */
    private void pruneExpired() {
        reservations.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private void runAsync(@NotNull Runnable runnable) {
        if (!plugin.isEnabled()) {
            // plugin shutting down: async tasks can no longer be scheduled
            runnable.run();
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                runnable.run();
            }
        }.runTaskAsynchronously(plugin);
    }

    public static final class Reservation {
        private final UUID owner;
        private final long expiresAt;

        Reservation(@NotNull UUID owner, long expiresAt) {
            this.owner = owner;
            this.expiresAt = expiresAt;
        }

        public @NotNull UUID getOwner() {
            return owner;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }

        /**
         * @return remaining minutes, rounded up (at least 1 while active)
         */
        public long getRemainingMinutes() {
            long remaining = expiresAt - System.currentTimeMillis();
            return Math.max(1, (remaining + 59_999) / 60_000);
        }
    }
}
