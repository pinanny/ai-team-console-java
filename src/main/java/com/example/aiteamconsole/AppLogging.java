package com.example.aiteamconsole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * File + console logging under the application data directory. API keys and prompts are never written by helpers here.
 */
public final class AppLogging {
    private static volatile boolean initialized;

    private AppLogging() {
    }

    public static Path logDirectory() {
        return StateStore.defaultStore().stateFile().getParent().resolve("logs");
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            Path dir = logDirectory();
            Files.createDirectories(dir);
            Logger app = Logger.getLogger("com.example.aiteamconsole");
            app.setUseParentHandlers(false);
            app.setLevel(Level.ALL);

            FileHandler files = new FileHandler(
                    dir.resolve("app-%g.log").toString(),
                    5_000_000,
                    5,
                    true
            );
            files.setFormatter(new SimpleFormatter());
            files.setLevel(Level.ALL);
            app.addHandler(files);

            ConsoleHandler console = new ConsoleHandler();
            console.setLevel(Level.INFO);
            console.setFormatter(new SimpleFormatter());
            app.addHandler(console);

            initialized = true;
            app.info("Logging initialized; files in " + dir);
        } catch (IOException e) {
            Logger.getLogger(AppLogging.class.getName()).warning("Could not initialize file logging: " + e.getMessage());
        }
    }

    public static Logger get(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    /**
     * Truncate long strings for log lines (responses, etc.).
     */
    public static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "… (" + s.length() + " chars total)";
    }
}
