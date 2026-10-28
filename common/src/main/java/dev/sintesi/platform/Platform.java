package dev.sintesi.platform;

import java.util.Collection;
import java.util.Optional;

public interface Platform {

    Collection<? extends PlatformPlayer> players();

    Optional<? extends PlatformPlayer> player(String name);

    Audience console();
}
