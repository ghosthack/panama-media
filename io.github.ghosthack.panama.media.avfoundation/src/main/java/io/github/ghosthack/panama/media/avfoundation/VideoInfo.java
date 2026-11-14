package io.github.ghosthack.panama.media.avfoundation;

/**
 * Video metadata returned by {@link AVFoundation#getVideoInfo}.
 *
 * @param width          display width in pixels: the track's stored width, or
 *                       its height when the container rotates it by 90&deg; or
 *                       270&deg;. Matches the frames {@link AVFoundation#extractFrame}
 *                       and {@link AVFoundation#openVideo} deliver.
 * @param height         display height in pixels, likewise
 * @param durationMillis total duration in milliseconds
 * @param frameRate      nominal frame rate (fps) of the first video track
 */
public record VideoInfo(int width, int height, long durationMillis, double frameRate) {}
