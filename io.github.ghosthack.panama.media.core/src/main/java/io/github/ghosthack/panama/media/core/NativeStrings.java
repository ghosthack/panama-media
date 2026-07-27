package io.github.ghosthack.panama.media.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Utility for safely reading null-terminated C strings from native memory.
 */
public final class NativeStrings {

    private NativeStrings() {}

    /**
     * Reads a null-terminated C string from a native pointer.
     * <p>
     * Scans at most {@code maxLen} bytes for a null terminator. Returns an
     * empty string if the pointer is {@code NULL}, has address zero, or if
     * no null terminator is found within {@code maxLen} bytes (corrupt or
     * invalid pointer).
     *
     * @param ptr    pointer to the C string
     * @param maxLen maximum number of bytes to scan. A value of 256 is safe
     *               for most version strings; use 1024 for error messages.
     * @return the decoded string, or an empty string for null/invalid pointers
     */
    public static String readCString(MemorySegment ptr, long maxLen) {
        if (ptr == null || MemorySegment.NULL.equals(ptr) || ptr.address() == 0) {
            return "";
        }
        MemorySegment bounded = ptr.reinterpret(maxLen);
        long len = 0;
        while (len < maxLen && bounded.get(ValueLayout.JAVA_BYTE, len) != 0) {
            len++;
        }
        if (len == 0 || len == maxLen) return "";
        return new String(bounded.asSlice(0, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }
}
