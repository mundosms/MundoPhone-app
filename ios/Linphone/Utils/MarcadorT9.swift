/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone (fork of linphone-iphone).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import Foundation

/// MundoPhone: buscar en el marcador por las letras del teclado, como los teléfonos de siempre: 7272
/// saca «Papa» y «Carlos SARAworld» además de los números que contienen 7272. Casa el INICIO de cualquier palabra del
/// nombre, sin acentos ni mayúsculas (la ñ va en el 6, con la n). Solo Foundation: se prueba aparte con swiftc.
enum MarcadorT9 {
	/// ¿Es una búsqueda del marcador por teclas? Solo cifras 0-9 (con *, # o + no: eso es marcar).
	static func esSoloCifras(_ texto: String) -> Bool {
		!texto.isEmpty && texto.allSatisfy { $0.isASCII && $0.isNumber }
	}

	/// La cifra de la tecla de una letra o cifra ya sin acento y en minúscula; nil para lo demás
	static func tecla(_ caracter: Character) -> Character? {
		switch caracter {
		case "0"..."9": return caracter
		case "a", "b", "c": return "2"
		case "d", "e", "f": return "3"
		case "g", "h", "i": return "4"
		case "j", "k", "l": return "5"
		case "m", "n", "o": return "6"
		case "p", "q", "r", "s": return "7"
		case "t", "u", "v": return "8"
		case "w", "x", "y", "z": return "9"
		default: return nil
		}
	}

	/// Las palabras del nombre en cifras de teclado («Carlos SARAworld» → ["227567", "727296753"])
	static func palabrasEnCifras(_ nombre: String) -> [String] {
		let plano = nombre.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "es_ES"))
			.lowercased()
		var palabras: [String] = []
		var actual = ""
		for caracter in plano {
			if let cifra = tecla(caracter) {
				actual.append(cifra)
			} else if !actual.isEmpty {
				palabras.append(actual)
				actual = ""
			}
		}
		if !actual.isEmpty {
			palabras.append(actual)
		}
		return palabras
	}

	/// ¿Casa el nombre con lo tecleado? Alguna palabra del nombre empieza por esas cifras.
	static func casa(nombre: String, cifras: String) -> Bool {
		guard esSoloCifras(cifras), !nombre.isEmpty else { return false }
		return palabrasEnCifras(nombre).contains { $0.hasPrefix(cifras) }
	}
}
