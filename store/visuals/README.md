# Google Play listing visuals

These assets describe the existing Android alpha. They do not add application features
or imply that the publisher operates a trading service or supplies a backend.

| File | Dimensions | Purpose |
| --- | --- | --- |
| `icon.png` | 512 × 512 | Store listing icon. |
| `feature-graphic.png` | 1024 × 500 | Store feature graphic. |
| `phone-market-chart.png` | 1080 × 1920 | Actual Android market chart with synthetic fixture data. |
| `phone-positions.png` | 1080 × 1920 | Actual Android positions view with a synthetic paper position. |

## Brand provenance

`logo-source.png` is a byte-for-byte copy of
`app/src/main/res/drawable-nodpi/snapper_logo.png`, the existing MIT-licensed Snapper
logo inherited from the iOS project. No original vector logo was available. The SVG
sources therefore reference this unchanged bitmap rather than approximate or redraw
its mountain, cross and wave shapes. Keep `logo-source.png` beside both SVG files.

`icon.svg` scales the existing identity to the store icon size. `feature-graphic.svg`
provides editable vector layout and text with the existing red/green brand palette.
Its only claims are "Snapper", "Your backend. Your markets." and "Open-source Android
client". The PNG exports were rendered with the existing Chromium browser, at a device
scale factor of 1 and the exact SVG dimensions, with an opaque background. The feature
text uses DejaVu Sans. No image-generation model or raster retouching was used.

To reproduce exports, load each SVG with its referenced logo resolved, wait for images
and fonts, and capture a margin-free browser viewport matching the SVG dimensions:
512 × 512 for the icon, and 1024 × 500 for the feature graphic. The original export
used the existing workspace Playwright tools and Chromium; no new dependency is needed
by the Android application.

## Screenshot provenance

The two phone screenshots are unchanged copies of the Android synthetic UAT captures
from October 15, 2026: `uat-market-chart.png` and `uat-positions.png`. They show fixture
candles and a paper position, not a real person's account or actual market performance.
No screen elements, values, status bars or navigation controls were added or removed.
These are historical alpha captures; check their correspondence with the submitted
build before using them in a listing. They are not screenshots of the publisher's
production account.

SHA-256 checksums in `SHA256SUMS` cover the image assets and SVG sources. Regenerate the
checksums after any intentional change to those assets.
