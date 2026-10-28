package dev.sintesi.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class IpRangeSet {

    public static final IpRangeSet EMPTY = new IpRangeSet(new long[0], new long[0], new long[0], new long[0]);

    private final long[] v4Starts;
    private final long[] v4Ends;
    private final long[] v6Starts;
    private final long[] v6Ends;

    private IpRangeSet(long[] v4Starts, long[] v4Ends, long[] v6Starts, long[] v6Ends) {
        this.v4Starts = v4Starts;
        this.v4Ends = v4Ends;
        this.v6Starts = v6Starts;
        this.v6Ends = v6Ends;
    }

    public static final class Builder {
        private final List<long[]> v4 = new ArrayList<>();
        private final List<long[]> v6 = new ArrayList<>();
        private int rejected;

        private int minimumV4Prefix;
        private int minimumV6Prefix;
        private int tooBroad;

        public Builder rejectBroaderThan(int v4Prefix, int v6Prefix) {
            this.minimumV4Prefix = v4Prefix;
            this.minimumV6Prefix = v6Prefix;
            return this;
        }

        public int tooBroad() {
            return tooBroad;
        }

        public boolean add(String line) {
            String text = line.strip();
            int comment = text.indexOf('#');
            if (comment >= 0) {
                text = text.substring(0, comment).strip();
            }
            int space = text.indexOf(' ');
            if (space >= 0) {
                text = text.substring(0, space);
            }
            if (text.isEmpty()) {
                return false;
            }
            int slash = text.indexOf('/');
            String host = slash < 0 ? text : text.substring(0, slash);
            InetAddress parsed = NumericAddress.parse(host);
            if (parsed == null) {
                rejected++;
                return false;
            }
            byte[] raw = parsed.getAddress();
            int bits = raw.length * 8;
            int prefix = bits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(text.substring(slash + 1));
                } catch (NumberFormatException invalid) {
                    prefix = -1;
                }
                if (prefix < 0 || prefix > bits) {
                    rejected++;
                    return false;
                }
            }
            if (prefix < (raw.length == 4 ? minimumV4Prefix : minimumV6Prefix)) {
                tooBroad++;
                return false;
            }
            if (raw.length == 4) {
                long value = toLong(raw, 4);
                long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                v4.add(new long[] {value & mask, (value & mask) | (~mask & 0xFFFFFFFFL)});
            } else {
                long high = toLong(raw, 8);
                int top = Math.min(prefix, 64);
                long mask = top == 0 ? 0 : -1L << (64 - top);
                v6.add(new long[] {high & mask, (high & mask) | ~mask});
            }
            return true;
        }

        public int rejected() {
            return rejected;
        }

        public IpRangeSet build() {
            long[][] four = merge(v4, false);
            long[][] six = merge(v6, true);
            return new IpRangeSet(four[0], four[1], six[0], six[1]);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public int size() {
        return v4Starts.length + v6Starts.length;
    }

    public double ipv4Coverage() {
        double covered = 0;
        for (int i = 0; i < v4Starts.length; i++) {
            covered += v4Ends[i] - v4Starts[i] + 1;
        }
        return covered / (1L << 32);
    }

    public boolean contains(InetAddress address) {
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            return find(v4Starts, v4Ends, toLong(raw, 4), false);
        }
        return find(v6Starts, v6Ends, toLong(raw, 8), true);
    }

    private static boolean find(long[] starts, long[] ends, long value, boolean unsigned) {
        int low = 0;
        int high = starts.length - 1;
        int found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (compare(starts[middle], value, unsigned) <= 0) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found >= 0 && compare(value, ends[found], unsigned) <= 0;
    }

    private static long[][] merge(List<long[]> ranges, boolean unsigned) {
        ranges.sort((a, b) -> compare(a[0], b[0], unsigned));
        long[] starts = new long[ranges.size()];
        long[] ends = new long[ranges.size()];
        int count = 0;
        for (long[] range : ranges) {
            if (count > 0 && (compare(range[0], ends[count - 1], unsigned) <= 0
                    || ends[count - 1] + 1 == range[0] && ends[count - 1] != (unsigned ? -1L : 0xFFFFFFFFL))) {
                if (compare(range[1], ends[count - 1], unsigned) > 0) {
                    ends[count - 1] = range[1];
                }
                continue;
            }
            starts[count] = range[0];
            ends[count] = range[1];
            count++;
        }
        return new long[][] {Arrays.copyOf(starts, count), Arrays.copyOf(ends, count)};
    }

    private static int compare(long a, long b, boolean unsigned) {
        return unsigned ? Long.compareUnsigned(a, b) : Long.compare(a, b);
    }

    private static long toLong(byte[] raw, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (raw[i] & 0xFFL);
        }
        return value;
    }
}
