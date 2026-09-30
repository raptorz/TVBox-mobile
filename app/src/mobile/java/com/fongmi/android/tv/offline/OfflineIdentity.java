package com.fongmi.android.tv.offline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Length-prefixed components avoid delimiter collisions; resolved URLs are deliberately excluded. */
public final class OfflineIdentity {
    public static String id(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static String cacheKey(String taskId, String uri) { return taskId + ":" + uri; }
}
