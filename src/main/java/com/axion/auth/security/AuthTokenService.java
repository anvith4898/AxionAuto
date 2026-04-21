package com.axion.auth.security;

import com.axion.auth.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthTokenService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final AuthProperties properties;
    private final ObjectMapper objectMapper;
    private SecretKeySpec signingKey;

    public AuthTokenService(AuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        this.signingKey = new SecretKeySpec(
                properties.tokenSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );
    }

    public String createToken(AuthenticatedUser user) {
        try {
            long expiresAt = Instant.now().plusSeconds(properties.sessionTtlSeconds()).getEpochSecond();
            String payloadJson = objectMapper.writeValueAsString(Map.of(
                    "uid", user.userId(),
                    "tid", user.tenantId(),
                    "email", user.email(),
                    "name", user.name(),
                    "role", user.role(),
                    "exp", expiresAt
            ));
            String payload = URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signature = URL_ENCODER.encodeToString(sign(payload));
            return payload + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create auth token", e);
        }
    }

    public AuthenticatedUser parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                return null;
            }

            String payload = parts[0];
            byte[] expected = sign(payload);
            byte[] actual = URL_DECODER.decode(parts[1]);
            if (!java.security.MessageDigest.isEqual(expected, actual)) {
                return null;
            }

            String payloadJson = new String(URL_DECODER.decode(payload), StandardCharsets.UTF_8);
            SessionPayload parsed = objectMapper.readValue(payloadJson, SessionPayload.class);
            if (parsed.exp() < Instant.now().getEpochSecond()) {
                return null;
            }

            return new AuthenticatedUser(
                    parsed.uid(),
                    parsed.tid(),
                    parsed.email(),
                    parsed.name(),
                    parsed.role()
            );
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(signingKey);
        return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    private record SessionPayload(
            String uid,
            UUID tid,
            String email,
            String name,
            String role,
            long exp
    ) {
    }
}
