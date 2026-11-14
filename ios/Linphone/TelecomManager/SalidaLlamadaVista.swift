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

import SwiftUI

/// MundoPhone: lo que enseña SalidaLlamada encima de cualquier pantalla: la pregunta «MundoPhone o SIM» (con
/// «Recordar»), el número que sale por la SIM y espera el toque, y el ofrecimiento de ser la app de llamadas.
struct SalidaLlamadaCapa: View {
	@ObservedObject var salida = SalidaLlamada.shared

	var body: some View {
		ZStack {
			if let peticion = salida.peticion {
				SalidaLlamadaDialogo(peticion: peticion)
					.id(peticion.id)
					.background(.black.opacity(0.65))
					.onTapGesture {
						salida.cancelar()
					}
			} else if salida.mostrarOferta {
				PopupView(
					isShowPopup: .constant(true),
					title: Text("mundosms_contactos_oferta_titulo"),
					content: Text("mundosms_contactos_oferta_texto"),
					titleFirstButton: Text("mundosms_contactos_oferta_ahora_no"),
					actionFirstButton: { salida.cerrarOferta(abrirAjustes: false) },
					titleSecondButton: Text("mundosms_contactos_oferta_abrir"),
					actionSecondButton: { salida.cerrarOferta(abrirAjustes: true) }
				)
				.background(.black.opacity(0.65))
			}
		}
	}
}

/// Como PopupView, con las dos casillas de Android («Recordar para este número» / «para todos», excluyentes)
struct SalidaLlamadaDialogo: View {
	let peticion: SalidaLlamada.Peticion
	@State private var recordarNumero = false
	@State private var recordarTodos = false

	var body: some View {
		GeometryReader { geometry in
			VStack(alignment: .leading) {
				Text(String(format: String(localized: peticion.soloSim ? "mundosms_contactos_sim_titulo" : "mundosms_contactos_titulo"),
							peticion.numero))
					.default_text_style_800(styleSize: 16)
					.frame(alignment: .leading)
					.padding(.bottom, 2)

				Text(peticion.soloSim ? "mundosms_contactos_sim_texto" : "mundosms_contactos_texto")
					.tint(Color.grayMain2c600)
					.default_text_style(styleSize: 15)
					.padding(.bottom, peticion.soloSim ? 20 : 10)

				if !peticion.soloSim {
					Toggle("mundosms_contactos_recordar_numero", isOn: Binding(
						get: { recordarNumero },
						set: { recordarNumero = $0; if $0 { recordarTodos = false } }
					))
					.default_text_style_700(styleSize: 15)
					.padding(.bottom, 6)

					Toggle("mundosms_contactos_recordar_todos", isOn: Binding(
						get: { recordarTodos },
						set: { recordarTodos = $0; if $0 { recordarNumero = false } }
					))
					.default_text_style_700(styleSize: 15)
					.padding(.bottom, 20)
				}

				// El principal va relleno: MundoPhone en la pregunta (como en Android) y la línea móvil cuando el número ya
				// está decidido por la SIM
				let sim = {
					SalidaLlamada.shared.elegirSim(peticion, recordarTodos: recordarTodos, recordarNumero: recordarNumero)
				}
				let mundoPhone = {
					SalidaLlamada.shared.elegirMundoPhone(peticion, recordarTodos: recordarTodos, recordarNumero: recordarNumero)
				}
				if peticion.soloSim {
					if peticion.conMundoPhone {
						boton("mundosms_contactos_boton_mundophone_esta_vez", relleno: false, accion: mundoPhone)
					}
					boton("mundosms_contactos_boton_linea_movil", relleno: true, accion: sim)
				} else {
					boton("mundosms_contactos_boton_sim", relleno: false, accion: sim)
					boton("mundosms_contactos_boton_mundophone", relleno: true, accion: mundoPhone)
				}
			}
			.padding(.horizontal, 20)
			.padding(.vertical, 20)
			.background(.white)
			.cornerRadius(20)
			.padding(.horizontal)
			.frame(maxHeight: .infinity)
			.shadow(color: Color.orangeMain500, radius: 0, x: 0, y: 2)
			.frame(maxWidth: SharedMainViewModel.shared.maxWidth)
			.position(x: geometry.size.width / 2, y: geometry.size.height / 2)
			// Que tocar dentro del cuadro no lo cierre (el fondo cancela)
			.onTapGesture {}
		}
	}

	@ViewBuilder
	private func boton(_ clave: LocalizedStringKey, relleno: Bool, accion: @escaping () -> Void) -> some View {
		if relleno {
			Button(action: accion, label: {
				Text(clave)
					.default_text_style_white_600(styleSize: 20)
					.frame(height: 35)
					.frame(maxWidth: .infinity)
			})
			.padding(.horizontal, 20)
			.padding(.vertical, 10)
			.background(Color.orangeMain500)
			.cornerRadius(60)
		} else {
			Button(action: accion, label: {
				Text(clave)
					.default_text_style_orange_600(styleSize: 20)
					.frame(height: 35)
					.frame(maxWidth: .infinity)
			})
			.padding(.horizontal, 20)
			.padding(.vertical, 10)
			.cornerRadius(60)
			.overlay(
				RoundedRectangle(cornerRadius: 60)
					.inset(by: 0.5)
					.stroke(Color.orangeMain500, lineWidth: 1)
			)
			.padding(.bottom, 10)
		}
	}
}
