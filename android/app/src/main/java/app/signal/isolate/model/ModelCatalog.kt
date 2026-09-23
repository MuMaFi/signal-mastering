package app.signal.isolate.model

/** A stem the app can write out. */
enum class Stem(val id: String, val label: String) {
    VOCALS("vocals", "Vocals"),
    INSTRUMENTAL("instrumental", "Instrumental"),
    DRUMS("drums", "Drums"),
    BASS("bass", "Bass"),
    OTHER("other", "Other"),
}

enum class EngineKind { ROFORMER, DEMUCS }

data class ModelFile(
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

data class ModelSpec(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val engine: EngineKind,
    /** The file ONNX Runtime opens; any others must sit beside it. */
    val entryFile: String,
    val files: List<ModelFile>,
    /**
     * An APK asset copied in beside the downloaded weights, used when the graph the app
     * runs is not the one published upstream. Weights are always downloaded.
     */
    val bundledGraphAsset: String? = null,
    val stems: List<Stem>,
    val quality: String,
    val speedHint: String,
    val minRamGb: Int,
    val license: String,
    val source: String,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

/**
 * The models the app can run on-device.
 *
 * Everything here is an ONNX export that ONNX Runtime opens directly — no PyTorch, no
 * server round-trip. Picking the top of the published SDR tables is not enough on a
 * phone: the winning checkpoints on mvsep.com's multisong leaderboard are either
 * service-only or ship as PyTorch `.ckpt` files with no mobile-runnable export, so the
 * catalogue is the best *openly exported* separators, ordered by measured quality.
 */
object ModelCatalog {

    /**
     * Mel-Band RoFormer, SYHFT / "Kim Vocal" lineage — the strongest open vocal
     * separator with a usable ONNX export. The graph takes a precomputed STFT and
     * returns a complex mask; the app owns the transform (see `RoformerEngine`).
     */
    val ROFORMER = ModelSpec(
        id = "melband_roformer_syhft",
        displayName = "Mel-Band RoFormer",
        subtitle = "Maximum quality · SYHFT / Kim Vocal lineage",
        engine = EngineKind.ROFORMER,
        // The graph ships in the APK; only the 741 MB weight blob is downloaded.
        entryFile = "roformer_core_dyn_time.onnx",
        bundledGraphAsset = "roformer_core_dyn_time.onnx",
        files = listOf(
            ModelFile(
                name = "syhft_core_folded_fp16_webgpu.onnx.data",
                url = "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/main/syhft_core_folded_fp16_webgpu.onnx.data",
                bytes = 741_190_540L,
                sha256 = "b08cfc80905e3560a4dd5d30f641299a47dd96d309ebbe9524d9d6c9d2a0356f",
            ),
        ),
        stems = listOf(Stem.VOCALS, Stem.INSTRUMENTAL),
        quality = "Best separation available offline. Clean sibilance, very little instrumental bleed.",
        // RTF 2.1 through ONNX Runtime's Java API, the closest proxy here for Android.
        speedHint = "~2x real time — a 4-minute song takes about 8 minutes.",
        minRamGb = 8,
        license = "MIT",
        source = "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx",
    )

    /**
     * The vocals specialist out of the HT-Demucs fine-tuned bag (median vocals SDR
     * 9.19 dB on MUSDB18-HQ). A quarter of the download, a fraction of the RAM.
     */
    val DEMUCS_FT_VOCALS = ModelSpec(
        id = "htdemucs_ft_vocals",
        displayName = "HT-Demucs FT (vocals)",
        subtitle = "Faster · fine-tuned vocals specialist",
        engine = EngineKind.DEMUCS,
        entryFile = "htdemucs_ft_vocals_fp16weights.onnx",
        files = listOf(
            ModelFile(
                name = "htdemucs_ft_vocals_fp16weights.onnx",
                url = "https://huggingface.co/StemSplitio/htdemucs-ft-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx",
                bytes = 165_612_636L,
                sha256 = "0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce",
            ),
        ),
        stems = listOf(Stem.VOCALS, Stem.INSTRUMENTAL),
        quality = "Very good vocal isolation; slightly more instrumental bleed than RoFormer.",
        speedHint = "~0.5x real time — a 4-minute song takes about 2 minutes.",
        // Measured peak RSS is in the same range as the RoFormer's. What this model
        // actually saves is time and download size, not memory.
        minRamGb = 8,
        license = "MIT",
        source = "https://huggingface.co/StemSplitio/htdemucs-ft-onnx",
    )

    /** Standard HT-Demucs: one session, all four stems. */
    val DEMUCS_4STEM = ModelSpec(
        id = "htdemucs_4stem",
        displayName = "HT-Demucs (4 stems)",
        subtitle = "Drums · bass · other · vocals",
        engine = EngineKind.DEMUCS,
        entryFile = "htdemucs_fp16weights.onnx",
        files = listOf(
            ModelFile(
                name = "htdemucs_fp16weights.onnx",
                url = "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx",
                bytes = 165_612_636L,
                sha256 = "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a",
            ),
        ),
        stems = listOf(Stem.VOCALS, Stem.INSTRUMENTAL, Stem.DRUMS, Stem.BASS, Stem.OTHER),
        quality = "Full band split when you want more than vocals and backing track.",
        speedHint = "~0.5x real time — a 4-minute song takes about 2 minutes.",
        minRamGb = 8,
        license = "MIT",
        source = "https://huggingface.co/StemSplitio/htdemucs-onnx",
    )

    val all = listOf(ROFORMER, DEMUCS_FT_VOCALS, DEMUCS_4STEM)

    fun byId(id: String): ModelSpec = all.firstOrNull { it.id == id } ?: ROFORMER
}
