# INCIDENT_STATUS_DESIGN.md
# 追加検討③: 動画保存失敗の記録 (IncidentStatus) 設計検討書

## 1. 要求と目的
従来は「MP4結合が成功して初めて `IncidentLog` を作成」していたため、以下のような問題があった：
- 動画保存が処理中（結合中）なのか、失敗したのかがユーザーにわからない。
- ディスク満杯やファイル破損等で動画保存が失敗した際、失敗した事実そのものが残らない。

目的：動画保存処理の開始時点で `IncidentLog` を即座に生成し、処理状態（`PENDING`, `SUCCESS`, `FAILED`）を管理・可視化する。

---

## 2. `IncidentStatus` スキーマ定義

```kotlin
enum class IncidentStatus(val displayName: String) {
    PENDING("保存中..."),   // MP4セグメント抽出・結合処理中
    SUCCESS("保存完了"),   // MP4動画の生成・メディアスキャン完了
    FAILED("保存失敗")     // エラー（ディスク満杯、ファイル破損、OOM等）
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
    var videoFilePath: String = "",                  // SUCCESS 時にMP4パスを設定
    var status: IncidentStatus = IncidentStatus.PENDING,
    var failureReason: String? = null                 // FAILED 時のエラー理由
)
```

---

## 3. レビュー事項の回答

### 3.1 UI 表示方法 (`MainScreen.kt`)
「保存動画一覧 (IncidentLog)」での表示分け：

| 状態 (`IncidentStatus`) | アイコン / バッジ | 操作ボタン |
| :--- | :--- | :--- |
| **`PENDING`** | ⏳ `保存処理中...` (CircularProgressIndicator) | 非活性 (再生不可) |
| **`SUCCESS`** | ✅ `保存完了` (緑色バッジ) | ▶ `再生` / 📤 `共有` ボタン有効 |
| **`FAILED`** | ⚠️ `保存失敗` (赤色バッジ + エラー理由) | 🔄 `再試行` ボタン (条件付き) |

### 3.2 リトライ（再試行）可否
- **リトライ可能な条件**:
  リングバッファ内のセグメントファイル（`seg_*.mp4`）がまだディスクからパージ（削除）されていない場合（＝通常、直近 3分〜5分以内）。
- **リトライ不可能な条件**:
  時間が経過し、対象時間帯のリングバッファセグメントが既に削除されている場合。
- **UI制御**:
  `IncidentLog` の `timestampMs` と現在のリングバッファ保持期間を比較し、セグメントが存在する場合のみ 🔄 `再試行` ボタンを有効化する。

### 3.3 ライドサマリーへの影響
- **影響なし (堅牢性の向上)**:
  ライドサマリーにおける危険度指標（走行距離あたりの危険件数件/km、時間あたりの件/h）は、すべて **`EventDetectionLog` から集計** される。
- したがって、ストレージ不足等で動画保存（`IncidentLog`）が `FAILED` になった場合でも、**ライドの危険度定量的指標や検知件数カウントは100%正確に保持**される。
