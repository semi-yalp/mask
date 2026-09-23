package io.sqlmask.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal JWT-compatible HS256 compact token (header.claims.signature,
 * base64url without padding). Hand-rolled on purpose — the repo's security
 * posture is small auditable filters, not a framework — and kept on the
 * standard JWS layout so a real JWT library or an API gateway can take over
 * later without changing the wire format.
 *
 * <p>Verification accepts exactly one algorithm (HS256), compares signatures
 * in constant time and requires: non-blank {@code sub}, a valid {@code role},
 * a (possibly empty) {@code groups} array and an unexpired {@code exp}.
 * Anything else is {@link AuthException.Code#INVALID_TOKEN} / EXPIRED_TOKEN.
 */
public final class AuthTokenService {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String JWT_ALG = "HS256";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final byte[] secret;
  private final long ttlSeconds;
  private final ObjectMapper mapper = new ObjectMapper();

  public AuthTokenService(String secret, java.time.Duration ttl) {
    if (secret == null
        || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "MASK_AUTH_SECRET must be at least 32 bytes so tokens cannot be brute-forced");
    }
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR, "token TTL must be positive");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.ttlSeconds = ttl.toSeconds();
  }

  /** A freshly signed token together with what the client needs to know about its lifetime. */
  public record IssuedToken(String token, long expiresInSeconds, long expiresAtEpochSecond) {
  }

  public IssuedToken issue(AuthPrincipal principal, Instant now) {
    long iat = now.getEpochSecond();
    long exp = iat + ttlSeconds;

    ObjectNode header = mapper.createObjectNode();
    header.put("alg", JWT_ALG);
    header.put("typ", "JWT");

    ObjectNode claims = mapper.createObjectNode();
    claims.put("sub", principal.username());
    if (principal.displayName() != null && !principal.displayName().isBlank()) {
      claims.put("name", principal.displayName());
    }
    claims.put("role", principal.role().name());
    ArrayNode groups = claims.putArray("groups");
    principal.groups().forEach(groups::add);
    claims.put("iat", iat);
    claims.put("exp", exp);

    String signingInput = encode(header.toString().getBytes(StandardCharsets.UTF_8))
        + "." + encode(claims.toString().getBytes(StandardCharsets.UTF_8));
    String token = signingInput + "." + encode(sign(signingInput));
    return new IssuedToken(token, ttlSeconds, exp);
  }

  public AuthPrincipal verify(String token, Instant now) {
    if (token == null || token.isBlank()) {
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "missing token");
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3) {
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "malformed token");
    }
    JsonNode header = parseJson(parts[0]);
    if (!JWT_ALG.equals(header.path("alg").asText(null))) {
      // never honor an attacker-chosen algorithm (incl. "none")
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "unsupported token algorithm");
    }
    byte[] expected = sign(parts[0] + "." + parts[1]);
    byte[] provided;
    try {
      provided = DECODER.decode(parts[2]);
    } catch (IllegalArgumentException e) {
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "malformed token signature");
    }
    if (!MessageDigest.isEqual(expected, provided)) {
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "token signature mismatch");
    }

    JsonNode claims = parseJson(parts[1]);
    String sub = claims.path("sub").asText(null);
    if (sub == null || sub.isBlank()) {
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "token has no subject");
    }
    Role role;
    try {
      role = Role.parse(claims.path("role").asText(null));
    } catch (IllegalArgumentException e) {
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "token has invalid role");
    }
    List<String> groups = new ArrayList<>();
    JsonNode groupsNode = claims.path("groups");
    if (groupsNode.isArray()) {
      groupsNode.forEach(n -> {
        String value = n.asText(null);
        if (value != null && !value.isBlank()) {
          groups.add(value);
        }
      });
    }
    long exp = claims.path("exp").asLong(0);
    if (exp <= 0) {
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "token has no expiry");
    }
    if (now.getEpochSecond() >= exp) {
      throw new AuthException(AuthException.Code.EXPIRED_TOKEN, "token expired");
    }
    String name = claims.path("name").asText(null);
    return new AuthPrincipal(sub, name, role, groups);
  }

  private JsonNode parseJson(String part) {
    try {
      JsonNode node = mapper.readTree(DECODER.decode(part));
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("not an object");
      }
      return node;
    } catch (IllegalArgumentException | java.io.IOException e) {
      throw new AuthException(AuthException.Code.INVALID_TOKEN, "malformed token payload");
    }
  }

  private byte[] sign(String signingInput) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
      return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  private static String encode(byte[] bytes) {
    return ENCODER.encodeToString(bytes);
  }
}
