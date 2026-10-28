package dev.sintesi.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Locale;

public final class VerifiedKey {

    private static final int SECRET_LENGTH = 16;
    private static final byte[] FINGERPRINT_INPUT = "sintesi verified store".getBytes(StandardCharsets.UTF_8);

    private final long k0;
    private final long k1;

    private VerifiedKey(byte[] secret) {
        ByteBuffer buffer = ByteBuffer.wrap(secret);
        this.k0 = buffer.getLong();
        this.k1 = buffer.getLong();
    }

    public static VerifiedKey random() {
        byte[] secret = new byte[SECRET_LENGTH];
        new SecureRandom().nextBytes(secret);
        return new VerifiedKey(secret);
    }

    public static VerifiedKey of(byte[] secret) {
        if (secret.length != SECRET_LENGTH) {
            throw new IllegalArgumentException("secret must be " + SECRET_LENGTH + " bytes");
        }
        return new VerifiedKey(secret.clone());
    }

    public static VerifiedKey loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] secret = Files.readAllBytes(file);
                if (secret.length == SECRET_LENGTH) {
                    return new VerifiedKey(secret);
                }
                throw new IllegalStateException(file + " is damaged, delete it to create a new secret"
                        + " (every player will be verified again)");
            }
            byte[] secret = new byte[SECRET_LENGTH];
            new SecureRandom().nextBytes(secret);
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException notPosix) {
                Files.createFile(file);
            } catch (FileAlreadyExistsException race) {
                return loadOrCreate(file);
            }
            Files.write(file, secret, StandardOpenOption.TRUNCATE_EXISTING);
            return new VerifiedKey(secret);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public long of(String name, long subnetKey) {
        byte[] lower = name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] data = ByteBuffer.allocate(lower.length + 8).put(lower).putLong(subnetKey).array();
        return SipHash.hash(k0, k1, data);
    }

    public long fingerprint() {
        return SipHash.hash(k0, k1, FINGERPRINT_INPUT);
    }
}
