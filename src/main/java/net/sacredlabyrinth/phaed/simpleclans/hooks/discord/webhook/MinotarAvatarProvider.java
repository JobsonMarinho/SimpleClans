package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.AvatarSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Avatar provider backed by https://minotar.net/, driven by the
 * {@code player-avatar} section of discord.yml (type, size, use-uuid, fallback).
 * Only builds URLs - Discord fetches the image itself.
 */
public final class MinotarAvatarProvider implements AvatarProvider {

    private final Supplier<DiscordWebhookConfig> config;

    public MinotarAvatarProvider(@NotNull Supplier<DiscordWebhookConfig> config) {
        this.config = config;
    }

    @Override
    public @NotNull String avatarUrl(@Nullable UUID uuid, @Nullable String name) {
        DiscordWebhookConfig c = config.get();
        AvatarSettings avatar = c != null ? c.getAvatar()
                : new DiscordWebhookConfig.AvatarSettings(true, true, "helm", 100, null, null);
        if (!avatar.isEnabled()) {
            return "";
        }
        String identifier;
        if (avatar.isUseUuid() && uuid != null) {
            // dashless uuid: immune to nickname changes
            identifier = uuid.toString().replace("-", "");
        } else if (name != null && !name.isEmpty()) {
            identifier = name;
        } else {
            identifier = avatar.getFallbackIdentifier();
        }
        return avatar.buildUrl(identifier);
    }
}
