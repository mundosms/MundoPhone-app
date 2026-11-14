#!/bin/bash
# Baja nuestro linphone-sdk (la release «sdk-<versión>» del repositorio), comprueba su sha256 contra SHA256SUMS y lo deja
# como maven_repository en <destino>. Es lo que lee la app con -PLinphoneSdkBuildDir=<destino> (settings.gradle.kts).
#
# Uso: obtener-sdk.sh <destino> [--con-simbolos]
#   La versión es la de gradle/libs.versions.toml (linphone = "…"). El repositorio es GITHUB_REPOSITORY (el CI lo pone)
#   o, si no está, mundosms/MundoPhone-app, que es público: no hace falta token. Si hay GH_TOKEN o GITHUB_TOKEN se usa
#   (nunca se imprime), y hace falta para un repositorio privado.
#   --con-simbolos baja también linphone-sdk-android-<versión>-libs-debug.zip (las .so sin recortar), que necesita la
#   firma de una versión para los símbolos nativos de Play.
#
# Falla cerrado: si el fichero no está en SHA256SUMS, si la suma no cuadra o si no se puede bajar, sale con error y la
# app NO se compila (con el SDK oficial de Belledonne en su lugar se compilaría «bien» sin nuestros parches).
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
DEST=${1:?destino}
CON_SIMBOLOS=${2:-}
VERSION=$(sed -n 's/^linphone = "\(.*\)"$/\1/p' "$DIR/../../gradle/libs.versions.toml")
REPO=${GITHUB_REPOSITORY:-mundosms/MundoPhone-app}
TOKEN=${GH_TOKEN:-${GITHUB_TOKEN:-}}
# Con token, cabecera de autorización; sin él (repositorio público), ninguna
AUTH=()
[ -z "$TOKEN" ] || AUTH=(-H "Authorization: Bearer $TOKEN")

[ -n "$VERSION" ] || { echo "No encuentro la versión de linphone en gradle/libs.versions.toml" >&2; exit 2; }
case "$CON_SIMBOLOS" in "" | --con-simbolos) ;; *) echo "Opción desconocida: $CON_SIMBOLOS" >&2; exit 2 ;; esac
mkdir -p "$DEST"

# Deja <destino>/<fichero> bajado de la release y con la suma de SHA256SUMS
bajar() {
  local asset=$1 expected release asset_id
  expected=$(awk -v f="$asset" '$2 == f { print $1 }' "$DIR/SHA256SUMS")
  [ -n "$expected" ] || { echo "$asset no está en $DIR/SHA256SUMS" >&2; exit 2; }

  if [ -f "$DEST/$asset" ] && echo "$expected  $DEST/$asset" | sha256sum -c --status; then
    echo "$asset ya descargado y comprobado"
    return
  fi
  release=$(curl -fsSL "${AUTH[@]}" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$REPO/releases/tags/sdk-$VERSION") ||
    { echo "No se puede leer la release sdk-$VERSION de $REPO" >&2; exit 2; }
  asset_id=$(printf '%s' "$release" | tr -d '\n ' | sed 's/{"url"/\n{"url"/g' | grep "\"name\":\"$asset\"" |
    sed -n 's/^{"url":"[^"]*","id":\([0-9]*\),.*/\1/p' | head -1)
  [ -n "$asset_id" ] || { echo "La release sdk-$VERSION no tiene $asset" >&2; exit 2; }
  curl -fsSL "${AUTH[@]}" -H "Accept: application/octet-stream" \
    "https://api.github.com/repos/$REPO/releases/assets/$asset_id" -o "$DEST/$asset.part"
  mv "$DEST/$asset.part" "$DEST/$asset"
  echo "$expected  $DEST/$asset" | sha256sum -c --status || { echo "sha256 de $asset no cuadra" >&2; rm -f "$DEST/$asset"; exit 3; }
  echo "$asset descargado; sha256 comprobado"
}

ASSET="linphone-sdk-android-$VERSION-maven.tar"
bajar "$ASSET"
rm -rf "$DEST/maven_repository"
tar -xf "$DEST/$ASSET" -C "$DEST"
test -f "$DEST/maven_repository/org/linphone/linphone-sdk-android/maven-metadata.xml"
echo "maven_repository listo en $DEST (usar -PLinphoneSdkBuildDir=$DEST)"

if [ "$CON_SIMBOLOS" = --con-simbolos ]; then
  bajar "linphone-sdk-android-$VERSION-libs-debug.zip"
fi
