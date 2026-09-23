package app.signal.isolate.model

/** A stem the app can write out. */
enum class Stem(val id: String, val label: String) {
    VOCALS("vocals", "Vocals"),
    INSTRUMENTAL("instrumental", "Instrumental"),
    DRUMS("drums", "Drums"),
    BASS("bass", "Bass"),
    OTHER("other", "Other"),
}

enum class EngineKind { ROFORMER, DEMUCS, SCNET }

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
    /**
     * Measured peak resident memory over consecutive chunks, in MiB: the kernel's
     * VmHWM through the Java API the app uses, above the process's own baseline,
     * optimiser off. The headroom check in [DeviceCapability] refuses to start without
     * it free.
     */
    val peakMemoryMb: Long,
    /**
     * The same peak with ONNX Runtime's graph optimiser on, for a model where that was
     * measured to pay off; `null` where it does not. The optimiser is used only when
     * this much is free — otherwise the model runs lean at [peakMemoryMb].
     *
     * Chosen per model because the same switch helps one and cripples another: it makes
     * the RoFormer 31 % faster for 1.2 GB, while on HT-Demucs its constant folding peaks
     * at 6.3 GB for 8 %, and on SCNet it buys nothing for 2.4× the memory.
     */
    val optimizedPeakMemoryMb: Long? = null,
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

    /** Steady state at RoformerEngine.FRAMES (5.5 s chunks): 1.76 GB over consecutive chunks. */
    private const val ROFORMER_PEAK_MB = 1_850L

    /** The same with the graph optimiser on: 2.99 GB, flat from chunk to chunk. */
    private const val ROFORMER_OPTIMIZED_PEAK_MB = 3_000L

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
        quality = "Best vocals and instrumental. Clean sibilance, very little bleed.",
        speedHint = "Slowest: about 7× longer than SCNet.",
        minRamGb = 6,
        peakMemoryMb = ROFORMER_PEAK_MB,
        // 31 % faster for 1.2 GB, when the phone has it to spare.
        optimizedPeakMemoryMb = ROFORMER_OPTIMIZED_PEAK_MB,
        license = "MIT",
        source = "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx",
    )

    /**
     * SCNet Small, exported from starrytong's MUSDB18 checkpoint by
     * tools/onnx/export_scnet.py and served from this repository (models/), because no
     * working export is hosted anywhere else.
     *
     * On the MUSDB18 test excerpts it beats HT-Demucs on vocals (9.75 vs 8.78 dB) and
     * "other", loses on bass (8.04 vs 9.03) and roughly ties on drums — at 2.3× the speed
     * and half the memory. It replaced the fine-tuned Demucs vocals model, which measured
     * within 0.3 dB of plain HT-Demucs and so added nothing.
     */
    val SCNET_SMALL = ModelSpec(
        id = "scnet_small",
        displayName = "SCNet",
        subtitle = "Fast · vocals, instrumental or four stems",
        engine = EngineKind.SCNET,
        entryFile = "scnet_small.onnx",
        files = listOf(
            ModelFile(
                name = "scnet_small.onnx",
                // Pinned to the commit that added it; the hash is checked either way.
                url = "https://raw.githubusercontent.com/MuMaFi/signal-mastering/" +
                    "9a228e6cdb4e773fb2a10a3d00a48abbc64a0494/models/scnet_small.onnx",
                bytes = 48_177_654L,
                sha256 = "2f055ae5bb5e2bb3a38adba56e11c8c8723563a2c0a2b6db314dec1eb038c995",
            ),
        ),
        stems = listOf(Stem.VOCALS, Stem.INSTRUMENTAL, Stem.DRUMS, Stem.BASS, Stem.OTHER),
        quality = "Better vocals than HT-Demucs in a fraction of the time. Also splits drums, bass and other.",
        speedHint = "Fastest: about 7× quicker than RoFormer.",
        minRamGb = 3,
        peakMemoryMb = 650,
        license = "MIT",
        source = "https://github.com/starrytong/SCNet",
    )

    /** Standard HT-Demucs: one session, all four stems. */
    val DEMUCS_4STEM = ModelSpec(
        id = "htdemucs_4stem",
        displayName = "HT-Demucs (4 stems)",
        subtitle = "Four stems · strongest on bass and drums",
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
        quality = "The best bass and drums of the three, when you want the whole band split.",
        speedHint = "About twice as long as SCNet.",
        minRamGb = 4,
        peakMemoryMb = 1_150,
        license = "MIT",
        source = "https://huggingface.co/StemSplitio/htdemucs-onnx",
    )

    val all = listOf(ROFORMER, SCNET_SMALL, DEMUCS_4STEM)

    fun byId(id: String): ModelSpec = all.firstOrNull { it.id == id } ?: ROFORMER
}
