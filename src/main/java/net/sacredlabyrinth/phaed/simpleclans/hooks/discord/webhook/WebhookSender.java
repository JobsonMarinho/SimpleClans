package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Plain {@link HttpURLConnection} POST to a Discord webhook URL.
 * <p>
 * Blocking - must only ever be called from an async thread. Performs a single
 * attempt; retry/backoff/rate-limit decisions belong to the queue in
 * {@link DiscordWebhookService}, which needs the {@code Retry-After} information
 * this class extracts from 429 responses.
 * </p>
 */
final class WebhookSender {

    private static final long DEFAULT_RETRY_AFTER_MILLIS = 2000L;

    private WebhookSender() {
    }

    static final class SendResult {
        private final boolean success;
        private final int statusCode;
        private final long retryAfterMillis;
        private final String reason;

        private SendResult(boolean success, int statusCode, long retryAfterMillis, @NotNull String reason) {
            this.success = success;
            this.statusCode = statusCode;
            this.retryAfterMillis = retryAfterMillis;
            this.reason = reason;
        }

        boolean isSuccess() {
            return success;
        }

        int getStatusCode() {
            return statusCode;
        }

        long getRetryAfterMillis() {
            return retryAfterMillis;
        }

        @NotNull
        String getReason() {
            return reason;
        }
    }

    static @NotNull SendResult send(@NotNull String url, @NotNull String jsonPayload, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "SimpleClans-DiscordWebhook");

            byte[] body = jsonPayload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int code = connection.getResponseCode();
            if (code >= 200 && code <= 299) {
                return new SendResult(true, code, 0L, "OK");
            }
            if (code == 429) {
                return new SendResult(false, code, parseRetryAfter(connection), "rate limited");
            }
            return new SendResult(false, code, 0L, "HTTP " + code);
        } catch (Exception ex) {
            return new SendResult(false, -1, 0L, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Reads how long Discord asked us to wait, in millis. Discord sends the value
     * in seconds (possibly fractional) via Retry-After / X-RateLimit-Reset-After.
     */
    private static long parseRetryAfter(@NotNull HttpURLConnection connection) {
        long parsed = parseSecondsHeader(connection.getHeaderField("Retry-After"));
        if (parsed <= 0) {
            parsed = parseSecondsHeader(connection.getHeaderField("X-RateLimit-Reset-After"));
        }
        return parsed > 0 ? parsed : DEFAULT_RETRY_AFTER_MILLIS;
    }

    private static long parseSecondsHeader(@Nullable String header) {
        if (header == null || header.isEmpty()) {
            return 0L;
        }
        try {
            return (long) Math.ceil(Double.parseDouble(header) * 1000.0);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
