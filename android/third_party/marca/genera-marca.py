#!/usr/bin/env python3
"""Genera la marca de MundoSMS de 2025 (la de la web, 25/10/2026) para la app: vectoriales de Android y los SVG de los
que salen los mapas de bits (mipmaps de reserva y recursos de Google Play).

Fuentes, tal cual las publica la web (fuente/):
  Blanco_Fondo_Negro.svg  el favicon: el símbolo (dos círculos unidos) en blanco sobre un cuadrado #1B1725, 100×100
  Principal-Blanco.svg    la palabra «mundosms» en blanco, 482×73; la «o» es el símbolo (el único path evenodd)

Uso: genera-marca.py <app/src/main/res> <dir-svg>
  Escribe los vectoriales directamente en res/ y deja en <dir-svg> los SVG que genera-marca.sh pasa a PNG/WebP.
  Solo biblioteca estándar.
"""
import os
import re
import sys

AQUI = os.path.dirname(os.path.abspath(__file__))
FAVICON = os.path.join(AQUI, "fuente", "Blanco_Fondo_Negro.svg")
PALABRA = os.path.join(AQUI, "fuente", "Principal-Blanco.svg")

# Colores de la marca (los mismos que values/colors.xml desde la fase B5)
OSCURO, LIMA, BLANCO = "#1B1725", "#A1F152", "#FFFFFF"  # el principal (#00647C) ya no va en la marca: 13/11

# Icono adaptativo: capas de 108 dp, de las que el lanzador enseña las 72 centrales y nunca recorta el círculo de 66.
# El favicon (100 unidades) se coloca centrado a escala 0,8: el símbolo mide 42×50 dp (Google pide un logo de 48 a
# 66 dp) y su punto más alejado del centro (el borde del círculo pequeño) queda a 29 dp, dentro de los 33 de la zona
# segura. El arranque usa la misma composición en 288 dp (Android 12+: el círculo visible son 192 dp = 72 de 108).
ESCALA_ICONO = 0.8
DESPLAZA_ICONO = 54 - 50 * ESCALA_ICONO  # el centro del favicon (50,50) al centro de la capa (54,54)


def lee_paths(svg):
    """[(d, evenodd)] de cada <path> del SVG, en orden."""
    texto = open(svg, encoding="utf-8").read()
    salida = []
    for attrs in re.findall(r"<path\b([^>]*?)/?>", texto):
        d = re.search(r'\sd="([^"]+)"', " " + attrs).group(1)
        salida.append((d, 'fill-rule="evenodd"' in attrs))
    return salida


def viewbox(svg):
    texto = open(svg, encoding="utf-8").read()
    x, y, w, h = (float(v) for v in re.search(r'viewBox="([^"]+)"', texto).group(1).split())
    assert (x, y) == (0, 0), "el generador supone viewBox con origen 0,0"
    return w, h


def caja(d):
    """Caja (x0, y0, x1, y1) de un path con órdenes absolutas M/L/C/H/V/Z (las que usan las fuentes). Incluye los puntos
    de control, que en estas fuentes caen sobre la curva en los extremos (tangentes horizontales y verticales)."""
    assert not re.search(r"[a-y]", d.replace("e-", "")), "el path usa órdenes relativas: ampliar caja()"
    xs, ys = [], []
    x = y = 0.0
    for orden, args in re.findall(r"([MLCHVZ])([^MLCHVZ]*)", d):
        nums = [float(n) for n in re.findall(r"-?\d*\.?\d+(?:e-?\d+)?", args)]
        if orden in "MLC":
            for i in range(0, len(nums), 2):
                x, y = nums[i], nums[i + 1]
                xs.append(x)
                ys.append(y)
        elif orden == "H":
            for x in nums:
                xs.append(x)
                ys.append(y)
        elif orden == "V":
            for y in nums:
                xs.append(x)
                ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


def argb(color):
    return "#FF" + color[1:].upper()


def g(numero):
    return f"{round(numero, 5):g}"


# ---------------------------------------------------------------- vectoriales de Android

def path_xml(d, color, evenodd, sangria):
    tipo = f'\n{sangria}    android:fillType="evenOdd"' if evenodd else ""
    return (f'{sangria}<path\n{sangria}    android:fillColor="{argb(color)}"{tipo}\n'
            f'{sangria}    android:pathData="{d}" />\n')


def vector_xml(comentario, ancho_dp, alto_dp, vw, vh, paths, escala=1.0, tx=0.0, ty=0.0):
    """paths: [(d, color, evenodd)]. Un <group> con escala y traslación (Android aplica primero la escala)."""
    cab = (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comentario}\n     Generado por third_party/marca/'
           f'genera-marca.py: no editar a mano. -->\n'
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           f'    android:width="{g(ancho_dp)}dp"\n    android:height="{g(alto_dp)}dp"\n'
           f'    android:viewportWidth="{g(vw)}"\n    android:viewportHeight="{g(vh)}">\n')
    if escala == 1.0 and tx == 0.0 and ty == 0.0:
        return cab + "".join(path_xml(d, c, e, "    ") for d, c, e in paths) + "</vector>\n"
    grupo = (f'    <group\n        android:scaleX="{g(escala)}"\n        android:scaleY="{g(escala)}"\n'
             f'        android:translateX="{g(tx)}"\n        android:translateY="{g(ty)}">\n')
    return cab + grupo + "".join(path_xml(d, c, e, "        ") for d, c, e in paths) + "    </group>\n</vector>\n"


def simbolo_cuadrado(lado_dp, alto_simbolo_dp, color, simbolo, caja_simbolo, comentario):
    """El símbolo solo, centrado por su caja en un cuadrado de lado_dp, con alto_simbolo_dp de alto."""
    x0, y0, x1, y1 = caja_simbolo
    lado_vp = (y1 - y0) * lado_dp / alto_simbolo_dp  # viewport en unidades del favicon
    tx = lado_vp / 2 - (x0 + x1) / 2
    ty = lado_vp / 2 - (y0 + y1) / 2
    return vector_xml(comentario, lado_dp, lado_dp, lado_vp, lado_vp, [(simbolo, color, True)], 1.0, tx, ty)


# ---------------------------------------------------------------- SVG para los mapas de bits

def svg(ancho, alto, cuerpo):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{g(ancho)}" height="{g(alto)}" '
            f'viewBox="0 0 {g(ancho)} {g(alto)}">\n{cuerpo}</svg>\n')


def svg_path(d, color, evenodd, escala=1.0, tx=0.0, ty=0.0):
    regla = ' fill-rule="evenodd"' if evenodd else ""
    return (f'<g transform="translate({g(tx)} {g(ty)}) scale({g(escala)})">'
            f'<path d="{d}" fill="{color}"{regla}/></g>\n')


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    res, dir_svg = sys.argv[1], sys.argv[2]
    os.makedirs(dir_svg, exist_ok=True)

    fav = lee_paths(FAVICON)
    assert len(fav) == 1 and fav[0][1], "el favicon debería ser un solo path evenodd"
    simbolo = fav[0][0]
    caja_simbolo = caja(simbolo)

    pal = lee_paths(PALABRA)
    simbolos = [d for d, e in pal if e]
    assert len(pal) == 8 and len(simbolos) == 1, "la palabra debería tener 7 letras y la «o» (el único evenodd)"
    letras = " ".join(d for d, e in pal if not e)  # las letras no se tocan: un solo path con relleno nonZero
    o = simbolos[0]
    pw, ph = viewbox(PALABRA)

    def palabra(color_letras, color_o):
        return [(letras, color_letras, False), (o, color_o, True)]

    ficheros = {
        # Icono adaptativo (también capa monocroma: Android solo usa su alfa) y arranque
        "drawable/ic_launcher_foreground.xml": vector_xml(
            "MundoSMS: primer plano del icono adaptativo y capa monocroma (símbolo lima sobre #1B1725; la capa "
            "monocroma solo usa el alfa)",
            108, 108, 108, 108, [(simbolo, LIMA, True)], ESCALA_ICONO, DESPLAZA_ICONO, DESPLAZA_ICONO),
        "drawable/linphone_splashscreen.xml": vector_xml(
            "MundoSMS: icono de arranque, símbolo lima sobre #1B1725 (windowSplashScreenBackground); la misma "
            "composición que el icono adaptativo",
            288, 288, 108, 108, [(simbolo, LIMA, True)], ESCALA_ICONO, DESPLAZA_ICONO, DESPLAZA_ICONO),
        # Pie del arranque de Android 12+ (200×80 dp): la variante del pie de la web, para fondo oscuro
        "drawable/linphone_splashscreen_branding.xml": vector_xml(
            "MundoSMS: pie del arranque de Android 12+ (200×80 dp), palabra blanca con el símbolo lima",
            200, 80, 200, 80, palabra(BLANCO, LIMA), 160 / pw, 20, (80 - ph * 160 / pw) / 2),
        # Icono pequeño de notificación: silueta (Android solo usa el alfa), 20 de 24 dp de alto
        "drawable/linphone_notification.xml": simbolo_cuadrado(
            24, 20, BLANCO, simbolo, caja_simbolo, "MundoSMS: icono de notificación, silueta del símbolo"),
        # Logo de la app: la palabra, 20 dp de alto (lo que pide el menú lateral); en otros sitios se escala
        "drawable/mundosms_logo.xml": vector_xml(
            "MundoSMS: logo (palabra #1B1725 y símbolo lima) para el tema claro: el símbolo es lima en los dos temas, como en "
            "el icono",
            20 * pw / ph, 20, pw, ph, palabra(OSCURO, LIMA)),
        "drawable-night/mundosms_logo.xml": vector_xml(
            "MundoSMS: logo (palabra blanca y símbolo lima) para el tema oscuro",
            20 * pw / ph, 20, pw, ph, palabra(BLANCO, LIMA)),
        # El símbolo a color, para huecos cuadrados donde la palabra no se leería (cabecera del widget, 20 dp)
        "drawable/mundosms_simbolo.xml": simbolo_cuadrado(
            24, 24, LIMA, simbolo, caja_simbolo, "MundoSMS: el símbolo a color (lima, como en el oscuro), tema claro"),
        "drawable-night/mundosms_simbolo.xml": simbolo_cuadrado(
            24, 24, LIMA, simbolo, caja_simbolo, "MundoSMS: el símbolo a color (lima), tema oscuro"),
    }
    for ruta, texto in ficheros.items():
        destino = os.path.join(res, ruta)
        os.makedirs(os.path.dirname(destino), exist_ok=True)
        open(destino, "w", encoding="utf-8").write(texto)
        print(f"{ruta}: {len(texto)} bytes")

    # Mapas de bits. Reserva del lanzador (48 dp, como los que hacía Image Asset Studio): cuadrado de 38 dp con las
    # 72 dp visibles del icono adaptativo, y círculo de 44 dp.
    s38 = 38 / 72 * ESCALA_ICONO
    s44 = 44 / 72 * ESCALA_ICONO
    mapas = {
        "mipmap_ic_launcher.svg": svg(48, 48, f'<rect x="5" y="5" width="38" height="38" rx="{g(38 * 0.12)}" '
                                              f'fill="{OSCURO}"/>\n' + svg_path(simbolo, LIMA, True, s38,
                                                                              24 - 50 * s38, 24 - 50 * s38)),
        "mipmap_ic_launcher_round.svg": svg(48, 48, f'<circle cx="24" cy="24" r="22" fill="{OSCURO}"/>\n' +
                                            svg_path(simbolo, LIMA, True, s44, 24 - 50 * s44, 24 - 50 * s44)),
        # Google Play: el favicon a pantalla completa y sin redondear (Play redondea solo)
        "play_icono_512.svg": svg(100, 100, f'<rect width="100" height="100" fill="{OSCURO}"/>\n' +
                                  svg_path(simbolo, LIMA, True)),
        # Imagen destacada 1024×500: palabra blanca con el símbolo lima, centrada, 600 px de ancho
        "play_destacada_1024x500.svg": svg(1024, 500, f'<rect width="1024" height="500" fill="{OSCURO}"/>\n' +
                                           svg_path(letras, BLANCO, False, 600 / pw, 212, (500 - ph * 600 / pw) / 2) +
                                           svg_path(o, LIMA, True, 600 / pw, 212, (500 - ph * 600 / pw) / 2)),
    }
    for nombre, texto in mapas.items():
        open(os.path.join(dir_svg, nombre), "w", encoding="utf-8").write(texto)
        print(f"{nombre}: {len(texto)} bytes")


if __name__ == "__main__":
    main()
