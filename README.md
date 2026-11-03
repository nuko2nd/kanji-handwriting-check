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
