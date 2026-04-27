package org.azraellykos.worldsmemory.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtil {

    private HashUtil() {}

    public static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(data);
            StringBuilder hex = new StringBuilder(40);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
