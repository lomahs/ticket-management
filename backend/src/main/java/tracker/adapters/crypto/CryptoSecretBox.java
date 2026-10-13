package tracker.adapters.crypto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;

import tracker.application.ports.SecretBox;

/** Encrypts secrets with AES-256-GCM (Spring Security's {@link Encryptors#delux}) under a random key. */
public final class CryptoSecretBox implements SecretBox {
    private final TextEncryptor encryptor;

    /** @param key a long random string; the key-derivation salt is derived from it */
    public CryptoSecretBox(String key) {
        this.encryptor = Encryptors.delux(key, salt(key));
    }

    private static String salt(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return new String(Hex.encode(java.util.Arrays.copyOf(digest, 8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Uses the key in {@code file}, creating the file with a new random key (readable by the owner only) when missing. */
    public static CryptoSecretBox fromKeyFile(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, new String(Hex.encode(KeyGenerators.secureRandom(32).generateKey())));
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException e) {
                    // not a POSIX file system
                }
            }
            return new CryptoSecretBox(Files.readString(file).strip());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create the key file " + file, e);
        }
    }

    @Override
    public String encrypt(String plain) {
        return encryptor.encrypt(plain);
    }

    @Override
    public String decrypt(String token) {
        return encryptor.decrypt(token);
    }
}
