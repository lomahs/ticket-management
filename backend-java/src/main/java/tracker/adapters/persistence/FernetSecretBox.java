package tracker.adapters.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import tracker.application.ports.SecretBox;

/**
 * Encrypts the stored Jira API token with Fernet (AES-128-CBC + HMAC-SHA256), the format of Python's
 * cryptography.fernet, so tokens and key files of the original backend keep working.
 */
public final class FernetSecretBox implements SecretBox {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] signingKey;
    private final byte[] encryptionKey;

    public FernetSecretBox(String key) {
        byte[] raw = Base64.getUrlDecoder().decode(key.strip());
        if (raw.length != 32) {
            throw new IllegalArgumentException("Fernet key must be 32 url-safe base64-encoded bytes.");
        }
        signingKey = Arrays.copyOfRange(raw, 0, 16);
        encryptionKey = Arrays.copyOfRange(raw, 16, 32);
    }

    public static String generateKey() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().encodeToString(raw);
    }

    /** Uses TRACKER_SECRET_KEY when set; otherwise a key file, created on first use. */
    public static FernetSecretBox fromKeyFile(Path path) {
        String env = System.getenv("TRACKER_SECRET_KEY");
        if (env != null && !env.isEmpty()) {
            return new FernetSecretBox(env);
        }
        try {
            if (!Files.exists(path)) {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, generateKey(), StandardCharsets.US_ASCII);
                try {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException e) {
                    // not a POSIX file system
                }
            }
            return new FernetSecretBox(Files.readString(path, StandardCharsets.US_ASCII).strip());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the secret key " + path, e);
        }
    }

    private byte[] hmac(byte[] data, int len) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        mac.update(data, 0, len);
        return mac.doFinal();
    }

    @Override
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer token = ByteBuffer.allocate(1 + 8 + 16 + ct.length + 32);
            token.put((byte) 0x80).putLong(System.currentTimeMillis() / 1000).put(iv).put(ct);
            token.put(hmac(token.array(), token.position()));
            return Base64.getUrlEncoder().encodeToString(token.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String decrypt(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            byte[] data = Base64.getUrlDecoder().decode(token);
            if (data.length < 1 + 8 + 16 + 16 + 32 || data[0] != (byte) 0x80) {
                throw new IllegalArgumentException("InvalidToken");
            }
            int body = data.length - 32;
            if (!MessageDigest.isEqual(hmac(data, body), Arrays.copyOfRange(data, body, data.length))) {
                throw new IllegalArgumentException("InvalidToken");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(data, 9, 16));
            return new String(cipher.doFinal(data, 25, body - 25), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("InvalidToken", e);
        }
    }
}
