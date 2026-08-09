package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of {@code discord.yml}, mirrored from the webhook pattern
 * proven in HypedAssinaturas/HypedProTags: multiple named webhooks, per-event
 * embed templates with {placeholders}, nested anti-flood, configurable avatar
 * provider and role mentions for high-priority events.
 * <p>
 * Loaded once on enable and rebuilt on reload; the hot path never touches disk.
 * </p>
 */
public final class DiscordWebhookConfig {

    private final boolean enabled;
    private final Settings settings;
    private final AntiFloodSettings antiFlood;
    private final AvatarSettings avatar;
    private final MentionRoleSettings mentionRole;
    private final Map<String, WebhookTarget> webhooks;
    private final Map<String, EventTemplate> events;

    private DiscordWebhookConfig(boolean enabled, Settings settings, AntiFloodSettings antiFlood,
                                 AvatarSettings avatar, MentionRoleSettings mentionRole,
                                 Map<String, WebhookTarget> webhooks, Map<String, EventTemplate> events) {
        this.enabled = enabled;
        this.settings = settings;
        this.antiFlood = antiFlood;
        this.avatar = avatar;
        this.mentionRole = mentionRole;
        this.webhooks = Collections.unmodifiableMap(webhooks);
        this.events = Collections.unmodifiableMap(events);
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

        Settings settings = new Settings(
                Math.max(1000, yaml.getInt("discord.settings.timeout-ms", 5000)),
                yaml.getBoolean("discord.settings.retry-on-fail", true),
                Math.max(0, yaml.getInt("discord.settings.max-retries", 2)),
                Math.max(0, yaml.getLong("discord.settings.min-send-interval-ms", 2000L)),
                yaml.getBoolean("discord.settings.log-failures", true),
                yaml.getString("discord.settings.log-file", "logs/discord.log"),
                yaml.getString("discord.settings.username", "SimpleClans"));

        AntiFloodSettings antiFlood = new AntiFloodSettings(
                yaml.getBoolean("discord.anti-flood.enabled", true),
                yaml.getBoolean("discord.anti-flood.ignore-anti-flood-for-critical", true),
                yaml.getBoolean("discord.anti-flood.per-player.enabled", true),
                Math.max(0, yaml.getInt("discord.anti-flood.per-player.cooldown-seconds", 10)),
                yaml.getBoolean("discord.anti-flood.per-event.enabled", true),
                Math.max(0, yaml.getInt("discord.anti-flood.per-event.cooldown-seconds", 3)),
                yaml.getBoolean("discord.anti-flood.queue.enabled", true),
                Math.max(10, yaml.getInt("discord.anti-flood.queue.max-size", 100)),
                Math.max(1, yaml.getInt("discord.anti-flood.queue.flush-interval-seconds", 5)),
                yaml.getBoolean("discord.anti-flood.aggregate.enabled", true),
                Math.max(0, yaml.getInt("discord.anti-flood.aggregate.same-event-window-seconds", 30)),
                Math.max(0, yaml.getInt("discord.anti-flood.technical-dedupe-seconds", 300)));

        String avatarType = yaml.getString("discord.player-avatar.minotar.type", "helm");
        AvatarSettings avatar = new AvatarSettings(
                yaml.getBoolean("discord.player-avatar.enabled", true),
                yaml.getBoolean("discord.player-avatar.minotar.use-uuid", true),
                avatarType,
                Math.max(16, yaml.getInt("discord.player-avatar.minotar.size", 100)),
                yaml.getString("discord.player-avatar.minotar.urls." + avatarType,
                        "https://minotar.net/" + avatarType + "/{identifier}/{size}.png"),
                yaml.getString("discord.player-avatar.fallback.identifier", "MHF_Steve"));

        List<String> mentionOnlyFor = yaml.getStringList("discord.priority.mention-role.only-for");
        MentionRoleSettings mentionRole = new MentionRoleSettings(
                yaml.getBoolean("discord.priority.mention-role.enabled", false),
                yaml.getString("discord.priority.mention-role.role-id", ""),
                mentionOnlyFor);

        Map<String, WebhookTarget> webhooks = new HashMap<>();
        ConfigurationSection webhooksSection = yaml.getConfigurationSection("discord.webhooks");
        if (webhooksSection != null) {
            for (String name : webhooksSection.getKeys(false)) {
                webhooks.put(name, new WebhookTarget(
                        webhooksSection.getBoolean(name + ".enabled", true),
                        webhooksSection.getString(name + ".url", "")));
            }
        }

        Map<String, EventTemplate> events = new HashMap<>();
        ConfigurationSection eventsSection = yaml.getConfigurationSection("discord.events");
        if (eventsSection != null) {
            for (String key : eventsSection.getKeys(false)) {
                events.put(key, loadEvent(eventsSection, key));
            }
        }

        return new DiscordWebhookConfig(yaml.getBoolean("discord.enabled", false),
                settings, antiFlood, avatar, mentionRole, webhooks, events);
    }

    private static @NotNull EventTemplate loadEvent(@NotNull ConfigurationSection section, @NotNull String key) {
        List<String> description = section.getStringList(key + ".embed.description");
        if (description.isEmpty()) {
            String single = section.getString(key + ".embed.description", "");
            if (!single.isEmpty()) {
                description = new ArrayList<>(Collections.singletonList(single));
            }
        }
        return new EventTemplate(
                section.getBoolean(key + ".enabled", true),
                section.getString(key + ".webhook", "alerts"),
                section.getString(key + ".priority", "MEDIUM"),
                section.getBoolean(key + ".use-player-avatar", true),
                section.getInt(key + ".anti-flood.per-player-cooldown-seconds", -1),
                section.getString(key + ".embed.title", key),
                section.getInt(key + ".embed.color", 3447003),
                description,
                section.getBoolean(key + ".embed.thumbnail.enabled", true),
                section.getString(key + ".embed.thumbnail.url", ""),
                section.getString(key + ".embed.footer", ""));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public @NotNull Settings getSettings() {
        return settings;
    }

    public @NotNull AntiFloodSettings getAntiFlood() {
        return antiFlood;
    }

    public @NotNull AvatarSettings getAvatar() {
        return avatar;
    }

    public @NotNull MentionRoleSettings getMentionRole() {
        return mentionRole;
    }

    public @Nullable WebhookTarget getWebhook(@NotNull String name) {
        return webhooks.get(name);
    }

    /**
     * Template for an event key; null when the event is not present in discord.yml
     * (following the reference pattern: unknown events never fire)
     */
    public @Nullable EventTemplate getEvent(@NotNull String eventKey) {
        return events.get(eventKey);
    }

    /* ------------------------------------------------------------------ */

    public static final class Settings {
        private final int timeoutMs;
        private final boolean retryOnFail;
        private final int maxRetries;
        private final long minSendIntervalMillis;
        private final boolean logFailures;
        private final String logFile;
        private final String username;

        Settings(int timeoutMs, boolean retryOnFail, int maxRetries, long minSendIntervalMillis,
                 boolean logFailures, @Nullable String logFile, @Nullable String username) {
            this.timeoutMs = timeoutMs;
            this.retryOnFail = retryOnFail;
            this.maxRetries = maxRetries;
            this.minSendIntervalMillis = minSendIntervalMillis;
            this.logFailures = logFailures;
            this.logFile = logFile == null || logFile.isEmpty() ? "logs/discord.log" : logFile;
            this.username = username == null ? "SimpleClans" : username;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public boolean isRetryOnFail() {
            return retryOnFail;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public long getMinSendIntervalMillis() {
            return minSendIntervalMillis;
        }

        public boolean isLogFailures() {
            return logFailures;
        }

        public @NotNull String getLogFile() {
            return logFile;
        }

        public @NotNull String getUsername() {
            return username;
        }
    }

    public static final class AntiFloodSettings {
        private final boolean enabled;
        private final boolean ignoreForCritical;
        private final boolean perPlayerEnabled;
        private final int perPlayerCooldownSeconds;
        private final boolean perEventEnabled;
        private final int perEventCooldownSeconds;
        private final boolean queueEnabled;
        private final int queueMaxSize;
        private final int flushIntervalSeconds;
        private final boolean aggregateEnabled;
        private final int aggregateWindowSeconds;
        private final int technicalDedupeSeconds;

        AntiFloodSettings(boolean enabled, boolean ignoreForCritical, boolean perPlayerEnabled,
                          int perPlayerCooldownSeconds, boolean perEventEnabled, int perEventCooldownSeconds,
                          boolean queueEnabled, int queueMaxSize, int flushIntervalSeconds,
                          boolean aggregateEnabled, int aggregateWindowSeconds, int technicalDedupeSeconds) {
            this.enabled = enabled;
            this.ignoreForCritical = ignoreForCritical;
            this.perPlayerEnabled = perPlayerEnabled;
            this.perPlayerCooldownSeconds = perPlayerCooldownSeconds;
            this.perEventEnabled = perEventEnabled;
            this.perEventCooldownSeconds = perEventCooldownSeconds;
            this.queueEnabled = queueEnabled;
            this.queueMaxSize = queueMaxSize;
            this.flushIntervalSeconds = flushIntervalSeconds;
            this.aggregateEnabled = aggregateEnabled;
            this.aggregateWindowSeconds = aggregateWindowSeconds;
            this.technicalDedupeSeconds = technicalDedupeSeconds;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isIgnoreForCritical() {
            return ignoreForCritical;
        }

        public boolean isPerPlayerEnabled() {
            return perPlayerEnabled;
        }

        public int getPerPlayerCooldownSeconds() {
            return perPlayerCooldownSeconds;
        }

        public boolean isPerEventEnabled() {
            return perEventEnabled;
        }

        public int getPerEventCooldownSeconds() {
            return perEventCooldownSeconds;
        }

        public boolean isQueueEnabled() {
            return queueEnabled;
        }

        public int getQueueMaxSize() {
            return queueMaxSize;
        }

        public int getFlushIntervalSeconds() {
            return flushIntervalSeconds;
        }

        public boolean isAggregateEnabled() {
            return aggregateEnabled;
        }

        public int getAggregateWindowSeconds() {
            return aggregateWindowSeconds;
        }

        public int getTechnicalDedupeSeconds() {
            return technicalDedupeSeconds;
        }
    }

    public static final class AvatarSettings {
        private final boolean enabled;
        private final boolean useUuid;
        private final String type;
        private final int size;
        private final String urlTemplate;
        private final String fallbackIdentifier;

        AvatarSettings(boolean enabled, boolean useUuid, @Nullable String type, int size,
                       @Nullable String urlTemplate, @Nullable String fallbackIdentifier) {
            this.enabled = enabled;
            this.useUuid = useUuid;
            this.type = type == null ? "helm" : type;
            this.size = size;
            this.urlTemplate = urlTemplate == null || urlTemplate.isEmpty()
                    ? "https://minotar.net/helm/{identifier}/{size}.png" : urlTemplate;
            this.fallbackIdentifier = fallbackIdentifier == null || fallbackIdentifier.isEmpty()
                    ? "MHF_Steve" : fallbackIdentifier;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isUseUuid() {
            return useUuid;
        }

        public @NotNull String getType() {
            return type;
        }

        public @NotNull String getFallbackIdentifier() {
            return fallbackIdentifier;
        }

        /**
         * Builds the final image URL for an identifier (name or dashless uuid)
         */
        public @NotNull String buildUrl(@NotNull String identifier) {
            return urlTemplate.replace("{identifier}", identifier).replace("{size}", String.valueOf(size));
        }
    }

    public static final class MentionRoleSettings {
        private final boolean enabled;
        private final String roleId;
        private final List<String> onlyFor;

        MentionRoleSettings(boolean enabled, @Nullable String roleId, @NotNull List<String> onlyFor) {
            this.enabled = enabled;
            this.roleId = roleId == null ? "" : roleId.trim();
            this.onlyFor = Collections.unmodifiableList(new ArrayList<>(onlyFor));
        }

        /**
         * Role id to mention for this event key, or null when no mention applies
         */
        public @Nullable String roleIdFor(@NotNull String eventKey) {
            if (!enabled || roleId.isEmpty() || !onlyFor.contains(eventKey)) {
                return null;
            }
            return roleId;
        }
    }

    public static final class WebhookTarget {
        private final boolean enabled;
        private final String url;

        WebhookTarget(boolean enabled, @Nullable String url) {
            this.enabled = enabled;
            this.url = url == null ? "" : url.trim();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public @NotNull String getUrl() {
            return url;
        }

        public boolean isConfigured() {
            return !url.isEmpty();
        }

        /**
         * Enabled with a plausible Discord webhook URL
         */
        public boolean isUsable() {
            return enabled && url.startsWith("https://") && url.contains("/webhooks/");
        }
    }

    public static final class EventTemplate {
        private final boolean enabled;
        private final String webhook;
        private final String priority;
        private final boolean usePlayerAvatar;
        private final int perPlayerCooldownOverride;
        private final String title;
        private final int color;
        private final List<String> descriptionLines;
        private final boolean thumbnailEnabled;
        private final String thumbnailUrl;
        private final String footer;

        EventTemplate(boolean enabled, @Nullable String webhook, @Nullable String priority, boolean usePlayerAvatar,
                      int perPlayerCooldownOverride, @Nullable String title, int color,
                      @NotNull List<String> descriptionLines, boolean thumbnailEnabled,
                      @Nullable String thumbnailUrl, @Nullable String footer) {
            this.enabled = enabled;
            this.webhook = webhook == null ? "alerts" : webhook;
            this.priority = priority == null ? "MEDIUM" : priority;
            this.usePlayerAvatar = usePlayerAvatar;
            this.perPlayerCooldownOverride = perPlayerCooldownOverride;
            this.title = title == null ? "" : title;
            this.color = color;
            this.descriptionLines = Collections.unmodifiableList(new ArrayList<>(descriptionLines));
            this.thumbnailEnabled = thumbnailEnabled;
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
            this.footer = footer == null ? "" : footer;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public @NotNull String getWebhook() {
            return webhook;
        }

        public @NotNull String getPriority() {
            return priority;
        }

        /**
         * HIGH and CRITICAL events bypass the anti-flood (when configured to)
         */
        public boolean isCritical() {
            return "CRITICAL".equalsIgnoreCase(priority) || "HIGH".equalsIgnoreCase(priority);
        }

        public boolean isUsePlayerAvatar() {
            return usePlayerAvatar;
        }

        /**
         * Per-event override of the per-player cooldown; -1 = use the global value
         */
        public int getPerPlayerCooldownOverride() {
            return perPlayerCooldownOverride;
        }

        public @NotNull String getTitle() {
            return title;
        }

        public int getColor() {
            return color;
        }

        public @NotNull List<String> getDescriptionLines() {
            return descriptionLines;
        }

        public boolean isThumbnailEnabled() {
            return thumbnailEnabled;
        }

        public @NotNull String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public @NotNull String getFooter() {
            return footer;
        }
    }
}
