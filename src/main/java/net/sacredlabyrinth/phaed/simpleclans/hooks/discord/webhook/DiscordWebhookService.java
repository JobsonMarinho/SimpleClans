package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.AntiFloodSettings;
import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.EventTemplate;
import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.Settings;
import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.WebhookTarget;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.SERVER_NAME;

/**
 * Discord webhook audit pipeline for SimpleClans, mirroring the outbound
 * pattern proven in HypedAssinaturas/HypedProTags:
 * <ul>
 *   <li><b>Total failure isolation</b> - every public entry point swallows its
 *   own exceptions; a broken webhook can never affect a clan operation.</li>
 *   <li><b>Config-driven embeds</b> - each event is a template in discord.yml
 *   (title/color/description/thumbnail/footer) resolved with {placeholders};
 *   missing values render as an em dash, never as a raw placeholder.</li>
 *   <li><b>No HTTP on the main thread</b> - events are queued and drained by a
 *   single async repeating task.</li>
 *   <li><b>Rate limits respected</b> - per-webhook pacing plus full 429 /
 *   {@code Retry-After} handling with bounded re-scheduling.</li>
 *   <li><b>Two-level anti-flood</b> - per-event and per-(event, player)
 *   cooldowns with an aggregation window; HIGH/CRITICAL events bypass it.</li>
 *   <li><b>Local audit trail</b> - every decision lands in logs/discord.log.</li>
 * </ul>
 */
public final class DiscordWebhookService {

    private static final long INVALID_WEBHOOK_BACKOFF_MILLIS = 10 * 60_000L;
    private static final int MAX_RATE_LIMIT_REQUEUES = 5;
    private static final long SHUTDOWN_DRAIN_DEADLINE_MILLIS = 3000L;
    private static final String[] AVATAR_VAR_KEYS = {"player_avatar_url", "target_avatar_url", "staff_avatar_url"};

    private final SimpleClans plugin;
    private final AvatarProvider avatarProvider;
    private final DiscordLogger logger;
    private final DiscordAntiFlood antiFlood = new DiscordAntiFlood();

    private volatile DiscordWebhookConfig config;
    private @Nullable BukkitTask flushTask;

    private final ConcurrentLinkedQueue<Outgoing> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();

    private final ConcurrentHashMap<String, Long> lastTechnicalAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastSentByWebhook = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> webhookDisabledUntil = new ConcurrentHashMap<>();

    private final AtomicInteger sentCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private final AtomicInteger droppedCount = new AtomicInteger();

    public DiscordWebhookService(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
        this.logger = new DiscordLogger(plugin.getDataFolder());
        this.avatarProvider = new MinotarAvatarProvider(() -> config);
        this.config = safeLoadConfig();
        applyLoggerSettings();
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    public void start() {
        DiscordWebhookConfig c = config;
        if (c == null || !c.isEnabled()) {
            return;
        }
        long ticks = c.getAntiFlood().getFlushIntervalSeconds() * 20L;
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::flush, ticks, ticks);
        plugin.getLogger().info("Discord webhooks enabled (flush every "
                + c.getAntiFlood().getFlushIntervalSeconds() + "s)");
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
        applyLoggerSettings();
        antiFlood.clear();
        lastTechnicalAt.clear();
        webhookDisabledUntil.clear();
        logger.reloaded();
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
        int shortTimeout = Math.min(1500, c.getSettings().getTimeoutMs());
        Outgoing out;
        while ((out = poll()) != null) {
            if (System.currentTimeMillis() >= deadline) {
                int left = queueSize.get() + 1;
                plugin.getLogger().info("Discord webhook queue discarded on shutdown: " + left + " message(s)");
                break;
            }
            WebhookTarget target = c.getWebhook(out.webhook);
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

    private void applyLoggerSettings() {
        DiscordWebhookConfig c = config;
        if (c != null) {
            logger.configure(c.getSettings().getLogFile(), c.getSettings().isLogFailures());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Public API (every entry point is failure-isolated)                  */
    /* ------------------------------------------------------------------ */

    public @NotNull AvatarProvider getAvatarProvider() {
        return avatarProvider;
    }

    /**
     * Builds the avatar URL for the given identity (empty when avatars are off)
     */
    public @NotNull String avatarUrl(@Nullable UUID uuid, @Nullable String name) {
        return avatarProvider.avatarUrl(uuid, name);
    }

    /**
     * Fresh variable map pre-filled with the global placeholders ({date},
     * {server}). Missing keys render as an em dash, so callers only set what
     * they actually have.
     */
    public @NotNull Map<String, String> newVars() {
        Map<String, String> vars = new HashMap<>(32);
        vars.put("date", new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
        vars.put("server", ChatUtils.stripColors(plugin.getSettingsManager().getColored(SERVER_NAME)));
        return vars;
    }

    /**
     * Fires an event through the pipeline. The destination webhook, priority
     * and embed template all come from the event's entry in discord.yml;
     * events absent from the file never fire.
     *
     * @param eventKey  event key in discord.yml
     * @param playerKey player driving the action, used for per-player anti-flood (nullable)
     * @param vars      {placeholder} values for the template
     */
    public void fire(@NotNull String eventKey, @Nullable UUID playerKey, @NotNull Map<String, String> vars) {
        try {
            dispatch(eventKey, playerKey, vars);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Failed to dispatch Discord webhook " + eventKey, ex);
        }
    }

    /**
     * Reports a technical problem. Repeated reports with the same context are
     * deduplicated for a configurable window, so an exploding database cannot
     * flood Discord.
     */
    public void technical(@NotNull String context, @Nullable String detail) {
        try {
            DiscordWebhookConfig c = config;
            if (c == null || !c.isEnabled()) {
                return;
            }
            long now = System.currentTimeMillis();
            long dedupeMillis = c.getAntiFlood().getTechnicalDedupeSeconds() * 1000L;
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

            Map<String, String> vars = newVars();
            vars.put("context", context);
            vars.put("detail", detail == null || detail.isEmpty() ? "sem detalhes" : detail);
            dispatch("technical-error", null, vars);
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
     * Only the provided values end up in the embed; the rest render as em dash.
     */
    public void onStaffAction(@NotNull CommandSender staff, @NotNull String action, @Nullable Clan clan,
                              @Nullable String target, @Nullable String oldValue, @Nullable String newValue) {
        try {
            Map<String, String> vars = newVars();
            vars.put("staff", staff.getName());
            vars.put("action", action);
            if (staff instanceof Player) {
                UUID uuid = ((Player) staff).getUniqueId();
                vars.put("staff_uuid", uuid.toString());
                vars.put("staff_avatar_url", avatarUrl(uuid, staff.getName()));
            }
            if (clan != null) {
                vars.put("clan", clan.getName());
                vars.put("tag", clan.getTag().toUpperCase());
            }
            if (target != null) {
                vars.put("target", target);
            }
            if (oldValue != null) {
                vars.put("old_value", oldValue);
            }
            if (newValue != null) {
                vars.put("new_value", newValue);
            }
            dispatch("staff-action", null, vars);
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

    /**
     * A leader kicked a member out of the clan
     */
    public void onPlayerKicked(@NotNull CommandSender kicker, @NotNull ClanPlayer kicked, @NotNull Clan clan) {
        try {
            Map<String, String> vars = newVars();
            vars.put("player", kicker.getName());
            vars.put("target", kicked.getName());
            UUID uuid = kicked.getUniqueId();
            if (uuid != null) {
                vars.put("target_uuid", uuid.toString());
                vars.put("target_avatar_url", avatarUrl(uuid, kicked.getName()));
            }
            vars.put("clan", clan.getName());
            vars.put("tag", clan.getTag().toUpperCase());
            dispatch("player-kicked", uuid, vars);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Failed to dispatch kick webhook", ex);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Pipeline                                                            */
    /* ------------------------------------------------------------------ */

    private void dispatch(@NotNull String eventKey, @Nullable UUID playerKey, @NotNull Map<String, String> vars) {
        DiscordWebhookConfig c = config;
        if (c == null || !c.isEnabled()) {
            return;
        }
        EventTemplate event = c.getEvent(eventKey);
        if (event == null || !event.isEnabled()) {
            return;
        }

        WebhookTarget target = c.getWebhook(event.getWebhook());
        if (target == null || !target.isEnabled()) {
            logger.webhookDisabled(eventKey, event.getWebhook());
            return;
        }
        if (!target.isConfigured()) {
            logger.urlMissing(eventKey, event.getWebhook());
            return;
        }
        if (!target.isUsable()) {
            logger.urlInvalid(eventKey, event.getWebhook());
            return;
        }

        String playerName = vars.get("player");
        AntiFloodSettings antiFloodSettings = c.getAntiFlood();
        switch (antiFlood.check(antiFloodSettings, eventKey, event, playerKey == null ? null : playerKey.toString())) {
            case BLOCK_EVENT:
                logger.blocked(eventKey, playerName, "per-event");
                return;
            case BLOCK_PLAYER:
                logger.blocked(eventKey, playerName, "per-player");
                return;
            case AGGREGATED:
                logger.aggregated(eventKey, playerName);
                return;
            case ALLOW:
            default:
                break;
        }

        if (!event.isUsePlayerAvatar() || !c.getAvatar().isEnabled()) {
            for (String key : AVATAR_VAR_KEYS) {
                vars.remove(key);
            }
        }

        String mentionRoleId = c.getMentionRole().roleIdFor(eventKey);
        String json = DiscordPayload.render(event, vars, c.getSettings().getUsername(), mentionRoleId);
        Outgoing out = new Outgoing(event.getWebhook(), eventKey, playerName, json);

        if (!antiFloodSettings.isQueueEnabled()) {
            // queue disabled: still never on the main thread
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> process(config, out));
            return;
        }
        if (!offer(out, c)) {
            droppedCount.incrementAndGet();
            logger.queueFull(eventKey);
        }
    }

    private boolean offer(@NotNull Outgoing out, @NotNull DiscordWebhookConfig c) {
        if (queueSize.get() >= c.getAntiFlood().getQueueMaxSize()) {
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
            logger.queueFull(out.eventKey);
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

    private void process(@Nullable DiscordWebhookConfig c, @NotNull Outgoing out) {
        if (c == null) {
            return;
        }
        Settings settings = c.getSettings();
        long now = System.currentTimeMillis();

        Long disabledUntil = webhookDisabledUntil.get(out.webhook);
        if (disabledUntil != null) {
            if (now < disabledUntil) {
                droppedCount.incrementAndGet();
                return;
            }
            webhookDisabledUntil.remove(out.webhook);
        }

        if (out.notBefore > now) {
            requeue(out, c);
            return;
        }

        // per-webhook pacing keeps us safely under Discord's ~30 requests/min limit
        Long lastSent = lastSentByWebhook.get(out.webhook);
        if (lastSent != null && now - lastSent < settings.getMinSendIntervalMillis()) {
            out.notBefore = lastSent + settings.getMinSendIntervalMillis();
            requeue(out, c);
            return;
        }

        WebhookTarget target = c.getWebhook(out.webhook);
        if (target == null || !target.isUsable()) {
            droppedCount.incrementAndGet();
            return;
        }

        lastSentByWebhook.put(out.webhook, now);
        WebhookSender.SendResult result = WebhookSender.send(target.getUrl(), out.json, settings.getTimeoutMs());

        if (result.isSuccess()) {
            sentCount.incrementAndGet();
            logger.sent(out.eventKey, out.playerName, out.webhook);
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
                logger.failed(out.eventKey, out.webhook, "HTTP 429 (limite de reagendamentos)");
            }
            return;
        }

        if (result.getStatusCode() == 404 || result.getStatusCode() == 401 || result.getStatusCode() == 403) {
            // webhook deleted or token revoked: stop hammering it for a while
            webhookDisabledUntil.put(out.webhook, now + INVALID_WEBHOOK_BACKOFF_MILLIS);
            failedCount.incrementAndGet();
            logger.failed(out.eventKey, out.webhook, result.getReason() + " (webhook suspenso por 10 min)");
            plugin.getLogger().warning("Discord webhook '" + out.webhook + "' rejected (" + result.getReason()
                    + "); suspended for 10 minutes. Check discord.yml.");
            return;
        }

        if (result.getStatusCode() >= 400 && result.getStatusCode() < 500) {
            // other 4xx = payload/client problem; retrying cannot help
            failedCount.incrementAndGet();
            logger.failed(out.eventKey, out.webhook, result.getReason());
            return;
        }

        // 5xx or I/O error: transient, retry with linear backoff (when enabled)
        out.attempts++;
        if (settings.isRetryOnFail() && out.attempts <= settings.getMaxRetries()) {
            out.notBefore = now + 2000L * out.attempts;
            requeue(out, c);
        } else {
            failedCount.incrementAndGet();
            logger.failed(out.eventKey, out.webhook, result.getReason() + " (após " + out.attempts + " tentativa(s))");
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
        private final String webhook;
        private final String eventKey;
        private final String playerName;
        private final String json;
        private int attempts;
        private long notBefore;

        Outgoing(@NotNull String webhook, @NotNull String eventKey, @Nullable String playerName,
                 @NotNull String json) {
            this.webhook = webhook;
            this.eventKey = eventKey;
            this.playerName = playerName;
            this.json = json;
        }
    }
}
