package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable snapshot of {@code discord.yml}.
 * <p>
 * Loaded once on enable and rebuilt on reload; the hot path never touches disk.
 * </p>
 */
public final class DiscordWebhookConfig {

    public static final String ALERTS = "alerts";
    public static final String STAFF = "staff";
    public static final String TECHNICAL = "technical";

    private final boolean enabled;
    private final String username;
    private final Map<String, WebhookTarget> webhooks;
    private final Map<String, Boolean> events;
    private final int timeoutMs;
    private final int maxRetries;
    private final long minSendIntervalMillis;
    private final int queueMaxSize;
    private final int flushIntervalSeconds;
    private final int perEventCooldownSeconds;
    private final int perPlayerCooldownSeconds;
    private final boolean ignoreAntiFloodForCritical;
    private final int technicalDedupeSeconds;

    private DiscordWebhookConfig(boolean enabled, String username, Map<String, WebhookTarget> webhooks,
                                 Map<String, Boolean> events, int timeoutMs, int maxRetries,
                                 long minSendIntervalMillis, int queueMaxSize, int flushIntervalSeconds,
                                 int perEventCooldownSeconds, int perPlayerCooldownSeconds,
                                 boolean ignoreAntiFloodForCritical, int technicalDedupeSeconds) {
        this.enabled = enabled;
        this.username = username;
        this.webhooks = Collections.unmodifiableMap(webhooks);
        this.events = Collections.unmodifiableMap(events);
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.minSendIntervalMillis = minSendIntervalMillis;
        this.queueMaxSize = queueMaxSize;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.perEventCooldownSeconds = perEventCooldownSeconds;
        this.perPlayerCooldownSeconds = perPlayerCooldownSeconds;
        this.ignoreAntiFloodForCritical = ignoreAntiFloodForCritical;
        this.technicalDedupeSeconds = technicalDedupeSeconds;
    }

    /**
     * Loads (creating on first run) the {@code discord.yml} snapshot
     */
    public static @NotNull DiscordWebhookConfig load(@NotNull SimpleClans plugin) {
        File file = new File(plugin.getDataFolder(), "discord.yml");
        if (!file.exists()) {
            plugin.saveResource("discord.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        Map<String, WebhookTarget> webhooks = new HashMap<>();
        for (String name : new String[]{ALERTS, STAFF, TECHNICAL}) {
            String base = "discord.webhooks." + name + ".";
            webhooks.put(name, new WebhookTarget(
                    yaml.getBoolean(base + "enabled", true),
                    yaml.getString(base + "url", "")));
        }

        Map<String, Boolean> events = new HashMap<>();
        org.bukkit.configuration.ConfigurationSection eventsSection = yaml.getConfigurationSection("discord.events");
        if (eventsSection != null) {
            for (String key : eventsSection.getKeys(false)) {
                events.put(key, eventsSection.getBoolean(key, true));
            }
        }

        return new DiscordWebhookConfig(
                yaml.getBoolean("discord.enabled", false),
                yaml.getString("discord.username", "SimpleClans"),
                webhooks,
                events,
                Math.max(1000, yaml.getInt("discord.settings.timeout-ms", 5000)),
                Math.max(0, yaml.getInt("discord.settings.max-retries", 2)),
                Math.max(0, yaml.getLong("discord.settings.min-send-interval-ms", 2000L)),
                Math.max(10, yaml.getInt("discord.anti-flood.queue-max-size", 100)),
                Math.max(1, yaml.getInt("discord.anti-flood.flush-interval-seconds", 3)),
                Math.max(0, yaml.getInt("discord.anti-flood.per-event-cooldown-seconds", 2)),
                Math.max(0, yaml.getInt("discord.anti-flood.per-player-cooldown-seconds", 5)),
                yaml.getBoolean("discord.anti-flood.ignore-for-critical", true),
                Math.max(0, yaml.getInt("discord.anti-flood.technical-dedupe-seconds", 300)));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public @NotNull String getUsername() {
        return username;
    }

    public @Nullable WebhookTarget getWebhook(@NotNull String name) {
        return webhooks.get(name);
    }

    /**
     * Whether the event key is enabled; unknown keys default to enabled so new
     * events never need a config migration
     */
    public boolean isEventEnabled(@NotNull String eventKey) {
        Boolean value = events.get(eventKey);
        return value == null || value;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getMinSendIntervalMillis() {
        return minSendIntervalMillis;
    }

    public int getQueueMaxSize() {
        return queueMaxSize;
    }

    public int getFlushIntervalSeconds() {
        return flushIntervalSeconds;
    }

    public int getPerEventCooldownSeconds() {
        return perEventCooldownSeconds;
    }

    public int getPerPlayerCooldownSeconds() {
        return perPlayerCooldownSeconds;
    }

    public boolean isIgnoreAntiFloodForCritical() {
        return ignoreAntiFloodForCritical;
    }

    public int getTechnicalDedupeSeconds() {
        return technicalDedupeSeconds;
    }

    public static final class WebhookTarget {
        private final boolean enabled;
        private final String url;

        WebhookTarget(boolean enabled, @Nullable String url) {
            this.enabled = enabled;
            this.url = url == null ? "" : url.trim();
        }

        public @NotNull String getUrl() {
            return url;
        }

        /**
         * Enabled with a plausible Discord webhook URL
         */
        public boolean isUsable() {
            return enabled && url.startsWith("https://") && url.contains("/webhooks/");
        }
    }
}
