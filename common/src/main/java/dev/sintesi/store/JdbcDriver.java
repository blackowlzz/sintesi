package dev.sintesi.store;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Driver;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

public final class JdbcDriver {

    public static final String VERSION = "3.5.10";
    private static final String FILE = "mariadb-java-client-" + VERSION + ".jar";
    private static final String URL = "https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/" + VERSION
            + "/" + FILE;
    private static final String SHA256 = "919b8c1c771d9ee3465811462f242c9543ab401e140c64988ddbf1d8abcb18b2";
    private static final String CLASS = "org.mariadb.jdbc.Driver";

    private JdbcDriver() {
    }

    public static Driver load(Path libraries, Consumer<String> info) throws IOException {
        Path jar = libraries.resolve(FILE);
        if (!Files.exists(jar) || !SHA256.equals(sha256(Files.readAllBytes(jar)))) {
            info.accept("Downloading the MariaDB driver " + VERSION + " from Maven Central");
            byte[] data = download();
            if (!SHA256.equals(sha256(data))) {
                throw new IOException("the downloaded MariaDB driver does not match the expected checksum");
            }
            Files.createDirectories(libraries);
            Path temporary = Files.createTempFile(libraries, "driver", ".tmp");
            try {
                Files.write(temporary, data);
                Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, JdbcDriver.class.getClassLoader());
        try {
            return (Driver) Class.forName(CLASS, true, loader).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException failure) {
            throw new IOException("could not load the MariaDB driver: " + failure, failure);
        }
    }

    private static byte[] download() throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Sintesi").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                return body.readNBytes(8 << 20);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
