package jp.hisanet.charisafe.data.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RearApproachDetectorTest {

    private val width = 100
    private val height = 100
    private val rowStride = 100
    private val gridN = 10

    @Test
    fun testUniformDarkResultsInZeroScore() {
        val data = ByteArray(width * height) { 20 } // 一様暗い
        val result = computeApproachScore(
            data = data,
            width = width,
            height = height,
            rowStride = rowStride,
            gridN = gridN,
            previousMaxClusterArea = 0,
            previousEmaScore = 0f
        )

        assertEquals(0.0f, result.approachScore, 0.001f)
    }

    @Test
    fun testLocalizedBrightClusterExpansionResultsInHighScore() {
        val data1 = ByteArray(width * height) { 20 }
        // 局所的に明るい領域 (中央付近)
        for (y in 40 until 60) {
            for (x in 40 until 60) {
                data1[y * rowStride + x] = 220.toByte()
            }
        }
        val res1 = computeApproachScore(data1, width, height, rowStride, gridN, 0, 0f)

        // フレーム2: 面積がさらに拡大 (ΔA >= 2)
        val data2 = ByteArray(width * height) { 20 }
        for (y in 25 until 75) {
            for (x in 25 until 75) {
                data2[y * rowStride + x] = 220.toByte()
            }
        }
        val res2 = computeApproachScore(data2, width, height, rowStride, gridN, res1.maxClusterArea, res1.emaScore)

        assertTrue(res2.approachScore > 0.0f)
    }

    @Test
    fun testUniformBrightResultsInZeroScore_StreetLightReflection() {
        // テストケース3: 画面全体が均一に明るくなる場合（街灯反射シミュレーション）
        // 全画面が均一に高輝度な場合、全体平均 G とブロック平均 B_r,c が等しくなるため相対輝度が 0 となり誤検知しないこと
        val data = ByteArray(width * height) { 200.toByte() } // 一様高輝度
        val result = computeApproachScore(
            data = data,
            width = width,
            height = height,
            rowStride = rowStride,
            gridN = gridN,
            previousMaxClusterArea = 0,
            previousEmaScore = 0f
        )

        assertEquals(0.0f, result.approachScore, 0.001f)
    }

    @Test
    fun testAEReductionRobustness_HeadlightDetection() {
        // テストケース4: AEによって全体輝度が低下する場合（AE減光耐性）
        // AEにより全体平均が低下（100 -> 30）しても、ヘッドライト等の局所高輝度ブロックの相対コントラストが維持され検知可能であること
        val dataBeforeAE = ByteArray(width * height) { 100 }
        for (y in 45 until 55) {
            for (x in 45 until 55) {
                dataBeforeAE[y * rowStride + x] = 240.toByte()
            }
        }
        val res1 = computeApproachScore(dataBeforeAE, width, height, rowStride, gridN, 0, 0f)

        // AE作動後：背景が暗くなり（30）、ヘッドライト部分は 220 を維持しつつ面積拡大 (ΔA >= 2)
        val dataAfterAE = ByteArray(width * height) { 30 }
        for (y in 25 until 75) {
            for (x in 25 until 75) {
                dataAfterAE[y * rowStride + x] = 220.toByte()
            }
        }
        val res2 = computeApproachScore(dataAfterAE, width, height, rowStride, gridN, res1.maxClusterArea, res1.emaScore)

        assertTrue(res2.approachScore > 0.0f)
    }

    @Test
    fun testElongatedStripeClusterAspectPenalty_CrosswalkSuppression() {
        // テストケース5: 横断歩道線などの細長い帯状クラスタに対するアスペクト比減衰ペナルティ (Waspect)
        val dataStripe = ByteArray(width * height) { 20 }
        // 横幅が長く高さが極めて低い横断歩道線状パターン (Y: 40..49 1ブロック高, X: 0..100 10ブロック幅 -> Aspect = 10)
        for (y in 40 until 50) {
            for (x in 0 until 100) {
                dataStripe[y * rowStride + x] = 220.toByte()
            }
        }
        val resStripe = computeApproachScore(dataStripe, width, height, rowStride, gridN, 0, 0f)

        // アスペクト比 Waspect ペナルティが適用され (Waspect <= 0.3f)、大きなスコア減衰が働くこと
        assertTrue(resStripe.wAspect <= 0.3f)
    }

    @Test
    fun testLogicalMaxApproachScoreDoesNotExceedSliderMaxOfFive() {
        // テストケース6: すべてのペナルティが 0（=ペナルティなし: Woccupancy=1.0, Wdensity=1.0, Waspect=1.0）となる理想条件
        // 暗い背景 (20) の中で正方形・高密度の高輝度ヘッドライトパターン (230) が膨張拡大するケース
        val data1 = ByteArray(width * height) { 20 }
        for (y in 40 until 60) { // 2x2 ブロック (20x20ピクセル)
            for (x in 40 until 60) {
                data1[y * rowStride + x] = 230.toByte()
            }
        }
        val res1 = computeApproachScore(data1, width, height, rowStride, gridN, 0, 0f)

        // フレーム2: 2x2 (4ブロック) から 3x3 (9ブロック) へ拡大 (ΔA = 5)
        val data2 = ByteArray(width * height) { 20 }
        for (y in 35 until 65) {
            for (x in 35 until 65) {
                data2[y * rowStride + x] = 230.toByte()
            }
        }
        val res2 = computeApproachScore(data2, width, height, rowStride, gridN, res1.maxClusterArea, res1.emaScore)

        // ペナルティなしの確認
        assertEquals(1.0f, res2.wOccupancy, 0.001f)
        assertEquals(1.0f, res2.wDensity, 0.001f)
        assertEquals(1.0f, res2.wAspect, 0.001f)

        // 接近スコアがスライダー上限 5.0f を超えないことの検証
        assertTrue("ApproachScore (${res2.approachScore}) should be <= 5.0f", res2.approachScore <= 5.0f)
    }
}
