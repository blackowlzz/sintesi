package dev.sintesi.check;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CookieSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int TAG_LENGTH = 16;
    public static final int COOKIE_LENGTH = 8 + TAG_LENGTH;

    private final byte[] currentKey;
    private final byte[] previousKey;
    private final long lifetimeMillis;

    public CookieSigner(byte[] currentKey, byte[] previousKey, long lifetimeMillis) {
        this.currentKey = currentKey.clone();
        this.previousKey = previousKey == null ? null : previousKey.clone();
        this.lifetimeMillis = lifetimeMillis;
    }

    public static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public byte[] issue(String name, long subnetKey, long nowMillis) {
        ByteBuffer buffer = ByteBuffer.allocate(COOKIE_LENGTH);
        buffer.putLong(nowMillis);
        buffer.put(tag(currentKey, name, subnetKey, nowMillis), 0, TAG_LENGTH);
        return buffer.array();
    }

    public boolean validate(byte[] cookie, String name, long subnetKey, long nowMillis) {
        if (cookie == null || cookie.length != COOKIE_LENGTH) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(cookie);
        long issuedAt = buffer.getLong();
        if (issuedAt > nowMillis || nowMillis - issuedAt > lifetimeMillis) {
            return false;
        }
        byte[] provided = new byte[TAG_LENGTH];
        buffer.get(provided);
        return matches(currentKey, provided, name, subnetKey, issuedAt)
                || (previousKey != null && matches(previousKey, provided, name, subnetKey, issuedAt));
    }

    private static boolean matches(byte[] key, byte[] provided, String name, long subnetKey, long issuedAt) {
        byte[] expected = tag(key, name, subnetKey, issuedAt);
        byte[] truncated = new byte[TAG_LENGTH];
        System.arraycopy(expected, 0, truncated, 0, TAG_LENGTH);
        return MessageDigest.isEqual(truncated, provided);
    }

    private static byte[] tag(byte[] key, String name, long subnetKey, long issuedAt) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update(ByteBuffer.allocate(16).putLong(issuedAt).putLong(subnetKey).array());
            mac.update(name.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
