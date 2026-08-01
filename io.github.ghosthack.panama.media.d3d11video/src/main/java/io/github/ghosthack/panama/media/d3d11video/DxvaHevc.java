package io.github.ghosthack.panama.media.d3d11video;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * The HEVC DXVA data structures ({@code DXVA_PicParams_HEVC},
 * {@code DXVA_PicEntry_HEVC}, {@code DXVA_Qmatrix_HEVC},
 * {@code DXVA_Slice_HEVC_Short}), hand-transcribed from Windows SDK
 * {@code dxva.h} (cross-checked against the mingw-w64 copy — the structs are
 * plain C and identical), following {@link DxvaAv1}'s pattern: layouts built
 * with named elements, every field offset derived by the FFM layout engine,
 * and the static initializer asserting the exact MSVC {@code sizeof} values
 * (232 / 1000 / 12 / 1) as a transcription check.
 *
 * <p>Bitfield unions are exposed as their underlying scalar
 * ({@code wFormatAndSequenceInfoFlags}, {@code dwCodingParamToolFlags},
 * {@code dwCodingSettingPicturePropertyFlags}, the {@code bPicEntry} byte) —
 * callers pack bits exactly as FFmpeg's {@code dxva2_hevc.c} does.</p>
 *
 * <p>Only the <b>short</b> slice-control format is transcribed
 * ({@code DXVA_Slice_HEVC_Short}): it is the only one FFmpeg's proven
 * {@code dxva2_hevc.c} path ever submits (config selection accepts
 * {@code ConfigBitstreamRaw == 1} for HEVC and fills short entries).</p>
 */
public final class DxvaHevc {

    private DxvaHevc() {}

    private static final ValueLayout.OfByte U8 = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT;

    // ── DXVA_PicEntry_HEVC (1 byte) ──────────────────────────────────────

    /**
     * {@code DXVA_PicEntry_HEVC}: {@code Index7Bits:7 | AssociatedFlag:1}
     * packed in one byte ({@code bPicEntry}). For {@code RefPicList} entries
     * the flag means "long-term reference"; {@code 0xFF} marks an unused
     * entry.
     */
    public static final class PicEntry {

        private PicEntry() {}

        public static final ValueLayout.OfByte LAYOUT = U8;

        /** Packs {@code Index7Bits | AssociatedFlag << 7}. */
        public static byte pack(int index7Bits, boolean associatedFlag) {
            return (byte) ((index7Bits & 0x7F) | (associatedFlag ? 0x80 : 0));
        }

        /** The "unused entry" sentinel ({@code bPicEntry = 0xFF}). */
        public static final byte UNUSED = (byte) 0xFF;
    }

    // ── DXVA_Slice_HEVC_Short (12 bytes) ─────────────────────────────────

    /**
     * {@code DXVA_Slice_HEVC_Short}: one slice's byte span within the
     * bitstream buffer (start code included), plus the chopping marker.
     */
    public static final class SliceShort {

        private SliceShort() {}

        public static final GroupLayout LAYOUT = MemoryLayout.structLayout(
                U32.withName("BSNALunitDataLocation"),
                U32.withName("SliceBytesInBuffer"),
                U16.withName("wBadSliceChopping"),
                MemoryLayout.paddingLayout(2)
        ).withName("DXVA_Slice_HEVC_Short");

        static {
            checkSize(LAYOUT, 12);
        }

        static final long DATA_LOCATION = LAYOUT.byteOffset(groupElement("BSNALunitDataLocation"));
        static final long BYTES_IN_BUFFER = LAYOUT.byteOffset(groupElement("SliceBytesInBuffer"));
        static final long BAD_CHOPPING = LAYOUT.byteOffset(groupElement("wBadSliceChopping"));

        public static long sizeof() {
            return LAYOUT.byteSize();
        }

        /** The slice entry at {@code index} within an array segment. */
        public static MemorySegment asSlice(MemorySegment array, long index) {
            return array.asSlice(LAYOUT.byteSize() * index, LAYOUT.byteSize());
        }

        public static void fill(MemorySegment s, int dataLocation, int bytesInBuffer) {
            s.set(U32, DATA_LOCATION, dataLocation);
            s.set(U32, BYTES_IN_BUFFER, bytesInBuffer);
            s.set(U16, BAD_CHOPPING, (short) 0);
        }

        /** Extends {@code SliceBytesInBuffer} (the last slice absorbs the 128-byte padding). */
        public static void addBytesInBuffer(MemorySegment s, int extra) {
            s.set(U32, BYTES_IN_BUFFER, s.get(U32, BYTES_IN_BUFFER) + extra);
        }
    }

    // ── DXVA_Qmatrix_HEVC (1000 bytes) ───────────────────────────────────

    /**
     * {@code DXVA_Qmatrix_HEVC}: the scaling lists in up-right-diagonal scan
     * order plus the size-2/3 DC coefficients, exactly the layout FFmpeg's
     * {@code fill_scaling_lists} writes.
     */
    public static final class Qmatrix {

        private Qmatrix() {}

        public static final GroupLayout LAYOUT = MemoryLayout.structLayout(
                MemoryLayout.sequenceLayout(6L * 16, U8).withName("ucScalingLists0"),
                MemoryLayout.sequenceLayout(6L * 64, U8).withName("ucScalingLists1"),
                MemoryLayout.sequenceLayout(6L * 64, U8).withName("ucScalingLists2"),
                MemoryLayout.sequenceLayout(2L * 64, U8).withName("ucScalingLists3"),
                MemoryLayout.sequenceLayout(6, U8).withName("ucScalingListDCCoefSizeID2"),
                MemoryLayout.sequenceLayout(2, U8).withName("ucScalingListDCCoefSizeID3")
        ).withName("DXVA_Qmatrix_HEVC");

        static {
            checkSize(LAYOUT, 1000);
        }

        public static final long LISTS_0 = off("ucScalingLists0");   // [6][16]
        public static final long LISTS_1 = off("ucScalingLists1");   // [6][64]
        public static final long LISTS_2 = off("ucScalingLists2");   // [6][64]
        public static final long LISTS_3 = off("ucScalingLists3");   // [2][64]
        public static final long DC_SIZE_ID_2 = off("ucScalingListDCCoefSizeID2");
        public static final long DC_SIZE_ID_3 = off("ucScalingListDCCoefSizeID3");

        private static long off(String name) {
            return LAYOUT.byteOffset(groupElement(name));
        }

        public static long sizeof() {
            return LAYOUT.byteSize();
        }
    }

    // ── DXVA_PicParams_HEVC (232 bytes) ──────────────────────────────────

    /**
     * {@code DXVA_PicParams_HEVC}. Field offsets are exposed as constants
     * relative to the whole struct, all derived from the layout. Array fields
     * ({@code column_width_minus1}, {@code RefPicList},
     * {@code PicOrderCntValList}, the three {@code RefPicSet*} index lists)
     * are addressed as base offset + element stride.
     */
    public static final class PicParams {

        private PicParams() {}

        public static final GroupLayout LAYOUT = MemoryLayout.structLayout(
                U16.withName("PicWidthInMinCbsY"),
                U16.withName("PicHeightInMinCbsY"),
                U16.withName("wFormatAndSequenceInfoFlags"),
                U8.withName("CurrPic"),
                U8.withName("sps_max_dec_pic_buffering_minus1"),
                U8.withName("log2_min_luma_coding_block_size_minus3"),
                U8.withName("log2_diff_max_min_luma_coding_block_size"),
                U8.withName("log2_min_transform_block_size_minus2"),
                U8.withName("log2_diff_max_min_transform_block_size"),
                U8.withName("max_transform_hierarchy_depth_inter"),
                U8.withName("max_transform_hierarchy_depth_intra"),
                U8.withName("num_short_term_ref_pic_sets"),
                U8.withName("num_long_term_ref_pics_sps"),
                U8.withName("num_ref_idx_l0_default_active_minus1"),
                U8.withName("num_ref_idx_l1_default_active_minus1"),
                U8.withName("init_qp_minus26"),
                U8.withName("ucNumDeltaPocsOfRefRpsIdx"),
                U16.withName("wNumBitsForShortTermRPSInSlice"),
                U16.withName("ReservedBits2"),
                U32.withName("dwCodingParamToolFlags"),
                U32.withName("dwCodingSettingPicturePropertyFlags"),
                U8.withName("pps_cb_qp_offset"),
                U8.withName("pps_cr_qp_offset"),
                U8.withName("num_tile_columns_minus1"),
                U8.withName("num_tile_rows_minus1"),
                MemoryLayout.sequenceLayout(19, U16).withName("column_width_minus1"),
                MemoryLayout.sequenceLayout(21, U16).withName("row_height_minus1"),
                U8.withName("diff_cu_qp_delta_depth"),
                U8.withName("pps_beta_offset_div2"),
                U8.withName("pps_tc_offset_div2"),
                U8.withName("log2_parallel_merge_level_minus2"),
                U32.withName("CurrPicOrderCntVal"),
                MemoryLayout.sequenceLayout(15, U8).withName("RefPicList"),
                U8.withName("ReservedBits5"),
                MemoryLayout.sequenceLayout(15, U32).withName("PicOrderCntValList"),
                MemoryLayout.sequenceLayout(8, U8).withName("RefPicSetStCurrBefore"),
                MemoryLayout.sequenceLayout(8, U8).withName("RefPicSetStCurrAfter"),
                MemoryLayout.sequenceLayout(8, U8).withName("RefPicSetLtCurr"),
                U16.withName("ReservedBits6"),
                U16.withName("ReservedBits7"),
                U32.withName("StatusReportFeedbackNumber")
        ).withName("DXVA_PicParams_HEVC");

        static {
            checkSize(LAYOUT, 232);
        }

        public static final long PIC_WIDTH_IN_MIN_CBS_Y = off("PicWidthInMinCbsY");
        public static final long PIC_HEIGHT_IN_MIN_CBS_Y = off("PicHeightInMinCbsY");
        public static final long FORMAT_AND_SEQUENCE_INFO_FLAGS = off("wFormatAndSequenceInfoFlags");
        public static final long CURR_PIC = off("CurrPic");
        public static final long SPS_MAX_DEC_PIC_BUFFERING_MINUS1 = off("sps_max_dec_pic_buffering_minus1");
        public static final long LOG2_MIN_LUMA_CODING_BLOCK_SIZE_MINUS3 = off("log2_min_luma_coding_block_size_minus3");
        public static final long LOG2_DIFF_MAX_MIN_LUMA_CODING_BLOCK_SIZE = off("log2_diff_max_min_luma_coding_block_size");
        public static final long LOG2_MIN_TRANSFORM_BLOCK_SIZE_MINUS2 = off("log2_min_transform_block_size_minus2");
        public static final long LOG2_DIFF_MAX_MIN_TRANSFORM_BLOCK_SIZE = off("log2_diff_max_min_transform_block_size");
        public static final long MAX_TRANSFORM_HIERARCHY_DEPTH_INTER = off("max_transform_hierarchy_depth_inter");
        public static final long MAX_TRANSFORM_HIERARCHY_DEPTH_INTRA = off("max_transform_hierarchy_depth_intra");
        public static final long NUM_SHORT_TERM_REF_PIC_SETS = off("num_short_term_ref_pic_sets");
        public static final long NUM_LONG_TERM_REF_PICS_SPS = off("num_long_term_ref_pics_sps");
        public static final long NUM_REF_IDX_L0_DEFAULT_ACTIVE_MINUS1 = off("num_ref_idx_l0_default_active_minus1");
        public static final long NUM_REF_IDX_L1_DEFAULT_ACTIVE_MINUS1 = off("num_ref_idx_l1_default_active_minus1");
        public static final long INIT_QP_MINUS26 = off("init_qp_minus26");
        public static final long UC_NUM_DELTA_POCS_OF_REF_RPS_IDX = off("ucNumDeltaPocsOfRefRpsIdx");
        public static final long W_NUM_BITS_FOR_SHORT_TERM_RPS_IN_SLICE = off("wNumBitsForShortTermRPSInSlice");
        public static final long CODING_PARAM_TOOL_FLAGS = off("dwCodingParamToolFlags");
        public static final long CODING_SETTING_PICTURE_PROPERTY_FLAGS = off("dwCodingSettingPicturePropertyFlags");
        public static final long PPS_CB_QP_OFFSET = off("pps_cb_qp_offset");
        public static final long PPS_CR_QP_OFFSET = off("pps_cr_qp_offset");
        public static final long NUM_TILE_COLUMNS_MINUS1 = off("num_tile_columns_minus1");
        public static final long NUM_TILE_ROWS_MINUS1 = off("num_tile_rows_minus1");
        public static final long COLUMN_WIDTH_MINUS1 = off("column_width_minus1"); // 19 × u16
        public static final long ROW_HEIGHT_MINUS1 = off("row_height_minus1");     // 21 × u16
        public static final long DIFF_CU_QP_DELTA_DEPTH = off("diff_cu_qp_delta_depth");
        public static final long PPS_BETA_OFFSET_DIV2 = off("pps_beta_offset_div2");
        public static final long PPS_TC_OFFSET_DIV2 = off("pps_tc_offset_div2");
        public static final long LOG2_PARALLEL_MERGE_LEVEL_MINUS2 = off("log2_parallel_merge_level_minus2");
        public static final long CURR_PIC_ORDER_CNT_VAL = off("CurrPicOrderCntVal");
        public static final long REF_PIC_LIST = off("RefPicList");                 // 15 × PicEntry byte
        public static final long PIC_ORDER_CNT_VAL_LIST = off("PicOrderCntValList"); // 15 × i32
        public static final long REF_PIC_SET_ST_CURR_BEFORE = off("RefPicSetStCurrBefore"); // 8 × u8
        public static final long REF_PIC_SET_ST_CURR_AFTER = off("RefPicSetStCurrAfter");   // 8 × u8
        public static final long REF_PIC_SET_LT_CURR = off("RefPicSetLtCurr");              // 8 × u8
        public static final long STATUS_REPORT_FEEDBACK_NUMBER = off("StatusReportFeedbackNumber");

        private static long off(String name) {
            return LAYOUT.byteOffset(groupElement(name));
        }

        public static long sizeof() {
            return LAYOUT.byteSize();
        }
    }

    /** Transcription check against MSVC {@code sizeof} (dxva.h). */
    private static void checkSize(GroupLayout layout, long expected) {
        if (layout.byteSize() != expected) {
            throw new ExceptionInInitializerError("DXVA HEVC layout transcription drifted: "
                    + layout.name().orElse("?") + "=" + layout.byteSize()
                    + " (want " + expected + ")");
        }
    }
}
