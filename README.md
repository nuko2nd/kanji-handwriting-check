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
- `--strictness strict|medium|relaxed` sets how strictly drawings are graded (other options then adjust it):
  - `strict` (the default) is the strictest. On the handwritten samples, about half of correct drawings pass, the rest are marked UNSURE, and no incorrect drawing passes. It also takes a little off (at most 0.1) for a drawing noticeably too large, too small or tilted.
  - `medium`: about 70% of correct drawings pass, none fail, and no incorrect sample passes.
  - `relaxed`: every correct drawing passes. Where the radicals sit relative to each other and how long the strokes are drawn are not judged, so for example 土 and 士 are accepted for each other. Missing strokes, extra marks and strokes drawn in the wrong direction still fail.
- `--lang ja` gives the results and the overlay in Japanese.
- `--scoring distance` compares the drawing with the reference in place, without snapping. This is stricter.
- `--tolerance 8 --falloff 2 --min-coverage 0.8` and similar make the checker more lenient or stricter.

Run with no arguments to list all options.

The checker compares shapes only: it does not check stroke order or direction, and hooks and stops (はね/とめ) are not judged. All settings are in `CheckerConfig`. On Windows, run `chcp 65001` first so kanji print correctly.

### Practice sheets

To check a whole sheet, write one character per cell on the practice grid ([`kanji_grid.png`](kanji_grid.png)), left to right and top to bottom, then open the checker window:

```
./gradlew gui
```

Choose the sheet image, press "Enter text…" and type the text you wrote (for example `私は日本語を勉強します。`), then press Check. The text box takes several lines: a line break starts the next row of the grid, as a new paragraph does on 原稿用紙 (a paragraph that ends exactly at the end of a row adds no empty row; a blank line leaves one), and an indent is a full-width space (　). Every character takes one cell, but only the kanji are checked: kana, punctuation, 々, 〆 and 〇 are skipped. A progress bar shows each kanji as it is checked. The results are then listed failures first, then those to check manually (unsure), then passes, lowest score first within each, each with its overlay; the sort box can list them in the order of the text instead. Click a result to see the overlay full size. "Compare text…" opens a window showing each typed character under the cell it belongs to, laid out as on the sheet, so that a typing mistake in the text is easy to spot before checking. Kana are not checked for now. The strictness box chooses strict, medium or relaxed grading (see the options above). The language box switches the whole window, including the feedback, between English and Japanese; on a Japanese system it starts in Japanese.

## Credits

### KanjiVG

The reference stroke data for each character (stroke order, stroke direction, stroke shapes and stroke types) comes from [KanjiVG](https://kanjivg.tagaini.net) ([GitHub](https://github.com/KanjiVG/kanjivg)).

> KanjiVG — Copyright (C) 2009/2010/2011 Ulrich Apel.
> Licensed under the [Creative Commons Attribution-Share Alike 3.0 License](https://creativecommons.org/licenses/by-sa/3.0/) (CC BY-SA 3.0).

- **Files used:** the SVG files from KanjiVG's `kanji` folder, including its variant forms (e.g. `04e00-Kaisho.svg`), stored without modification in [`app/src/main/resources/kanjivg/`](app/src/main/resources/kanjivg/). Each file keeps its original copyright and license header.
- **Added file:** `0304a-Straight.svg` is this project's modified copy of KanjiVG's お (`0304a.svg`), with the first stroke and the dot drawn straight. It is distributed under CC BY-SA 3.0, and its header records the change.
- **Changes:** at runtime this program converts the stroke paths into point lists and keeps only the strokes and their types. Stroke data shown or served by this program (for example, reference strokes and stroke-order animations) is derived from KanjiVG and is also distributed under CC BY-SA 3.0.
- The KanjiVG data is licensed separately from this program's source code. CC BY-SA 3.0 applies only to the KanjiVG data and works derived from it.

### Kanji grid

The practice sheet [`kanji_grid.png`](kanji_grid.png) is based on the A4 manuscript paper from [Kurashi Print (くらしのプリント)](https://www.kurashiprint.com/paper/manuscript/#variant=EX-M400H-A4-P-plain). It is not covered by this program's license.

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
- `--strictness strict|medium|relaxed`：判定の厳しさを選ぶ（他のオプションはこれを基に調整される）。
  - `strict`（既定）：最も厳しい。手書きの見本では、正しい字の約半数が合格し、残りは要確認となり、誤った字は合格しない。字全体が大きすぎる・小さすぎる・傾いている場合も少し減点する（最大 0.1）。
  - `medium`：正しい字の約 70% が合格し、不合格になるものはなく、誤った見本は合格しない。
  - `relaxed`：正しい字はすべて合格する。部首どうしの位置関係や画の長さは判定しないため、例えば「土」と「士」は互いに合格となる。画の不足、余分な線、向きの誤った画は引き続き不合格となる。
- `--lang ja`：結果と重ね合わせ画像を日本語で表示する。
- `--scoring distance`：合わせ込みをせず、手本と同じ位置で比較する（より厳しい）。
- `--tolerance 8 --falloff 2 --min-coverage 0.8` など：判定を緩く、または厳しくする。

引数なしで実行するとオプションの一覧が表示される。

本プログラムは字形のみを比較する。筆順や画の向きは判定せず、はね・とめも判定の対象外である。設定はすべて `CheckerConfig` にまとめてある。Windows では、漢字を正しく表示するため先に `chcp 65001` を実行すること。

### 練習用シート

シート全体を判定するには、練習用の原稿用紙（[`kanji_grid.png`](kanji_grid.png)）のマスに左から右、上から下の順で一字ずつ書き、判定ウィンドウを開く：

```
./gradlew gui
```

シートの画像を選び、「文を入力…」を押して書いた文（例：`私は日本語を勉強します。`）を入力し、「判定」を押す。入力欄は複数行に対応しており、改行すると原稿用紙の段落と同じく次の行から書き始める（行末ちょうどで終わった段落の後に空行は入らない。空行を入れるとその行は空ける）。字下げには全角スペース（　）を使う。すべての文字が一マスずつ使われるが、判定するのは漢字のみで、仮名・句読点・々・〆・〇は飛ばされる。判定中は進捗バーが漢字ごとに進む。結果は不合格・要確認・合格の順に（それぞれの中では得点の低い順に）、重ね合わせ画像とともに表示される（並べ替えの選択欄で、文の順に並べることもできる）。結果をクリックすると重ね合わせ画像が原寸で表示される。「文と見比べる…」を押すと、入力した各文字がシートの各マスの下に並んだウィンドウが開き、入力の打ち間違いを判定前に確認できる。仮名は今のところ判定しない。厳しさの選択欄で判定の厳しさ（上記のオプションを参照）を選べる。言語の選択欄で、ウィンドウ全体（判定結果の説明を含む）を英語と日本語で切り替えられる。日本語環境では最初から日本語で表示される。

## クレジット

### KanjiVG

各文字の参照用筆画データ（筆順、画の向き、画の形、画の種類）は [KanjiVG](https://kanjivg.tagaini.net)（[GitHub](https://github.com/KanjiVG/kanjivg)）のものを使用している。

> KanjiVG — Copyright (C) 2009/2010/2011 Ulrich Apel.
> [クリエイティブ・コモンズ 表示 - 継承 3.0 ライセンス](https://creativecommons.org/licenses/by-sa/3.0/deed.ja)（CC BY-SA 3.0）の下で提供されている。

- **使用ファイル：** KanjiVG の `kanji` フォルダの SVG ファイル（`04e00-Kaisho.svg` などの異体字を含む）を、改変せずに [`app/src/main/resources/kanjivg/`](app/src/main/resources/kanjivg/) に収録している。各ファイルの著作権・ライセンス表記はそのまま残している。
- **追加ファイル：** `0304a-Straight.svg` は KanjiVG の「お」（`0304a.svg`）を本プロジェクトで改変したもので、一画目と点を直線にしている。CC BY-SA 3.0 の下で提供し、改変内容はファイルのヘッダーに記載している。
- **改変：** 本プログラムは実行時に筆画のパスを点列に変換し、画とその種類のみを利用する。本プログラムが表示・提供する筆画データ（参照用の筆画や筆順アニメーションなど）は KanjiVG の二次的著作物であり、同じく CC BY-SA 3.0 の下で提供される。
- KanjiVG のデータと本プログラムのソースコードは別々にライセンスされている。CC BY-SA 3.0 は KanjiVG のデータとその二次的著作物にのみ適用される。

### 原稿用紙

練習用シート [`kanji_grid.png`](kanji_grid.png) は[くらしのプリント](https://www.kurashiprint.com/paper/manuscript/#variant=EX-M400H-A4-P-plain)の A4 原稿用紙を元にしている。本プログラムのライセンスの対象外である。

## ライセンス

本プログラムのソースコードは [MIT ライセンス](LICENSE)の下で提供される。KanjiVG のデータは MIT ライセンスの対象外であり、上記のとおり CC BY-SA 3.0 が適用される。
