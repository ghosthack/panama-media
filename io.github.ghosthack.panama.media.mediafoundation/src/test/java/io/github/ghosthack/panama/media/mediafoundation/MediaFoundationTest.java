package io.github.ghosthack.panama.media.mediafoundation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.PixelFormat;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class MediaFoundationTest {

    private static final String SMALL_MP4_BASE64 =
            "AAAAHGZ0eXBtcDQyAAAAAWlzb21tcDQxbXA0MgAAAAFtZGF0AAAAAAAAAaIAAAA7BgUyR1ZK3FxMQz+U78URPNFDqAEAAAMAAQMAAAMAAQIAAeYACwAAAwAAAwAAAwBuDAOJEQQN/////4AAAABYJbggH///giigACAP44AB+OAAINE8GQoY4Y4AB+++++++++++bFDHDHAAP111111111111111111111111111111111111111111111111111111111114AAAABoGBRVHVkrcXExDP5TvxRE80UOoAwAAAwABgAAAAFwluBAD///4IooAB7xwABBbjgACDnPBqKMcAAtHAANX333333333zYoxwAC0cAA1XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXgAAAABoGBRVHVkrcXExDP5TvxRE80UOoAwAAAwABgAAAAFcluCAF///4IooAAgV+OAAISEcABngzFADHAAP6++++++++++bFADHAAP6uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuvAAAAK4bW9vdgAAAGxtdmhkAAAAAOXb3D7l29w/AAACWAAABwgAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAkR0cmFrAAAAXHRraGQAAAAB5dvcP+Xb3D8AAAABAAAAAAAABwgAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAcIAAAAAAABAAAAAAG8bWRpYQAAACBtZGhkAAAAAOXb3D/l29w/AAACWAAABwhVxAAAAAAAMWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABDb3JlIE1lZGlhIFZpZGVvAAAAAWNtaW5mAAAAFHZtaGQAAAABAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAAEjc3RibAAAAJxzdHNkAAAAAAAAAAEAAACMYXZjMQAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAQABAASAAAAEgAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABj//wAAACJhdmNDAUIAC//hAAsnQgALq4YbwLMFKAEABCjOPIAAAAAKZmllbAEAAAAACmNocm0AAAAAABhzdHRzAAAAAAAAAAEAAAADAAACWAAAAA9zZHRwAAAAACAgIAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAgc3RzegAAAAAAAAAAAAAAAwAAAJsAAAB+AAAAeQAAABxzdGNvAAAAAAAAAAMAAAAsAAAAxwAAAUU=";

    @Test
    void fitsFrameWithoutUpscaling() {
        long source = ((long) 1920 << 32) | 1080;

        assertEquals(((long) 640 << 32) | 360, MediaFoundation.fitFrameSize(source, 640));
        assertEquals(0, MediaFoundation.fitFrameSize(source, 1920));
        assertEquals(0, MediaFoundation.fitFrameSize(source, 0));
    }

    @Test
    void infersAlignedSurfaceStrides() {
        assertEquals(32, MediaFoundation.inferBgraStride(96, 4, 3));
        assertEquals(6, MediaFoundation.inferNv12Stride(27, 4, 3));
    }

    @Test
    void copiesOnlyVisibleBgraRows() {
        byte[] padded = new byte[48];
        for (int i = 0; i < padded.length; i++) {
            padded[i] = (byte) i;
        }

        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> image =
                    MediaFoundation.copyVisibleBgra(
                            MemorySegment.ofArray(padded),
                            16,
                            new MediaFoundation.VisibleArea(1, 1, 2, 2),
                            arena);

            assertEquals(2, image.width());
            assertEquals(2, image.height());
            assertEquals(8, image.stride());
            assertArrayEquals(
                    new byte[] {
                        20, 21, 22, 23, 24, 25, 26, 27,
                        36, 37, 38, 39, 40, 41, 42, 43
                    },
                    image.pixels().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void extractFrameRemovesDecoderSurfacePadding(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(MediaFoundation.isAvailable());
        Path video = tempDir.resolve("small.mp4");
        Files.write(video, Base64.getDecoder().decode(SMALL_MP4_BASE64));

        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame =
                    MediaFoundation.extractFrame(
                            arena, video.toString(), 0);

            assertEquals(16, frame.width());
            assertEquals(16, frame.height());
            assertEquals(16 * 4, frame.stride());
            assertEquals(16L * 16 * 4, frame.pixels().byteSize());
        }
    }
}
