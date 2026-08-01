package io.github.ghosthack.panama.media.d3d11video;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * The AV1 DXVA data structures ({@code DXVA_PicParams_AV1},
 * {@code DXVA_PicEntry_AV1}, {@code DXVA_Tile_AV1}), hand-transcribed from
 * Windows SDK 10.0.26100 {@code dxva.h} (also cross-checked against the
 * mingw-w64 copy — the structs are plain C and identical).
 *
 * <p>Unlike the H.264 structs these are not jextract output — jextract can
 * only run against the Windows SDK headers on a Windows box, and the AV1
 * structs postdate the checked-in generation pass. The layouts here are
 * built with named elements and <b>every</b> field offset is derived by the
 * FFM layout engine (no hand arithmetic); the static initializer asserts the
 * exact MSVC {@code sizeof} values (912 / 36 / 16) as a transcription
 * check.</p>
 *
 * <p>Bitfield unions are exposed as their underlying scalar
 * ({@code CodingParamToolFlags}, {@code FormatAndPictureInfoFlags}, the
 * per-substruct {@code ControlFlags}, the CDEF {@code combined} strengths) —
 * callers pack bits exactly as FFmpeg's {@code dxva2_av1.c} does.</p>
 */
public final class DxvaAv1 {

    private DxvaAv1() {}

    private static final ValueLayout.OfByte U8 = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT;

    // ── DXVA_PicEntry_AV1 (36 bytes) ─────────────────────────────────────

    /**
     * {@code DXVA_PicEntry_AV1}: one reference-frame entry of
     * {@code frame_refs[7]} — dimensions, global-motion params and the
     * texture-array index.
     */
    public static final class PicEntry {

        private PicEntry() {}

        public static final GroupLayout LAYOUT = MemoryLayout.structLayout(
                U32.withName("width"),
                U32.withName("height"),
                MemoryLayout.sequenceLayout(6, U32).withName("wmmat"),
                U8.withName("GlobalMotionFlags"), // wminvalid:1 wmtype:2
                U8.withName("Index"),
                U16.withName("Reserved16Bits")
        ).withName("DXVA_PicEntry_AV1");

        static {
            checkSize(LAYOUT, 36);
        }

        static final long WIDTH = LAYOUT.byteOffset(groupElement("width"));
        static final long HEIGHT = LAYOUT.byteOffset(groupElement("height"));
        static final long WMMAT = LAYOUT.byteOffset(groupElement("wmmat"));
        static final long GM_FLAGS = LAYOUT.byteOffset(groupElement("GlobalMotionFlags"));
        static final long INDEX = LAYOUT.byteOffset(groupElement("Index"));

        public static long sizeof() {
            return LAYOUT.byteSize();
        }

        public static void width(MemorySegment e, int v) {
            e.set(U32, WIDTH, v);
        }

        public static void height(MemorySegment e, int v) {
            e.set(U32, HEIGHT, v);
        }

        public static void wmmat(MemorySegment e, int i, int v) {
            e.set(U32, WMMAT + 4L * i, v);
        }

        /** Packs {@code wminvalid:1 | wmtype:2}. */
        public static void globalMotionFlags(MemorySegment e, boolean wminvalid, int wmtype) {
            e.set(U8, GM_FLAGS, (byte) ((wminvalid ? 1 : 0) | ((wmtype & 0x3) << 1)));
        }

        public static void index(MemorySegment e, int v) {
            e.set(U8, INDEX, (byte) v);
        }
    }

    // ── DXVA_Tile_AV1 (16 bytes) ─────────────────────────────────────────

    /** {@code DXVA_Tile_AV1}: one tile's byte span within the bitstream buffer. */
    public static final class Tile {

        private Tile() {}

        public static final GroupLayout LAYOUT = MemoryLayout.structLayout(
                U32.withName("DataOffset"),
                U32.withName("DataSize"),
                U16.withName("row"),
                U16.withName("column"),
                U16.withName("Reserved16Bits"),
                U8.withName("anchor_frame"),
                U8.withName("Reserved8Bits")
        ).withName("DXVA_Tile_AV1");

        static {
            checkSize(LAYOUT, 16);
        }

        static final long DATA_OFFSET = LAYOUT.byteOffset(groupElement("DataOffset"));
        static final long DATA_SIZE = LAYOUT.byteOffset(groupElement("DataSize"));
        static final long ROW = LAYOUT.byteOffset(groupElement("row"));
        static final long COLUMN = LAYOUT.byteOffset(groupElement("column"));
        static final long ANCHOR_FRAME = LAYOUT.byteOffset(groupElement("anchor_frame"));

        public static long sizeof() {
            return LAYOUT.byteSize();
        }

        /** The tile entry at {@code index} within an array segment. */
        public static MemorySegment asSlice(MemorySegment array, long index) {
            return array.asSlice(LAYOUT.byteSize() * index, LAYOUT.byteSize());
        }

        public static void fill(MemorySegment t, int dataOffset, int dataSize,
                                int row, int column) {
            t.set(U32, DATA_OFFSET, dataOffset);
            t.set(U32, DATA_SIZE, dataSize);
            t.set(U16, ROW, (short) row);
            t.set(U16, COLUMN, (short) column);
            t.set(U8, ANCHOR_FRAME, (byte) 0xFF); // no large-scale-tile anchor
        }
    }

    // ── DXVA_PicParams_AV1 (912 bytes) ───────────────────────────────────

    /**
     * {@code DXVA_PicParams_AV1}. Substruct offsets are exposed as
     * {@code *_BASE} constants plus field offsets relative to the whole
     * struct, all derived from the layout.
     */
    public static final class PicParams {

        private PicParams() {}

        public static final GroupLayout LAYOUT = MemoryLayout.structLayout(
                U32.withName("width"),
                U32.withName("height"),
                U32.withName("max_width"),
                U32.withName("max_height"),
                U8.withName("CurrPicTextureIndex"),
                U8.withName("superres_denom"),
                U8.withName("bitdepth"),
                U8.withName("seq_profile"),
                MemoryLayout.structLayout(
                        U8.withName("cols"),
                        U8.withName("rows"),
                        U16.withName("context_update_id"),
                        MemoryLayout.sequenceLayout(64, U16).withName("widths"),
                        MemoryLayout.sequenceLayout(64, U16).withName("heights")
                ).withName("tiles"),
                U32.withName("CodingParamToolFlags"),
                U8.withName("FormatAndPictureInfoFlags"),
                U8.withName("primary_ref_frame"),
                U8.withName("order_hint"),
                U8.withName("order_hint_bits"),
                MemoryLayout.sequenceLayout(7, PicEntry.LAYOUT).withName("frame_refs"),
                MemoryLayout.sequenceLayout(8, U8).withName("RefFrameMapTextureIndex"),
                MemoryLayout.structLayout(
                        MemoryLayout.sequenceLayout(2, U8).withName("filter_level"),
                        U8.withName("filter_level_u"),
                        U8.withName("filter_level_v"),
                        U8.withName("sharpness_level"),
                        U8.withName("ControlFlags"),
                        MemoryLayout.sequenceLayout(8, U8).withName("ref_deltas"),
                        MemoryLayout.sequenceLayout(2, U8).withName("mode_deltas"),
                        U8.withName("delta_lf_res"),
                        MemoryLayout.sequenceLayout(3, U8).withName("frame_restoration_type"),
                        MemoryLayout.sequenceLayout(3, U16).withName("log2_restoration_unit_size"),
                        U16.withName("Reserved16Bits")
                ).withName("loop_filter"),
                MemoryLayout.structLayout(
                        U8.withName("ControlFlags"),
                        U8.withName("base_qindex"),
                        U8.withName("y_dc_delta_q"),
                        U8.withName("u_dc_delta_q"),
                        U8.withName("v_dc_delta_q"),
                        U8.withName("u_ac_delta_q"),
                        U8.withName("v_ac_delta_q"),
                        U8.withName("qm_y"),
                        U8.withName("qm_u"),
                        U8.withName("qm_v"),
                        U16.withName("Reserved16Bits")
                ).withName("quantization"),
                MemoryLayout.structLayout(
                        U8.withName("ControlFlags"),
                        MemoryLayout.sequenceLayout(8, U8).withName("y_strengths"),
                        MemoryLayout.sequenceLayout(8, U8).withName("uv_strengths")
                ).withName("cdef"),
                U8.withName("interp_filter"),
                MemoryLayout.structLayout(
                        U8.withName("ControlFlags"),
                        MemoryLayout.sequenceLayout(3, U8).withName("Reserved24Bits"),
                        MemoryLayout.sequenceLayout(8, U8).withName("feature_mask"),
                        MemoryLayout.sequenceLayout(64, U16).withName("feature_data")
                ).withName("segmentation"),
                MemoryLayout.structLayout(
                        U16.withName("ControlFlags"),
                        U16.withName("grain_seed"),
                        MemoryLayout.sequenceLayout(28, U8).withName("scaling_points_y"),
                        U8.withName("num_y_points"),
                        MemoryLayout.sequenceLayout(20, U8).withName("scaling_points_cb"),
                        U8.withName("num_cb_points"),
                        MemoryLayout.sequenceLayout(20, U8).withName("scaling_points_cr"),
                        U8.withName("num_cr_points"),
                        MemoryLayout.sequenceLayout(24, U8).withName("ar_coeffs_y"),
                        MemoryLayout.sequenceLayout(25, U8).withName("ar_coeffs_cb"),
                        MemoryLayout.sequenceLayout(25, U8).withName("ar_coeffs_cr"),
                        U8.withName("cb_mult"),
                        U8.withName("cb_luma_mult"),
                        U8.withName("cr_mult"),
                        U8.withName("cr_luma_mult"),
                        U8.withName("Reserved8Bits"),
                        U16.withName("cb_offset"),
                        U16.withName("cr_offset")
                ).withName("film_grain"),
                U32.withName("Reserved32Bits"),
                U32.withName("StatusReportFeedbackNumber")
        ).withName("DXVA_PicParams_AV1");

        static {
            checkSize(LAYOUT, 912);
        }

        public static final long WIDTH = off("width");
        public static final long HEIGHT = off("height");
        public static final long MAX_WIDTH = off("max_width");
        public static final long MAX_HEIGHT = off("max_height");
        public static final long CURR_PIC_TEXTURE_INDEX = off("CurrPicTextureIndex");
        public static final long SUPERRES_DENOM = off("superres_denom");
        public static final long BITDEPTH = off("bitdepth");
        public static final long SEQ_PROFILE = off("seq_profile");
        public static final long TILES_COLS = off("tiles", "cols");
        public static final long TILES_ROWS = off("tiles", "rows");
        public static final long TILES_CONTEXT_UPDATE_ID = off("tiles", "context_update_id");
        public static final long TILES_WIDTHS = off("tiles", "widths");
        public static final long TILES_HEIGHTS = off("tiles", "heights");
        public static final long CODING_FLAGS = off("CodingParamToolFlags");
        public static final long FORMAT_FLAGS = off("FormatAndPictureInfoFlags");
        public static final long PRIMARY_REF_FRAME = off("primary_ref_frame");
        public static final long ORDER_HINT = off("order_hint");
        public static final long ORDER_HINT_BITS = off("order_hint_bits");
        public static final long FRAME_REFS = off("frame_refs");
        public static final long REF_FRAME_MAP_TEXTURE_INDEX = off("RefFrameMapTextureIndex");
        public static final long LF_FILTER_LEVEL = off("loop_filter", "filter_level");
        public static final long LF_FILTER_LEVEL_U = off("loop_filter", "filter_level_u");
        public static final long LF_FILTER_LEVEL_V = off("loop_filter", "filter_level_v");
        public static final long LF_SHARPNESS = off("loop_filter", "sharpness_level");
        public static final long LF_CONTROL_FLAGS = off("loop_filter", "ControlFlags");
        public static final long LF_REF_DELTAS = off("loop_filter", "ref_deltas");
        public static final long LF_MODE_DELTAS = off("loop_filter", "mode_deltas");
        public static final long LF_DELTA_LF_RES = off("loop_filter", "delta_lf_res");
        public static final long LF_RESTORATION_TYPE = off("loop_filter", "frame_restoration_type");
        public static final long LF_LOG2_RESTORATION_UNIT_SIZE = off("loop_filter", "log2_restoration_unit_size");
        public static final long Q_CONTROL_FLAGS = off("quantization", "ControlFlags");
        public static final long Q_BASE_QINDEX = off("quantization", "base_qindex");
        public static final long Q_Y_DC_DELTA = off("quantization", "y_dc_delta_q");
        public static final long Q_U_DC_DELTA = off("quantization", "u_dc_delta_q");
        public static final long Q_V_DC_DELTA = off("quantization", "v_dc_delta_q");
        public static final long Q_U_AC_DELTA = off("quantization", "u_ac_delta_q");
        public static final long Q_V_AC_DELTA = off("quantization", "v_ac_delta_q");
        public static final long Q_QM_Y = off("quantization", "qm_y");
        public static final long Q_QM_U = off("quantization", "qm_u");
        public static final long Q_QM_V = off("quantization", "qm_v");
        public static final long CDEF_CONTROL_FLAGS = off("cdef", "ControlFlags");
        public static final long CDEF_Y_STRENGTHS = off("cdef", "y_strengths");
        public static final long CDEF_UV_STRENGTHS = off("cdef", "uv_strengths");
        public static final long INTERP_FILTER = off("interp_filter");
        public static final long SEG_CONTROL_FLAGS = off("segmentation", "ControlFlags");
        public static final long SEG_FEATURE_MASK = off("segmentation", "feature_mask");
        public static final long SEG_FEATURE_DATA = off("segmentation", "feature_data");
        public static final long FG_CONTROL_FLAGS = off("film_grain", "ControlFlags");
        public static final long FG_GRAIN_SEED = off("film_grain", "grain_seed");
        public static final long FG_SCALING_POINTS_Y = off("film_grain", "scaling_points_y");
        public static final long FG_NUM_Y_POINTS = off("film_grain", "num_y_points");
        public static final long FG_SCALING_POINTS_CB = off("film_grain", "scaling_points_cb");
        public static final long FG_NUM_CB_POINTS = off("film_grain", "num_cb_points");
        public static final long FG_SCALING_POINTS_CR = off("film_grain", "scaling_points_cr");
        public static final long FG_NUM_CR_POINTS = off("film_grain", "num_cr_points");
        public static final long FG_AR_COEFFS_Y = off("film_grain", "ar_coeffs_y");
        public static final long FG_AR_COEFFS_CB = off("film_grain", "ar_coeffs_cb");
        public static final long FG_AR_COEFFS_CR = off("film_grain", "ar_coeffs_cr");
        public static final long FG_CB_MULT = off("film_grain", "cb_mult");
        public static final long FG_CB_LUMA_MULT = off("film_grain", "cb_luma_mult");
        public static final long FG_CR_MULT = off("film_grain", "cr_mult");
        public static final long FG_CR_LUMA_MULT = off("film_grain", "cr_luma_mult");
        public static final long FG_CB_OFFSET = off("film_grain", "cb_offset");
        public static final long FG_CR_OFFSET = off("film_grain", "cr_offset");
        public static final long STATUS_REPORT_FEEDBACK_NUMBER = off("StatusReportFeedbackNumber");

        private static long off(String... path) {
            MemoryLayout.PathElement[] p = new MemoryLayout.PathElement[path.length];
            for (int i = 0; i < path.length; i++) {
                p[i] = groupElement(path[i]);
            }
            return LAYOUT.byteOffset(p);
        }

        public static long sizeof() {
            return LAYOUT.byteSize();
        }

        /** The {@code frame_refs[i]} entry as its own segment. */
        public static MemorySegment frameRef(MemorySegment pp, int i) {
            return pp.asSlice(FRAME_REFS + i * PicEntry.LAYOUT.byteSize(),
                    PicEntry.LAYOUT.byteSize());
        }
    }

    /** Transcription check against MSVC {@code sizeof} (dxva.h, SDK 10.0.26100). */
    private static void checkSize(GroupLayout layout, long expected) {
        if (layout.byteSize() != expected) {
            throw new ExceptionInInitializerError("DXVA AV1 layout transcription drifted: "
                    + layout.name().orElse("?") + "=" + layout.byteSize()
                    + " (want " + expected + ")");
        }
    }
}
