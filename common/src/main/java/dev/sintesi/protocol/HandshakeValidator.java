package dev.sintesi.protocol;

import java.util.Set;

public final class HandshakeValidator {

    private final int minProtocol;
    private final int maxProtocol;
    private final Set<String> requiredHostnames;

    public HandshakeValidator(int minProtocol, int maxProtocol, Set<String> requiredHostnames) {
        this.minProtocol = minProtocol;
        this.maxProtocol = maxProtocol;
        this.requiredHostnames = Set.copyOf(requiredHostnames);
    }

    public boolean accepts(Handshake handshake) {
        if (handshake.protocol() < minProtocol || handshake.protocol() > maxProtocol) {
            return false;
        }
        if (handshake.nextState() != Handshake.STATUS && handshake.nextState() != Handshake.LOGIN
                && handshake.nextState() != Handshake.TRANSFER) {
            return false;
        }
        if (handshake.port() <= 0) {
            return false;
        }
        String host = handshake.address();
        if (host.isEmpty() || !isHostnameSafe(host)) {
            return false;
        }
        return requiredHostnames.isEmpty() || requiredHostnames.contains(host.toLowerCase());
    }

    private static boolean isHostnameSafe(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == ':' || c == '[' || c == ']';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
