package jp.hisanet.charisafe.data.camera

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import jp.hisanet.charisafe.data.debug.ApproachDebugLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

data class ApproachCalculationResult(
    val rawScore: Float,
    val areaExpansion: Int,
    val effectiveExpansion: Int,
    val emaScore: Float,
    val approachScore: Float,
    val activeBlockRatio: Float,
    val maxClusterArea: Int,
    val clusterDensity: Float,
    val clusterAspect: Float,
    val wOccupancy: Float,
    val wDensity: Float,
    val wAspect: Float,
    val globalAvg: Float
)

fun computeApproachScore(
    data: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    gridN: Int,
    previousMaxClusterArea: Int,
    previousEmaScore: Float
): ApproachCalculationResult {
    val blockW = (width / gridN).coerceAtLeast(1)
    val blockH = (height / gridN).coerceAtLeast(1)

    val blockSums = Array(gridN) { FloatArray(gridN) }

    var totalSum = 0.0
    var totalSampled = 0

    val step = 4
    for (r in 0 until gridN) {
        val startY = r * blockH
        val endY = ((r + 1) * blockH).coerceAtMost(height)
        for (c in 0 until gridN) {
            val startX = c * blockW
            val endX = ((c + 1) * blockW).coerceAtMost(width)

            var sum = 0L
            var count = 0
            for (y in startY until endY step step) {
                val rowOffset = y * rowStride
                for (x in startX until endX step step) {
                    if (rowOffset + x < data.size) {
                        val pixel = data[rowOffset + x].toInt() and 0xFF
                        sum += pixel
                        count++
                    }
                }
            }
            val avg = if (count > 0) sum.toFloat() / count else 0f
            blockSums[r][c] = avg
            totalSum += sum
            totalSampled += count
        }
    }

    val globalAvg = if (totalSampled > 0) (totalSum / totalSampled).toFloat() else 0f

    val activeBlocks = Array(gridN) { BooleanArray(gridN) }
    val relativeLuminance = Array(gridN) { FloatArray(gridN) }
    var activeBlockCount = 0

    for (r in 0 until gridN) {
        for (c in 0 until gridN) {
            val rel = (blockSums[r][c] - globalAvg).coerceAtLeast(0f)
            relativeLuminance[r][c] = rel
            if (rel >= 35.0f) {
                activeBlocks[r][c] = true
                activeBlockCount++
            }
        }
    }

    val totalGridCount = (gridN * gridN).toFloat()
    val activeBlockRatio = activeBlockCount / totalGridCount

    // 全有効ブロック占有率ペナルティ (W_occupancy)
    val wOccupancy = if (activeBlockRatio <= 0.20f) {
        1.0f
    } else {
        (1.0f - (activeBlockRatio - 0.20f) / 0.30f).coerceAtLeast(0.1f)
    }

    val visited = Array(gridN) { BooleanArray(gridN) }
    var maxClusterArea = 0
    var maxClusterAvgRelLum = 0f
    var bestClusterDensity = 1.0f
    var bestClusterAspect = 1.0f
    var bestWDensity = 1.0f
    var bestWAspect = 1.0f

    val dr = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    val dc = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)

    for (r in 0 until gridN) {
        for (c in 0 until gridN) {
            if (activeBlocks[r][c] && !visited[r][c]) {
                var clusterArea = 0
                var clusterRelLumSum = 0f

                var minR = r
                var maxR = r
                var minC = c
                var maxC = c

                val queueR = IntArray(gridN * gridN)
                val queueC = IntArray(gridN * gridN)
                var head = 0
                var tail = 0

                queueR[tail] = r
                queueC[tail] = c
                tail++
                visited[r][c] = true

                while (head < tail) {
                    val currR = queueR[head]
                    val currC = queueC[head]
                    head++

                    clusterArea++
                    clusterRelLumSum += relativeLuminance[currR][currC]

                    if (currR < minR) minR = currR
                    if (currR > maxR) maxR = currR
                    if (currC < minC) minC = currC
                    if (currC > maxC) maxC = currC

                    for (d in 0 until 8) {
                        val nr = currR + dr[d]
                        val nc = currC + dc[d]
                        if (nr in 0 until gridN && nc in 0 until gridN) {
                            if (activeBlocks[nr][nc] && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                queueR[tail] = nr
                                queueC[tail] = nc
                                tail++
                            }
                        }
                    }
                }

                val bboxW = (maxC - minC + 1).toFloat()
                val bboxH = (maxR - minR + 1).toFloat()
                val bboxArea = bboxW * bboxH

                // クラスタ密度 (D = clusterArea / bboxArea)
                val density = if (bboxArea > 0f) clusterArea.toFloat() / bboxArea else 1.0f
                val wDensity = (density / 0.50f).coerceAtMost(1.0f)

                // アスペクト比 (Aspect = max(W, H) / min(W, H))
                val aspect = maxOf(bboxW, bboxH) / minOf(bboxW, bboxH)
                val wAspect = if (aspect <= 2.0f) {
                    1.0f
                } else {
                    (2.0f / aspect).coerceAtLeast(0.1f)
                }

                val clusterAvgRelLum = if (clusterArea > 0) clusterRelLumSum / clusterArea else 0f

                if (clusterArea > maxClusterArea) {
                    maxClusterArea = clusterArea
                    maxClusterAvgRelLum = clusterAvgRelLum
                    bestClusterDensity = density
                    bestClusterAspect = aspect
                    bestWDensity = wDensity
                    bestWAspect = wAspect
                }
            }
        }
    }

    val areaExpansion = (maxClusterArea - previousMaxClusterArea).coerceAtLeast(0)
    // 1. デッドバンド (ΔA < 2 は 0)
    val effectiveExpansion = if (areaExpansion < 2) 0 else areaExpansion

    val sRaw = (effectiveExpansion * maxClusterAvgRelLum) * wOccupancy * bestWDensity * bestWAspect
    val rawScore = ln(1.0f + sRaw / 10.0f)

    // 2. スムーズな指数平滑化 (EMA alpha = 0.3f)
    val alpha = 0.3f
    val emaScore = (previousEmaScore * (1.0f - alpha) + rawScore * alpha)

    return ApproachCalculationResult(
        rawScore = rawScore,
        areaExpansion = areaExpansion,
        effectiveExpansion = effectiveExpansion,
        emaScore = emaScore,
        approachScore = emaScore,
        activeBlockRatio = activeBlockRatio,
        maxClusterArea = maxClusterArea,
        clusterDensity = bestClusterDensity,
        clusterAspect = bestClusterAspect,
        wOccupancy = wOccupancy,
        wDensity = bestWDensity,
        wAspect = bestWAspect,
        globalAvg = globalAvg
    )
}

/**
 * 案D: ブロック単位面積拡大率 ＋ 全体平均差 アルゴリズムによる後方車両接近検知器
 */
class RearApproachDetector(
    private val approachDebugLogger: ApproachDebugLogger? = null,
    private val getApproachSensitivity: () -> Float = { 100f },
    private val getApproachGridCount: () -> Int = { 10 },
    private val onApproachDetected: () -> Unit
) : ImageAnalysis.Analyzer {

    constructor(
        getApproachSensitivity: () -> Float,
        onApproachDetected: () -> Unit
    ) : this(null, getApproachSensitivity, { 10 }, onApproachDetected)

    constructor(
        onApproachDetected: () -> Unit
    ) : this(null, { 100f }, { 10 }, onApproachDetected)

    companion object {
        private const val TAG = "RearApproachDetector"
        private const val REQUIRED_CONSECUTIVE_FRAMES = 3 // 連続成立フレーム数
    }

    private val _approachScoreState = MutableStateFlow(0f)
    val approachScoreState: StateFlow<Float> = _approachScoreState.asStateFlow()

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private var previousMaxClusterArea = 0
    private var previousEmaScore = 0f
    private var frameCounter = 0

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val plane = mediaImage.planes[0]
            val buffer = plane.buffer
            val width = mediaImage.width
            val height = mediaImage.height
            val rowStride = plane.rowStride
            val data = buffer.toByteArray()

            val gridN = getApproachGridCount().coerceIn(4, 40)
            val result = computeApproachScore(
                data = data,
                width = width,
                height = height,
                rowStride = rowStride,
                gridN = gridN,
                previousMaxClusterArea = previousMaxClusterArea,
                previousEmaScore = previousEmaScore
            )

            previousMaxClusterArea = result.maxClusterArea
            previousEmaScore = result.emaScore
            val approachScore = result.approachScore

            val uiSensitivity = getApproachSensitivity()
            var triggered = false

            _approachScoreState.value = approachScore
            Log.d(
                TAG,
                "📊 [Trace] ActiveBlockRatio: ${result.activeBlockRatio}, MaxClusterArea: ${result.maxClusterArea}, ClusterDensity: ${result.clusterDensity}, ClusterAspect: ${result.clusterAspect}, Woccupancy: ${result.wOccupancy}, Wdensity: ${result.wDensity}, Waspect: ${result.wAspect}, RawScore: ${result.rawScore}, AreaExpansion: ${result.areaExpansion}, EffectiveExpansion: ${result.effectiveExpansion}, EmaScore: ${result.emaScore}, ApproachScore: $approachScore (Th: $uiSensitivity)"
            )

            if (approachScore >= uiSensitivity && frameCounter-- <= 0) {
                Log.w(
                    TAG,
                    "🚨 後方車両接近検知! Score: $approachScore (Th: $uiSensitivity), ActiveBlockRatio: ${result.activeBlockRatio}, MaxClusterArea: ${result.maxClusterArea}, Aspect: ${result.clusterAspect}"
                )
                triggered = true
                frameCounter = REQUIRED_CONSECUTIVE_FRAMES
                toneGenerator.startTone(ToneGenerator.TONE_DTMF_S, 600)
                onApproachDetected()
            }

            approachDebugLogger?.log(
                luminosityRatio = result.globalAvg / 255.0,
                activeBlockRatio = result.activeBlockRatio,
                maxClusterArea = result.maxClusterArea,
                clusterDensity = result.clusterDensity,
                clusterAspect = result.clusterAspect,
                wOccupancy = result.wOccupancy,
                wDensity = result.wDensity,
                wAspect = result.wAspect,
                rawScore = result.rawScore,
                areaExpansion = result.areaExpansion,
                effectiveExpansion = result.effectiveExpansion,
                emaScore = result.emaScore,
                approachSensitivity = uiSensitivity,
                triggered = triggered
            )

            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            Log.i("ApproachTrace", "$timeStr\nDetector=$approachScore")

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image frame for approach", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        val data = ByteArray(remaining())
        get(data)
        return data
    }
}
