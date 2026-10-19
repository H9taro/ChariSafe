# ChariSafe アーキテクチャ・ドキュメント

## 1. システム概要
ChariSafe は、自転車の安全運転をサポートするためのセーフティレコーダーアプリです。
長時間（目標12時間）のバックグラウンド録画、後方接近検知、およびイベント時自動保存機能を備えます。

## 2. 技術スタック
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Camera API**: CameraX (`VideoCapture` + `ImageAnalysis`)
- **Service**: Foreground Service (`foregroundServiceType="camera"`)
- **Wake Lock**: `PARTIAL_WAKE_LOCK`

## 3. 実装前検証 (PoC) 計画
詳細な技術実現性検証計画については、[POC_PLAN.md](POC_PLAN.md) を参照してください。

### 検証項目一覧
- **PoC-1**: 後方動画撮影の連続安定性 (720p / 15fps / H.265 / 2時間以上)
- **PoC-2**: 消費電力・熱測定 (12時間連続運用可能性評価)
- **PoC-3**: 5分リングバッファ方式評価 (CameraX vs MediaRecorder vs Camera2+MediaCodec)
- **PoC-4**: 動画録画と画像解析 (`ImageAnalysis`) の同時利用
- **PoC-5**: 夜間ヘッドライト検知（ルールベース/軽量輝度解析）
- **PoC-6**: ポケット収納自動停止 (省電力スリープ)
