# Dynamic Wallpaper for IntelliJ IDEA

Play **Wallpaper Engine**-style animated wallpapers behind your IDE — video, GIF, image
sequences and web wallpapers — without slowing the editor down.

> Install from the [JetBrains Marketplace](https://plugins.jetbrains.com/) (search for
> *Dynamic Wallpaper*), or use **Settings → Plugins → ⚙ → Install Plugin from Disk** with a
> release ZIP.

## Why it doesn't lag

Most "background image" plugins paint inside the IDE's Swing hierarchy, so every animation frame
repaints the whole IDE window. This plugin does the opposite: the wallpaper lives in a **separate
per-pixel translucent top-level window** that the OS composites over the IDE. The IDE never
repaints, and a native `WS_EX_TRANSPARENT` style makes the overlay fully click-through, so all
mouse and keyboard input reaches the editor.

## Features

- Full-window animated wallpaper (file tree, editor, status bar)
- Auto-discovers your local Wallpaper Engine workshop library; custom folder supported
- **Opacity**, **brightness** and **frame rate (5–60 fps)** controls in a right-side tool window
- Fixed-cadence display clock (no judder from uneven frame arrival)
- Drops to low fps while you type / when the window is hidden
- Graceful fallbacks: no ffmpeg → pure-Java decoder; no JCEF → native video path

## Requirements

| | |
|---|---|
| IDE | IntelliJ IDEA 2026.2 (build 262) or newer |
| OS | Windows recommended (translucent overlay + click-through use Win32 styles) |
| Video | `ffmpeg` on `PATH` or in a standard install location — **not bundled** |

Without `ffmpeg` the plugin still works, using the bundled pure-Java decoder at a lower frame rate.

## Privacy

100% offline. The plugin reads only local media files from the folder you configure, keeps its
settings in the IDE configuration directory, and never sends anything over the network.

## Building from source

Two machine-specific values are intentionally **not** committed:

1. **`local.properties`** — your local IDEA installation:

   ```properties
   localIdePath=C:/Users/<your-name>/AppData/Local/JetBrains/Toolbox/apps/IDEA-U/ch-0/262.10968.63
   ```

   (see `local.properties.example`; you can also pass `-PlocalIdePath=...` or set `IDEA_HOME`)

2. **`JBR_HOME`** — the JetBrains Runtime used as the compilation toolchain (JDK 25,
   because IntelliJ Platform 2026.2 class files are bytecode v69):

   ```powershell
   $env:JBR_HOME="$env:LOCALAPPDATA\JetBrains\Toolbox\apps\IDEA-U\ch-0\262.10968.63\jbr"
   ```

Then:

```bash
./gradlew buildPlugin     # → build/distributions/dwallpaper-1.0.0.zip
./gradlew verifyPlugin    # optional JetBrains verification
```

The plugin itself is compiled to Java 17 bytecode (`--release 17`) and runs on the IDE's
bundled JBR.

## Third-party components

- [JCodec](https://github.com/jcodec/jcodec) 0.2.5 (BSD-2-Clause) — bundled, pure-Java fallback decoder
- `ffmpeg` — invoked as an external process when present, never redistributed

See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## License

MIT License — see [LICENSE](LICENSE).
