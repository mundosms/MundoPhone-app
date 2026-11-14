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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import SwiftUI

/**
 * MundoPhone: la línea de estado de un compañero de la lista del borde («En llamada con Ana · 3:12»), en la fila de Equipo
 * y en su ficha. Con una llamada contestada, el contador avanza cada segundo; si no, no se redibuja sola.
 *
 * En la ficha (`detalle`), sin línea de Equipo pinta la presencia de siempre (`lastPresenceInfo`). Decide esta vista y no
 * la que la contiene porque esta observa el modelo: la ficha del historial y la de la conversación no lo hacen, y la
 * línea puede llegar cuando ya están abiertas.
 */
struct TeamStatusText: View {
	@ObservedObject var contactAvatarModel: ContactAvatarModel

	/** En la ficha (centrada y más fina) o en la fila de la lista */
	var detalle = false

	var body: some View {
		if let linea = contactAvatarModel.teamLinea {
			if linea.desde != nil {
				TimelineView(.periodic(from: .now, by: 1)) { contexto in
					texto(linea, contexto.date)
				}
			} else {
				texto(linea, Date())
			}
		} else if detalle {
			Text(contactAvatarModel.lastPresenceInfo)
				.foregroundStyle(contactAvatarModel.lastPresenceInfo == "Online" ? Color.greenSuccess500 : Color.orangeWarning600)
				.multilineTextAlignment(.center)
				.default_text_style_300(styleSize: 12)
				.frame(maxWidth: .infinity)
		}
	}

	@ViewBuilder
	private func texto(_ linea: TeamRls.Linea, _ fecha: Date) -> some View {
		let cadena = TeamRls.texto(linea, con: contactAvatarModel.teamCon, ahora: Int64(fecha.timeIntervalSince1970)) {
			NSLocalizedString($0, tableName: nil, bundle: .main, value: TeamRls.textosEs[$0] ?? $0, comment: "")
		}
		// El color antes que el estilo: el estilo trae su gris y el modificador más cercano al texto es el que manda
		if detalle {
			Text(cadena)
				.foregroundStyle(TeamStatusText.color(linea))
				.multilineTextAlignment(.center)
				.default_text_style_300(styleSize: 12)
				.frame(maxWidth: .infinity)
		} else {
			Text(cadena)
				.foregroundStyle(TeamStatusText.color(linea))
				.lineLimit(1)
				.default_text_style(styleSize: 13)
				.frame(maxWidth: .infinity, alignment: .leading)
		}
	}

	static func color(_ linea: TeamRls.Linea) -> Color {
		switch TeamRls.tono(linea.situacion) {
		case .verde: return Color.greenSuccess500
		case .naranja: return Color.orangeWarning600
		case .rojo: return Color.redDanger500
		}
	}
}
