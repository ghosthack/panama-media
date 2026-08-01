package io.github.ghosthack.panama.media.d3d11video;

import io.github.ghosthack.panama.media.comruntime.Ole32;
import io.github.ghosthack.panama.media.core.HardwareAdapterInfo;
import io.github.ghosthack.panama.media.core.NativeVideoSurface;
import io.github.ghosthack.panama.media.d3d11video.jextract.D3D11_TEXTURE2D_DESC;
import io.github.ghosthack.panama.media.d3d11video.jextract.DXGI_SAMPLE_DESC;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Caller-owned single-slice copy of a D3D11 decoder texture. */
public final class D3d11TextureSurface implements NativeVideoSurface {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle GET_DEVICE = LINKER.downcallHandle(
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_IMMEDIATE_CONTEXT = LINKER.downcallHandle(
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_DESC = LINKER.downcallHandle(
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final int D3D11_BIND_SHADER_RESOURCE = 0x8;

    private final String provider;
    private final MemorySegment texture;
    private final int width;
    private final int height;
    private final String pixelFormat;
    private final HardwareAdapterInfo adapterInfo;
    private final AtomicBoolean closed = new AtomicBoolean();

    private D3d11TextureSurface(
            String provider,
            MemorySegment texture,
            int width,
            int height,
            String pixelFormat,
            HardwareAdapterInfo adapterInfo) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.texture = Objects.requireNonNull(texture, "texture");
        this.width = width;
        this.height = height;
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.adapterInfo = Objects.requireNonNull(adapterInfo, "adapterInfo");
    }

    /** GPU-copies one texture-array subresource into an independent texture. */
    public static D3d11TextureSurface copyOf(
            String provider,
            MemorySegment source,
            int sourceSubresource,
            int visibleWidth,
            int visibleHeight,
            HardwareAdapterInfo adapterInfo) {
        Objects.requireNonNull(source, "source");
        if (MemorySegment.NULL.equals(source)) {
            throw new IllegalArgumentException("source texture must not be NULL");
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment ppDevice = scratch.allocate(ValueLayout.ADDRESS);
            GET_DEVICE.invokeExact(Ole32.vtable(source, 3), source, ppDevice);
            MemorySegment device = ppDevice.get(ValueLayout.ADDRESS, 0);
            if (MemorySegment.NULL.equals(device)) {
                throw new IllegalStateException(
                        "ID3D11DeviceChild::GetDevice returned NULL");
            }
            try {
                MemorySegment ppContext = scratch.allocate(ValueLayout.ADDRESS);
                GET_IMMEDIATE_CONTEXT.invokeExact(
                        Ole32.vtable(device, 40), device, ppContext);
                MemorySegment context = ppContext.get(ValueLayout.ADDRESS, 0);
                if (MemorySegment.NULL.equals(context)) {
                    throw new IllegalStateException(
                            "ID3D11Device::GetImmediateContext returned NULL");
                }
                try {
                    MemorySegment sourceDesc =
                            scratch.allocate(D3D11_TEXTURE2D_DESC.layout());
                    GET_DESC.invokeExact(Ole32.vtable(source, 10), source, sourceDesc);
                    int format = D3D11_TEXTURE2D_DESC.Format(sourceDesc);
                    String pixelFormat = switch (format) {
                        case D3D11Video.DXGI_FORMAT_NV12 -> "nv12";
                        case D3D11Video.DXGI_FORMAT_P010 -> "p010";
                        default -> throw new IllegalStateException(
                                "unsupported D3D11 decoder texture format " + format);
                    };

                    MemorySegment desc =
                            scratch.allocate(D3D11_TEXTURE2D_DESC.layout());
                    D3D11_TEXTURE2D_DESC.Width(
                            desc, D3D11_TEXTURE2D_DESC.Width(sourceDesc));
                    D3D11_TEXTURE2D_DESC.Height(
                            desc, D3D11_TEXTURE2D_DESC.Height(sourceDesc));
                    D3D11_TEXTURE2D_DESC.MipLevels(desc, 1);
                    D3D11_TEXTURE2D_DESC.ArraySize(desc, 1);
                    D3D11_TEXTURE2D_DESC.Format(desc, format);
                    MemorySegment sampleDesc = D3D11_TEXTURE2D_DESC.SampleDesc(desc);
                    MemorySegment sourceSampleDesc =
                            D3D11_TEXTURE2D_DESC.SampleDesc(sourceDesc);
                    DXGI_SAMPLE_DESC.Count(
                            sampleDesc, DXGI_SAMPLE_DESC.Count(sourceSampleDesc));
                    DXGI_SAMPLE_DESC.Quality(
                            sampleDesc, DXGI_SAMPLE_DESC.Quality(sourceSampleDesc));
                    D3D11_TEXTURE2D_DESC.Usage(
                            desc, D3D11Video.D3D11_USAGE_DEFAULT);
                    D3D11_TEXTURE2D_DESC.BindFlags(desc,
                            D3D11Video.D3D11_BIND_DECODER
                                    | D3D11_BIND_SHADER_RESOURCE);
                    D3D11_TEXTURE2D_DESC.CPUAccessFlags(desc, 0);
                    D3D11_TEXTURE2D_DESC.MiscFlags(desc, 0);

                    MemorySegment ppTexture =
                            scratch.allocate(ValueLayout.ADDRESS);
                    int hr = D3D11Video.createTexture2D(
                            device, desc, ppTexture);
                    Ole32.check(hr, "CreateTexture2D (FFmpeg D3D11 surface)");
                    MemorySegment owned = ppTexture.get(ValueLayout.ADDRESS, 0);
                    try {
                        D3D11Video.copySubresourceRegion(
                                context, owned, 0, source, sourceSubresource);
                        return new D3d11TextureSurface(
                                provider, owned, visibleWidth, visibleHeight,
                                pixelFormat, adapterInfo);
                    } catch (RuntimeException failure) {
                        Ole32.release(owned);
                        throw failure;
                    }
                } finally {
                    Ole32.release(context);
                }
            } finally {
                Ole32.release(device);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new RuntimeException("copy D3D11 decoder surface failed", failure);
        }
    }

    @Override public String provider() { return provider; }
    @Override public String nativeType() { return "ID3D11Texture2D"; }
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public String pixelFormat() { return pixelFormat; }
    @Override public HardwareAdapterInfo adapterInfo() { return adapterInfo; }
    @Override public MemorySegment nativeHandle() {
        return closed.get() ? MemorySegment.NULL : texture;
    }
    @Override public boolean isClosed() { return closed.get(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Ole32.release(texture);
        }
    }
}
