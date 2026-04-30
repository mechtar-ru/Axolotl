package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES/GCM encryption service for sensitive data (API keys, etc.).
 * Uses 128-bit key derived from axolotl.encryption.key system property.
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);
    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final String ENCRYPTION_KEY = System.getProperty(
            "axolotl.encryption.key", "Axolotl2026SecKey!"); // 16+ chars = AES-128

    private final SecretKeySpec aesKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptionService() {
        byte[] keyBytes = ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] aesKeyBytes = new byte[16];
        System.arraycopy(keyBytes, 0, aesKeyBytes, 0, Math.min(keyBytes.length, 16));
        this.aesKey = new SecretKeySpec(aesKeyBytes, "AES");
    }

    /**
     * Encrypt plaintext. Returns Base64 encoded string with IV prepended.
     * Returns plaintext as-is if input is null/blank.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed: {}", e.getMessage());
            return plaintext;
        }
    }

    /**
     * Decrypt ciphertext (Base64 with prepended IV).
     * Returns ciphertext as-is if decryption fails (for backward compatibility).
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return ciphertext;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < GCM_IV_LENGTH) return ciphertext; // Not encrypted
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Might be plaintext (old data) - return as-is
            return ciphertext;
        }
    }
}
