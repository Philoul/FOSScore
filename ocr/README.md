# :ocr

Reads the text a score draws as outlines or as pixels, which the music needs: the number of a
metronome mark ("♩ = 70") on a PDF printed without its fonts, and later the words over the music,
titles and measure numbers of scans.

- `TextReader`: one line of text from a gray picture (`GrayCrop`), as words or as digits only.
- `TesseractReader`: the reader, by [Tesseract](https://github.com/tesseract-ocr/tesseract)
  (Apache-2.0) through [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)
  4.9.0 (Apache-2.0, from JitPack: x86, x86_64, armeabi-v7a and arm64-v8a, minSdk 21).
- `OcrText`: what is read, cleaned of the usual misreadings of a score's text (a "7O" is 70).

## Language data

[tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) 4.1.0 (Apache-2.0), English,
French, Italian and German (about 9.5 MB): the languages of the words over the music. The first
build downloads it into `third_party/` (ignored by Git) and checks each file's SHA-256
(`build.gradle.kts`); later builds work offline. The app installs it from its assets into
`filesDir/ocr/tessdata` the first time it reads text.

## Size

About 7.5 MB of native libraries per ABI, plus the language data.

## Why Tesseract

Evaluated on 2026-11-18 against PaddleOCR (PP-OCRv5 through ONNX Runtime) and ML Kit, on crops of
score text (titles, tempo words, numbers), clean and scanned: Tesseract reads 79 % of clean crops
and 74 % of scanned ones exactly, 98 % of numbers; PaddleOCR reads more (82 % of scanned crops, 97 %
of tempo words) but ONNX Runtime adds 36 to 43 MB and needs Android 7.0; ML Kit is not free
software. The reader is behind `TextReader`, so another engine can take its place.
