package io.github.ghosthack.panama.media.core;

/**
 * Shared image dimension utilities for panama-media modules.
 */
public final class ImageDimensions {

    /**
     * Maximum total pixel count accepted by panama-media decoders.
     * <p>
     * 256 MP — large enough for professional medium-format images at full
     * resolution, small enough to prevent OOM on a malicious or corrupt header
     * claiming an impossibly large image.
     */
    public static final long MAX_PIXELS = 256L * 1024 * 1024;

    /**
     * Maximum total pixel count, summed over every frame, that a decoder
     * returning a whole animation at once will hold.
     * <p>
     * A per-frame check alone does not bound an animation: a few kilobytes of
     * GIF can declare hundreds of large frames. Equal to {@link #MAX_PIXELS},
     * i.e. 1 GiB of 32-bit pixels across all frames.
     */
    public static final long MAX_ANIMATION_PIXELS = MAX_PIXELS;

    private ImageDimensions() {}

    /**
     * Validates one more animation frame against both the per-frame limit and
     * the running whole-animation budget, before its buffer is allocated.
     *
     * @param pixelsSoFar total pixels of the frames already decoded
     * @param w           width of the next frame in pixels
     * @param h           height of the next frame in pixels
     * @return the new running total, {@code pixelsSoFar + w * h}
     * @throws IllegalArgumentException if the frame fails
     *         {@link #validateDimensions(int, int)} or the new total exceeds
     *         {@link #MAX_ANIMATION_PIXELS}
     */
    public static long addAnimationFrame(long pixelsSoFar, int w, int h) {
        validateDimensions(w, h);
        long total = pixelsSoFar + (long) w * h;
        if (total > MAX_ANIMATION_PIXELS)
            throw new IllegalArgumentException(
                    "Animation too large: " + total + " px across frames exceeds limit of "
                    + MAX_ANIMATION_PIXELS);
        return total;
    }

    /**
     * Validates image dimensions before buffer allocation.
     *
     * @param w image width in pixels
     * @param h image height in pixels
     * @throws IllegalArgumentException if {@code w} or {@code h} is &lt;= 0,
     *         or if {@code (long) w * h} exceeds {@link #MAX_PIXELS}
     */
    public static void validateDimensions(int w, int h) {
        if (w <= 0 || h <= 0)
            throw new IllegalArgumentException(
                    "Invalid image dimensions: " + w + "x" + h);
        long total = (long) w * h;
        if (total > MAX_PIXELS)
            throw new IllegalArgumentException(
                    "Image too large: " + w + "x" + h +
                    " (" + total + " px exceeds limit of " + MAX_PIXELS + ")");
    }
}
