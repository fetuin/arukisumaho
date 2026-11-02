# 歩きスマホ注意 by usePocket

歩きながらスマホの画面を使うと、点滅する警告表示とバイブでしつこく知らせ、
警告の回数を端末内に長期保存して履歴を表示する Android アプリです。
子どもの歩きスマホ対策として作りました。

- **配布ページ（使い方の説明つき）**: https://fetuin.github.io/arukisumaho/
- **APK（最新版）**: https://fetuin.github.io/arukisumaho/arukisumaho.apk （リポジトリ内では `docs/arukisumaho.apk`）
- 開発元: [usePocket（usepocket.com）](https://usepocket.com)

## 動き
- ロックを解除した画面を見たまま、5秒以上歩き続けると警告開始
  （歩いて→立ち止まって→画面を開いた、では出ない）
- 警告は画面中央に赤い帯で表示（下のアプリは操作できる）、3秒ごとにバイブ
- 警告から10秒たつと、警告が全画面に広がる
- 3秒止まる・画面を消す・通話中は警告終了
- 上の秒数・バイブ・全画面化は設定画面で変更できる（暗証番号つき）
- 警告1回ごとに「開始・終了時刻・歩数」を記録
- 5分以上アプリが動いていなかった期間も「見守り停止」として記録
  （電源オフ／強制停止など／保護者の停止を区別）
- インターネット接続の権限は持たず、記録は端末内（SQLite）にだけ保存

## 履歴画面
今日・7日・30日・累計の回数、1日平均、日別グラフ（30日）、時間帯別グラフ、
警告の一覧、見守り停止の一覧、CSVでの共有、暗証番号つきの全消去。

## 動作環境
Android 8.0（API 26）以上。Android 11・17 で動作確認。

## フォルダ構成
| パス | 内容 |
|---|---|
| `app/src/main/java/jp/walkguard/` | アプリ本体（Java、外部ライブラリなし） |
| `app/src/main/res/` | アイコンなどのリソース |
| `docs/` | 配布ページと配布用APK（GitHub Pages で公開） |
| `tools/make_icon.py` | アイコン画像の生成スクリプト（`uplogo02.gif` から作成） |

判定の初期値は `Prefs.java`、固定の判定パラメータは `WalkGuardService.java` 冒頭の定数にあります。

## ビルド
JDK 17 以上と Android SDK（platform 34）が必要です。

```
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵について
署名鍵はリポジトリに入れていません。署名つきでビルドするときは、プロジェクト直下に
`keystore.properties` を作り、鍵の場所とパスワードを書きます（このファイルも `.gitignore` 済み）。

```
storeFile=keystore/walkguard.jks
storePassword=********
keyAlias=walkguard
keyPassword=********
```

`keystore.properties` が無いときは署名なしの APK になります。
**配布済みの APK と同じ鍵で署名しないと上書き更新できず、入れ直すと履歴が消える**ので、鍵は大切に保管してください。

## 新しいバージョンの公開手順
1. `app/build.gradle` の `versionCode` を1つ増やし、`versionName` を更新
2. `./gradlew assembleRelease` でビルド
3. できた APK を `docs/arukisumaho.apk` に上書きコピー（配布ページのボタンとQRコードはこのファイルを指します）
4. `docs/index.html` のバージョン表記・サイズと「更新履歴」を更新してコミット

## 配布ページの公開（GitHub Pages）
リポジトリの Settings → Pages で、Source を「Deploy from a branch」、Branch を `main` ／ `/docs` にして保存します。
数分後に https://fetuin.github.io/arukisumaho/ で公開されます。

## アクセスカウンター
配布ページ上部のアクセス数は、`https://usepocket.com/api/counter.cgi` の汎用カウンターを
`page=arukisumaho` で呼び出して表示しています。CGI に届かないときは帯ごと非表示になります。
