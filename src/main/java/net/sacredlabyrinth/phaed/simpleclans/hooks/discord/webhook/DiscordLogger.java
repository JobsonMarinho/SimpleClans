package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Thread-safe audit trail of the webhook pipeline in {@code logs/discord.log}
 * (inside the plugin data folder), with the standardized prefixes of the
 * reference pattern: SENT, FAILED, BLOCKED_ANTIFLOOD, AGGREGATED,
 * WEBHOOK_DISABLED, URL_MISSING, URL_INVALID, QUEUE_FULL, DISCORD_RELOAD.
 */
final class DiscordLogger {

    private final File dataFolder;
    private final Object lock = new Object();
    private volatile String relativePath = "logs/discord.log";
    private volatile boolean logFailures = true;

    DiscordLogger(@NotNull File dataFolder) {
        this.dataFolder = dataFolder;
    }

    void configure(@NotNull String relativePath, boolean logFailures) {
        this.relativePath = relativePath;
        this.logFailures = logFailures;
    }

    void sent(@NotNull String event, @Nullable String player, @NotNull String webhook) {
        write("SENT | Event=" + event + playerPart(player) + " | Webhook=" + webhook);
    }

    void failed(@NotNull String event, @NotNull String webhook, @NotNull String reason) {
        if (logFailures) {
            write("FAILED | Event=" + event + " | Webhook=" + webhook + " | Error=" + reason);
        }
    }

    void blocked(@NotNull String event, @Nullable String player, @NotNull String level) {
        write("BLOCKED_ANTIFLOOD | Event=" + event + playerPart(player) + " | level=" + level);
    }

    void aggregated(@NotNull String event, @Nullable String player) {
        write("AGGREGATED | Event=" + event + playerPart(player));
    }

    void webhookDisabled(@NotNull String event, @NotNull String webhook) {
        write("WEBHOOK_DISABLED | Event=" + event + " | Webhook=" + webhook);
    }

    void urlMissing(@NotNull String event, @NotNull String webhook) {
        write("URL_MISSING | Event=" + event + " | Webhook=" + webhook);
    }

    void urlInvalid(@NotNull String event, @NotNull String webhook) {
        write("URL_INVALID | Event=" + event + " | Webhook=" + webhook);
    }

    void queueFull(@NotNull String event) {
        write("QUEUE_FULL | Event=" + event + " | descartado");
    }

    void reloaded() {
        write("DISCORD_RELOAD | discord.yml recarregado");
    }

    private @NotNull String playerPart(@Nullable String player) {
        return player == null || player.isEmpty() ? "" : " | Player=" + player;
    }

    private void write(@NotNull String line) {
        synchronized (lock) {
            try {
                File file = new File(dataFolder, relativePath);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return;
                }
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
                    String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    writer.write("[" + stamp + "] " + line);
                    writer.newLine();
                }
            } catch (IOException ignored) {
                // the local log must never break the pipeline
            }
        }
    }
}
