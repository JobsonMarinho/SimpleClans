package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook.DiscordWebhookConfig.EventTemplate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * Renders the Discord webhook JSON payload from an {@link EventTemplate} plus a
 * {placeholder} variable map. Unknown placeholders resolve to an em dash, so an
 * embed never shows a raw {variable}. JSON is produced with the bundled Gson,
 * which handles all string escaping.
 */
final class DiscordPayload {

    private static final String EMPTY_VALUE = "—"; // em dash

    private DiscordPayload() {
    }

    static @NotNull String render(@NotNull EventTemplate template, @NotNull Map<String, String> vars,
                                  @NotNull String username, @Nullable String mentionRoleId) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", apply(template.getTitle(), vars));
        embed.addProperty("color", template.getColor());

        if (!template.getDescriptionLines().isEmpty()) {
            StringBuilder description = new StringBuilder();
            for (String line : template.getDescriptionLines()) {
                if (description.length() > 0) {
                    description.append('\n');
                }
                description.append(apply(line, vars));
            }
            embed.addProperty("description", description.toString());
        }

        if (template.isThumbnailEnabled()) {
            String thumbnailUrl = apply(template.getThumbnailUrl(), vars);
            // after resolution the url must be a real link (an unresolved avatar
            // placeholder becomes the em dash and is skipped entirely)
            if (thumbnailUrl.startsWith("http")) {
                JsonObject thumbnail = new JsonObject();
                thumbnail.addProperty("url", thumbnailUrl);
                embed.add("thumbnail", thumbnail);
            }
        }

        String footer = apply(template.getFooter(), vars);
        if (!footer.isEmpty()) {
            JsonObject footerObject = new JsonObject();
            footerObject.addProperty("text", footer);
            embed.add("footer", footerObject);
        }

        embed.addProperty("timestamp", Instant.now().toString());

        JsonObject payload = new JsonObject();
        if (!username.isEmpty()) {
            payload.addProperty("username", username);
        }
        if (mentionRoleId != null && !mentionRoleId.isEmpty()) {
            payload.addProperty("content", "<@&" + mentionRoleId + ">");
            JsonObject allowedMentions = new JsonObject();
            JsonArray roles = new JsonArray();
            roles.add(mentionRoleId);
            allowedMentions.add("roles", roles);
            payload.add("allowed_mentions", allowedMentions);
        }
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        return payload.toString();
    }

    /**
     * Resolves every {key} in the input; missing keys become an em dash
     */
    static @NotNull String apply(@NotNull String input, @NotNull Map<String, String> vars) {
        if (input.indexOf('{') < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length() + 16);
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '{') {
                int end = input.indexOf('}', i + 1);
                if (end > i) {
                    String value = vars.get(input.substring(i + 1, end));
                    out.append(value == null || value.isEmpty() ? EMPTY_VALUE : value);
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
