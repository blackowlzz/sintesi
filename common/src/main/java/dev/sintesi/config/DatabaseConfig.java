package dev.sintesi.config;

public record DatabaseConfig(boolean enabled, String host, int port, String database, String username, String password,
        String tablePrefix, String sslMode, long syncMillis) {

    public String url() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + database + "?sslMode=" + sslMode
                + "&connectTimeout=5000&socketTimeout=15000";
    }
}
