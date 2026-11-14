# MundoSMS brand in the app

The app uses the MundoSMS brand of the mundosms.es website: a symbol made of two joined circles (a large one and a small
one at the top right) and the word «mundosms» in lower case, where the «o» is the symbol.

Sources, exactly as published by the website, in `fuente/`:

| File | What it is |
|---|---|
| `Blanco_Fondo_Negro.svg` | The favicon: the symbol in white on a `#1B1725` square (100×100, a single evenodd path) |
| `Principal-Blanco.svg` | The word in white, 482×73: seven letters and the «o», which is the only evenodd path |

Colours (the ones in `values/colors.xml`): main `#00647C`, light `#A3E0EF`, lime `#A1F152` and dark `#1B1725`
(`mundosms_dark`).

## Regenerating

    third_party/marca/genera-marca.sh [<play-dir>]

`genera-marca.py` writes the vector drawables into `app/src/main/res` and the SVGs for the bitmaps; `genera-marca.sh` runs
it and converts those SVGs into the fallback mipmaps (lossless WebP) and, if a directory is given, into the Google Play
assets. It needs python3 and ImageMagick. The upstream resource names are kept wherever the themes and the code already
used them (`linphone_splashscreen`, `linphone_notification`…), to touch as few upstream files as possible.

| Resource | What it is |
|---|---|
| `drawable/ic_launcher_foreground.xml` | Foreground of the adaptive icon **and** its monochrome layer (Android 13+ themed icons): the lime symbol (the monochrome layer only uses the alpha) |
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | Adaptive icon: background `@color/ic_launcher_background` (= `#1B1725`), foreground and monochrome |
| `mipmap-*/ic_launcher{,_round}.webp` | Launcher fallback (48 dp: 38 dp square and 44 dp circle) |
| `drawable/linphone_splashscreen.xml` | Splash screen icon (288 dp), on `windowSplashScreenBackground` = `#1B1725` in light and dark themes |
| `drawable/linphone_splashscreen_branding.xml` | Android 12+ splash screen branding (200×80 dp): white word with the lime symbol |
| `drawable/linphone_notification.xml` | Small notification icon: silhouette of the symbol, 20 of 24 dp high |
| `drawable/mundosms_logo.xml` | The logo: word in `#1B1725` and lime symbol, 20 dp high (side menu, welcome screen, assistant on tablets) |
| `drawable-night/mundosms_logo.xml` | The same in the dark theme: white word and lime symbol |
| `drawable{,-night}/mundosms_simbolo.xml` | The coloured symbol (lime in both themes) for square spots where the word cannot be read (widget header) |

Google Play assets (outside the repository, with `<play-dir>`): `icono-512.png` (the favicon full size and not rounded,
32-bit PNG: Play rounds it) and `destacada-1024x500.png` (`#1B1725` background, white word with the lime symbol centred,
24-bit PNG: Play does not accept alpha in the feature graphic).

## Geometry

Adaptive icon: 108 dp layers, of which the launcher shows the central 72 and never crops the 66 dp circle. The favicon is
centred at scale 0.8: the symbol is 42×50 dp (Google asks for a 48 to 66 dp logo) and its farthest point from the centre,
the edge of the small circle, is at 29 dp, inside the 33 dp safe zone. The splash screen icon is the same composition at
288 dp: on Android 12+, without an icon background, the visible circle is 192 dp, i.e. the 72 of 108.

## Checking

`vector-a-svg.py` converts a VectorDrawable (the XML that goes into the app, not the generator's intent) into SVG to look
at it:

    python3 -I third_party/marca/vector-a-svg.py app/src/main/res/drawable/mundosms_logo.xml 600 '#FFFFFF' > /tmp/logo.svg
    convert -background none /tmp/logo.svg /tmp/logo.png
