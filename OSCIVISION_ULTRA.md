# OsciVision Ultra v1

Native Android image/GIF/video to stereo XY oscilloscope synthesizer.

## Core design

- Left channel = X coordinate, right channel = Y coordinate.
- No raster sweep. Frames are compiled into dense, space-filling XY paths.
- 48/96/192 kHz output with automatic runtime fallback.
- 15/24/30/60 vector FPS.
- Hybrid photo-density + Sobel microdetail mode.
- Residual-feedback allocation loop compares predicted beam visitation density against the source frame and reallocates samples toward missing structure.
- Hilbert path ordering preserves local continuity without horizontal/vertical scanline traversal.
- Full-image luminance is encoded as beam dwell/revisit density.
- Live software oscilloscope is driven from the same XY sample buffer as the audio engine.
- Image, GIF, and video playback.
- Stereo WAV export for still images and complete animations/videos.

## Hardware path

For a physical oscilloscope, use XY mode and route stereo audio left to X and right to Y. The app's internal preview works without external hardware.

A single-beam physical scope forms the complete image through rapid XY motion within the phosphor/persistence window. Higher sample rates provide more coordinate points per frame: 192 kHz gives 12,800 XY coordinate pairs at 15 fps, 8,000 at 24 fps, 6,400 at 30 fps, and 3,200 at 60 fps.
