# INCIDENT_STATUS_DESIGN.md
# 追加検討: 動画保護状態管理 (IncidentStatus) 設計検討書

## 1. 要求と目的
新方式（セグメント保護方式）において、セグメント動画の保護・保存処理が「正常完了したか」「現在処理中か」「ストレージ不足等で失敗したか」を明確にトラッキング・可視化するために `IncidentStatus` を定義する。

---

## 2. `IncidentStatus` スキーマ定義

```kotlin
enum class IncidentStatus(val displayName: String) {
    PENDING("保護処理中..."),   // セグメント退避・メディアスキャン中
    SUCCESS("保護完了"),       // 動画セグメントの保護・メディアスキャン完了
    FAILED("保護失敗")         // エラー（ストレージ不足、ファイルコピー失敗等）
}

data class IncidentLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val triggerEventType: EventType,
    val triggerDetectionLogId: String,
    val relatedDetectionIds: List<String> = emptyList(),
    val highestSeverityType: EventType = triggerEventType,
    val latitude: Double,
    val longitude: Double,
    var coveredSegmentFiles: List<String> = emptyList(), // 保護完了した動画セグメントパス群
    var status: IncidentStatus = IncidentStatus.PENDING,
    var failureReason: String? = null
)
```

---

## 3. UI 表示および動作ルール

### 3.1 UI 表示方法 (`MainScreen.kt`)

| 状態 (`IncidentStatus`) | アイコン / バッジ | 操作ボタン |
| :--- | :--- | :--- |
| **`PENDING`** | ⏳ `保護処理中...` (CircularProgressIndicator) | 非活性 (再生不可) |
| **`SUCCESS`** | 🔒 `保護完了` (緑色バッジ) | ▶ `再生` / 📤 `共有` ボタン有効 |
| **`FAILED`** | ⚠️ `保護失敗` (赤色バッジ + エラー理由) | 🔄 `再試行` ボタン (条件付き) |

### 3.2 ライドサマリーへの影響
- **影響なし (定量データ100%保持)**:
  ライドサマリーにおける危険度指標（走行距離あたりの危険件数件/km、時間あたりの件/h）は、すべて **`EventDetectionLog` から集計** されるため、万が一ストレージ不足で動画保護が `FAILED` になった場合でも、危険度指標やイベント件数は100%正確に保持される。
