package io.github.ghosthack.panama.media.core;

import java.lang.foreign.MemorySegment;

/**
 * Callback for scanline-level progressive image decoding.
 * <p>
 * Streaming decode methods in panama-media modules invoke this callback
 * once per decoded scanline, enabling progressive rendering, early
 * cancellation, and zero-copy pipelines.
 * <p>
 * The {@code row} segment points into the destination pixel buffer and
 * remains valid for the lifetime of the {@link java.lang.foreign.Arena}
 * that owns the decode. Callers that need to retain scanline data beyond
 * the Arena must copy it.
 */
@FunctionalInterface
public interface ScanlineCallback {

    /**
     * Called once per decoded scanline.
     *
     * @param y   zero-based scanline index (0 = top row)
     * @param row pixel data for this scanline; the segment's size equals
     *            {@code stride} bytes and the layout matches the pixel
     *            format of the decode (e.g. RGBA8 = 4 bytes per pixel)
     */
    void onScanline(int y, MemorySegment row);
}
