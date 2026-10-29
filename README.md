# C/C++ Colored Brackets (Rider plugin)

Colors matching bracket pairs in C/C++ files by nesting level, like VS Code's bracket pair colorization.
Rider only. Applies to `.c .cc .cpp .cxx .c++ .cppm .ixx .h .hh .hpp .hxx .h++ .inl .ipp .tpp .tcc .inc .ino`.

## Features
- **4 levels by default.** Each color in the list is one level, so adding colors colors deeper levels.
  Levels past the end of the list stay plain unless **Repeat colors** is on.
- **Custom color order.** Add, remove, edit (double-click) and reorder the colors.
- **Bracket types:** `( )`, `[ ]`, `{ }`, and optionally template `< >` (off by default; it's a best
  guess, since `<`/`>` are also comparisons).
- **Indent coloring** (off by default). Tints each indent level's leading whitespace. By default it uses the
  bracket colors in the same order; turn off **Use the bracket color order** to give it its own list.
  The tint strength and indent width are adjustable. Indent width is detected from the file by default.
- **Unmatched brackets** are shown in red (can be turned off).
- Skips brackets in comments, strings, char literals, raw strings, `#include <...>` and `#if 0` blocks.
  Brackets inside a macro definition are matched on their own, so `#define BEGIN {` doesn't throw off
  the rest of the file.

## Where to customize
- **Status bar** (bottom right): click the nested-arcs icon for quick toggles and **Customize Colors and
  Levels…**. If you don't see it, right-click the status bar and enable *C/C++ Colored Brackets*.
- **Settings → Editor → C/C++ Colored Brackets** has all the options.

Until you change them, the default colors follow the editor theme: VS Code's gold, orchid and blue plus
green on dark themes, and blue, green, brown and pink on light themes.

## Build
Needs the local Rider install at `D:/jetbrains_ides/Rider`. To use a different path, change `riderPath` and
`org.gradle.java.installations.paths` in `gradle.properties`.

```
gradlew.bat test buildPlugin
```
The output is `build/distributions/CppColoredBrackets-<version>.zip`.

## Install
In Rider: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, then restart Rider.

## How it works
Rider's C++ language support runs in its backend, so the plugin reads the editor text with its own small
C/C++ lexer (`CppBracketScanner`) and paints with editor highlighters. It doesn't run the preprocessor, so
code like `#ifdef A` / `void f() {` / `#else` / `void f(int) {` / `#endif` can make brackets look unmatched.
VS Code has the same limitation.
