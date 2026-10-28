package dev.sintesi.net;

import dev.sintesi.config.ConfigException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public final class ProxyTrust {

    private record Range(byte[] network, int prefix) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Range> ranges;

    private ProxyTrust(List<Range> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    public static ProxyTrust parse(List<String> entries) {
        List<Range> ranges = new ArrayList<>();
        for (String entry : entries) {
            String text = entry.trim();
            int slash = text.indexOf('/');
            String host = slash < 0 ? text : text.substring(0, slash);
            InetAddress parsed = NumericAddress.parse(host);
            if (parsed == null) {
                throw new ConfigException("proxy-protocol.trusted-addresses must contain IP addresses or ranges, got "
                        + entry);
            }
            byte[] network = parsed.getAddress();
            int prefix = network.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(text.substring(slash + 1));
                } catch (NumberFormatException invalid) {
                    prefix = -1;
                }
                if (prefix < 0 || prefix > network.length * 8) {
                    throw new ConfigException("proxy-protocol.trusted-addresses has an invalid range " + entry);
                }
            }
            ranges.add(new Range(network, prefix));
        }
        return new ProxyTrust(ranges);
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public boolean trusts(InetAddress address) {
        byte[] raw = address.getAddress();
        for (Range range : ranges) {
            if (range.contains(raw)) {
                return true;
            }
        }
        return false;
    }
}
