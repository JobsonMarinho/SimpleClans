package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Small fluent builder for a Discord webhook payload with a single embed.
 * <p>
 * Empty or null values are silently skipped, so embeds never show blank fields.
 * JSON is produced with the Gson the plugin already bundles.
 * </p>
 */
public final class DiscordEmbed {

    public static final int COLOR_GREEN = 5763719;
    public static final int COLOR_YELLOW = 16705372;
    public static final int COLOR_RED = 15548997;
    public static final int COLOR_BLUE = 3447003;
    public static final int COLOR_PURPLE = 10181046;

    private static final int MAX_FIELDS = 25;
    private static final int MAX_FIELD_VALUE = 1024;

    private final JsonObject embed = new JsonObject();
    private final JsonArray fields = new JsonArray();

    private DiscordEmbed(@NotNull String title, int color) {
        embed.addProperty("title", title);
        embed.addProperty("color", color);
    }

    public static @NotNull DiscordEmbed of(@NotNull String title, int color) {
        return new DiscordEmbed(title, color);
    }

    public @NotNull DiscordEmbed description(@Nullable String description) {
        if (description != null && !description.isEmpty()) {
            embed.addProperty("description", description);
        }
        return this;
    }

    /**
     * Adds an inline field; skipped entirely when the value is null or empty
     */
    public @NotNull DiscordEmbed field(@NotNull String name, @Nullable String value) {
        return field(name, value, true);
    }

    public @NotNull DiscordEmbed field(@NotNull String name, @Nullable String value, boolean inline) {
        if (value == null || value.isEmpty() || fields.size() >= MAX_FIELDS) {
            return this;
        }
        if (value.length() > MAX_FIELD_VALUE) {
            value = value.substring(0, MAX_FIELD_VALUE - 1) + "…";
        }
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value);
        field.addProperty("inline", inline);
        fields.add(field);
        return this;
    }

    public @NotNull DiscordEmbed thumbnail(@Nullable String url) {
        if (url != null && !url.isEmpty()) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", url);
            embed.add("thumbnail", thumbnail);
        }
        return this;
    }

    public @NotNull DiscordEmbed footer(@Nullable String text) {
        if (text != null && !text.isEmpty()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", text);
            embed.add("footer", footer);
        }
        return this;
    }

    /**
     * Renders the final webhook payload
     *
     * @param username the webhook display name (skipped when empty)
     */
    public @NotNull String toPayload(@Nullable String username) {
        embed.addProperty("timestamp", Instant.now().toString());
        if (fields.size() > 0) {
            embed.add("fields", fields);
        }
        JsonObject payload = new JsonObject();
        if (username != null && !username.isEmpty()) {
            payload.addProperty("username", username);
        }
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        return payload.toString();
    }
}
