package dev.sintesi.platform;

public interface Audience {

    boolean hasPermission(String permission);

    void sendMessage(String text);
}
