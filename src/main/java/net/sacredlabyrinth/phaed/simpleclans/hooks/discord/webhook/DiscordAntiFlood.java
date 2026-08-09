package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.AntiFloodSettings;
import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.EventTemplate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-level anti-flood from the reference pattern: a global per-event cooldown
 * plus a per-(event, player) cooldown with an optional aggregation window.
 * Critical events bypass everything when so configured.
 */
final class DiscordAntiFlood {

    enum Decision {ALLOW, BLOCK_EVENT, BLOCK_PLAYER, AGGREGATED}

    private final ConcurrentHashMap<String, Long> lastEvent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPlayerEvent = new ConcurrentHashMap<>();

    @NotNull
    Decision check(@NotNull AntiFloodSettings cfg, @NotNull String eventKey, @NotNull EventTemplate event,
                   @Nullable String playerKey) {
        if (!cfg.isEnabled()) {
            return Decision.ALLOW;
        }
        if (event.isCritical() && cfg.isIgnoreForCritical()) {
            return Decision.ALLOW;
        }
        long now = System.currentTimeMillis();

        if (cfg.isPerEventEnabled() && cfg.getPerEventCooldownSeconds() > 0) {
            Long last = lastEvent.get(eventKey);
            if (last != null && now - last < cfg.getPerEventCooldownSeconds() * 1000L) {
                return Decision.BLOCK_EVENT;
            }
            lastEvent.put(eventKey, now);
        }

        if (playerKey != null && cfg.isPerPlayerEnabled()) {
            int cooldown = event.getPerPlayerCooldownOverride() >= 0
                    ? event.getPerPlayerCooldownOverride() : cfg.getPerPlayerCooldownSeconds();
            String combo = eventKey + "|" + playerKey;
            Long last = lastPlayerEvent.get(combo);
            if (last != null) {
                long elapsed = now - last;
                if (cooldown > 0 && elapsed < cooldown * 1000L) {
                    return Decision.BLOCK_PLAYER;
                }
                if (cfg.isAggregateEnabled() && elapsed < cfg.getAggregateWindowSeconds() * 1000L) {
                    lastPlayerEvent.put(combo, now);
                    return Decision.AGGREGATED;
                }
            }
            lastPlayerEvent.put(combo, now);
            if (lastPlayerEvent.size() > 1024) {
                long cutoff = now - Math.max(cooldown, cfg.getAggregateWindowSeconds()) * 1000L;
                lastPlayerEvent.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            }
        }
        return Decision.ALLOW;
    }

    void clear() {
        lastEvent.clear();
        lastPlayerEvent.clear();
    }
}
