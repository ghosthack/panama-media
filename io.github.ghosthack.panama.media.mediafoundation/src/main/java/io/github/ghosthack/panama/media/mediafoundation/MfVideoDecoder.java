package io.github.ghosthack.panama.media.mediafoundation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import io.github.ghosthack.panama.media.core.DecodeException;
import io.github.ghosthack.panama.media.comruntime.Ole32;

/**
 * A single open Media Foundation video decoder MFT driven over the <b>raw
 * compressed-sample path</b>: caller-supplied compressed samples in, locked
 * NV12/P010 frame views out — no Source Reader, no container. It is intended
 * for callers that demux separately and hand the codec layer pre-cut samples
 * (for AV1: raw low-overhead OBU temporal units; for H.264/HEVC: Annex-B
 * access units with the parameter sets riding in-band).
 *
 * <p>The transform is resolved with {@code MFTEnumEx} by compressed subtype
 * (after {@link MediaFoundation#registerStoreCodecs()}, so Store-distributed
 * decoders such as the AV1 Video Extension are discoverable), fed via
 * {@code IMFTransform::ProcessInput} with hand-built {@code IMFSample}s, and
 * drained via {@code ProcessOutput} with the module's usual
 * {@code STREAM_CHANGE}/{@code NEED_MORE_INPUT} handling. A D3D11 DXGI device
 * manager is attached best-effort ({@code MFT_MESSAGE_SET_D3D_MANAGER}) so
 * capable decoders run hardware-accelerated; {@link #isHardwareAccelerated()}
 * reports whether that succeeded.</p>
 *
 * <h2>Output shape</h2>
 * The output type is negotiated to exactly NV12 (8-bit) or P010 (10-bit) —
 * semi-planar 4:2:0, the same download formats the other direct hardware
 * tiers use. {@link #nextFrame()} returns a {@link Frame} view over the
 * <em>locked</em> contiguous output buffer; the caller copies the pixels out
 * and must call {@link #releaseFrame()} before the next
 * {@code nextFrame()}/{@code close()}. The caller id stamped on
 * {@link #sendSample} rides through the MFT as the sample time and is echoed
 * on the matching output frame.
 *
 * <p>Only functional on Windows; construction is guarded by
 * {@link #isAvailable()}. Not thread-safe — one driving thread.</p>
 */
public final class MfVideoDecoder implements AutoCloseable {

    /**
     * One decoded frame: a view over the locked contiguous output buffer,
     * valid until {@link #releaseFrame()}.
     *
     * @param data     the locked buffer (luma plane at offset 0)
     * @param uvOffset byte offset of the interleaved CbCr plane
     * @param width    display width in pixels
     * @param height   display height in pixels
     * @param stride   bytes per row (luma and chroma)
     * @param tenBit   {@code true} for P010, {@code false} for NV12
     * @param id       the caller id stamped on the sample that carried this picture
     */
    public record Frame(MemorySegment data, long uvOffset, int width, int height,
                        int stride, boolean tenBit, long id) {}

    /** MFT_OUTPUT_DATA_BUFFER: {DWORD dwStreamID; IMFSample*; DWORD dwStatus; IMFMediaEvent*}. */
    private static final long ODB_SAMPLE_OFFSET = 8;
    private static final long ODB_STATUS_OFFSET = 16;
    private static final long ODB_EVENTS_OFFSET = 24;

    // ── process-wide platform startup (never torn down — see MediaFoundation) ──

    private static final Object PLATFORM_LOCK = new Object();
    private static volatile boolean platformStarted;

    private static void ensurePlatform() throws Throwable {
        Ole32.coInitializeEx(); // idempotent per thread (MTA)
        if (platformStarted) {
            return;
        }
        synchronized (PLATFORM_LOCK) {
            if (platformStarted) {
                return;
            }
            int hr = (int) MediaFoundation.H_MF_STARTUP.invokeExact(
                    MediaFoundation.MF_VERSION, 0);
            Ole32.check(hr, "MFStartup");
            platformStarted = true;
        }
    }

    // ── instance state ──────────────────────────────────────────────────

    /** Session-lived scratch: out-pointers and the output data buffer record. */
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment pp = arena.allocate(ValueLayout.ADDRESS);
    private final MemorySegment pp2 = arena.allocate(ValueLayout.ADDRESS);
    private final MemorySegment pInt = arena.allocate(ValueLayout.JAVA_INT);
    private final MemorySegment pLong = arena.allocate(ValueLayout.JAVA_LONG);
    private final MemorySegment outputBuf = arena.allocate(32);
    private final MemorySegment pdwStatus = arena.allocate(ValueLayout.JAVA_INT);
    /** MFT_OUTPUT_STREAM_INFO: {DWORD dwFlags; DWORD cbSize; DWORD cbAlignment} — 12 bytes. */
    private final MemorySegment pStreamInfo = arena.allocate(12);

    private MemorySegment decoder = MemorySegment.NULL;
    private MemorySegment d3dDevice = MemorySegment.NULL;
    private MemorySegment d3dContext = MemorySegment.NULL;
    private MemorySegment dxgiManager = MemorySegment.NULL;

    private final boolean tenBit;
    private boolean hardwareAccelerated;

    /** Surface (aligned) dimensions from MF_MT_FRAME_SIZE — the buffer geometry. */
    private int width;
    private int height;
    /** Visible dimensions (MF_MT_MINIMUM_DISPLAY_APERTURE, else clamped to the open request). */
    private int displayWidth;
    private int displayHeight;
    /** The dimensions requested at open (the stream's coded size). */
    private final int requestedWidth;
    private final int requestedHeight;
    private int stride;
    private boolean decoderProvidesSamples;
    private int outputBufSize;

    /** The frame currently locked for the caller, or {@code null}. */
    private MemorySegment lockedBuffer = MemorySegment.NULL;
    private MemorySegment lockedSample = MemorySegment.NULL;

    private boolean closed;

    /** Whether Media Foundation is present (Windows with mfplat/mfreadwrite/d3d11). */
    public static boolean isAvailable() {
        return MediaFoundation.isAvailable();
    }

    /**
     * Opens an AV1 decoder MFT for raw OBU temporal-unit samples.
     *
     * @param width   coded frame width (from the sequence header)
     * @param height  coded frame height
     * @param tenBit  negotiate P010 output (10-bit) instead of NV12 (8-bit)
     * @throws IllegalStateException if Media Foundation or an AV1 decoder MFT
     *                               is unavailable
     */
    public static MfVideoDecoder openAv1(int width, int height, boolean tenBit) {
        if (!isAvailable()) {
            throw new IllegalStateException("Media Foundation is unavailable");
        }
        return new MfVideoDecoder(MediaFoundation.MFVideoFormat_AV01, width, height, tenBit);
    }

    /**
     * Opens an H.264 decoder MFT (the OS-shipped Microsoft H264 Video Decoder)
     * for Annex-B access-unit samples — SPS/PPS ride in-band with the first
     * sample. The Microsoft decoder is 8-bit 4:2:0 only, so output is always
     * NV12.
     *
     * @param width  coded frame width (from the SPS)
     * @param height coded frame height
     * @throws IllegalStateException if Media Foundation or an H.264 decoder
     *                               MFT is unavailable
     */
    public static MfVideoDecoder openH264(int width, int height) {
        if (!isAvailable()) {
            throw new IllegalStateException("Media Foundation is unavailable");
        }
        return new MfVideoDecoder(MediaFoundation.MFVideoFormat_H264, width, height, false);
    }

    /**
     * Opens an HEVC decoder MFT (the Store-distributed "HEVC Video Extensions",
     * surfaced by {@code MediaFoundation.registerStoreCodecs()}) for Annex-B
     * access-unit samples — VPS/SPS/PPS ride in-band with the first sample.
     *
     * @param width   coded frame width (from the SPS)
     * @param height  coded frame height
     * @param tenBit  negotiate P010 output (Main10) instead of NV12 (Main)
     * @throws IllegalStateException if Media Foundation or an HEVC decoder MFT
     *                               is unavailable
     */
    public static MfVideoDecoder openHevc(int width, int height, boolean tenBit) {
        if (!isAvailable()) {
            throw new IllegalStateException("Media Foundation is unavailable");
        }
        return new MfVideoDecoder(MediaFoundation.MFVideoFormat_HEVC, width, height, tenBit);
    }

    private MfVideoDecoder(MemorySegment subtype, int width, int height, boolean tenBit) {
        this.tenBit = tenBit;
        this.requestedWidth = width;
        this.requestedHeight = height;
        boolean ok = false;
        try (Arena temp = Arena.ofConfined()) {
            ensurePlatform();
            MediaFoundation.registerStoreCodecs();

            decoder = MediaFoundation.activateDecoderForSubtype(temp, subtype);
            if (MemorySegment.NULL.equals(decoder)) {
                throw new IllegalStateException(
                        "no decoder MFT registered for the requested subtype");
            }

            attachD3dManager(temp);
            setInputType(temp, subtype, width, height);
            negotiateOutputTypeAtOpen(temp);
            readStreamInfo();

            int ignoredBegin = (int) MediaFoundation.IMFTransform_ProcessMessage.invokeExact(
                    Ole32.vtable(decoder, 23), decoder,
                    MediaFoundation.MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0L);
            int ignoredStart = (int) MediaFoundation.IMFTransform_ProcessMessage.invokeExact(
                    Ole32.vtable(decoder, 23), decoder,
                    MediaFoundation.MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0L);
            ok = true;
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("MfVideoDecoder open failed", t);
        } finally {
            if (!ok) {
                close();
            }
        }
    }

    /** Whether a D3D11 DXGI device manager is attached (hardware-accelerated decode). */
    public boolean isHardwareAccelerated() {
        return hardwareAccelerated;
    }

    /** Display width of the negotiated output type (updated on stream change). */
    public int width() {
        return width;
    }

    /** Display height of the negotiated output type (updated on stream change). */
    public int height() {
        return height;
    }

    // ── open helpers ────────────────────────────────────────────────────

    /** Best-effort D3D11 device + DXGI manager, mirroring the Source Reader path. */
    private void attachD3dManager(Arena temp) throws Throwable {
        MemorySegment ppDevice = temp.allocate(ValueLayout.ADDRESS);
        MemorySegment ppContext = temp.allocate(ValueLayout.ADDRESS);
        int hr = (int) MediaFoundation.H_D3D11_CREATE_DEVICE.invokeExact(
                MemorySegment.NULL, 1 /* HARDWARE */, MemorySegment.NULL, 0x820,
                MemorySegment.NULL, 0, 7, ppDevice, MemorySegment.NULL, ppContext);
        if (Ole32.failed(hr)) {
            return; // software decode without a D3D manager
        }
        d3dDevice = ppDevice.get(ValueLayout.ADDRESS, 0);
        d3dContext = ppContext.get(ValueLayout.ADDRESS, 0);

        MemorySegment ppMt = temp.allocate(ValueLayout.ADDRESS);
        hr = (int) MediaFoundation.IUnknown_QueryInterface.invokeExact(
                Ole32.vtable(d3dDevice, 0), d3dDevice,
                MediaFoundation.IID_ID3D10Multithread, ppMt);
        if (!Ole32.failed(hr)) {
            MemorySegment mt = ppMt.get(ValueLayout.ADDRESS, 0);
            int ignoredMt = (int) MediaFoundation.ID3D10Multithread_SetMultithreadProtected
                    .invokeExact(Ole32.vtable(mt, 5), mt, 1);
            Ole32.release(mt);
        }

        MemorySegment pResetToken = temp.allocate(ValueLayout.JAVA_INT);
        MemorySegment ppManager = temp.allocate(ValueLayout.ADDRESS);
        hr = (int) MediaFoundation.H_MF_CREATE_DXGI_DEVICE_MANAGER.invokeExact(
                pResetToken, ppManager);
        if (Ole32.failed(hr)) {
            return;
        }
        dxgiManager = ppManager.get(ValueLayout.ADDRESS, 0);
        hr = (int) MediaFoundation.IMFDXGIDeviceManager_ResetDevice.invokeExact(
                Ole32.vtable(dxgiManager, 7), dxgiManager,
                d3dDevice, pResetToken.get(ValueLayout.JAVA_INT, 0));
        if (Ole32.failed(hr)) {
            Ole32.release(dxgiManager);
            dxgiManager = MemorySegment.NULL;
            return;
        }
        hr = (int) MediaFoundation.IMFTransform_ProcessMessage.invokeExact(
                Ole32.vtable(decoder, 23), decoder,
                MediaFoundation.MFT_MESSAGE_SET_D3D_MANAGER, dxgiManager.address());
        hardwareAccelerated = !Ole32.failed(hr);
    }

    /**
     * Sets the compressed input media type. Preferred path: take the
     * transform's own advertised input type for our subtype
     * ({@code GetInputAvailableType} — it carries whatever attributes the
     * decoder insists on), stamp the frame size on it, and set it. Falls back
     * to a hand-built type when the transform advertises none.
     */
    private void setInputType(Arena temp, MemorySegment subtype, int w, int h) throws Throwable {
        MemorySegment ppType = temp.allocate(ValueLayout.ADDRESS);
        MemorySegment guidBuf = temp.allocate(16);
        for (int i = 0; ; i++) {
            int hr = (int) MediaFoundation.IMFTransform_GetInputAvailableType.invokeExact(
                    Ole32.vtable(decoder, 13), decoder, 0, i, ppType);
            if (Ole32.failed(hr)) {
                break; // none advertised (or exhausted) — build one by hand
            }
            MemorySegment type = ppType.get(ValueLayout.ADDRESS, 0);
            boolean matched = false;
            try {
                hr = (int) MediaFoundation.IMFAttributes_GetGUID.invokeExact(
                        Ole32.vtable(type, 10), type, MediaFoundation.MF_MT_SUBTYPE, guidBuf);
                if (!Ole32.failed(hr) && guidEquals(guidBuf, subtype)) {
                    hr = (int) MediaFoundation.IMFAttributes_SetUINT64.invokeExact(
                            Ole32.vtable(type, 22), type,
                            MediaFoundation.MF_MT_FRAME_SIZE,
                            ((long) w << 32) | (h & 0xFFFFFFFFL));
                    Ole32.check(hr, "SetUINT64(MF_MT_FRAME_SIZE)");
                    hr = (int) MediaFoundation.IMFTransform_SetInputType.invokeExact(
                            Ole32.vtable(decoder, 15), decoder, 0, type, 0);
                    Ole32.check(hr, "Decoder SetInputType (advertised)");
                    matched = true;
                }
            } finally {
                Ole32.release(type);
            }
            if (matched) {
                return;
            }
        }

        MemorySegment ppNew = temp.allocate(ValueLayout.ADDRESS);
        int hr = (int) MediaFoundation.H_MF_CREATE_MEDIA_TYPE.invokeExact(ppNew);
        Ole32.check(hr, "MFCreateMediaType");
        MemorySegment type = ppNew.get(ValueLayout.ADDRESS, 0);
        try {
            hr = (int) MediaFoundation.IMFAttributes_SetGUID.invokeExact(
                    Ole32.vtable(type, 24), type,
                    MediaFoundation.MF_MT_MAJOR_TYPE, MediaFoundation.MFMediaType_Video);
            Ole32.check(hr, "SetGUID(MF_MT_MAJOR_TYPE)");
            hr = (int) MediaFoundation.IMFAttributes_SetGUID.invokeExact(
                    Ole32.vtable(type, 24), type,
                    MediaFoundation.MF_MT_SUBTYPE, subtype);
            Ole32.check(hr, "SetGUID(MF_MT_SUBTYPE)");
            hr = (int) MediaFoundation.IMFAttributes_SetUINT64.invokeExact(
                    Ole32.vtable(type, 22), type,
                    MediaFoundation.MF_MT_FRAME_SIZE, ((long) w << 32) | (h & 0xFFFFFFFFL));
            Ole32.check(hr, "SetUINT64(MF_MT_FRAME_SIZE)");
            // Decoder MFTs read these three during SetInputType and fail with
            // MF_E_ATTRIBUTENOTFOUND when absent (verified against the AV1
            // decoder MFT): progressive, nominal 30fps, square pixels.
            hr = (int) MediaFoundation.IMFAttributes_SetUINT32.invokeExact(
                    Ole32.vtable(type, 21), type,
                    MediaFoundation.MF_MT_INTERLACE_MODE, 2 /* MFVideoInterlace_Progressive */);
            Ole32.check(hr, "SetUINT32(MF_MT_INTERLACE_MODE)");
            hr = (int) MediaFoundation.IMFAttributes_SetUINT64.invokeExact(
                    Ole32.vtable(type, 22), type,
                    MediaFoundation.MF_MT_FRAME_RATE, (30L << 32) | 1L);
            Ole32.check(hr, "SetUINT64(MF_MT_FRAME_RATE)");
            hr = (int) MediaFoundation.IMFAttributes_SetUINT64.invokeExact(
                    Ole32.vtable(type, 22), type,
                    MediaFoundation.MF_MT_PIXEL_ASPECT_RATIO, (1L << 32) | 1L);
            Ole32.check(hr, "SetUINT64(MF_MT_PIXEL_ASPECT_RATIO)");
            hr = (int) MediaFoundation.IMFTransform_SetInputType.invokeExact(
                    Ole32.vtable(decoder, 15), decoder, 0, type, 0);
            Ole32.check(hr, "Decoder SetInputType");
        } finally {
            Ole32.release(type);
        }
    }

    /**
     * Scans the transform's available output types for exactly the requested
     * download format (NV12 or P010), sets it, and records frame
     * size/stride. Also the {@code STREAM_CHANGE} re-negotiation.
     */
    private void negotiateOutputType(Arena temp) throws Throwable {
        if (!trySetOutputSubtype(temp, false)) {
            throw new DecodeException("decoder offers no "
                    + (tenBit ? "P010" : "NV12") + " output type");
        }
    }

    /**
     * The open-time sibling of {@link #negotiateOutputType}: some decoder MFTs
     * (e.g. the HEVC one) advertise the 10-bit P010 output type only after
     * parsing the stream, so at open the wanted subtype may be absent from
     * {@code GetOutputAvailableType} (MF_E_NO_MORE_TYPES). Falling back to the
     * first offered type is safe: no frame is ever produced under it — the
     * first {@code ProcessOutput} reports a stream change before any output,
     * and that renegotiation (strict {@link #negotiateOutputType}) locks the
     * wanted subtype once the decoder knows the stream.
     */
    private void negotiateOutputTypeAtOpen(Arena temp) throws Throwable {
        if (!trySetOutputSubtype(temp, false) && !trySetOutputSubtype(temp, true)) {
            throw new DecodeException("decoder offers no output types at open");
        }
    }

    /**
     * Walks {@code GetOutputAvailableType} and sets the first type whose
     * subtype is the wanted NV12/P010 (or the first type of any subtype when
     * {@code anySubtype}); returns {@code false} when the enumeration ends
     * without a match.
     */
    private boolean trySetOutputSubtype(Arena temp, boolean anySubtype) throws Throwable {
        MemorySegment wanted = tenBit
                ? MediaFoundation.MFVideoFormat_P010 : MediaFoundation.MFVideoFormat_NV12;
        MemorySegment ppType = temp.allocate(ValueLayout.ADDRESS);
        MemorySegment guidBuf = temp.allocate(16);
        for (int i = 0; ; i++) {
            int hr = (int) MediaFoundation.IMFTransform_GetOutputAvailableType.invokeExact(
                    Ole32.vtable(decoder, 14), decoder, 0, i, ppType);
            if (Ole32.failed(hr)) {
                return false;
            }
            MemorySegment type = ppType.get(ValueLayout.ADDRESS, 0);
            boolean matched = false;
            try {
                hr = (int) MediaFoundation.IMFAttributes_GetGUID.invokeExact(
                        Ole32.vtable(type, 10), type, MediaFoundation.MF_MT_SUBTYPE, guidBuf);
                if (!Ole32.failed(hr) && (anySubtype || guidEquals(guidBuf, wanted))) {
                    hr = (int) MediaFoundation.IMFTransform_SetOutputType.invokeExact(
                            Ole32.vtable(decoder, 16), decoder, 0, type, 0);
                    Ole32.check(hr, "Decoder SetOutputType");
                    readOutputGeometry(type);
                    matched = true;
                }
            } finally {
                Ole32.release(type);
            }
            if (matched) {
                return true;
            }
        }
    }

    private void readOutputGeometry(MemorySegment type) throws Throwable {
        int hr = (int) MediaFoundation.IMFAttributes_GetUINT64.invokeExact(
                Ole32.vtable(type, 8), type, MediaFoundation.MF_MT_FRAME_SIZE, pLong);
        Ole32.check(hr, "GetUINT64(MF_MT_FRAME_SIZE)");
        long fs = pLong.get(ValueLayout.JAVA_LONG, 0);
        width = (int) (fs >>> 32);
        height = (int) (fs & 0xFFFFFFFFL);
        if (width <= 0 || height <= 0) {
            throw new DecodeException("bad output dimensions " + width + "x" + height);
        }

        // MF_MT_FRAME_SIZE on decoder output is the ALIGNED surface size
        // (e.g. 212→224); the visible region rides in the display aperture
        // (an MFVideoArea blob: MFOffset x, y; LONG cx, cy). Fall back to the
        // open-time request clamped to the surface.
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment area = temp.allocate(16);
            hr = (int) MediaFoundation.IMFAttributes_GetBlob.invokeExact(
                    Ole32.vtable(type, 15), type,
                    MediaFoundation.MF_MT_MINIMUM_DISPLAY_APERTURE, area, 16,
                    MemorySegment.NULL);
            if (!Ole32.failed(hr)) {
                displayWidth = area.get(ValueLayout.JAVA_INT, 8);
                displayHeight = area.get(ValueLayout.JAVA_INT, 12);
            } else {
                displayWidth = Math.min(requestedWidth, width);
                displayHeight = Math.min(requestedHeight, height);
            }
        }
        if (displayWidth <= 0 || displayWidth > width
                || displayHeight <= 0 || displayHeight > height) {
            displayWidth = Math.min(requestedWidth, width);
            displayHeight = Math.min(requestedHeight, height);
        }

        hr = (int) MediaFoundation.IMFAttributes_GetUINT32.invokeExact(
                Ole32.vtable(type, 7), type, MediaFoundation.MF_MT_DEFAULT_STRIDE, pInt);
        int s = Ole32.failed(hr) ? 0 : pInt.get(ValueLayout.JAVA_INT, 0);
        if (s == 0) {
            s = width * (tenBit ? 2 : 1); // packed fallback
        }
        if (s < 0) {
            throw new DecodeException("bottom-up output stride " + s + " is unsupported");
        }
        stride = s;
    }

    /** Records whether the decoder allocates output samples and its buffer size. */
    private void readStreamInfo() throws Throwable {
        int hr = (int) MediaFoundation.IMFTransform_GetOutputStreamInfo.invokeExact(
                Ole32.vtable(decoder, 7), decoder, 0, pStreamInfo);
        if (!Ole32.failed(hr)) {
            decoderProvidesSamples = (pStreamInfo.get(ValueLayout.JAVA_INT, 0)
                    & MediaFoundation.MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;
            outputBufSize = pStreamInfo.get(ValueLayout.JAVA_INT, 4);
        }
    }

    // ── streaming ───────────────────────────────────────────────────────

    /**
     * Feeds one compressed sample, stamped with {@code id} (echoed on the
     * matching {@link Frame#id()}).
     *
     * @throws DecodeException if the transform rejects the sample — including
     *                         {@code MF_E_NOTACCEPTING} when queued output has
     *                         not been drained with {@link #nextFrame()}
     */
    public void sendSample(byte[] data, long id) {
        ensureOpen();
        try {
            int hr = (int) MediaFoundation.H_MF_CREATE_SAMPLE.invokeExact(pp);
            Ole32.check(hr, "MFCreateSample");
            MemorySegment sample = pp.get(ValueLayout.ADDRESS, 0);
            try {
                hr = (int) MediaFoundation.H_MF_CREATE_MEMORY_BUFFER.invokeExact(
                        Math.max(1, data.length), pp2);
                Ole32.check(hr, "MFCreateMemoryBuffer");
                MemorySegment buffer = pp2.get(ValueLayout.ADDRESS, 0);
                try {
                    hr = (int) MediaFoundation.IMFMediaBuffer_Lock.invokeExact(
                            Ole32.vtable(buffer, 3), buffer, pp, MemorySegment.NULL,
                            MemorySegment.NULL);
                    Ole32.check(hr, "input IMFMediaBuffer::Lock");
                    try {
                        MemorySegment dst = pp.get(ValueLayout.ADDRESS, 0)
                                .reinterpret(data.length);
                        MemorySegment.copy(data, 0, dst, ValueLayout.JAVA_BYTE, 0, data.length);
                    } finally {
                        int ignored = (int) MediaFoundation.IMFMediaBuffer_Unlock.invokeExact(
                                Ole32.vtable(buffer, 4), buffer);
                    }
                    hr = (int) MediaFoundation.IMFMediaBuffer_SetCurrentLength.invokeExact(
                            Ole32.vtable(buffer, 6), buffer, data.length);
                    Ole32.check(hr, "IMFMediaBuffer::SetCurrentLength");
                    hr = (int) MediaFoundation.IMFSample_AddBuffer.invokeExact(
                            Ole32.vtable(sample, 42), sample, buffer);
                    Ole32.check(hr, "IMFSample::AddBuffer");
                } finally {
                    Ole32.release(buffer);
                }
                hr = (int) MediaFoundation.IMFSample_SetSampleTime.invokeExact(
                        Ole32.vtable(sample, 36), sample, id);
                Ole32.check(hr, "IMFSample::SetSampleTime");

                hr = (int) MediaFoundation.IMFTransform_ProcessInput.invokeExact(
                        Ole32.vtable(decoder, 24), decoder, 0, sample, 0);
                if (hr == MediaFoundation.MF_E_NOTACCEPTING) {
                    throw new DecodeException(
                            "decoder is not accepting input — drain nextFrame() first");
                }
                Ole32.check(hr, "Decoder ProcessInput");
            } finally {
                Ole32.release(sample);
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("sendSample failed", t);
        }
    }

    /**
     * The next decoded frame as a locked buffer view, or {@code null} when
     * the transform needs more input. The view stays valid until
     * {@link #releaseFrame()}, which must be called before the next
     * {@code nextFrame()}.
     */
    public Frame nextFrame() {
        ensureOpen();
        if (!MemorySegment.NULL.equals(lockedBuffer)) {
            throw new IllegalStateException("previous frame not released");
        }
        try (Arena temp = Arena.ofConfined()) {
            for (int guard = 0; guard < 100_000; guard++) {
                MemorySegment callerSample = MemorySegment.NULL;
                outputBuf.set(ValueLayout.JAVA_INT, 0, 0);
                outputBuf.set(ValueLayout.JAVA_INT, ODB_STATUS_OFFSET, 0);
                outputBuf.set(ValueLayout.ADDRESS, ODB_EVENTS_OFFSET, MemorySegment.NULL);
                if (decoderProvidesSamples) {
                    outputBuf.set(ValueLayout.ADDRESS, ODB_SAMPLE_OFFSET, MemorySegment.NULL);
                } else {
                    callerSample = createOutputSample();
                    outputBuf.set(ValueLayout.ADDRESS, ODB_SAMPLE_OFFSET, callerSample);
                }

                int hr = (int) MediaFoundation.IMFTransform_ProcessOutput.invokeExact(
                        Ole32.vtable(decoder, 25), decoder, 0, 1, outputBuf, pdwStatus);

                if (hr == MediaFoundation.MF_E_TRANSFORM_TYPE_NOT_SET
                        || hr == MediaFoundation.MF_E_TRANSFORM_STREAM_CHANGE) {
                    Ole32.release(callerSample);
                    negotiateOutputType(temp);
                    readStreamInfo();
                    continue;
                }
                if (hr == MediaFoundation.MF_E_TRANSFORM_NEED_MORE_INPUT) {
                    Ole32.release(callerSample);
                    return null;
                }
                Ole32.check(hr, "Decoder ProcessOutput");

                MemorySegment decodedSample = outputBuf.get(ValueLayout.ADDRESS, ODB_SAMPLE_OFFSET);
                if (!MemorySegment.NULL.equals(callerSample)
                        && !callerSample.equals(decodedSample)) {
                    Ole32.release(callerSample);
                }
                if (MemorySegment.NULL.equals(decodedSample)) {
                    continue;
                }
                return lockFrame(decodedSample);
            }
            throw new DecodeException("decoder made no progress");
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("nextFrame failed", t);
        }
    }

    /** Locks {@code sample}'s contiguous buffer and builds the caller view. */
    private Frame lockFrame(MemorySegment sample) throws Throwable {
        boolean ok = false;
        try {
            long id = 0;
            int hrPts = (int) MediaFoundation.IMFSample_GetSampleTime.invokeExact(
                    Ole32.vtable(sample, 35), sample, pLong);
            if (!Ole32.failed(hrPts)) {
                id = pLong.get(ValueLayout.JAVA_LONG, 0);
            }

            int hr = (int) MediaFoundation.IMFSample_ConvertToContiguousBuffer.invokeExact(
                    Ole32.vtable(sample, 41), sample, pp);
            Ole32.check(hr, "ConvertToContiguousBuffer");
            MemorySegment buffer = pp.get(ValueLayout.ADDRESS, 0);
            try {
                hr = (int) MediaFoundation.IMFMediaBuffer_Lock.invokeExact(
                        Ole32.vtable(buffer, 3), buffer, pp2, MemorySegment.NULL, pInt);
                Ole32.check(hr, "output IMFMediaBuffer::Lock");
                MemorySegment data = pp2.get(ValueLayout.ADDRESS, 0);
                int curLen = pInt.get(ValueLayout.JAVA_INT, 0);
                // The luma plane spans exactly 2/3 of a contiguous 4:2:0
                // buffer whatever the pitch, so the chroma offset comes from
                // the buffer length. The pitch itself differs by path — a 2D
                // (DXGI) buffer contiguous-copies to packed rows, a system
                // memory buffer is laid out at the media type's default
                // stride — so resolve whichever the length matches.
                int packed = width * (tenBit ? 2 : 1);
                int pitch;
                if (curLen == (long) stride * height * 3 / 2) {
                    pitch = stride;
                } else if (curLen == (long) packed * height * 3 / 2) {
                    pitch = packed;
                } else if (curLen % 3 == 0 && ((curLen / 3L) * 2) % height == 0
                        && (curLen / 3L) * 2 / height >= packed) {
                    pitch = (int) ((curLen / 3L) * 2 / height);
                } else {
                    int ignored = (int) MediaFoundation.IMFMediaBuffer_Unlock.invokeExact(
                            Ole32.vtable(buffer, 4), buffer);
                    throw new DecodeException("unexpected output buffer length " + curLen
                            + " for " + width + "x" + height + " (stride " + stride + ")");
                }
                long uvOffset = (curLen / 3L) * 2;
                lockedBuffer = buffer;
                lockedSample = sample;
                ok = true;
                return new Frame(data.reinterpret(curLen), uvOffset,
                        displayWidth, displayHeight, pitch, tenBit, id);
            } finally {
                if (!ok) {
                    Ole32.release(buffer);
                }
            }
        } finally {
            if (!ok) {
                Ole32.release(sample);
            }
        }
    }

    /** Unlocks and releases the frame returned by the last {@link #nextFrame()}. */
    public void releaseFrame() {
        if (MemorySegment.NULL.equals(lockedBuffer)) {
            return;
        }
        try {
            int ignored = (int) MediaFoundation.IMFMediaBuffer_Unlock.invokeExact(
                    Ole32.vtable(lockedBuffer, 4), lockedBuffer);
        } catch (Throwable ignored) {
            // native unlock cannot meaningfully fail here
        }
        Ole32.release(lockedBuffer);
        Ole32.release(lockedSample);
        lockedBuffer = MemorySegment.NULL;
        lockedSample = MemorySegment.NULL;
    }

    /** Allocates a fresh output sample backed by a memory buffer for ProcessOutput. */
    private MemorySegment createOutputSample() throws Throwable {
        int bufSz = (outputBufSize > 0) ? outputBufSize
                : stride * height * 3 / 2;
        int hr = (int) MediaFoundation.H_MF_CREATE_SAMPLE.invokeExact(pp);
        Ole32.check(hr, "MFCreateSample");
        MemorySegment sample = pp.get(ValueLayout.ADDRESS, 0);
        hr = (int) MediaFoundation.H_MF_CREATE_MEMORY_BUFFER.invokeExact(bufSz, pp2);
        Ole32.check(hr, "MFCreateMemoryBuffer");
        MemorySegment memBuf = pp2.get(ValueLayout.ADDRESS, 0);
        hr = (int) MediaFoundation.IMFSample_AddBuffer.invokeExact(
                Ole32.vtable(sample, 42), sample, memBuf);
        Ole32.check(hr, "IMFSample::AddBuffer");
        Ole32.release(memBuf);
        return sample;
    }

    /** Signals end-of-stream so buffered frames become receivable via {@link #nextFrame()}. */
    public void drain() {
        ensureOpen();
        try {
            int ignored = (int) MediaFoundation.IMFTransform_ProcessMessage.invokeExact(
                    Ole32.vtable(decoder, 23), decoder,
                    MediaFoundation.MFT_MESSAGE_COMMAND_DRAIN, 0L);
        } catch (Throwable t) {
            throw new DecodeException("drain failed", t);
        }
    }

    /** Discards all decode state for a fresh sequence. */
    public void flush() {
        ensureOpen();
        releaseFrame();
        try {
            int ignoredFlush = (int) MediaFoundation.IMFTransform_ProcessMessage.invokeExact(
                    Ole32.vtable(decoder, 23), decoder,
                    MediaFoundation.MFT_MESSAGE_COMMAND_FLUSH, 0L);
            int ignoredStart = (int) MediaFoundation.IMFTransform_ProcessMessage.invokeExact(
                    Ole32.vtable(decoder, 23), decoder,
                    MediaFoundation.MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0L);
        } catch (Throwable t) {
            throw new DecodeException("flush failed", t);
        }
    }

    private static boolean guidEquals(MemorySegment a, MemorySegment b) {
        for (int i = 0; i < 16; i++) {
            if (a.get(ValueLayout.JAVA_BYTE, i) != b.get(ValueLayout.JAVA_BYTE, i)) {
                return false;
            }
        }
        return true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MfVideoDecoder is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseFrame();
        Ole32.release(decoder);
        Ole32.release(dxgiManager);
        Ole32.release(d3dContext);
        Ole32.release(d3dDevice);
        decoder = MemorySegment.NULL;
        dxgiManager = MemorySegment.NULL;
        d3dContext = MemorySegment.NULL;
        d3dDevice = MemorySegment.NULL;
        arena.close();
    }
}
