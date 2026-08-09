package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.WebhookTarget;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.STAFF;
import static net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.TECHNICAL;

/**
 * Discord webhook audit pipeline for SimpleClans.
 * <p>
 * Design rules (mirrors the pattern proven in production elsewhere):
 * </p>
 * <ul>
 *   <li><b>Total failure isolation</b> - every public entry point swallows its own
 *   exceptions. A broken webhook can never affect a clan operation.</li>
 *   <li><b>No HTTP on the main thread</b> - events are queued and drained by a
 *   single async repeating task; no thread is created per webhook.</li>
 *   <li><b>Discord rate limits respected</b> - per-webhook pacing plus full 429 /
 *   {@code Retry-After} handling with bounded re-scheduling.</li>
 *   <li><b>Anti-flood</b> - per-event and per-player cooldowns, bounded queue with
 *   controlled drop, and deduplication of repeated technical errors.</li>
 * </ul>
 */
public final class DiscordWebhookService {

    private static final long INVALID_WEBHOOK_BACKOFF_MILLIS = 10 * 60_000L;
    private static final int MAX_RATE_LIMIT_REQUEUES = 5;
    private static final long SHUTDOWN_DRAIN_DEADLINE_MILLIS = 3000L;

    private final SimpleClans plugin;
    private final AvatarProvider avatarProvider = new MinotarAvatarProvider();

    private volatile DiscordWebhookConfig config;
    private @Nullable BukkitTask flushTask;

    private final ConcurrentLinkedQueue<Outgoing> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();

    private final ConcurrentHashMap<String, Long> lastEventAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPlayerEventAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTechnicalAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastSentByWebhook = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> webhookDisabledUntil = new ConcurrentHashMap<>();

    private final AtomicInteger sentCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private final AtomicInteger droppedCount = new AtomicInteger();

    public DiscordWebhookService(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
        this.config = safeLoadConfig();
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    public void start() {
        DiscordWebhookConfig c = config;
        if (c == null || !c.isEnabled()) {
            return;
        }
        long ticks = c.getFlushIntervalSeconds() * 20L;
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::flush, ticks, ticks);
        plugin.getLogger().info("Discord webhooks enabled (flush every " + c.getFlushIntervalSeconds() + "s)");
    }

    /**
     * Reloads {@code discord.yml} and restarts the flush task
     */
    public void reload() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        config = safeLoadConfig();
        lastEventAt.clear();
        lastPlayerEventAt.clear();
        lastTechnicalAt.clear();
        webhookDisabledUntil.clear();
        start();
    }

    /**
     * Cancels the flush task and drains the queue best-effort with a short
     * deadline, so a full queue can never hold the server shutdown hostage
     */
    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        DiscordWebhookConfig c = config;
        if (c == null || !c.isEnabled()) {
            return;
        }
        long deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_DEADLINE_MILLIS;
        int shortTimeout = Math.min(1500, c.getTimeoutMs());
        Outgoing out;
        while ((out = poll()) != null) {
            if (System.currentTimeMillis() >= deadline) {
                int left = queueSize.get() + 1;
                plugin.getLogger().info("Discord webhook queue discarded on shutdown: " + left + " message(s)");
                break;
            }
            WebhookTarget target = c.getWebhook(out.category);
            if (target != null && target.isUsable()) {
                WebhookSender.send(target.getUrl(), out.json, shortTimeout);
            }
        }
    }

    private @Nullable DiscordWebhookConfig safeLoadConfig() {
        try {
            return DiscordWebhookConfig.load(plugin);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not load discord.yml, Discord webhooks disabled", ex);
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Public API (every entry point is failure-isolated)                  */
    /* ------------------------------------------------------------------ */

    public @NotNull AvatarProvider getAvatarProvider() {
        return avatarProvider;
    }

    /**
     * Sends a clan-activity embed to the {@code alerts} webhook
     *
     * @param eventKey  event toggle key from discord.yml
     * @param playerKey player driving the action, used for per-player anti-flood (nullable)
     */
    public void alert(@NotNull String eventKey, @Nullable UUID playerKey, @NotNull DiscordEmbed embed) {
        dispatch(DiscordWebhookConfig.ALERTS, eventKey, playerKey, false, embed);
    }

    /**
     * Sends a staff-audit embed to the {@code staff} webhook (bypasses anti-flood:
     * administrative actions must always be recorded)
     */
    public void staff(@NotNull String eventKey, @NotNull DiscordEmbed embed) {
        dispatch(STAFF, eventKey, null, true, embed);
    }

    /**
     * Reports a technical problem to the {@code technical} webhook. Repeated
     * reports with the same context are deduplicated for a configurable window,
     * so an exploding database cannot flood Discord.
     */
    public void technical(@NotNull String context, @Nullable String detail) {
        try {
            DiscordWebhookConfig c = config;
            if (c == null || !c.isEnabled() || !c.isEventEnabled("technical-error")) {
                return;
            }
            long now = System.currentTimeMillis();
            long dedupeMillis = c.getTechnicalDedupeSeconds() * 1000L;
            Long last = lastTechnicalAt.get(context);
            if (last != null && now - last < dedupeMillis) {
                return;
            }
            lastTechnicalAt.put(context, now);
            // opportunistic cleanup keeps the dedupe map from growing forever
            if (lastTechnicalAt.size() > 256) {
                long cutoff = now - dedupeMillis;
                lastTechnicalAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            }

            DiscordEmbed embed = DiscordEmbed.of("⛔ Erro técnico", DiscordEmbed.COLOR_RED)
                    .field("Contexto", context, false)
                    .field("Detalhe", detail == null || detail.isEmpty() ? "sem detalhes" : detail, false)
                    .footer("SimpleClans • Log técnico");
            dispatch(TECHNICAL, "technical-error", null, true, embed);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Failed to dispatch technical webhook", ex);
        }
    }

    /**
     * Null-safe static bridge for code that runs before/without the service
     * (e.g. StorageManager catch blocks during startup)
     */
    public static void technicalLog(@NotNull String context, @Nullable String detail) {
        SimpleClans plugin = SimpleClans.getInstance();
        if (plugin == null) {
            return;
        }
        DiscordWebhookService service = plugin.getDiscordWebhookService();
        if (service != null) {
            service.technical(context, detail);
        }
    }

    /**
     * Generic staff-audit entry: one call per administrative command.
     * Only non-null fields end up in the embed.
     */
    public void onStaffAction(@NotNull CommandSender staff, @NotNull String action, @Nullable Clan clan,
                              @Nullable String target, @Nullable String oldValue, @Nullable String newValue) {
        try {
            DiscordEmbed embed = DiscordEmbed.of("🛠 " + action, DiscordEmbed.COLOR_PURPLE)
                    .field("Staff", staff.getName())
                    .footer("SimpleClans • Auditoria administrativa");
            if (staff instanceof Player) {
                UUID uuid = ((Player) staff).getUniqueId();
                embed.field("UUID do staff", uuid.toString());
                embed.thumbnail(avatarProvider.avatarUrl(uuid, staff.getName()));
            }
            if (clan != null) {
                embed.field("Clã", clan.getName());
                embed.field("TAG", clan.getTag().toUpperCase());
            }
            embed.field("Jogador afetado", target);
            embed.field("Valor anterior", oldValue);
            embed.field("Novo valor", newValue);
            staff("staff-action", embed);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Failed to dispatch staff webhook", ex);
        }
    }

    /**
     * Staff audit for the administrative tag change (old tag → new tag)
     */
    public void onStaffTagChange(@NotNull Player staff, @NotNull Clan clan, @NotNull String oldTag,
                                 @NotNull String newTag) {
        onStaffAction(staff, "Alteração de TAG", clan, null, oldTag, newTag);
    }

    /* ------------------------------------------------------------------ */
    /* Pipeline                                                            */
    /* ------------------------------------------------------------------ */

    private void dispatch(@NotNull String category, @NotNull String eventKey, @Nullable UUID playerKey,
                          boolean critical, @NotNull DiscordEmbed embed) {
        try {
            DiscordWebhookConfig c = config;
            if (c == null || !c.isEnabled() || !c.isEventEnabled(eventKey)) {
                return;
            }
            WebhookTarget target = c.getWebhook(category);
            if (target == null || !target.isUsable()) {
                return;
            }
            if ((!critical || !c.isIgnoreAntiFloodForCritical()) && !antiFloodAllows(c, eventKey, playerKey)) {
                return;
            }
            String json = embed.toPayload(c.getUsername());
            if (!offer(new Outgoing(category, eventKey, json), c)) {
                droppedCount.incrementAndGet();
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Failed to dispatch Discord webhook " + eventKey, ex);
        }
    }

    private boolean antiFloodAllows(@NotNull DiscordWebhookConfig c, @NotNull String eventKey,
                                    @Nullable UUID playerKey) {
        long now = System.currentTimeMillis();
        if (c.getPerEventCooldownSeconds() > 0) {
            Long last = lastEventAt.get(eventKey);
            if (last != null && now - last < c.getPerEventCooldownSeconds() * 1000L) {
                return false;
            }
            lastEventAt.put(eventKey, now);
        }
        if (playerKey != null && c.getPerPlayerCooldownSeconds() > 0) {
            String combo = eventKey + "|" + playerKey;
            Long last = lastPlayerEventAt.get(combo);
            if (last != null && now - last < c.getPerPlayerCooldownSeconds() * 1000L) {
                return false;
            }
            lastPlayerEventAt.put(combo, now);
            if (lastPlayerEventAt.size() > 1024) {
                long cutoff = now - c.getPerPlayerCooldownSeconds() * 1000L;
                lastPlayerEventAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            }
        }
        return true;
    }

    private boolean offer(@NotNull Outgoing out, @NotNull DiscordWebhookConfig c) {
        if (queueSize.get() >= c.getQueueMaxSize()) {
            return false;
        }
        queue.add(out);
        queueSize.incrementAndGet();
        return true;
    }

    private @Nullable Outgoing poll() {
        Outgoing out = queue.poll();
        if (out != null) {
            queueSize.decrementAndGet();
        }
        return out;
    }

    /**
     * Re-queues a paced/retrying message; counts it as dropped when the queue is full
     */
    private void requeue(@NotNull Outgoing out, @NotNull DiscordWebhookConfig c) {
        if (!offer(out, c)) {
            droppedCount.incrementAndGet();
        }
    }

    /**
     * Runs on the async flush task: drains everything that is ready to send,
     * re-queueing what is paced, rate-limited or waiting for a retry window
     */
    private void flush() {
        try {
            DiscordWebhookConfig c = config;
            if (c == null || !c.isEnabled()) {
                return;
            }
            // bounded by the snapshot size so re-queued items wait for the next cycle
            int snapshot = queueSize.get();
            for (int i = 0; i < snapshot; i++) {
                Outgoing out = poll();
                if (out == null) {
                    break;
                }
                process(c, out);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Discord webhook flush failed", ex);
        }
    }

    private void process(@NotNull DiscordWebhookConfig c, @NotNull Outgoing out) {
        long now = System.currentTimeMillis();

        Long disabledUntil = webhookDisabledUntil.get(out.category);
        if (disabledUntil != null) {
            if (now < disabledUntil) {
                droppedCount.incrementAndGet();
                return;
            }
            webhookDisabledUntil.remove(out.category);
        }

        if (out.notBefore > now) {
            requeue(out, c);
            return;
        }

        // per-webhook pacing keeps us safely under Discord's ~30 requests/min limit
        Long lastSent = lastSentByWebhook.get(out.category);
        if (lastSent != null && now - lastSent < c.getMinSendIntervalMillis()) {
            out.notBefore = lastSent + c.getMinSendIntervalMillis();
            requeue(out, c);
            return;
        }

        WebhookTarget target = c.getWebhook(out.category);
        if (target == null || !target.isUsable()) {
            droppedCount.incrementAndGet();
            return;
        }

        lastSentByWebhook.put(out.category, now);
        WebhookSender.SendResult result = WebhookSender.send(target.getUrl(), out.json, c.getTimeoutMs());

        if (result.isSuccess()) {
            sentCount.incrementAndGet();
            return;
        }

        if (result.getStatusCode() == 429) {
            // rate limited: honor Retry-After, but never re-schedule forever
            out.attempts++;
            if (out.attempts <= MAX_RATE_LIMIT_REQUEUES) {
                out.notBefore = now + result.getRetryAfterMillis();
                requeue(out, c);
            } else {
                failedCount.incrementAndGet();
            }
            return;
        }

        if (result.getStatusCode() == 404 || result.getStatusCode() == 401 || result.getStatusCode() == 403) {
            // webhook deleted or token revoked: stop hammering it for a while
            webhookDisabledUntil.put(out.category, now + INVALID_WEBHOOK_BACKOFF_MILLIS);
            failedCount.incrementAndGet();
            plugin.getLogger().warning("Discord webhook '" + out.category + "' rejected (" + result.getReason()
                    + "); suspended for 10 minutes. Check discord.yml.");
            return;
        }

        if (result.getStatusCode() >= 400 && result.getStatusCode() < 500) {
            // other 4xx = payload/client problem; retrying cannot help
            failedCount.incrementAndGet();
            plugin.getLogger().warning("Discord webhook '" + out.eventKey + "' failed: " + result.getReason());
            return;
        }

        // 5xx or I/O error: transient, retry with linear backoff
        out.attempts++;
        if (out.attempts <= c.getMaxRetries()) {
            out.notBefore = now + 2000L * out.attempts;
            requeue(out, c);
        } else {
            failedCount.incrementAndGet();
            plugin.getLogger().warning("Discord webhook '" + out.eventKey + "' dropped after "
                    + out.attempts + " attempt(s): " + result.getReason());
        }
    }

    public int getQueueSize() {
        return queueSize.get();
    }

    public int getSentCount() {
        return sentCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    public int getDroppedCount() {
        return droppedCount.get();
    }

    private static final class Outgoing {
        private final String category;
        private final String eventKey;
        private final String json;
        private int attempts;
        private long notBefore;

        Outgoing(@NotNull String category, @NotNull String eventKey, @NotNull String json) {
            this.category = category;
            this.eventKey = eventKey;
            this.json = json;
        }
    }
}
