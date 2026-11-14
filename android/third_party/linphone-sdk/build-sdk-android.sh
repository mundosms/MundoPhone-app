#!/bin/bash
# Compila linphone-sdk para Android en la imagen fijada (docker/android-r30.Dockerfile), como el CI de Belledonne:
#   cmake --preset=android-sdk -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DLINPHONESDK_ANDROID_ARCHS=…
#
# Uso: build-sdk-android.sh <fuentes> <salida> [archs]
#   <fuentes>  clon de linphone-sdk con submódulos (p. ej. linphone-sdk-5.5.38)
#   <salida>   carpeta de compilación (p. ej. ~/.cache/mundophone-sdk-build/build-5.5.38); se crea si no existe
#   [archs]    por defecto "arm64,armv7", las ABI de la app 6.2
#
# Deja el AAR en <salida>/linphone-sdk/bin/outputs/aar/ y el repositorio maven en <salida>/maven_repository, que es lo
# que lee la app 6.2 con -PLinphoneSdkBuildDir=<salida>.
# Se niega a arrancar con menos de 60 GB libres. Nunca en tubería: el código de salida es el de la compilación.
set -euo pipefail

SRC=$(realpath "${1:?fuentes}")
OUT=${2:?salida}
ARCHS=${3:-arm64,armv7}
IMG=mundophone/linphone-sdk-android:r30-20261022
CCACHE=${SDK_CCACHE:-$HOME/.cache/mundophone-sdk-build/ccache}
# Por defecto la mitad de los núcleos y prioridad baja, por si la máquina es compartida. SDK_CPUS=16 para usarla entera cuando esté libre.
CPUS=${SDK_CPUS:-8}
# Versión que publica el SDK (coordenada maven y linphone_sdk_version). Vacía = la de git (la oficial, p. ej. 5.5.35);
# para un SDK con parches, una propia (p. ej. 5.5.35-mundosms.1) para que nunca se confunda con el AAR de Belledonne.
VERSION_OPT=${SDK_VERSION:+-DLINPHONESDK_VERSION=$SDK_VERSION}

libre_gb=$(df --output=avail -BG / | tail -1 | tr -dc '0-9')
if [ "$libre_gb" -lt 60 ]; then
  echo "Solo quedan ${libre_gb} GB libres (mínimo 60): no se compila" >&2
  exit 2
fi

mkdir -p "$OUT" "$CCACHE"
OUT=$(realpath "$OUT")
echo "Fuentes $SRC ($(git -C "$SRC" describe --tags --always)), salida $OUT, archs $ARCHS, imagen $IMG"
inicio=$(date +%s)

set +e
nice -n 19 docker run --rm --cpus "$CPUS" -v "$SRC:/src" -v "$OUT:/build" -v "$CCACHE:/ccache" -e CCACHE_DIR=/ccache -e CCACHE_MAXSIZE=8G \
  -w /src "$IMG" bash -c '
    git config --global --add safe.directory "*"
    cmake --preset=android-sdk -B /build -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
      -DLINPHONESDK_ANDROID_ARCHS="'"$ARCHS"'" '"$VERSION_OPT"' &&
    cmake --build /build --parallel '"$CPUS"'
    r=$?
    chown -R '"$(id -u):$(id -g)"' /build /ccache
    exit $r'
r=$?
set -e

echo "Tiempo: $(( ($(date +%s) - inicio) / 60 )) min; código $r"
du -sh "$OUT" 2>/dev/null || true
ls -la "$OUT"/linphone-sdk/bin/outputs/aar/ 2>/dev/null || true
exit $r
