#!/usr/bin/env python3
"""Pasa un VectorDrawable de Android a SVG para mirarlo (convert lo rasteriza): así se comprueba el XML que va en la
app, no la intención del generador. Entiende lo que usa la marca: <vector> con viewport, <group> con escala, pivote y
traslación (Android aplica escala y luego traslación), y <path> con fillColor (#RGB, #RRGGBB o #AARRGGBB), fillAlpha,
fillType y pathData.

Uso: vector-a-svg.py <vector.xml> [ancho_px] [color_fondo]   (el SVG sale por la salida estándar)
     Sin ancho, el del vector en dp. Con color de fondo, un rectángulo de ese color detrás.
"""
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"


def color(c):
    c = c.strip()
    if len(c) == 4:  # #RGB
        return "#" + "".join(x * 2 for x in c[1:]), 1.0
    if len(c) == 9:  # #AARRGGBB
        return "#" + c[3:], int(c[1:3], 16) / 255
    return c, 1.0


def num(v, defecto=0.0):
    return float(v.replace("dp", "")) if v is not None else defecto


def nodo(e, sangria):
    salida = []
    if e.tag == "group":
        sx, sy = num(e.get(A + "scaleX"), 1.0), num(e.get(A + "scaleY"), 1.0)
        px, py = num(e.get(A + "pivotX")), num(e.get(A + "pivotY"))
        tx, ty = num(e.get(A + "translateX")), num(e.get(A + "translateY"))
        rot = num(e.get(A + "rotation"))
        t = (f"translate({tx + px} {ty + py}) rotate({rot}) scale({sx} {sy}) translate({-px} {-py})")
        salida.append(f'{sangria}<g transform="{t}">')
        for h in e:
            salida += nodo(h, sangria + "  ")
        salida.append(f"{sangria}</g>")
    elif e.tag == "path":
        fill, alfa = color(e.get(A + "fillColor", "#00000000"))
        alfa *= num(e.get(A + "fillAlpha"), 1.0)
        regla = "evenodd" if e.get(A + "fillType") == "evenOdd" else "nonzero"
        salida.append(f'{sangria}<path d="{e.get(A + "pathData")}" fill="{fill}" fill-opacity="{alfa:g}" '
                      f'fill-rule="{regla}"/>')
    elif e.tag == "clip-path":
        sys.exit("clip-path sin soporte en vector-a-svg.py")
    return salida


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    raiz = ET.parse(sys.argv[1]).getroot()
    assert raiz.tag == "vector", f"no es un <vector>: {raiz.tag}"
    vw, vh = num(raiz.get(A + "viewportWidth")), num(raiz.get(A + "viewportHeight"))
    w, h = num(raiz.get(A + "width")), num(raiz.get(A + "height"))
    if len(sys.argv) > 2:
        ancho = float(sys.argv[2])
        w, h = ancho, ancho * h / w
    cuerpo = []
    if len(sys.argv) > 3:
        cuerpo.append(f'  <rect width="{vw}" height="{vh}" fill="{sys.argv[3]}"/>')
    for hijo in raiz:
        cuerpo += nodo(hijo, "  ")
    print(f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:g}" height="{h:g}" viewBox="0 0 {vw:g} {vh:g}" '
          f'preserveAspectRatio="none">')
    print("\n".join(cuerpo))
    print("</svg>")


if __name__ == "__main__":
    main()
