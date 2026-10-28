package dev.sintesi.config;

public record UpdateCheckConfig(boolean enabled, int intervalHours, Channel channel) {

    public static final UpdateCheckConfig DEFAULTS = new UpdateCheckConfig(true, 6, Channel.AUTO);

    public enum Channel {
        AUTO(-1),
        RELEASE(0),
        BETA(1),
        ALPHA(2);

        private final int rank;

        Channel(int rank) {
            this.rank = rank;
        }

        public boolean includes(Channel other) {
            return other.rank <= rank;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
