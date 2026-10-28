package dev.sintesi.net;

import java.net.InetAddress;

public final class VpnGuard {

    private volatile IpRangeSet blocked = IpRangeSet.EMPTY;
    private volatile IpRangeSet allowed = IpRangeSet.EMPTY;

    public void update(IpRangeSet blocked, IpRangeSet allowed) {
        this.allowed = allowed;
        this.blocked = blocked;
    }

    public boolean blocks(InetAddress address) {
        return blocked.contains(address) && !allowed.contains(address);
    }

    public int size() {
        return blocked.size();
    }
}
