package tracker.application.ports;

public interface SecretBox {
    String encrypt(String plain);

    String decrypt(String token);
}
