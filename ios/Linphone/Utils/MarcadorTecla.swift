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

import UIKit
import AudioToolbox

/// MundoPhone: lo que hace cada tecla del marcador al pulsarla, como la app Teléfono: una vibración
/// corta siempre (respeta «Respuesta háptica del sistema») y, fuera de llamada, el tono DTMF del sistema (respeta el
/// interruptor de silencio). En llamada el tono no se añade: la tecla ya va al otro lado con sendDtmf.
enum MarcadorTecla {
	private static let vibracion = UIImpactFeedbackGenerator(style: .light)

	/// En el hilo principal (las acciones de los botones)
	static func pulsar(_ tecla: String, enLlamada: Bool) {
		vibracion.impactOccurred()
		vibracion.prepare()
		guard !enLlamada, let sonido = sonidoDtmf(tecla) else { return }
		AudioServicesPlaySystemSound(sonido)
	}

	/// Los sonidos DTMF del sistema: 1200-1209 son las teclas 0-9, 1210 el asterisco y 1211 la almohadilla. El «+»
	/// (pulsación larga del 0) no suena.
	static func sonidoDtmf(_ tecla: String) -> SystemSoundID? {
		switch tecla {
		case "0", "1", "2", "3", "4", "5", "6", "7", "8", "9": return SystemSoundID(1200 + Int(tecla)!)
		case "*": return 1210
		case "#": return 1211
		default: return nil
		}
	}
}
