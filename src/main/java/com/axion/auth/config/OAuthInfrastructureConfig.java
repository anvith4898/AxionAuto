package com.axion.auth.config;

import com.axion.auth.exception.TokenEncryptionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;

/**
 * Infrastructure beans for OAuth flow:
 * - AES-256-GCM SecretKey derived from the env-var-supplied encryption key
 * - RestClient configured for Meta Graph API calls (with timeouts)
 * - RedisTemplate for OAuth state (CSRF) management
 */
@Slf4j
@Configuration
public class OAuthInfrastructureConfig {

    private static final String AES_ALGORITHM = "AES";
    private static final int REQUIRED_KEY_LENGTH_BYTES = 32; // 256 bits

    /**
     * AES-256-GCM SecretKey bean.
     * Key source: base64-encoded 32-byte value from TOKEN_ENCRYPTION_KEY env var.
     */
    @Bean
    public SecretKey tokenEncryptionKey(TokenEncryptionProperties props) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(props.encryptionKey());
        } catch (IllegalArgumentException e) {
            throw new TokenEncryptionException(
                    "TOKEN_ENCRYPTION_KEY is not valid Base64. "
                            + "Generate a valid key with: openssl rand -base64 32", e);
        }

        if (keyBytes.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new TokenEncryptionException(
                    "TOKEN_ENCRYPTION_KEY must decode to exactly 32 bytes (256 bits). "
                            + "Found: " + keyBytes.length + " bytes. "
                            + "Generate a valid key with: openssl rand -base64 32");
        }

        log.info("Token encryption SecretKey initialized (AES-256-GCM). Key length validated: {} bytes",
                keyBytes.length);
        return new SecretKeySpec(keyBytes, AES_ALGORITHM);
    }

    /**
     * RestClient for Meta Graph API calls.
     *
     * <p>Uses JDK HttpClient backend (virtual-thread friendly, no extra deps).
     *
     * <p>Timeouts:
     * <ul>
     *   <li><b>connectTimeout 10s</b>: fail fast when Meta is unreachable.</li>
     *   <li><b>readTimeout 15s</b>: prevents indefinite stalls on hung 5xx responses.
     *       Without this, a response that opens but never sends body data blocks the
     *       calling virtual thread forever, exhausting the downstream pipeline.</li>
     * </ul>
     *
     * <p><b>followRedirects(NEVER)</b>: prevents silent token leakage. A 3xx redirect
     * could forward the {@code access_token} query parameter to an attacker-controlled URL.
     */
    @Bean("metaGraphApiRestClient")
    public RestClient metaGraphApiRestClient(MetaOAuthProperties props) {
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
        factory.setReadTimeout(Duration.ofSeconds(15));

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.versionedBaseUrl())
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * RedisTemplate for OAuth state (CSRF protection).
     * Keys: String, Values: String (JSON-serialized OAuthStatePayload).
     */
    @Bean("oauthStateRedisTemplate")
    public RedisTemplate<String, String> oauthStateRedisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();

        return template;
    }
}
