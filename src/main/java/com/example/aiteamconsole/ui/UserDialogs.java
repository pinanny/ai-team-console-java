package com.example.aiteamconsole.ui;

/**
 * Application-supplied UI surfaces for errors, confirmations, and lightweight notices.
 */
public interface UserDialogs {
    void showError(String message);

    void showInfo(String message);

    boolean confirm(String title, String headerText, String contentText);
}
