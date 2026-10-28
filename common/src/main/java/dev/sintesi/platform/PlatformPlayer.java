package dev.sintesi.platform;

import java.net.InetAddress;
import java.util.UUID;

public interface PlatformPlayer extends Audience {

    UUID uniqueId();

    String name();

    InetAddress address();

    void sendActionBar(String text);

    void showTitle(String title, String subtitle, int fadeInMillis, int stayMillis, int fadeOutMillis);
}
