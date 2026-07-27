# panama-media

Source-only Java 26 bindings to Windows media APIs using the Foreign Function
and Memory (FFM) API. There is no JNI shim and no bundled native code: the
bindings call the COM runtime, Windows Imaging Component, Media Foundation,
and D3D11 libraries supplied by Windows.

The current public snapshot contains the Windows family:

| Maven artifact | JPMS module | Purpose |
|---|---|---|
| `panama-media-core` | `io.github.ghosthack.panama.media.core` | Shared native loading, pixel formats, decoded-image records, and memory helpers |
| `panama-media-comruntime` | `io.github.ghosthack.panama.media.comruntime` | COM lifecycle, `IUnknown`, GUID/HRESULT, and vtable dispatch |
| `panama-media-wic` | `io.github.ghosthack.panama.media.wic` | WIC still-image dimensions, decode, thumbnails, metadata, and animated GIF |
| `panama-media-mediafoundation` | `io.github.ghosthack.panama.media.mediafoundation` | Media probing, frame extraction/streaming, and raw H.264/HEVC/AV1 MFT decode |

The bindings are designed for 64-bit Windows and are CI-tested on Windows
x64. They compile on other operating systems; platform-specific entry points
report unavailable and native smoke tests skip there.

## Build

Requires JDK 26 and Maven 3.9 or newer.

```sh
mvn verify
mvn install
```

`verify` compiles and packages the complete public reactor. The project test
suite is deliberately not included in this source snapshot. `install` makes
the four `0.1.0` artifacts available to another local Maven project.

## Use

For WIC, declare the top-level artifact; Maven brings in `core` and
`comruntime` transitively:

```xml
<dependency>
  <groupId>io.github.ghosthack</groupId>
  <artifactId>panama-media-wic</artifactId>
  <version>0.1.0</version>
</dependency>
```

A minimal still-image decode:

```java
import java.lang.foreign.Arena;
import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.PixelFormat;
import io.github.ghosthack.panama.media.wic.WIC;

try (Arena arena = Arena.ofConfined()) {
    DecodedImage<PixelFormat> image =
            WIC.decodeFromPath(arena, "C:\\images\\photo.jpg");
    System.out.printf("%dx%d %s%n",
            image.width(), image.height(), image.format());
    // image.pixels() remains valid until arena is closed.
}
```

Media Foundation is analogous:

```xml
<dependency>
  <groupId>io.github.ghosthack</groupId>
  <artifactId>panama-media-mediafoundation</artifactId>
  <version>0.1.0</version>
</dependency>
```

FFM native access must be enabled for the binding modules used by the
application. Enabling the complete public family is:

```text
--enable-native-access=io.github.ghosthack.panama.media.core,io.github.ghosthack.panama.media.comruntime,io.github.ghosthack.panama.media.wic,io.github.ghosthack.panama.media.mediafoundation
```

The caller owns every `Arena` passed to a decode method and therefore controls
the lifetime of returned `MemorySegment` pixel data. Streaming decoder types
also implement `AutoCloseable`; close them on the thread that opened them.

## License

The Java source is licensed under the [MIT License](LICENSE). Runtime system
components and the test-only dependency are described in
[THIRD-PARTY.md](THIRD-PARTY.md).
