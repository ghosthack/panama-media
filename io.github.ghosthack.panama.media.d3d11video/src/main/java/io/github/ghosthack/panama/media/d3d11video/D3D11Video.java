package io.github.ghosthack.panama.media.d3d11video;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.ghosthack.panama.media.comruntime.Ole32;
import io.github.ghosthack.panama.media.core.DecodeException;
import io.github.ghosthack.panama.media.core.HardwareAdapterInfo;
import io.github.ghosthack.panama.media.core.HardwareAdapterSelector;
import io.github.ghosthack.panama.media.core.Platform;

/**
 * Panama FFM bindings to D3D11 video decode (DXVA): {@code ID3D11VideoDevice}
 * / {@code ID3D11VideoContext} / {@code ID3D11VideoDecoder}, driven directly
 * rather than through libavcodec's {@code d3d11va} hwaccel.
 * <p>
 * Callers supply an {@code ID3D11Device}/{@code ID3D11DeviceContext} pointer
 * (e.g. from {@code panama-media-mediafoundation}'s {@code D3D11CreateDevice}
 * call, or their own) and {@link #queryVideoDevice}/{@link #queryVideoContext}
 * to obtain the video-decode interfaces via {@code QueryInterface}. From
 * there: {@link #createVideoDecoder} once per stream, then per frame
 * {@link #decoderBeginFrame}, {@link #getDecoderBuffer} /
 * {@link #releaseDecoderBuffer} to fill the DXVA parameter/slice/bitstream
 * buffers (see {@code io.github.ghosthack.panama.media.d3d11video.jextract}
 * for the DXVA struct layouts), {@link #submitDecoderBuffers}, and
 * {@link #decoderEndFrame}.
 * <p>
 * Vtable slot indices and struct offsets were verified with a real jextract
 * pass against the Windows SDK headers (10.0.26100.0) rather than transcribed
 * from documentation — see {@code panama-media/jextract-verify/headers/d3d11video_wrapper.h}.
 * <p>
 * COM vtable dispatch follows this project's existing convention (see
 * {@link Ole32}): {@link Linker#downcallHandle(FunctionDescriptor)} with no
 * fixed address yields a {@link MethodHandle} that takes the function
 * pointer (read from the object's vtable via {@link Ole32#vtable}) as a
 * leading argument.
 * <p>
 * Only functional on Windows; {@link #isAvailable()} returns {@code false}
 * on other platforms, and all handles are {@code null}.
 */
public final class D3D11Video {

    private D3D11Video() {}

    // ── OS guard ────────────────────────────────────────────────────────

    private static final boolean IS_WINDOWS = Platform.IS_WINDOWS;

    public static boolean isAvailable() {
        return IS_WINDOWS;
    }

    // ── IIDs (real values from d3d11.h's DEFINE_GUID — see class docs) ──
    //
    // Not resolved via runtime symbol lookup: these GUIDs are data owned by
    // whichever translation unit links uuid.lib, not an export of d3d11.dll,
    // so SymbolLookup.find("IID_...") is not reliable for a dynamically
    // loaded DLL. jextract's own --include-var output confirmed this gap
    // (see the jextract subpackage's package-info).

    /** {@code IID_ID3D11VideoDevice} ({@code 10ec4d5b-975a-4689-b9e4-d0aac30fe333}). */
    public static MemorySegment iidId3D11VideoDevice(Arena arena) {
        return Ole32.guid(arena, 0x10EC4D5B, (short) 0x975A, (short) 0x4689,
                new byte[] { (byte) 0xB9, (byte) 0xE4, (byte) 0xD0, (byte) 0xAA, (byte) 0xC3, (byte) 0x0F, (byte) 0xE3, (byte) 0x33 });
    }

    /** {@code IID_ID3D11VideoContext} ({@code 61f21c45-3c0e-4a74-9cea-67100d9ad5e4}). */
    public static MemorySegment iidId3D11VideoContext(Arena arena) {
        return Ole32.guid(arena, 0x61F21C45, (short) 0x3C0E, (short) 0x4A74,
                new byte[] { (byte) 0x9C, (byte) 0xEA, (byte) 0x67, (byte) 0x10, (byte) 0x0D, (byte) 0x9A, (byte) 0xD5, (byte) 0xE4 });
    }

    /** {@code IID_ID3D11VideoDecoder} ({@code 3c9c5b51-995d-48d1-9b8d-fa5caeded65c}). */
    public static MemorySegment iidId3D11VideoDecoder(Arena arena) {
        return Ole32.guid(arena, 0x3C9C5B51, (short) 0x995D, (short) 0x48D1,
                new byte[] { (byte) 0x9B, (byte) 0x8D, (byte) 0xFA, (byte) 0x5C, (byte) 0xAE, (byte) 0xDE, (byte) 0xD6, (byte) 0x5C });
    }

    /**
     * {@code IID_ID3D10Multithread} ({@code 9b7e4e00-342c-4106-a19f-4f2704f689f0}).
     * Every D3D11 device that shares work across engines (3D/copy and the
     * video-decode engine, exactly this module's case) must have multithread
     * protection enabled via {@link #setMultithreadProtected} — without it,
     * the runtime does not serialize the decode engine's writes against a
     * later {@code CopySubresourceRegion}/{@code Map} readback, so a CPU
     * read can silently observe pre-decode (zeroed) surface contents even
     * though every DXVA call in between reports success.
     */
    public static MemorySegment iidId3D10Multithread(Arena arena) {
        return Ole32.guid(arena, 0x9B7E4E00, (short) 0x342C, (short) 0x4106,
                new byte[] { (byte) 0xA1, (byte) 0x9F, (byte) 0x4F, (byte) 0x27, (byte) 0x04, (byte) 0xF6, (byte) 0x89, (byte) 0xF0 });
    }

    /**
     * {@code DXVA_ModeH264_E} ({@code 1b81be68-a0c7-11d3-b984-00c04f2e73c5}) —
     * H.264 VLD, no film-grain technology. The decoder-profile GUID for
     * {@link #createVideoDecoder}'s {@code D3D11_VIDEO_DECODER_DESC.Guid}
     * (same profile FFmpeg's {@code d3d11va} hwaccel negotiates for AVC).
     */
    public static MemorySegment profileH264VldNoFgt(Arena arena) {
        return Ole32.guid(arena, 0x1B81BE68, (short) 0xA0C7, (short) 0x11D3,
                new byte[] { (byte) 0xB9, (byte) 0x84, (byte) 0x00, (byte) 0xC0, (byte) 0x4F, (byte) 0x2E, (byte) 0x73, (byte) 0xC5 });
    }

    /**
     * {@code DXVA_ModeHEVC_VLD_Main}
     * ({@code 5b11d51b-2f4c-4452-bcc3-09f2a1160cc0}) — HEVC Main profile VLD
     * (4:2:0, 8-bit). The decoder-profile GUID for 8-bit HEVC decode via
     * {@link #createVideoDecoder} (same profile FFmpeg's {@code d3d11va}
     * hwaccel negotiates for 8-bit HEVC; dxva.h).
     */
    public static MemorySegment profileHevcVldMain(Arena arena) {
        return Ole32.guid(arena, 0x5B11D51B, (short) 0x2F4C, (short) 0x4452,
                new byte[] { (byte) 0xBC, (byte) 0xC3, (byte) 0x09, (byte) 0xF2, (byte) 0xA1, (byte) 0x16, (byte) 0x0C, (byte) 0xC0 });
    }

    /**
     * {@code DXVA_ModeHEVC_VLD_Main10}
     * ({@code 107af0e0-ef1a-4d19-aba8-67a163073d13}) — HEVC Main10 profile VLD
     * (4:2:0, 10-bit, P010 output). The decoder-profile GUID for 10-bit HEVC
     * decode via {@link #createVideoDecoder} (dxva.h).
     */
    public static MemorySegment profileHevcVldMain10(Arena arena) {
        return Ole32.guid(arena, 0x107AF0E0, (short) 0xEF1A, (short) 0x4D19,
                new byte[] { (byte) 0xAB, (byte) 0xA8, (byte) 0x67, (byte) 0xA1, (byte) 0x63, (byte) 0x07, (byte) 0x3D, (byte) 0x13 });
    }

    /**
     * {@code DXVA_ModeAV1_VLD_Profile0}
     * ({@code b8be4ccb-cf53-46ba-8d59-d6b8a6da5d2a}) — AV1 Main profile VLD
     * (4:2:0, 8/10-bit). The decoder-profile GUID for AV1 decode via
     * {@link #createVideoDecoder} (same profile FFmpeg's {@code d3d11va}
     * hwaccel negotiates for AV1; dxva.h SDK 10.0.26100).
     */
    public static MemorySegment profileAV1VldProfile0(Arena arena) {
        return Ole32.guid(arena, 0xB8BE4CCB, (short) 0xCF53, (short) 0x46BA,
                new byte[] { (byte) 0x8D, (byte) 0x59, (byte) 0xD6, (byte) 0xB8, (byte) 0xA6, (byte) 0xDA, (byte) 0x5D, (byte) 0x2A });
    }

    // ── D3D11_VIDEO_DECODER_BUFFER_TYPE (d3d11.h) ───────────────────────

    public static final int D3D11_VIDEO_DECODER_BUFFER_PICTURE_PARAMETERS = 0;
    public static final int D3D11_VIDEO_DECODER_BUFFER_MACROBLOCK_CONTROL = 1;
    public static final int D3D11_VIDEO_DECODER_BUFFER_RESIDUAL_DIFFERENCE = 2;
    public static final int D3D11_VIDEO_DECODER_BUFFER_DEBLOCKING_CONTROL = 3;
    public static final int D3D11_VIDEO_DECODER_BUFFER_INVERSE_QUANTIZATION_MATRIX = 4;
    public static final int D3D11_VIDEO_DECODER_BUFFER_SLICE_CONTROL = 5;
    public static final int D3D11_VIDEO_DECODER_BUFFER_BITSTREAM = 6;
    public static final int D3D11_VIDEO_DECODER_BUFFER_MOTION_VECTOR = 7;
    public static final int D3D11_VIDEO_DECODER_BUFFER_FILM_GRAIN = 8;
    public static final int D3D11_VIDEO_DECODER_BUFFER_HUFFMAN_TABLE = 9;

    // ── Output-surface constants (d3d11.h / dxgiformat.h) ───────────────

    /** {@code DXGI_FORMAT_NV12} — the decoder output format for this DXVA profile. */
    public static final int DXGI_FORMAT_NV12 = 103;
    /** {@code DXGI_FORMAT_P010} — the 10-bit semi-planar 4:2:0 decoder output format. */
    public static final int DXGI_FORMAT_P010 = 104;
    /** {@code D3D11_BIND_DECODER} — required {@code D3D11_TEXTURE2D_DESC.BindFlags} bit for a decoder output surface. */
    public static final int D3D11_BIND_DECODER = 0x200;
    /** {@code D3D11_USAGE_DEFAULT} — {@code D3D11_TEXTURE2D_DESC.Usage} for a GPU-only decoder surface. */
    public static final int D3D11_USAGE_DEFAULT = 0;
    /** {@code D3D11_VDOV_DIMENSION_TEXTURE2D} — {@code D3D11_VIDEO_DECODER_OUTPUT_VIEW_DESC.ViewDimension}. */
    public static final int D3D11_VDOV_DIMENSION_TEXTURE2D = 1;

    // ── CPU-readback constants (d3d11.h) ────────────────────────────────

    /** {@code D3D11_USAGE_STAGING} — {@code D3D11_TEXTURE2D_DESC.Usage} for a CPU-readable copy target. */
    public static final int D3D11_USAGE_STAGING = 3;
    /** {@code D3D11_CPU_ACCESS_READ} — {@code D3D11_TEXTURE2D_DESC.CPUAccessFlags} for a staging texture. */
    public static final int D3D11_CPU_ACCESS_READ = 0x20000;
    /** {@code D3D11_MAP_READ} — {@link #map}'s {@code MapType} for a read-only CPU map. */
    public static final int D3D11_MAP_READ = 1;

    // ── Linker / vtable dispatch handles ─────────────────────────────────
    //
    // Slot indices confirmed via jextract against ID3D11VideoDeviceVtbl /
    // ID3D11VideoContextVtbl (10.0.26100.0) — see class docs.

    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * {@code D3D11CreateDevice} — same downcall shape and load path as
     * {@code panama-media-mediafoundation}'s {@code MediaFoundation.java}
     * (duplicated rather than depended-on: that module also pulls in
     * {@code mfplat}/{@code mfreadwrite}, which this decode-only path has no
     * use for).
     */
    private static final MethodHandle H_D3D11_CREATE_DEVICE;

    static {
        MethodHandle h = null;
        if (IS_WINDOWS) {
            try {
                System.loadLibrary("d3d11");
                SymbolLookup lookup = SymbolLookup.loaderLookup();
                h = LINKER.downcallHandle(
                        lookup.findOrThrow("D3D11CreateDevice"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            } catch (Throwable ignored) {
                h = null;
            }
        }
        H_D3D11_CREATE_DEVICE = h;
    }

    private static final int D3D_DRIVER_TYPE_UNKNOWN = 0;
    private static final int D3D_DRIVER_TYPE_HARDWARE = 1;
    private static final int D3D_DRIVER_TYPE_WARP = 5;
    /** {@code D3D11_CREATE_DEVICE_VIDEO_SUPPORT (0x800) | D3D11_CREATE_DEVICE_BGRA_SUPPORT (0x20)}. */
    private static final int D3D11_CREATE_DEVICE_FLAGS = 0x820;
    private static final int D3D11_SDK_VERSION = 7;

    /**
     * An {@code ID3D11Device*} paired with its immediate context and the
     * adapter the created device actually owns.
     */
    public record Device(
            MemorySegment device,
            MemorySegment context,
            HardwareAdapterInfo adapterInfo) {}

    /** One decoder profile advertised by an {@code ID3D11VideoDevice}. */
    public record DecoderProfile(String guid, String name) {}

    /**
     * Decoder profiles advertised by one exact D3D11 adapter.
     *
     * <p>The profile list is driver capability metadata only. A codec tier
     * still needs fixture-backed decoder creation and frame output before it
     * can be classified as usable.</p>
     */
    public record DecoderProfileInventory(
            HardwareAdapterInfo adapterInfo,
            List<DecoderProfile> profiles) {
        public DecoderProfileInventory {
            profiles = List.copyOf(profiles);
        }

        public boolean advertises(String name) {
            return profiles.stream().anyMatch(profile -> profile.name().equals(name));
        }
    }

    /**
     * Creates a D3D11 device with video-decode support ({@code
     * D3D11_CREATE_DEVICE_VIDEO_SUPPORT}), trying the hardware driver first
     * and falling back to WARP (software rasterizer — no video decode
     * capability, but keeps the caller's code path uniform when no GPU is
     * present). Same recipe as {@code MediaFoundation.java}'s
     * {@code setupDecoder}, proven on this project's D3D11VA hardware tests.
     * <p>
     * {@code pAdapter=NULL} + {@code D3D_DRIVER_TYPE_HARDWARE} lets Windows
     * pick whichever adapter it considers default — on a multi-GPU host
     * (e.g. an AMD iGPU alongside an NVIDIA dGPU) that is not necessarily
     * the adapter a vendor-specific caller needs. Use {@link
     * #createDeviceForVendor} instead when the decode path only works on a
     * specific vendor's silicon (e.g. AMD AMF).
     *
     * @throws DecodeException if both attempts fail
     */
    public static Device createDevice(Arena arena) {
        MemorySegment ppDevice = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment ppContext = arena.allocate(ValueLayout.ADDRESS);
        int hr = createDevice(D3D_DRIVER_TYPE_HARDWARE, ppDevice, ppContext);
        if (Ole32.failed(hr)) {
            hr = createDevice(D3D_DRIVER_TYPE_WARP, ppDevice, ppContext);
        }
        Ole32.check(hr, "D3D11CreateDevice");
        MemorySegment device = ppDevice.get(ValueLayout.ADDRESS, 0);
        return new Device(device, ppContext.get(ValueLayout.ADDRESS, 0),
                adapterInfoForDevice(arena, device));
    }

    private static int createDevice(int driverType, MemorySegment ppDevice, MemorySegment ppContext) {
        try {
            return (int) H_D3D11_CREATE_DEVICE.invokeExact(
                    MemorySegment.NULL, driverType, MemorySegment.NULL, D3D11_CREATE_DEVICE_FLAGS,
                    MemorySegment.NULL, 0, D3D11_SDK_VERSION,
                    ppDevice, MemorySegment.NULL, ppContext);
        } catch (Throwable t) {
            throw rethrow("D3D11CreateDevice", t);
        }
    }

    /**
     * Creates a D3D11 device pinned to a specific {@code IDXGIAdapter*}
     * (from {@link #findAdapterByVendor}), falling back to the
     * default-adapter behavior of {@link #createDevice(Arena)} when {@code
     * adapter} is {@code null}/{@link MemorySegment#NULL} or device creation
     * on it fails. Per {@code D3D11CreateDevice}'s own contract, a non-null
     * {@code pAdapter} requires {@code DriverType=D3D_DRIVER_TYPE_UNKNOWN}
     * (not {@code HARDWARE}).
     */
    public static Device createDevice(Arena arena, MemorySegment adapter) {
        if (adapter == null || MemorySegment.NULL.equals(adapter)) {
            return createDevice(arena);
        }
        MemorySegment ppDevice = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment ppContext = arena.allocate(ValueLayout.ADDRESS);
        int hr;
        try {
            hr = (int) H_D3D11_CREATE_DEVICE.invokeExact(
                    adapter, D3D_DRIVER_TYPE_UNKNOWN, MemorySegment.NULL, D3D11_CREATE_DEVICE_FLAGS,
                    MemorySegment.NULL, 0, D3D11_SDK_VERSION,
                    ppDevice, MemorySegment.NULL, ppContext);
        } catch (Throwable t) {
            throw rethrow("D3D11CreateDevice(pinned adapter)", t);
        }
        if (Ole32.failed(hr)) {
            return createDevice(arena);
        }
        MemorySegment device = ppDevice.get(ValueLayout.ADDRESS, 0);
        return new Device(device, ppContext.get(ValueLayout.ADDRESS, 0),
                adapterInfoForDevice(arena, device));
    }

    /**
     * Creates a device on the selected adapter. Explicit vendor/exact
     * selectors are strict: absence or device-creation failure throws instead
     * of silently falling back to another adapter.
     */
    public static Device createDevice(Arena arena, HardwareAdapterSelector selector) {
        HardwareAdapterSelector requested = selector == null
                ? HardwareAdapterSelector.defaultAdapter()
                : selector;
        if (!requested.isExplicit()) {
            return createDevice(arena);
        }
        SelectedAdapter selected = findAdapter(arena, requested);
        if (selected == null) {
            throw new DecodeException("requested D3D11 adapter not found: " + requested);
        }
        try {
            MemorySegment ppDevice = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ppContext = arena.allocate(ValueLayout.ADDRESS);
            int hr;
            try {
                hr = (int) H_D3D11_CREATE_DEVICE.invokeExact(
                        selected.pointer(), D3D_DRIVER_TYPE_UNKNOWN, MemorySegment.NULL,
                        D3D11_CREATE_DEVICE_FLAGS, MemorySegment.NULL, 0,
                        D3D11_SDK_VERSION, ppDevice, MemorySegment.NULL, ppContext);
            } catch (Throwable t) {
                throw rethrow("D3D11CreateDevice(" + requested + ")", t);
            }
            Ole32.check(hr, "D3D11CreateDevice(" + requested + ")");
            return new Device(ppDevice.get(ValueLayout.ADDRESS, 0),
                    ppContext.get(ValueLayout.ADDRESS, 0), selected.info());
        } finally {
            Ole32.release(selected.pointer());
        }
    }

    /**
     * Creates a D3D11 device pinned to the adapter chosen for the given
     * {@code VendorId} (e.g. {@link #VENDOR_AMD}). Selection is strict:
     * absence or device-creation failure throws instead of falling back to
     * the default adapter. Needed for vendor-locked decode paths (AMD AMF)
     * on multi-GPU hosts where an unpinned {@code D3D11CreateDevice} call
     * may resolve to a different GPU than the one the caller actually needs.
     */
    public static Device createDeviceForVendor(Arena arena, int vendorId) {
        return createDevice(arena, HardwareAdapterSelector.vendor(vendorId));
    }

    // ── DXGI adapter enumeration (vendor pinning) ───────────────────────

    /** PCI vendor id for AMD adapters ({@code DXGI_ADAPTER_DESC1.VendorId}). */
    public static final int VENDOR_AMD = 0x1002;
    /** PCI vendor id for NVIDIA adapters. */
    public static final int VENDOR_NVIDIA = 0x10DE;
    /** PCI vendor id for Intel adapters. */
    public static final int VENDOR_INTEL = 0x8086;

    private static final MethodHandle H_CREATE_DXGI_FACTORY1;

    static {
        MethodHandle h = null;
        if (IS_WINDOWS) {
            try {
                System.loadLibrary("dxgi");
                SymbolLookup lookup = SymbolLookup.loaderLookup();
                // HRESULT CreateDXGIFactory1(REFIID riid, void **ppFactory)
                h = LINKER.downcallHandle(
                        lookup.findOrThrow("CreateDXGIFactory1"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            } catch (Throwable ignored) {
                h = null;
            }
        }
        H_CREATE_DXGI_FACTORY1 = h;
    }

    /** {@code IID_IDXGIFactory1} ({@code 770aae78-f26f-4dba-a829-253c83d1b387}). */
    private static MemorySegment iidIDXGIFactory1(Arena arena) {
        return Ole32.guid(arena, 0x770AAE78, (short) 0xF26F, (short) 0x4DBA,
                new byte[] { (byte) 0xA8, (byte) 0x29, (byte) 0x25, (byte) 0x3C, (byte) 0x83, (byte) 0xD1, (byte) 0xB3, (byte) 0x87 });
    }

    /** {@code IDXGIFactory1::EnumAdapters1} (vtable[12]; IUnknown[0-2] + IDXGIObject[3-6] + IDXGIFactory[7-11] + EnumAdapters1[12]). */
    private static final MethodHandle IDXGIFactory1_EnumAdapters1 = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** {@code IDXGIAdapter1::GetDesc1} (vtable[10]; IUnknown[0-2] + IDXGIObject[3-6] + IDXGIAdapter[7-9] + GetDesc1[10]). */
    private static final MethodHandle IDXGIAdapter1_GetDesc1 = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** {@code IDXGIAdapter::CheckInterfaceSupport} (vtable[9]). */
    private static final MethodHandle IDXGIAdapter_CheckInterfaceSupport = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** {@code IDXGIDevice::GetAdapter} (vtable[7]). */
    private static final MethodHandle IDXGIDevice_GetAdapter = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /**
     * {@code DXGI_ADAPTER_DESC1} layout (dxgi1_2.h): only {@code VendorId} is
     * ever read out of it here (see {@link #findAdapterByVendor}), but the
     * full layout is needed so that field's byte offset is right and the
     * allocation is large enough for {@code GetDesc1} to write into.
     */
    private static final MemoryLayout DXGI_ADAPTER_DESC1 = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(128, ValueLayout.JAVA_CHAR).withName("Description"),
            ValueLayout.JAVA_INT.withName("VendorId"),
            ValueLayout.JAVA_INT.withName("DeviceId"),
            ValueLayout.JAVA_INT.withName("SubSysId"),
            ValueLayout.JAVA_INT.withName("Revision"),
            ValueLayout.JAVA_LONG.withName("DedicatedVideoMemory"),
            ValueLayout.JAVA_LONG.withName("DedicatedSystemMemory"),
            ValueLayout.JAVA_LONG.withName("SharedSystemMemory"),
            MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT).withName("AdapterLuid"),
            ValueLayout.JAVA_INT.withName("Flags"),
            MemoryLayout.paddingLayout(4));

    private static final long DESC1_VENDOR_ID_OFFSET =
            DXGI_ADAPTER_DESC1.byteOffset(MemoryLayout.PathElement.groupElement("VendorId"));
    private static final long DESC1_DEVICE_ID_OFFSET =
            DXGI_ADAPTER_DESC1.byteOffset(MemoryLayout.PathElement.groupElement("DeviceId"));
    private static final long DESC1_SUBSYS_ID_OFFSET =
            DXGI_ADAPTER_DESC1.byteOffset(MemoryLayout.PathElement.groupElement("SubSysId"));
    private static final long DESC1_REVISION_OFFSET =
            DXGI_ADAPTER_DESC1.byteOffset(MemoryLayout.PathElement.groupElement("Revision"));
    private static final long DESC1_DEDICATED_VIDEO_MEMORY_OFFSET =
            DXGI_ADAPTER_DESC1.byteOffset(MemoryLayout.PathElement.groupElement("DedicatedVideoMemory"));

    private record SelectedAdapter(
            MemorySegment pointer,
            HardwareAdapterInfo info,
            long dedicatedVideoMemory) {}

    /** Returns every real DXGI adapter in enumeration order. */
    public static List<HardwareAdapterInfo> enumerateAdapters() {
        if (H_CREATE_DXGI_FACTORY1 == null) {
            return List.of();
        }
        try (Arena arena = Arena.ofConfined()) {
            List<HardwareAdapterInfo> result = new ArrayList<>();
            for (SelectedAdapter adapter : enumerateAdapterPointers(arena)) {
                result.add(adapter.info());
                Ole32.release(adapter.pointer());
            }
            return List.copyOf(result);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * Finds the DXGI adapter whose {@code VendorId} matches (e.g. {@link
     * #VENDOR_AMD}) by enumerating via {@code CreateDXGIFactory1}/{@code
     * IDXGIFactory1::EnumAdapters1}. When more than one adapter matches
     * (e.g. an AMD iGPU alongside a discrete Radeon), picks the one with the
     * most {@code DedicatedVideoMemory} rather than just the first one
     * enumerated: an integrated adapter shares system RAM and reports
     * near-zero dedicated VRAM, while a discrete card reports its actual
     * VRAM size, so this reliably prefers the more capable adapter without
     * needing GPU-preference APIs ({@code IDXGIFactory6}, Windows 10 1803+)
     * this project doesn't otherwise depend on. Returns the {@code
     * IDXGIAdapter1*} (caller must {@link Ole32#release} it) or {@link
     * MemorySegment#NULL} if none matched or DXGI enumeration itself is
     * unavailable. Never throws.
     */
    public static MemorySegment findAdapterByVendor(Arena arena, int vendorId) {
        SelectedAdapter selected = findAdapter(arena, HardwareAdapterSelector.vendor(vendorId));
        return selected == null ? MemorySegment.NULL : selected.pointer();
    }

    private static SelectedAdapter findAdapter(
            Arena arena,
            HardwareAdapterSelector selector) {
        SelectedAdapter best = null;
        for (SelectedAdapter candidate : enumerateAdapterPointers(arena)) {
            if (!selector.matches(candidate.info())) {
                Ole32.release(candidate.pointer());
                continue;
            }
            if (best == null
                    || Long.compareUnsigned(candidate.dedicatedVideoMemory(),
                            best.dedicatedVideoMemory()) > 0) {
                if (best != null) {
                    Ole32.release(best.pointer());
                }
                best = candidate;
            } else {
                Ole32.release(candidate.pointer());
            }
        }
        return best;
    }

    private static List<SelectedAdapter> enumerateAdapterPointers(Arena arena) {
        if (H_CREATE_DXGI_FACTORY1 == null) {
            return List.of();
        }
        MemorySegment factory = MemorySegment.NULL;
        List<SelectedAdapter> adapters = new ArrayList<>();
        try {
            MemorySegment ppFactory = arena.allocate(ValueLayout.ADDRESS);
            int hr = (int) H_CREATE_DXGI_FACTORY1.invokeExact(iidIDXGIFactory1(arena), ppFactory);
            if (Ole32.failed(hr)) {
                return List.of();
            }
            factory = ppFactory.get(ValueLayout.ADDRESS, 0);

            for (int i = 0; ; i++) {
                MemorySegment ppAdapter = arena.allocate(ValueLayout.ADDRESS);
                int ehr = (int) IDXGIFactory1_EnumAdapters1.invokeExact(
                        Ole32.vtable(factory, 12), factory, i, ppAdapter);
                if (Ole32.failed(ehr)) break; // DXGI_ERROR_NOT_FOUND (end of list) or other failure
                MemorySegment adapter = ppAdapter.get(ValueLayout.ADDRESS, 0);
                MemorySegment desc = arena.allocate(DXGI_ADAPTER_DESC1);
                int dhr = (int) IDXGIAdapter1_GetDesc1.invokeExact(Ole32.vtable(adapter, 10), adapter, desc);
                if (Ole32.failed(dhr)) {
                    Ole32.release(adapter);
                    continue;
                }
                adapters.add(new SelectedAdapter(adapter, adapterInfo(adapter, desc, arena),
                        desc.get(ValueLayout.JAVA_LONG, DESC1_DEDICATED_VIDEO_MEMORY_OFFSET)));
            }
            return adapters;
        } catch (Throwable t) {
            for (SelectedAdapter adapter : adapters) {
                Ole32.release(adapter.pointer());
            }
            return List.of();
        } finally {
            Ole32.release(factory);
        }
    }

    private static HardwareAdapterInfo adapterInfoForDevice(
            Arena arena,
            MemorySegment d3dDevice) {
        MemorySegment dxgiDevice = MemorySegment.NULL;
        MemorySegment adapter = MemorySegment.NULL;
        try {
            MemorySegment ppDxgiDevice = arena.allocate(ValueLayout.ADDRESS);
            int hr = Ole32.queryInterface(d3dDevice, iidIDXGIDevice(arena), ppDxgiDevice);
            if (Ole32.failed(hr)) {
                return HardwareAdapterInfo.unknown("dxgi");
            }
            dxgiDevice = ppDxgiDevice.get(ValueLayout.ADDRESS, 0);
            MemorySegment ppAdapter = arena.allocate(ValueLayout.ADDRESS);
            hr = (int) IDXGIDevice_GetAdapter.invokeExact(
                    Ole32.vtable(dxgiDevice, 7), dxgiDevice, ppAdapter);
            if (Ole32.failed(hr)) {
                return HardwareAdapterInfo.unknown("dxgi");
            }
            adapter = ppAdapter.get(ValueLayout.ADDRESS, 0);
            MemorySegment desc = arena.allocate(DXGI_ADAPTER_DESC1);
            hr = (int) IDXGIAdapter1_GetDesc1.invokeExact(
                    Ole32.vtable(adapter, 10), adapter, desc);
            return Ole32.failed(hr)
                    ? HardwareAdapterInfo.unknown("dxgi")
                    : adapterInfo(adapter, desc, arena);
        } catch (Throwable ignored) {
            return HardwareAdapterInfo.unknown("dxgi");
        } finally {
            Ole32.release(adapter);
            Ole32.release(dxgiDevice);
        }
    }

    private static HardwareAdapterInfo adapterInfo(
            MemorySegment adapter,
            MemorySegment desc,
            Arena arena) {
        return new HardwareAdapterInfo(
                "dxgi",
                readWideString(desc, 128),
                desc.get(ValueLayout.JAVA_INT, DESC1_VENDOR_ID_OFFSET),
                desc.get(ValueLayout.JAVA_INT, DESC1_DEVICE_ID_OFFSET),
                desc.get(ValueLayout.JAVA_INT, DESC1_SUBSYS_ID_OFFSET),
                desc.get(ValueLayout.JAVA_INT, DESC1_REVISION_OFFSET),
                driverVersion(adapter, arena));
    }

    private static String driverVersion(MemorySegment adapter, Arena arena) {
        try {
            MemorySegment version = arena.allocate(ValueLayout.JAVA_LONG);
            int hr = (int) IDXGIAdapter_CheckInterfaceSupport.invokeExact(
                    Ole32.vtable(adapter, 9), adapter, iidIDXGIDevice(arena), version);
            if (Ole32.failed(hr)) {
                return "unknown";
            }
            long value = version.get(ValueLayout.JAVA_LONG, 0);
            return ((value >>> 48) & 0xFFFF) + "."
                    + ((value >>> 32) & 0xFFFF) + "."
                    + ((value >>> 16) & 0xFFFF) + "."
                    + (value & 0xFFFF);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String readWideString(MemorySegment desc, int maxChars) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < maxChars; i++) {
            char c = desc.get(ValueLayout.JAVA_CHAR, (long) i * Character.BYTES);
            if (c == 0) {
                break;
            }
            text.append(c);
        }
        return text.toString();
    }

    /** {@code IID_IDXGIDevice} ({@code 54ec77fa-1377-44e6-8c32-88fd5f44c84c}). */
    private static MemorySegment iidIDXGIDevice(Arena arena) {
        return Ole32.guid(arena, 0x54EC77FA, (short) 0x1377, (short) 0x44E6,
                new byte[] { (byte) 0x8C, (byte) 0x32, (byte) 0x88, (byte) 0xFD,
                        (byte) 0x5F, (byte) 0x44, (byte) 0xC8, (byte) 0x4C });
    }

    /** {@code IID_ID3D11Device} ({@code db6f6ddb-ac77-4e88-8253-819df9bbf140}). */
    private static MemorySegment iidId3D11Device(Arena arena) {
        return Ole32.guid(arena, 0xDB6F6DDB, (short) 0xAC77, (short) 0x4E88,
                new byte[] { (byte) 0x82, (byte) 0x53, (byte) 0x81, (byte) 0x9D,
                        (byte) 0xF9, (byte) 0xBB, (byte) 0xF1, (byte) 0x40 });
    }

    /** ID3D11Device::CreateTexture2D -- vtable[5] */
    private static final MethodHandle ID3D11Device_CreateTexture2D = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /**
     * {@code ID3D11Device::CreateTexture2D} — allocates the decoder output
     * surface (e.g. a {@code D3D11_TEXTURE2D_DESC} texture array, one slice
     * per DPB entry, {@code BindFlags = }{@link #D3D11_BIND_DECODER},
     * {@code Format = }{@link #DXGI_FORMAT_NV12}). Pass {@code null} initial
     * data — decoder surfaces are written by the hardware, never uploaded
     * from the CPU.
     *
     * @param d3dDevice   the {@code ID3D11Device*}
     * @param desc        a {@code D3D11_TEXTURE2D_DESC} (see the {@code jextract}
     *                    subpackage for the struct layout)
     * @param ppTexture2D receives the {@code ID3D11Texture2D*}
     * @return the HRESULT
     */
    public static int createTexture2D(MemorySegment d3dDevice, MemorySegment desc, MemorySegment ppTexture2D) {
        try {
            return (int) ID3D11Device_CreateTexture2D.invokeExact(
                    Ole32.vtable(d3dDevice, 5), d3dDevice, desc, MemorySegment.NULL, ppTexture2D);
        } catch (Throwable t) {
            throw rethrow("ID3D11Device::CreateTexture2D", t);
        }
    }

    /** ID3D11VideoDevice::CreateVideoDecoder -- vtable[3] */
    private static final MethodHandle ID3D11VideoDevice_CreateVideoDecoder = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** ID3D11VideoDevice::CreateVideoDecoderOutputView -- vtable[7] */
    private static final MethodHandle ID3D11VideoDevice_CreateVideoDecoderOutputView = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** ID3D11VideoDevice::GetVideoDecoderProfileCount -- vtable[11] */
    private static final MethodHandle ID3D11VideoDevice_GetVideoDecoderProfileCount =
            LINKER.downcallHandle(
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** ID3D11VideoDevice::GetVideoDecoderProfile -- vtable[12] */
    private static final MethodHandle ID3D11VideoDevice_GetVideoDecoderProfile =
            LINKER.downcallHandle(
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** ID3D11VideoDevice::GetVideoDecoderConfigCount -- vtable[14] */
    private static final MethodHandle ID3D11VideoDevice_GetVideoDecoderConfigCount = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** ID3D11VideoDevice::GetVideoDecoderConfig -- vtable[15] */
    private static final MethodHandle ID3D11VideoDevice_GetVideoDecoderConfig = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** ID3D11VideoContext::GetDecoderBuffer -- vtable[7] */
    private static final MethodHandle ID3D11VideoContext_GetDecoderBuffer = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** ID3D11VideoContext::ReleaseDecoderBuffer -- vtable[8] */
    private static final MethodHandle ID3D11VideoContext_ReleaseDecoderBuffer = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** ID3D11VideoContext::DecoderBeginFrame -- vtable[9] */
    private static final MethodHandle ID3D11VideoContext_DecoderBeginFrame = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** ID3D11VideoContext::DecoderEndFrame -- vtable[10] */
    private static final MethodHandle ID3D11VideoContext_DecoderEndFrame = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** ID3D11VideoContext::SubmitDecoderBuffers -- vtable[11] */
    private static final MethodHandle ID3D11VideoContext_SubmitDecoderBuffers = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /**
     * ID3D11DeviceContext::Map -- vtable[14], ID3D11DeviceContext::Unmap -- vtable[15],
     * ID3D11DeviceContext::CopySubresourceRegion -- vtable[46]. Indices confirmed via a
     * real jextract pass against {@code ID3D11DeviceContextVtbl} (10.0.26100.0), same
     * verification rigor as the {@code ID3D11Video*} indices above.
     */
    private static final MethodHandle ID3D11DeviceContext_Map = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** ID3D11DeviceContext::Unmap -- vtable[15] */
    private static final MethodHandle ID3D11DeviceContext_Unmap = LINKER.downcallHandle(
            FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** ID3D11DeviceContext::CopySubresourceRegion -- vtable[46] */
    private static final MethodHandle ID3D11DeviceContext_CopySubresourceRegion = LINKER.downcallHandle(
            FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /**
     * ID3D10Multithread::SetMultithreadProtected -- vtable[5]. Indices confirmed via a real
     * jextract pass against {@code ID3D10MultithreadVtbl} (10.0.26100.0): QueryInterface(0)/
     * AddRef(1)/Release(2)/Enter(3)/Leave(4)/SetMultithreadProtected(5)/GetMultithreadProtected(6).
     */
    private static final MethodHandle ID3D10Multithread_SetMultithreadProtected = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // ── QueryInterface helpers ───────────────────────────────────────────

    /**
     * {@code d3dDevice->QueryInterface(IID_ID3D11VideoDevice, ...)}.
     *
     * @param arena     arena backing the IID allocation and the returned segment
     * @param d3dDevice an {@code ID3D11Device*} (e.g. from
     *                  {@code panama-media-mediafoundation}'s {@code D3D11CreateDevice})
     * @return the {@code ID3D11VideoDevice*}
     * @throws DecodeException if the interface is not supported
     */
    public static MemorySegment queryVideoDevice(Arena arena, MemorySegment d3dDevice) {
        MemorySegment ppv = arena.allocate(ValueLayout.ADDRESS);
        int hr = Ole32.queryInterface(d3dDevice, iidId3D11VideoDevice(arena), ppv);
        Ole32.check(hr, "ID3D11Device::QueryInterface(IID_ID3D11VideoDevice)");
        return ppv.get(ValueLayout.ADDRESS, 0);
    }

    /**
     * Enumerates every decoder profile exposed by the selected adapter.
     * Device and COM-interface ownership is contained entirely by this call.
     */
    public static DecoderProfileInventory decoderProfiles(
            HardwareAdapterSelector selector) {
        if (!isAvailable()) {
            throw new IllegalStateException("D3D11 video is unavailable");
        }
        try (Arena arena = Arena.ofConfined()) {
            Device selected = createDevice(arena, selector);
            MemorySegment videoDevice = MemorySegment.NULL;
            try {
                videoDevice = queryVideoDevice(arena, selected.device());
                int count = getVideoDecoderProfileCount(videoDevice);
                if (count < 0 || count > 4096) {
                    throw new DecodeException(
                            "invalid D3D11 decoder profile count: "
                                    + Integer.toUnsignedLong(count));
                }
                List<DecoderProfile> profiles = new ArrayList<>(count);
                MemorySegment guid = arena.allocate(16);
                for (int i = 0; i < count; i++) {
                    int hr = getVideoDecoderProfile(videoDevice, i, guid);
                    Ole32.check(hr, "ID3D11VideoDevice::GetVideoDecoderProfile");
                    String value = decoderProfileGuid(guid);
                    profiles.add(new DecoderProfile(value, decoderProfileName(value)));
                }
                return new DecoderProfileInventory(selected.adapterInfo(), profiles);
            } finally {
                Ole32.release(videoDevice);
                Ole32.release(selected.context());
                Ole32.release(selected.device());
            }
        }
    }

    /** {@code ID3D11VideoDevice::GetVideoDecoderProfileCount}. */
    public static int getVideoDecoderProfileCount(MemorySegment videoDevice) {
        try {
            return (int) ID3D11VideoDevice_GetVideoDecoderProfileCount.invokeExact(
                    Ole32.vtable(videoDevice, 11), videoDevice);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoDevice::GetVideoDecoderProfileCount", t);
        }
    }

    /** {@code ID3D11VideoDevice::GetVideoDecoderProfile}. */
    public static int getVideoDecoderProfile(
            MemorySegment videoDevice,
            int index,
            MemorySegment profileOut) {
        try {
            return (int) ID3D11VideoDevice_GetVideoDecoderProfile.invokeExact(
                    Ole32.vtable(videoDevice, 12), videoDevice, index, profileOut);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoDevice::GetVideoDecoderProfile", t);
        }
    }

    /** Converts an in-memory Windows {@code GUID} to canonical text. */
    public static String decoderProfileGuid(MemorySegment guid) {
        return String.format(Locale.ROOT,
                "%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                guid.get(ValueLayout.JAVA_INT, 0),
                guid.get(ValueLayout.JAVA_SHORT, 4) & 0xFFFF,
                guid.get(ValueLayout.JAVA_SHORT, 6) & 0xFFFF,
                guid.get(ValueLayout.JAVA_BYTE, 8) & 0xFF,
                guid.get(ValueLayout.JAVA_BYTE, 9) & 0xFF,
                guid.get(ValueLayout.JAVA_BYTE, 10) & 0xFF,
                guid.get(ValueLayout.JAVA_BYTE, 11) & 0xFF,
                guid.get(ValueLayout.JAVA_BYTE, 12) & 0xFF,
                guid.get(ValueLayout.JAVA_BYTE, 13) & 0xFF,
                guid.get(ValueLayout.JAVA_BYTE, 14) & 0xFF,
                guid.get(ValueLayout.JAVA_BYTE, 15) & 0xFF);
    }

    /**
     * Stable names for the legacy profiles covered by the decoder-provider
     * survey. Unknown profiles retain their GUID as their name.
     */
    public static String decoderProfileName(String guid) {
        return switch (guid.toLowerCase(Locale.ROOT)) {
            case "1b81be03-a0c7-11d3-b984-00c04f2e73c5" -> "h263-a";
            case "1b81be04-a0c7-11d3-b984-00c04f2e73c5" -> "h263-b";
            case "1b81be05-a0c7-11d3-b984-00c04f2e73c5" -> "h263-c";
            case "1b81be06-a0c7-11d3-b984-00c04f2e73c5" -> "h263-d";
            case "1b81be07-a0c7-11d3-b984-00c04f2e73c5" -> "h263-e";
            case "1b81be08-a0c7-11d3-b984-00c04f2e73c5" -> "h263-f";
            case "1b81bea0-a0c7-11d3-b984-00c04f2e73c5" -> "vc1-a";
            case "1b81bea1-a0c7-11d3-b984-00c04f2e73c5" -> "vc1-b";
            case "1b81bea2-a0c7-11d3-b984-00c04f2e73c5" -> "vc1-c";
            case "1b81bea3-a0c7-11d3-b984-00c04f2e73c5" -> "vc1-vld";
            case "1b81bea4-a0c7-11d3-b984-00c04f2e73c5" -> "vc1-vld-2010";
            case "efd64d74-c9e8-41d7-a5e9-e9b0e39fa319" -> "mpeg4p2-simple";
            case "ed418a9f-010d-4eda-9ae3-9a65358d8d2e" -> "mpeg4p2-advanced-simple-no-gmc";
            case "ab998b5b-4258-44a9-9feb-94e597a6baae" -> "mpeg4p2-advanced-simple-gmc";
            case "725cb506-0c29-43c4-9440-8e9397903a04" -> "mjpeg-420";
            case "5b77b9cd-1a35-4c30-9fd8-ef4b60c035dd" -> "mjpeg-422";
            case "d95161f9-0d44-47e6-bcf5-1bfbfb268f97" -> "mjpeg-444";
            case "c91748d5-fd18-4aca-9db3-3a6634ab547d" -> "mjpeg-4444";
            case "b8be4ccb-cf53-46ba-8d59-d6b8a6da5d2a" -> "av1-profile0";
            default -> guid;
        };
    }

    /**
     * {@code d3dContext->QueryInterface(IID_ID3D11VideoContext, ...)}.
     *
     * @param arena      arena backing the IID allocation and the returned segment
     * @param d3dContext an {@code ID3D11DeviceContext*}
     * @return the {@code ID3D11VideoContext*}
     * @throws DecodeException if the interface is not supported
     */
    public static MemorySegment queryVideoContext(Arena arena, MemorySegment d3dContext) {
        MemorySegment ppv = arena.allocate(ValueLayout.ADDRESS);
        int hr = Ole32.queryInterface(d3dContext, iidId3D11VideoContext(arena), ppv);
        Ole32.check(hr, "ID3D11DeviceContext::QueryInterface(IID_ID3D11VideoContext)");
        return ppv.get(ValueLayout.ADDRESS, 0);
    }

    /**
     * {@code d3dDevice->QueryInterface(IID_ID3D10Multithread, ...)}.
     *
     * @param arena     arena backing the IID allocation and the returned segment
     * @param d3dDevice an {@code ID3D11Device*}
     * @return the {@code ID3D10Multithread*}
     * @throws DecodeException if the interface is not supported
     */
    public static MemorySegment queryMultithread(Arena arena, MemorySegment d3dDevice) {
        MemorySegment ppv = arena.allocate(ValueLayout.ADDRESS);
        int hr = Ole32.queryInterface(d3dDevice, iidId3D10Multithread(arena), ppv);
        Ole32.check(hr, "ID3D11Device::QueryInterface(IID_ID3D10Multithread)");
        return ppv.get(ValueLayout.ADDRESS, 0);
    }

    /**
     * {@code ID3D10Multithread::SetMultithreadProtected} — required on any D3D11 device this
     * module drives a decoder on (see {@link #iidId3D10Multithread} for why).
     *
     * @param multithread the {@code ID3D10Multithread*} from {@link #queryMultithread}
     * @param protect     {@code true} to enable multithread protection
     * @return the previous protection state
     */
    public static boolean setMultithreadProtected(MemorySegment multithread, boolean protect) {
        try {
            int prev = (int) ID3D10Multithread_SetMultithreadProtected.invokeExact(
                    Ole32.vtable(multithread, 5), multithread, protect ? 1 : 0);
            return prev != 0;
        } catch (Throwable t) {
            throw rethrow("ID3D10Multithread::SetMultithreadProtected", t);
        }
    }

    // ── ID3D11VideoDevice ────────────────────────────────────────────────

    /**
     * {@code ID3D11VideoDevice::CreateVideoDecoder}.
     *
     * @param videoDevice the {@code ID3D11VideoDevice*}
     * @param videoDesc   a {@code D3D11_VIDEO_DECODER_DESC} (see the
     *                    {@code jextract} subpackage for the struct layout)
     * @param config      a {@code D3D11_VIDEO_DECODER_CONFIG} chosen from
     *                    {@link #getVideoDecoderConfig}
     * @param ppDecoder   receives the {@code ID3D11VideoDecoder*}
     * @return the HRESULT
     */
    public static int createVideoDecoder(MemorySegment videoDevice, MemorySegment videoDesc,
                                          MemorySegment config, MemorySegment ppDecoder) {
        try {
            return (int) ID3D11VideoDevice_CreateVideoDecoder.invokeExact(
                    Ole32.vtable(videoDevice, 3), videoDevice, videoDesc, config, ppDecoder);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoDevice::CreateVideoDecoder", t);
        }
    }

    /**
     * {@code ID3D11VideoDevice::GetVideoDecoderConfigCount}.
     *
     * @param videoDevice the {@code ID3D11VideoDevice*}
     * @param videoDesc   a {@code D3D11_VIDEO_DECODER_DESC}
     * @param countOut    receives the config count (a {@code UINT} slot)
     * @return the HRESULT
     */
    public static int getVideoDecoderConfigCount(MemorySegment videoDevice, MemorySegment videoDesc,
                                                  MemorySegment countOut) {
        try {
            return (int) ID3D11VideoDevice_GetVideoDecoderConfigCount.invokeExact(
                    Ole32.vtable(videoDevice, 14), videoDevice, videoDesc, countOut);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoDevice::GetVideoDecoderConfigCount", t);
        }
    }

    /**
     * {@code ID3D11VideoDevice::GetVideoDecoderConfig}.
     *
     * @param videoDevice the {@code ID3D11VideoDevice*}
     * @param videoDesc   a {@code D3D11_VIDEO_DECODER_DESC}
     * @param index       config index, {@code < GetVideoDecoderConfigCount()}
     * @param configOut   receives the {@code D3D11_VIDEO_DECODER_CONFIG}
     * @return the HRESULT
     */
    public static int getVideoDecoderConfig(MemorySegment videoDevice, MemorySegment videoDesc,
                                             int index, MemorySegment configOut) {
        try {
            return (int) ID3D11VideoDevice_GetVideoDecoderConfig.invokeExact(
                    Ole32.vtable(videoDevice, 15), videoDevice, videoDesc, index, configOut);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoDevice::GetVideoDecoderConfig", t);
        }
    }

    /**
     * {@code ID3D11VideoDevice::CreateVideoDecoderOutputView} — wraps one
     * slice of the {@link #createTexture2D} output array as a view the
     * decoder can render into (one call per array slice, {@code ViewDimension
     * = }{@link #D3D11_VDOV_DIMENSION_TEXTURE2D}, {@code Texture2D.ArraySlice
     * = } the slice index).
     *
     * @param videoDevice the {@code ID3D11VideoDevice*}
     * @param resource    the {@code ID3D11Texture2D*} (as {@code ID3D11Resource*}) from {@link #createTexture2D}
     * @param desc        a {@code D3D11_VIDEO_DECODER_OUTPUT_VIEW_DESC}
     * @param ppView      receives the {@code ID3D11VideoDecoderOutputView*}
     * @return the HRESULT
     */
    public static int createVideoDecoderOutputView(MemorySegment videoDevice, MemorySegment resource,
                                                    MemorySegment desc, MemorySegment ppView) {
        try {
            return (int) ID3D11VideoDevice_CreateVideoDecoderOutputView.invokeExact(
                    Ole32.vtable(videoDevice, 7), videoDevice, resource, desc, ppView);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoDevice::CreateVideoDecoderOutputView", t);
        }
    }

    // ── ID3D11VideoContext ───────────────────────────────────────────────

    /**
     * {@code ID3D11VideoContext::GetDecoderBuffer} — maps a DXVA buffer
     * (picture params / slice control / bitstream / ...) for writing.
     *
     * @param videoContext the {@code ID3D11VideoContext*}
     * @param decoder      the {@code ID3D11VideoDecoder*}
     * @param bufferType   one of the {@code D3D11_VIDEO_DECODER_BUFFER_*} constants
     * @param pBufferSize  receives the mapped buffer's size in bytes (a {@code UINT} slot)
     * @param ppBuffer     receives the mapped buffer pointer (a {@code void**} slot)
     * @return the HRESULT
     */
    public static int getDecoderBuffer(MemorySegment videoContext, MemorySegment decoder,
                                        int bufferType, MemorySegment pBufferSize, MemorySegment ppBuffer) {
        try {
            return (int) ID3D11VideoContext_GetDecoderBuffer.invokeExact(
                    Ole32.vtable(videoContext, 7), videoContext, decoder, bufferType, pBufferSize, ppBuffer);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoContext::GetDecoderBuffer", t);
        }
    }

    /**
     * {@code ID3D11VideoContext::ReleaseDecoderBuffer} — unmaps a buffer
     * obtained from {@link #getDecoderBuffer}, submitting it to the decoder.
     *
     * @param videoContext the {@code ID3D11VideoContext*}
     * @param decoder      the {@code ID3D11VideoDecoder*}
     * @param bufferType   the same {@code D3D11_VIDEO_DECODER_BUFFER_*} constant passed to {@link #getDecoderBuffer}
     * @return the HRESULT
     */
    public static int releaseDecoderBuffer(MemorySegment videoContext, MemorySegment decoder, int bufferType) {
        try {
            return (int) ID3D11VideoContext_ReleaseDecoderBuffer.invokeExact(
                    Ole32.vtable(videoContext, 8), videoContext, decoder, bufferType);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoContext::ReleaseDecoderBuffer", t);
        }
    }

    /**
     * {@code ID3D11VideoContext::DecoderBeginFrame} — begins decode into the
     * given output view (a view onto one element of the decoder's target
     * texture array).
     *
     * @param videoContext   the {@code ID3D11VideoContext*}
     * @param decoder        the {@code ID3D11VideoDecoder*}
     * @param outputView     the target {@code ID3D11VideoDecoderOutputView*}
     * @param contentKeySize size of {@code contentKey} in bytes, or 0
     * @param contentKey     an encryption content key, or {@link MemorySegment#NULL}
     * @return the HRESULT (retry on {@code E_PENDING} per MSDN)
     */
    public static int decoderBeginFrame(MemorySegment videoContext, MemorySegment decoder,
                                         MemorySegment outputView, int contentKeySize, MemorySegment contentKey) {
        try {
            return (int) ID3D11VideoContext_DecoderBeginFrame.invokeExact(
                    Ole32.vtable(videoContext, 9), videoContext, decoder, outputView, contentKeySize, contentKey);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoContext::DecoderBeginFrame", t);
        }
    }

    /**
     * {@code ID3D11VideoContext::DecoderEndFrame} — ends the frame begun by
     * {@link #decoderBeginFrame}.
     *
     * @param videoContext the {@code ID3D11VideoContext*}
     * @param decoder      the {@code ID3D11VideoDecoder*}
     * @return the HRESULT
     */
    public static int decoderEndFrame(MemorySegment videoContext, MemorySegment decoder) {
        try {
            return (int) ID3D11VideoContext_DecoderEndFrame.invokeExact(
                    Ole32.vtable(videoContext, 10), videoContext, decoder);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoContext::DecoderEndFrame", t);
        }
    }

    /**
     * {@code ID3D11VideoContext::SubmitDecoderBuffers} — submits the buffers
     * filled via {@link #getDecoderBuffer}/{@link #releaseDecoderBuffer}
     * (picture params, slice control, bitstream, ...) for this frame.
     *
     * @param videoContext  the {@code ID3D11VideoContext*}
     * @param decoder       the {@code ID3D11VideoDecoder*}
     * @param numBuffers    number of entries in {@code bufferDescArray}
     * @param bufferDescArray a contiguous array of {@code D3D11_VIDEO_DECODER_BUFFER_DESC}
     * @return the HRESULT
     */
    public static int submitDecoderBuffers(MemorySegment videoContext, MemorySegment decoder,
                                            int numBuffers, MemorySegment bufferDescArray) {
        try {
            return (int) ID3D11VideoContext_SubmitDecoderBuffers.invokeExact(
                    Ole32.vtable(videoContext, 11), videoContext, decoder, numBuffers, bufferDescArray);
        } catch (Throwable t) {
            throw rethrow("ID3D11VideoContext::SubmitDecoderBuffers", t);
        }
    }

    // ── ID3D11DeviceContext (CPU readback) ──────────────────────────────

    /**
     * {@code ID3D11DeviceContext::CopySubresourceRegion} — copies one whole
     * subresource (e.g. one array slice of the decoder's output texture
     * array) into a same-sized destination subresource at offset (0,0,0),
     * with no source-box restriction ({@code pSrcBox = NULL}, i.e. "copy the
     * whole thing") — the shape this module needs (one decoder-output slice
     * → one 1-slice staging texture for {@link #map}).
     *
     * @param context        the {@code ID3D11DeviceContext*} (the device's
     *                       immediate context, e.g. {@link Device#context()})
     * @param dstResource    the destination {@code ID3D11Resource*} (e.g. a staging texture)
     * @param dstSubresource destination subresource index (0 for a 1-slice staging texture)
     * @param srcResource    the source {@code ID3D11Resource*} (e.g. the decoder output array)
     * @param srcSubresource source subresource index (the array slice to copy)
     */
    public static void copySubresourceRegion(MemorySegment context, MemorySegment dstResource,
                                              int dstSubresource, MemorySegment srcResource,
                                              int srcSubresource) {
        try {
            ID3D11DeviceContext_CopySubresourceRegion.invokeExact(
                    Ole32.vtable(context, 46), context, dstResource, dstSubresource,
                    0, 0, 0, srcResource, srcSubresource, MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow("ID3D11DeviceContext::CopySubresourceRegion", t);
        }
    }

    /**
     * {@code ID3D11DeviceContext::Map} — maps a subresource (e.g. a staging
     * texture after {@link #copySubresourceRegion}) for CPU access.
     *
     * @param context           the {@code ID3D11DeviceContext*}
     * @param resource          the {@code ID3D11Resource*} to map
     * @param subresource       subresource index (0 for a 1-slice staging texture)
     * @param mapType           one of the {@code D3D11_MAP_*} constants (e.g. {@link #D3D11_MAP_READ})
     * @param mapFlags          {@code D3D11_MAP_FLAG_*} bits, or 0
     * @param mappedResourceOut receives a {@code D3D11_MAPPED_SUBRESOURCE}
     * @return the HRESULT
     */
    public static int map(MemorySegment context, MemorySegment resource, int subresource,
                           int mapType, int mapFlags, MemorySegment mappedResourceOut) {
        try {
            return (int) ID3D11DeviceContext_Map.invokeExact(
                    Ole32.vtable(context, 14), context, resource, subresource,
                    mapType, mapFlags, mappedResourceOut);
        } catch (Throwable t) {
            throw rethrow("ID3D11DeviceContext::Map", t);
        }
    }

    /**
     * {@code ID3D11DeviceContext::Unmap} — unmaps a subresource obtained from {@link #map}.
     *
     * @param context     the {@code ID3D11DeviceContext*}
     * @param resource    the {@code ID3D11Resource*} to unmap
     * @param subresource the same subresource index passed to {@link #map}
     */
    public static void unmap(MemorySegment context, MemorySegment resource, int subresource) {
        try {
            ID3D11DeviceContext_Unmap.invokeExact(
                    Ole32.vtable(context, 15), context, resource, subresource);
        } catch (Throwable t) {
            throw rethrow("ID3D11DeviceContext::Unmap", t);
        }
    }

    private static RuntimeException rethrow(String what, Throwable t) {
        if (t instanceof RuntimeException re) return re;
        return new DecodeException(what + " failed", t);
    }
}
