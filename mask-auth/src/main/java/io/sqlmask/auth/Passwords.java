package io.sqlmask.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2 password hashing for the simple auth mode — no external dependency,
 * constant-time comparison, self-describing storage format
 * {@code pbkdf2$<iterations>$<salt-b64>$<hash-b64>}.
 */
public final class Passwords {

  private static final int ITERATIONS = 120_000;
  private static final int KEY_BITS = 256;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Passwords() {
  }

  public static String hash(String password) {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    byte[] derived = pbkdf2(password, salt, ITERATIONS);
    return "pbkdf2$" + ITERATIONS
        + "$" + Base64.getEncoder().encodeToString(salt)
        + "$" + Base64.getEncoder().encodeToString(derived);
  }

  /** True when the stored hash matches the candidate; false for any legacy
   * or malformed hash format (never throws). */
  public static boolean verify(String storedHash, String candidate) {
    if (storedHash == null || candidate == null || !storedHash.startsWith("pbkdf2$")) {
      return false;
    }
    String[] parts = storedHash.split("\\$");
    if (parts.length != 4) {
      return false;
    }
    int iterations;
    byte[] salt;
    byte[] expected;
    try {
      iterations = Integer.parseInt(parts[1]);
      salt = Base64.getDecoder().decode(parts[2]);
      expected = Base64.getDecoder().decode(parts[3]);
    } catch (IllegalArgumentException e) {
      return false;
    }
    byte[] actual = pbkdf2(candidate, salt, iterations);
    return MessageDigest.isEqual(expected, actual);
  }

  private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("PBKDF2 unavailable in this JVM", e);
    }
  }
}
