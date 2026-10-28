package dev.sintesi.net;

import io.netty.util.NetUtil;
import java.net.InetAddress;

public final class NumericAddress {

    private NumericAddress() {
    }

    public static InetAddress parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 64) {
            return null;
        }
        return NetUtil.createInetAddressFromIpAddressString(text);
    }
}
