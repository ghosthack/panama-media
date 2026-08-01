/**
 * Raw struct layouts for D3D11 video-decode (DXVA) types, kept close to
 * jextract's literal output rather than hand-transcribed.
 * <p>
 * Generated from the real Windows SDK headers ({@code d3d11.h}/{@code dxva.h},
 * SDK 10.0.26100.0) via {@code panama-media/jextract-verify/headers/d3d11video_wrapper.h}
 * — see that directory's README for how to regenerate after an SDK upgrade.
 * Unlike other panama-media modules (where jextract output is a reference
 * corpus only, cross-checked against hand-written bindings), these struct
 * accessor classes are large, mechanical, and offset-only — no behavior to
 * curate — so they are kept as generated. The one exception: the {@code IID_*}
 * accessors jextract emits (which resolve via runtime symbol lookup) are
 * dropped from this copy, because these GUIDs are data embedded by the
 * *linking* translation unit (traditionally {@code uuid.lib}), not an export
 * of {@code d3d11.dll} — {@link io.github.ghosthack.panama.media.d3d11video.D3D11Video}
 * hardcodes the real GUID byte values from the SDK header's
 * {@code DEFINE_GUID} instead.
 * <p>
 * The COM interface/vtable struct classes jextract also generates
 * ({@code ID3D11VideoDevice}, {@code ...Vtbl}, etc.) are intentionally not
 * copied here — {@link io.github.ghosthack.panama.media.d3d11video.D3D11Video}
 * dispatches through {@link io.github.ghosthack.panama.media.comruntime.Ole32#vtable}
 * with hardcoded slot indices (verified against this same jextract pass),
 * matching this project's existing COM-binding convention.
 */
package io.github.ghosthack.panama.media.d3d11video.jextract;
