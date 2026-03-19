package com.anthropic.eclipse.claude.preferences;

import java.io.IOException;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import com.anthropic.eclipse.claude.Activator;

/**
 * Stores the Anthropic API key in Eclipse's encrypted secure-preferences store
 * (uses OS key-chain / password wallet on each platform) instead of the plain
 * IPreferenceStore that writes to workspace/.metadata/.plugins/.
 *
 * <p>On first access after an upgrade, any value previously stored in the plain
 * IPreferenceStore is migrated automatically and the plaintext entry is cleared.
 */
public final class SecureApiKeyStore {

    private static final String NODE_PATH  = "/com/anthropic/eclipse/claude";
    private static final String KEY_APIKEY = "apiKey";

    private SecureApiKeyStore() {}

    /** Read the stored API key; returns "" if not set or on error. */
    public static String getApiKey() {
        try {
            return secureNode().get(KEY_APIKEY, "");
        } catch (StorageException e) {
            Activator.logError("[SecureApiKeyStore] Cannot read API key", e);
            return "";
        }
    }

    /** Persist the API key in encrypted storage. */
    public static void setApiKey(String apiKey) {
        try {
            ISecurePreferences node = secureNode();
            node.put(KEY_APIKEY, apiKey != null ? apiKey : "", /* encrypt = */ true);
            node.flush();
        } catch (StorageException | IOException e) {
            Activator.logError("[SecureApiKeyStore] Cannot save API key", e);
        }
    }

    /**
     * One-time migration: if the API key is still stored in the plain
     * IPreferenceStore it is moved to secure storage and the plain entry is
     * cleared. Safe to call multiple times.
     */
    public static void migrateIfNeeded() {
        try {
            // Only migrate if secure storage has nothing yet
            if (!secureNode().get(KEY_APIKEY, "").isEmpty()) return;

            if (Activator.getDefault() == null) return;
            String plainKey = Activator.getDefault().getPreferenceStore()
                    .getString(PreferenceConstants.API_KEY);
            if (plainKey != null && !plainKey.isBlank()) {
                setApiKey(plainKey);
                Activator.getDefault().getPreferenceStore()
                        .setValue(PreferenceConstants.API_KEY, "");
                Activator.logInfo("[SecureApiKeyStore] Migrated API key to secure storage");
            }
        } catch (Exception e) {
            Activator.logError("[SecureApiKeyStore] Migration failed", e);
        }
    }

    private static ISecurePreferences secureNode() {
        return SecurePreferencesFactory.getDefault().node(NODE_PATH);
    }
}
