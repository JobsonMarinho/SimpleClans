package net.sacredlabyrinth.phaed.simpleclans.loggers;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.logging.*;

import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.DEBUG;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.ECONOMY_BANK_LOG_ENABLED;

/**
 * Logs all bank actions to one CSV file per day.
 * <p>
 * It accepts {@link BankLog} as a record
 * and uses the format from {@link CSVFormatter}
 * <p>
 * Typical usage:
 * <pre>{@code
 *     CSVBankLogger bankLogger = new CSVBankLogger(plugin);
 *     bankLogger.log(new BankLog(sender, clan, economyResponse, operation, cause, amount));
 * }</pre>
 *
 * @since 2.15.3
 */
public class CSVBankLogger implements BankLogger {

    private final Logger logger = Logger.getLogger(getClass().getSimpleName());
    private final SimpleClans plugin;
    /** FileHandler currently attached to {@link #logger}; null when disabled. */
    private FileHandler currentHandler;
    /** The date the current FileHandler was opened for. */
    private LocalDate handlerDate;

    static final int FILE_SIZE = (int) (100 * Math.pow(10, 6));
    static final int FILE_COUNT = 1;

    public CSVBankLogger(SimpleClans plugin) {
        this.plugin = plugin;

        if (!plugin.getSettingsManager().is(ECONOMY_BANK_LOG_ENABLED)) {
            return;
        }

        openHandlerForDate(LocalDate.now());
        if (!plugin.getSettingsManager().is(DEBUG)) {
            logger.setUseParentHandlers(false);
        }
    }

    @Override
    public synchronized void log(BankLog log) {
        if (!plugin.getSettingsManager().is(ECONOMY_BANK_LOG_ENABLED)) {
            return;
        }
        // Swap handler if the calendar day changed.
        LocalDate today = LocalDate.now();
        if (!today.equals(handlerDate)) {
            closeHandler();
            openHandlerForDate(today);
        }

        logger.log(Level.INFO, toCSV(log));
    }

    /** Closes the current FileHandler cleanly; called by SimpleClans.onDisable() or on rollover. */
    public synchronized void close() {
        closeHandler();
    }

    private void openHandlerForDate(LocalDate date) {
        try {
            FileHandler fh = new FileHandler(getFilePath(date), FILE_SIZE, FILE_COUNT, true);
            fh.setFormatter(new CSVFormatter());
            fh.setLevel(Level.INFO);
            fh.setEncoding("UTF-8");
            logger.addHandler(fh);
            currentHandler = fh;
            handlerDate = date;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void closeHandler() {
        if (currentHandler != null) {
            logger.removeHandler(currentHandler);
            currentHandler.close();
            currentHandler = null;
            handlerDate = null;
        }
    }

    /** Returns today's file path (kept for tests). */
    @NotNull
    String getFilePath() {
        return getFilePath(LocalDate.now());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @NotNull
    String getFilePath(LocalDate date) {
        String folderPath = plugin.getDataFolder().getPath() + File.separator + "logs" + File.separator + "bank";
        String filePath = folderPath + File.separator + date.toString() + ".csv";

        File folder = new File(folderPath);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        File file = new File(filePath);
        if (!file.exists()) {
            try {
                file.createNewFile();
                makeHeader(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return filePath;
    }

    private void makeHeader(File file) throws FileNotFoundException {
        try (PrintWriter pw = new PrintWriter(file)) {
            pw.println(String.join(",", BankLog.getHeader()));
        }
    }

    private String toCSV(BankLog log) {
        return String.join(",", log.getValues());
    }

    @NotNull
    public Logger getLogger() {
        return logger;
    }

    static class CSVFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            return record.getMessage() + "\n";
        }
    }
}
