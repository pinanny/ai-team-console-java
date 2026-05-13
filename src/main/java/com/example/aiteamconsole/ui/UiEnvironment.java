package com.example.aiteamconsole.ui;

import javafx.stage.Stage;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * FX-thread scheduling and lightweight UI affordances wired from {@link com.example.aiteamconsole.AiTeamConsoleApplication}.
 */
public record UiEnvironment(
        Consumer<Runnable> fxRunner,
        UserDialogs dialogs,
        Consumer<String> openUrl,
        Supplier<Stage> primaryStage
) {
}
