# DEBUG_LOGGER_IMPLEMENTATION_PLAN.md
# デバッグログ永続化機能 実装計画書

## 1. 実装コンポーネント
1. **`CsvDebugLogger.kt`**: CSV出力基底クラス（UTF-8, ヘッダー管理, 日付別ローテーション, 追記モード）
2. **`ApproachDebugLogger.kt`**: 接近検知用ロガー（指定条件成立時に行出力、EventLog ID連携）
3. **`AudioDebugLogger.kt`**: 音量検知用ロガー（`maxAmplitude > 100` 時に行出力、EventLog ID連携）
4. **`DebugLogCleanupManager.kt`**: 起動時31日以上前ファイルクリーンアップ
5. **`DebugLogShareManager.kt`**: ログフォルダのZIP圧縮および共有シート起動、全削除処理
6. **UI & Config 連携**:
   - `AppConfig` / `ConfigRepository`: CSV出力有効フラグの保持
   - `MainViewModel`: ログ共有・削除アクション
   - `MainScreen`: 設定画面に「デバッグ」セクション（チェックボックス、共有・削除ボタン）追加

## 2. 処理フロー
- **接近検知ログ**: `RearApproachDetector` 内で `approachScore > 0 || consecutiveCount > 0 || triggered` の条件を満たした場合に `ApproachDebugLogger.log(...)` を呼出し。イベント発火時は生成された `eventLogId` を紐付け。
- **音量検知ログ**: `AudioImpactDetector` 内で `maxAmplitude > 100` の場合に `AudioDebugLogger.log(...)` を呼出し。
- **共有**: `debug` フォルダ内を `ZipOutputStream` で圧縮し、`FileProvider` URI を通じて `Intent.ACTION_SEND` で共有。
