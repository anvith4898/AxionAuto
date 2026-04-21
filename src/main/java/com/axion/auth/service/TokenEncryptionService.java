package com.axion.auth.service;

import com.axion.auth.exception.TokenEncryptionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;

/**
 * Service for encrypting and decrypting OAuth tokens at rest using AES-256-GCM.
 * This ensures that even if the database is compromised, the Meta access tokens remain safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BIT = 128;
    private static final int GCM_IV_LENGTH_BYTE = 12;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypts the plaintext token.
     *
     * @param plaintextToken The raw token string to encrypt
     * @return EncryptedTokenResult containing ciphertext, IV, and auth tag
     */
    public EncryptedTokenResult encrypt(String plaintextToken) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTE];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmParameterSpec);

            byte[] cipherTextWithTag = cipher.doFinal(plaintextToken.getBytes());

            // Extract the auth tag from the end of the ciphertext
            int tagStartIndex = cipherTextWithTag.length - (GCM_TAG_LENGTH_BIT / 8);
            byte[] cipherText = new byte[tagStartIndex];
            byte[] authTag = new byte[GCM_TAG_LENGTH_BIT / 8];

            System.arraycopy(cipherTextWithTag, 0, cipherText, 0, tagStartIndex);
            System.arraycopy(cipherTextWithTag, tagStartIndex, authTag, 0, authTag.length);

            return new EncryptedTokenResult(cipherText, iv, authTag);

        } catch (Exception e) {
            log.error("Failed to encrypt token: {}", e.getMessage());
            throw new TokenEncryptionException("Failed to encrypt token", e);
        }
    }

    /**
     * Decrypts the token using the provided ciphertext, IV, and auth tag.
     *
     * @param cipherText The encrypted token bytes
     * @param iv         The 12-byte initialization vector used for encryption
     * @param authTag    The 16-byte GCM authentication tag
     * @return The plaintext token string
     */
    public String decrypt(byte[] cipherText, byte[] iv, byte[] authTag) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);

            // Reconstruct the ciphertext with the auth tag for decryption
            byte[] cipherTextWithTag = new byte[cipherText.length + authTag.length];
            System.arraycopy(cipherText, 0, cipherTextWithTag, 0, cipherText.length);
            System.arraycopy(authTag, 0, cipherTextWithTag, cipherText.length, authTag.length);

            byte[] plainTextBytes = cipher.doFinal(cipherTextWithTag);
            return new String(plainTextBytes);

        } catch (Exception e) {
            log.error("Failed to decrypt token. Possible tampering or key rotation issue: {}", e.getMessage());
            throw new TokenEncryptionException("Failed to decrypt token", e);
        }
    }

    public record EncryptedTokenResult(byte[] cipherText, byte[] iv, byte[] authTag) {}
}
