# Third-party notices

This plugin redistributes the following third-party components inside
`lib/` of the plugin distribution archive:

| Component | Version | License | Project |
|---|---|---|---|
| JCodec (`jcodec`) | 0.2.5 | BSD-2-Clause | https://github.com/jcodec/jcodec |
| JCodec Java SE support (`jcodec-javase`) | 0.2.5 | BSD-2-Clause | https://github.com/jcodec/jcodec |

## JCodec license text (BSD-2-Clause)

```
Copyright (c) 2013, The JCodec project
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## ffmpeg

This plugin does **not** bundle or redistribute ffmpeg. If an `ffmpeg`
executable is present on the user's machine, the plugin launches it as a child
process to decode the user's own local video files. ffmpeg licensing (LGPL/GPL)
therefore remains entirely with the user's own installation.

## Wallpaper Engine content

The plugin only reads media files from folders that the user already owns
(for example a local Steam workshop directory). No third-party wallpaper content
is bundled or redistributed.
