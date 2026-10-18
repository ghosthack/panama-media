# panama-media

Source-only Java 26 bindings to operating-system media and graphics APIs using
the Foreign Function and Memory (FFM) API. There is no JNI shim and no bundled
native code: the bindings call libraries supplied by the operating system —
the COM runtime, Windows Imaging Component, Media Foundation and D3D11 on
Windows, Accelerate (vImage) on macOS, and the system OpenGL on all three
platforms.

The current public snapshot contains:

| Maven artifact | JPMS module | Purpose |
|---|---|---|
| `panama-media-core` | `io.github.ghosthack.panama.media.core` | Shared native loading, pixel formats, decoded-image records, and memory helpers |
| `panama-media-comruntime` | `io.github.ghosthack.panama.media.comruntime` | COM lifecycle, `IUnknown`, GUID/HRESULT, and vtable dispatch |
| `panama-media-fileassoc` | `io.github.ghosthack.panama.media.fileassoc` | Registry (advapi32) reads/writes and shell association-change notification for file-type registration |
| `panama-media-wic` | `io.github.ghosthack.panama.media.wic` | WIC still-image dimensions, decode, thumbnails, metadata, and animated GIF |
| `panama-media-d3d11video` | `io.github.ghosthack.panama.media.d3d11video` | D3D11 video devices, decoder surfaces, DXVA profiles, and hardware decode structures |
| `panama-media-mediafoundation` | `io.github.ghosthack.panama.media.mediafoundation` | Media probing, frame extraction/streaming, and raw H.264/HEVC/AV1 MFT decode |
| `panama-media-accelerate` | `io.github.ghosthack.panama.media.accelerate` | macOS vImage: NV12 (Y'CbCr 4:2:0) to ARGB8888 conversion and 90° rotations of ARGB8888, Planar8 and Planar16U buffers |
| `panama-media-opengl` | `io.github.ghosthack.panama.media.opengl` | Offscreen OpenGL contexts (CGL on macOS, WGL on Windows, EGL on Linux) and the texture, framebuffer, shader and pixel-buffer calls of a render-and-readback pipeline |

The Windows family is designed for 64-bit Windows, `accelerate` for macOS, and
`opengl` for macOS, Windows and Linux. Every module compiles on every
operating system; platform-specific entry points report unavailable (or throw
`UnsupportedOperationException`) elsewhere, and native smoke tests skip there.
CI verifies the reactor on Windows x64 and Linux x64.

## Build

Requires JDK 26 and Maven 3.9 or newer.

```sh
mvn verify
mvn install
```

`verify` compiles and packages the complete public reactor. The project test
suite is deliberately not included in this source snapshot. `install` makes
the eight `0.1.2` artifacts available to another local Maven project.

## Use

For WIC, declare the top-level artifact; Maven brings in `core` and
`comruntime` transitively:

```xml
<dependency>
  <groupId>io.github.ghosthack</groupId>
  <artifactId>panama-media-wic</artifactId>
  <version>0.1.2</version>
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
  <version>0.1.2</version>
</dependency>
```

`MediaFoundation.extractFrame(...)` is the normalized poster-frame API: it
returns tightly packed BGRA pixels at the visible display dimensions, hiding
decoder surface alignment and padding. Callers that demux compressed video
themselves can use `MfVideoDecoder` for the lower-level path; its `Frame`
retains the native NV12/P010 row stride and UV-plane offset while reporting
the visible dimensions.

`Accelerate` (macOS) is a raw vImage binding: each `vImage*` call returns the
native `vImage_Error`, and buffers are caller-owned `vImage_Buffer` structs
built with `Accelerate.allocateBuffer` and `Accelerate.setBuffer`.

`GLContext.create()` (`opengl`) makes an offscreen context current on the
calling thread — CGL, WGL or EGL for the host platform — and `context.gl()`
is that context's function table. A context, and everything created with it,
is confined to the thread that created it:

```java
import io.github.ghosthack.panama.media.opengl.GL;
import io.github.ghosthack.panama.media.opengl.GLContext;

try (GLContext context = GLContext.create()) {
    GL gl = context.gl();
    System.out.println(context.api() + ": " + gl.glGetString(GL.GL_RENDERER));
}
```

FFM native access must be enabled for the binding modules used by the
application. Enabling the complete public family is:

```text
--enable-native-access=io.github.ghosthack.panama.media.core,io.github.ghosthack.panama.media.comruntime,io.github.ghosthack.panama.media.fileassoc,io.github.ghosthack.panama.media.wic,io.github.ghosthack.panama.media.d3d11video,io.github.ghosthack.panama.media.mediafoundation,io.github.ghosthack.panama.media.accelerate,io.github.ghosthack.panama.media.opengl
```

The caller owns every `Arena` passed to a decode method and therefore controls
the lifetime of returned `MemorySegment` pixel data. Streaming decoder types
also implement `AutoCloseable`; close them on the thread that opened them.

## License

The Java source is licensed under the [MIT License](LICENSE). Runtime system
components and the test-only dependency are described in
[THIRD-PARTY.md](THIRD-PARTY.md).
