# Japanese Handwriting Recognition and Correction Program

*Project for JAPN 320 at UBC*

This program uses handwriting and stroke recognition to detect handwritten kanji and kana. It compares each character with a database of correctly written forms and identifies possible errors, such as radical position, stroke order, stroke direction, and stroke count. The goal is to help users write characters that are accurate, easy to read, and visually appealing.

More details to come.

## Development Plan

Development of this program will follow the V-Model of the Software Development Life Cycle (SDLC).

### Estimated Timeline

| Phase | Date | Milestone |
|---|---|---|
| **Systems Design** | November 6 | Completion of system design and specifications |
| **Development** | November 22 | Completion of backend and unit testing |
| | November 25 | Completion of frontend and unit testing |
| **Integration and Testing** | November 27 | Completion of integration testing and system validation |
| **Submission** | November 27 | **Deadline for submission** |

## Usage

Check a drawing (a PNG of one kanji drawn on a square canvas, dark ink on white):

```
./gradlew run --args="drawing.png 語"
```

This prints pass/fail, the score, the alignment, and each component's score (worst first) with per-stroke coverage. It also saves an overlay image next to the drawing (`drawing-check.png`), or to a path given as a third argument. In the overlay, the reference is grey, the drawing is tinted from red (bad) to green (good) by component, missing parts are red dashes, and extra ink is orange.

Each part of the character, and then each stroke, is snapped onto the nearest matching ink. Small slips cost nothing, and the score drops the further a part or stroke had to move, turn, or stretch to fit. The overall size of the drawing is not scored. Thin lines in the overlay show where each stroke was snapped to.

Add options after the kanji:
- `--lang ja` gives the results and the overlay in Japanese.
- `--scoring distance` compares the drawing with the reference in place, without snapping. This is stricter.
- `--tolerance 8 --falloff 2 --min-coverage 0.8` and similar make the checker more lenient or stricter.

Run with no arguments to list all options.

The checker compares shapes only: it does not check stroke order or direction, and hooks and stops (はね/とめ) are not judged. All settings are in `CheckerConfig`. On Windows, run `chcp 65001` first so kanji print correctly.

## Credits

### KanjiVG

The reference stroke data for each character (stroke order, stroke direction, stroke shapes and stroke types) comes from [KanjiVG](https://kanjivg.tagaini.net) ([GitHub](https://github.com/KanjiVG/kanjivg)).

> KanjiVG — Copyright (C) 2009/2010/2011 Ulrich Apel.
> Licensed under the [Creative Commons Attribution-Share Alike 3.0 License](https://creativecommons.org/licenses/by-sa/3.0/) (CC BY-SA 3.0).

- **Files used:** the SVG files from KanjiVG's main `kanji` folder (variants excluded), stored without modification in [`app/src/main/resources/kanjivg/`](app/src/main/resources/kanjivg/). Each file keeps its original copyright and license header.
- **Changes:** at runtime this program converts the stroke paths into point lists and keeps only the strokes and their types. Stroke data shown or served by this program (for example, reference strokes and stroke-order animations) is derived from KanjiVG and is also distributed under CC BY-SA 3.0.
- The KanjiVG data is licensed separately from this program's source code. CC BY-SA 3.0 applies only to the KanjiVG data and works derived from it.

## License

This program's source code is licensed under the [MIT License](LICENSE). The KanjiVG data is not covered by the MIT License; it remains under CC BY-SA 3.0, as described above.

---

# 手書き文字の認識・添削プログラム

*UBC JAPN 320 授業プロジェクト*

本プログラムは、手書き文字と筆順の認識技術を用い、手書きの漢字及び仮名を読み取るものである。読み取った文字は、データベースに登録された正しい字形と一字ずつ照合され、誤りの可能性がある点（例：偏や旁の位置、筆順、画の向き、画数）が指摘される。これにより、正確で読みやすく、美しい字を書けるようになることを目指す。

詳細は追って記載する。

## 開発計画

本プログラムの開発は、ソフトウェア開発ライフサイクル（SDLC）のVモデルに従って進める。

### 予定スケジュール

| フェーズ | 日付 | マイルストーン |
|---|---|---|
| **システム設計** | 11月6日 | システム設計及び仕様の完成 |
| **開発** | 11月22日 | バックエンド及び単体テストの完了 |
| | 11月25日 | フロントエンド及び単体テストの完了 |
| **結合・テスト** | 11月27日 | 結合テスト及びシステム検証の完了 |
| **提出** | 11月27日 | **提出締め切り** |

## 使い方

手書き画像（正方形のキャンバスに白地・濃い色で一字を書いた PNG）を判定する：

```
./gradlew run --args="drawing.png 語"
```

合否、得点、位置合わせの結果、及び各部品の得点（低い順）と画ごとの被覆率が表示される。あわせて、判定結果を重ねた画像が手書き画像と同じ場所に保存される（`drawing-check.png`。三つ目の引数で保存先を指定することもできる）。重ね合わせ画像では、手本が灰色、手書きの線が部品ごとに赤（悪い）から緑（良い）の色、足りない部分が赤の破線、余分な線が橙色で示される。

文字の各部品、続いて各画を、最も近い手書きの線に合わせてから採点する。小さなずれは減点されず、部品や画を大きく動かす・傾ける・伸び縮みさせる必要があるほど得点が下がる。文字全体の大きさは採点に含まれない。重ね合わせ画像の細線は、各画を合わせた位置を示す。

漢字の後に次のオプションを付けられる：
- `--lang ja`：結果と重ね合わせ画像を日本語で表示する。
- `--scoring distance`：合わせ込みをせず、手本と同じ位置で比較する（より厳しい）。
- `--tolerance 8 --falloff 2 --min-coverage 0.8` など：判定を緩く、または厳しくする。

引数なしで実行するとオプションの一覧が表示される。

本プログラムは字形のみを比較する。筆順や画の向きは判定せず、はね・とめも判定の対象外である。設定はすべて `CheckerConfig` にまとめてある。Windows では、漢字を正しく表示するため先に `chcp 65001` を実行すること。

## クレジット

### KanjiVG

各文字の参照用筆画データ（筆順、画の向き、画の形、画の種類）は [KanjiVG](https://kanjivg.tagaini.net)（[GitHub](https://github.com/KanjiVG/kanjivg)）のものを使用している。

> KanjiVG — Copyright (C) 2009/2010/2011 Ulrich Apel.
> [クリエイティブ・コモンズ 表示 - 継承 3.0 ライセンス](https://creativecommons.org/licenses/by-sa/3.0/deed.ja)（CC BY-SA 3.0）の下で提供されている。

- **使用ファイル：** KanjiVG のメインの `kanji` フォルダ（異体字を除く）の SVG ファイルを、改変せずに [`app/src/main/resources/kanjivg/`](app/src/main/resources/kanjivg/) に収録している。各ファイルの著作権・ライセンス表記はそのまま残している。
- **改変：** 本プログラムは実行時に筆画のパスを点列に変換し、画とその種類のみを利用する。本プログラムが表示・提供する筆画データ（参照用の筆画や筆順アニメーションなど）は KanjiVG の二次的著作物であり、同じく CC BY-SA 3.0 の下で提供される。
- KanjiVG のデータと本プログラムのソースコードは別々にライセンスされている。CC BY-SA 3.0 は KanjiVG のデータとその二次的著作物にのみ適用される。

## ライセンス

本プログラムのソースコードは [MIT ライセンス](LICENSE)の下で提供される。KanjiVG のデータは MIT ライセンスの対象外であり、上記のとおり CC BY-SA 3.0 が適用される。
