/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone, a fork of linphone-android.
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
package org.linphone.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import org.linphone.core.tools.Log

/**
 * MundoPhone: la IP del borde en un fichero de hosts que belle-sip consulta antes que el DNS ([net] dns_user_hosts_file,
 * parche 0011 del SDK). belle-sip no tiene caché de DNS y push.mundosms.es es un CNAME con TTL de 300 s, así que en casi
 * todo arranque en frío por push el REGISTER esperaba ~150 ms a la consulta (registros del A52s, 22/10/2026).
 *
 * El fichero lo escribe la app en segundo plano en cada arranque con lo que da el resolvedor de Android (que sí tiene
 * caché). belle-sip lo relee en cada resolución, así que si la IP cambia vale desde el siguiente intento; y si no hay red
 * para resolver, se queda el anterior. Solo se le pasa a belle-sip si ya existe: el primer arranque usa el DNS normal.
 */
object MundoPhoneDns {
    private const val TAG = "[MundoPhone DNS]"
    private const val HOST = "push.mundosms.es"
    private const val FILE_NAME = "mundosms_hosts"

    /** Antes de crear el Core, con la configuración ya cargada. */
    fun prepare(context: Context, config: Config) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists() && file.length() > 0) {
            config.setString("net", "dns_user_hosts_file", file.absolutePath)
        } else {
            config.setString("net", "dns_user_hosts_file", "")
        }
        Thread({ refresh(file) }, "MundoPhone DNS").start()
    }

    private fun refresh(file: File) {
        try {
            val start = SystemClock.elapsedRealtime()
            val ips = InetAddress.getAllByName(HOST).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            if (ips.isEmpty()) {
                Log.w("$TAG No IPv4 address for [$HOST], keeping the current hosts file")
                return
            }
            val content = ips.joinToString("") { "$it $HOST\n" }
            if (file.exists() && file.readText() == content) return
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            Log.i(
                "$TAG [$HOST] is [${ips.joinToString()}] (resolved in [${SystemClock.elapsedRealtime() - start}] ms), hosts file updated"
            )
        } catch (e: Exception) {
            Log.w("$TAG Couldn't resolve [$HOST], keeping the current hosts file: $e")
        }
    }
}
