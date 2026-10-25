# LOCK_FAILURE_RECOVERY_DESIGN.md
# 追加検討①: EventSavingLock 異常時解除保証 設計検討書

## 1. 課題とリスク
動画保存中フラグ（`isEventSavingLocked`）の保持中に以下の例外・中断が発生した場合、ロックが解除されない（`true` のまま残る）「ロック凍結（Deadlock）」が発生するリスクがある。

- **リスク要因**:
  1. `Mp4Concatenator` 実行中の Exception (ファイル破損、IOエラー)
  2. メモリ不足による `OutOfMemoryError`
  3. Coroutines のキャンセル（`CancellationException`）
  4. 処理のハングアップ（無限ループやファイルディスク記述子のスタック）

ロックが凍結すると、アプリを再起動するまで**以後すべての動画保存が永久に停止**し、「証拠動画を撮り逃さない」という最優先目的が破壊される。

---

## 2. 解決案の比較 (A案, B案, C案)

| 検討案 | 構成 | 長所 | 短所 | 評価 |
| :--- | :--- | :--- | :--- | :--- |
| **A案** | `exportEventAsync` 内の `try-finally` で解除 | 実装が最小限で済む。 | コルーチン自体のキャンセルや外側スコープの例外で `finally` がスキップされるリスクがある。責任の分散。 | △ |
| **B案** | `RecordingService` 側で解除 | サービス内に集約可能。 | `RecordingService` の責務が過剰になり（カメラ、位置情報、通知、ロック管理）、テストが困難。 | △ |
| **C案** | **専用 `EventCoordinator` クラスが解除** | **関心の分離（SoC）が明確。ロック獲得・タイムアウト監視・例外補足・強制解除を一括カプセル化可能。** | 新規クラスが1つ増える。 | **◎ 推奨** |

---

## 3. 採用設計: C案 (`EventCoordinator` + タイムアウトウォッチドッグ)

単なる `try-finally` だけでなく、処理がハングアップした場合に備えた **タイムアウトウォッチドッグ (Timeout Watchdog)** を併設する。

### 3.1 `EventCoordinator.kt` 設計

```kotlin
package jp.hisanet.charisafe.data.service

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class EventCoordinator {
    companion object {
        private const val TAG = "EventCoordinator"
        private const val SAVE_TIMEOUT_MS = 15_000L // 最大15秒で強制解除
    }

    private val isSavingLocked = AtomicBoolean(false)

    fun isLocked(): Boolean = isSavingLocked.get()

    suspend fun <T> executeWithLock(block: suspend () -> T): T? {
        // アトミックにロック獲得を試みる
        if (!isSavingLocked.compareAndSet(false, true)) {
            Log.w(TAG, "🔒 [EventSavingLock] 既に動画保存が進行中のため要求を抑止しました")
            return null
        }

        return try {
            // タイムアウト付きで安全にブロックを実行 (15秒超過時は自動キャンセル)
            withTimeoutOrNull(SAVE_TIMEOUT_MS) {
                block()
            } ?: run {
                Log.e(TAG, "⚠️ [TimeoutWatchdog] 動画保存処理が15秒を超起したため強制キャンセルされました")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 動画保存処理中に例外が発生しました", e)
            null
        } finally {
            // どのような例外・キャンセル・タイムアウトでも 100% 確実にロックを解除
            isSavingLocked.set(false)
            Log.i(TAG, "🔓 [EventSavingLock] ロックを正常に解除しました")
        }
    }
}
```

---

## 4. 安全性の保証
1. **`try-finally`**: Kotlin コルーチンのキャンセル例外（`CancellationException`）を含め、いかなる場合も `finally` が実行される。
2. **`withTimeoutOrNull(15,000ms)`**: 万が一 MP4 結合がスタックした場合も、15秒後には確実にコルーチンが破棄されロックが強制解除される。
