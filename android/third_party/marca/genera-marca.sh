#!/bin/bash
# Regenera la marca de MundoSMS de la app: vectoriales en app/src/main/res (genera-marca.py), mipmaps de reserva del
# lanzador en WebP sin pérdida y, si se da <dir-play>, los recursos de Google Play (icono 512×512 PNG de 32 bits e
# imagen destacada 1024×500 PNG de 24 bits: Play no admite alfa en esta).
#
# Uso: third_party/marca/genera-marca.sh [<dir-play>]
# Necesita python3 e ImageMagick 7 (convert). Se rasteriza a 8× y se reduce: los bordes salen suavizados igual en
# todas las densidades.
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
RES="$DIR/../../app/src/main/res"
PLAY=${1:-}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

python3 -I "$DIR/genera-marca.py" "$RES" "$TMP"

# <svg> <px> <salida> [formato]
rasteriza() {
  local svg=$1 px=$2 sal=$3
  local ancho
  ancho=$(sed -n 's/.*<svg[^>]* width="\([0-9.]*\)".*/\1/p' "$svg" | head -1)
  convert -background none -density "$(awk -v p="$px" -v w="$ancho" 'BEGIN { print 96 * 8 * p / w }')" "$svg" \
    -resize "${px}x" "$sal"
}

for par in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  dens=${par%%:*}
  px=${par##*:}
  for nombre in ic_launcher ic_launcher_round; do
    rasteriza "$TMP/mipmap_$nombre.svg" "$px" "$TMP/$nombre-$dens.png"
    convert "$TMP/$nombre-$dens.png" -define webp:lossless=true "$RES/mipmap-$dens/$nombre.webp"
  done
done
echo "mipmaps de reserva: $(ls "$RES"/mipmap-*/ic_launcher*.webp | wc -l) WebP"

if [ -n "$PLAY" ]; then
  mkdir -p "$PLAY"
  rasteriza "$TMP/play_icono_512.svg" 512 "PNG32:$PLAY/icono-512.png"
  rasteriza "$TMP/play_destacada_1024x500.svg" 1024 "$TMP/destacada.png"
  convert "$TMP/destacada.png" -background '#1B1725' -alpha remove -alpha off "PNG24:$PLAY/destacada-1024x500.png"
  identify "$PLAY/icono-512.png" "$PLAY/destacada-1024x500.png"
fi
