package com.platform.notifications;

/** Mail sent BY the platform to its own users (verification, reset, billing notices). */
public interface PlatformMailer {
    void send(String to, String subject, String textBody, String htmlBody);
}
