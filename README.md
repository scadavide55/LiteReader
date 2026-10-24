# LiteReader 

Light e-reader for old Android devices ( minimum deployment : Android 4 "Ice cream sandwich" , API 14. . APK about 3.5 MB, no network access, only `READ_EXTERNAL_STORAGE`. Everything below was measured on that tablet with real files; numbers are in `REPORT.md`.

## How to use
- **Home (library):** recent books with "Page N of M (x%)", then the contents of the `Books` and `Documents` folders (3 levels deep), and a **File explorer** button at the bottom. Long-press a recent book to remove it from the list.
- **File explorer:** folders and supported files; known unsupported types (DjVu, CHM, Word, KFX) are listed in grey and explain why when tapped.
- **Reading:** one page per screen. Tap the **centre** for the menu (Library, Mark, Bookmarks, direction, page slider). Tap the **left/right quarter** to turn. **Drag** to turn (the page follows your finger). **Pinch** to zoom 1x–5x, **double-tap** to zoom 2.5x and back, drag to pan (fling continues), push past the page edge while zoomed to turn. Volume keys and D-pad also turn pages. Back closes the menu first.
- **Direction button** (image books only): **Manga ←** = right-to-left (next page on the left, default for mangas), **Normal/Comics →** = left-to-right. PDFs, text books and TXT always read left-to-right and hide the button.
- **Saved position:** saved on every page change and when the screen turns off or the app closes; reopening the same file (same path and size) resumes on that page. **Bookmarks:** "Mark" toggles the current page, "Bookmarks" lists and jumps; stored per book.
- **Previews:** low-resolution page previews are built in the background while you are idle (about 10 pages/s) so scrubbing and jumping show a page instantly ("previews N%" in the menu).

## Supported formats (verified on the tablet)
| Format | Supported | Route (engine) | Measured | Limits |
|---|---|---|---|---|
| EPUB with image pages (manga/comics) | yes | image engine: native PNG/JPEG decoder, one GPU texture per page | 1.86 GB / 2,787 pages: first page 2.9 s, peak 129 MB | file under 2 GB; reading order from the OPF spine |
| CBZ / zip of images | yes | image engine | small sample verified | under 2 GB, natural file-name order |
| MOBI6 comic (`.mobi`, image pages) | yes | image engine, records read in place, page order from the book text | 1.03 GB / 2,787 pages: 2.4 s, 126 MB | page order verified against the EPUB for the one real file |
| KF8/AZW3 comic | yes | libmobi converts to a cached EPUB, then image engine | 175–189 pages: 1.7–2.5 s, about 100 MB; order verified on all 15 volumes | file up to 150 MB (tested to 32 MB) |
| EPUB with text | yes | MuPDF renders reflowed pages | 513 pages: 3.3 s, 174 MB; 50 MB text: 91 s, 162 MB | one chapter file at most 10 MB; opening takes about 1.5 s per MB of text |
| MOBI6 / AZW3 / KF8 text (no DRM) | yes | libmobi to cached EPUB, then MuPDF | Dante 2.8–3.9 s, 140–150 MB; 50 MB AZW3: 122 s, 222 MB | file up to 150 MB; MOBI6 text block at most 10 MB |
| PDF (text and image) | yes | MuPDF, pages rendered at 2.8 Mpixel | 179 MB / 4,351 pages: first page 6.4 s, peak 192 MB | **under 2 GB (larger files are refused)**; complex pages can take 3–5 s; password PDFs refused |
| TXT | yes | MuPDF | 9.5 MB: 18 s, 269 MB | **up to 10 MB** |
| DOCX (Word, `.docx`) | yes | MuPDF converts the text to reflowed pages (layout not preserved) | 2.4 MB file / 9.1 MB of text, 2,891 pages: first page 11.7 s, peak 223 MB; 0.35 MB file: 2.5 s, 140 MB | **file up to 15 MB and document text up to 12 MB** (the 12 MB guard is tested only up to 9.1 MB); not tested with images/tables in large files |
| DRM-protected AZW/AZW3/MOBI | no (message) | — | detection tested with a flagged real AZW3 | cannot be opened without the owner's key |
| KFX, AZW4, DjVu, CHM, old Word (.doc), ODT, RTF, XLSX, PPTX | no (message) | — | KFX/DjVu/CHM messages captured on the tablet | convert to EPUB/PDF/CBZ/DOCX first |
| FB2, XPS, SVG, single images | not offered | — | MuPDF can read them; not enabled | — |

## Converting what is not supported (on a computer, with Calibre)
- MOBI/AZW3 over 150 MB, or a MOBI6 text book that is one huge block: `ebook-convert book.mobi book.epub`
- DjVu: `ebook-convert book.djvu book.cbz` or `ddjvu -format=pdf book.djvu book.pdf`
- CHM: `ebook-convert book.chm book.epub` · old Word/ODT/RTF: save as DOCX or export to PDF
- KFX / DRM books: remove DRM only where the law allows it for your own books, then convert to EPUB.
- TXT over 10 MB: split it, or `ebook-convert big.txt big.epub --chapter-mark pagebreak`

## Storage used
`/data/data/com.litereader/`: page index per book (small), preview images (about 150–250 MB for a 2,800-page book, deleted with the app), converted EPUBs for MOBI/AZW3 (size of the book), reading positions and bookmarks. Nothing is written to your book folders.

## Engines and licences
- Image engine: own C decoder (zlib + IJG libjpeg), OpenGL ES 2.0 renderer, 2 decode threads, prefetch 3 pages ahead and 1 behind.
- Text/PDF engine (`liblrmu.so`, loaded only when needed): **MuPDF 1.28.5 — AGPL-3.0**; **libmobi — LGPL-3.0**; miniz (MIT/public domain). Because MuPDF is AGPL, the whole app must be offered under AGPL-3.0 with source if you ever give the APK to anyone; for private use there is no obligation. Source and build scripts are in `litereader/` and `scripts/`.

## Known limits
- Text books are laid out at a fixed size (page 360x576 pt, 11 pt font); font size and margins are not adjustable yet.
- Zoom on PDFs/text scales the rendered page (sharp to about 2x); there is no re-render at higher zoom.
- Touch gestures were verified through a gesture simulator on the real GPU engine and by code review; multi-touch translation has not been tested with real fingers by me.
- Android 4.0 only gives about 768 MB of RAM to the system; the limits above exist so that the system never kills the app.
