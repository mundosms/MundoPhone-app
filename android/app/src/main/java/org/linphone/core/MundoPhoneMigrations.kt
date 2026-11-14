/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone (fork of linphone-android).
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

import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.tools.Log

/**
 * Lo propio de MundoPhone que corre al arrancar, en tres momentos:
 * - [beforeCoreStart]: sobre la configuración, ANTES de que el Core la lea (LinphoneApplication);
 * - [upstreamConfigVersion]: la versión de configuración que ven las migraciones de upstream (CoreContext);
 * - [afterCoreStart]: después de esas migraciones, y [afterProvisioning]: tras aprovisionar un QR con la app en marcha.
 *
 * Lo necesario para actualizar encima de una instalación de la serie 22 (sobre la 6.0 de upstream), junto en un solo
 * sitio.
 */
object MundoPhoneMigrations {
    private const val TAG = "[MundoPhone Migrations]"

    private val accountSection = Regex("proxy_\\d+")
    private const val PUSH_HOST = "push.mundosms.es"

    // [app]: las identidades (sip:usuario@dominio) cuyo transporte eligió el usuario en los ajustes de la cuenta. A esas
    // ya no se les pone TCP en cada arranque: TCP por defecto, pero que se pueda cambiar. Va en [app] y no en
    // [proxy_N] porque el SDK reescribe la sección de la cuenta al guardarla y se perdería.
    private const val USER_TRANSPORT_KEY = "mundosms_user_transport"
    private val identityRegex = Regex("""sips?:[^\s<>;"]+@[^\s<>;"]+""", RegexOption.IGNORE_CASE)

    // El servidor de push de antes (removeOldPushServerAuthInfosOnce)
    private const val OLD_PUSH_HOST = "pushserver02.mundosms.es"

    /**
     * La serie 22 guarda en [app] config_version su versionCode, por debajo de 600000: upstream creería que viene de la
     * 5.x y correría SOLO configurationMigration5To6 (su cadena es if/else if), que vuelve a aceptar el vídeo solo. La
     * serie 22 es un fork de la 6.0.x, así que se presenta como 6.0.9: corre la rama de la 6.2.0 y nunca la de la 6.0.4
     * (disablePushNotificationsFromThirdPartySipAccounts, que apagaría nuestro push). Una instalación nueva trae 52005
     * y sigue pasando por la 5→6, como en upstream.
     */
    fun upstreamConfigVersion(stored: Int): Int = if (isVersion22(stored)) 600009 else stored

    fun isVersion22(stored: Int): Boolean = stored in 60000..69999

    /** «sip:usuario@dominio» en minúsculas de lo que haya en reg_identity o en una dirección; vacío si no hay. */
    internal fun identityKey(value: String): String = identityRegex.find(value)?.value?.lowercase().orEmpty()

    internal fun userTransportIdentities(config: Config): Set<String> =
        config.getString("app", USER_TRANSPORT_KEY, "").orEmpty().split(' ').filter { it.isNotBlank() }.toSet()

    /** El usuario ha elegido el transporte de esta cuenta en sus ajustes: desde ahora manda el suyo. */
    fun rememberUserTransport(config: Config, identity: String) {
        val key = identityKey(identity)
        if (key.isEmpty()) return
        val identities = userTransportIdentities(config)
        if (key in identities) return
        config.setString("app", USER_TRANSPORT_KEY, (identities + key).joinToString(" "))
    }

    /** Sobre la configuración, antes de que el Core la lea: los valores van por cuenta en [proxy_N] y mandan sobre los por defecto. */
    fun beforeCoreStart(config: Config) {
        Log.i("$TAG Before Core start: ${applyBeforeCoreStart(config)}")
    }

    /**
     * El trabajo de [beforeCoreStart] sin el log (que es nativo del SDK), para probarlo en la JVM (app/src/test) con
     * una Config de mentira. Devuelve el resumen de lo cambiado que va al log.
     */
    internal fun applyBeforeCoreStart(config: Config): String {
        var forceRegister = 0
        var port = 0
        var tcp = 0
        var remotePush = 0
        var pushEnabled = 0
        val pushFixDone = config.getInt("app", "mundosms_push_allowed_fix", 0) >= 1
        val userTransport = userTransportIdentities(config)

        for (section in config.sectionsNamesList) {
            if (!accountSection.matches(section)) continue
            val userChoseTransport = identityKey(config.getString(section, "reg_identity", "").orEmpty()) in userTransport

            // El borde solo reanuda la llamada suspendida con el REGISTER que la app manda al recibir el push, y el SDK solo
            // lo manda si la cuenta tiene force_register_on_push (las creadas antes de 03/12/2025 lo tienen a 0).
            if (config.getInt(section, "force_register_on_push", 0) != 1) {
                config.setInt(section, "force_register_on_push", 1)
                forceRegister++
            }

            for (key in listOf("reg_proxy", "reg_route")) {
                val value = config.getString(section, key, "").orEmpty()
                if (!value.contains(PUSH_HOST)) continue
                // Sin puerto, el SDK consulta NAPTR/SRV que no existen antes de mandar el REGISTER; el borde escucha en 6060
                var fixed = Regex("""sip:push\.mundosms\.es(?=[;>,]|$)""").replace(value, "sip:$PUSH_HOST:6060")
                if (fixed != value) port++
                // TCP: por UDP los mensajes de llamada (~1,8-2,5 KB) salen fragmentados y las redes móviles los tiran.
                // Salvo que el usuario haya elegido otro en los ajustes de la cuenta
                if (!userChoseTransport) {
                    val transportParam = Regex(""";transport=[A-Za-z]+""", RegexOption.IGNORE_CASE)
                    val withTcp = when {
                        transportParam.containsMatchIn(fixed) -> transportParam.replace(fixed, ";transport=tcp")
                        fixed.endsWith(">") -> fixed.dropLast(1) + ";transport=tcp>"
                        else -> "$fixed;transport=tcp"
                    }
                    if (withTcp != fixed) tcp++
                    fixed = withTcp
                }
                if (fixed != value) config.setString(section, key, fixed)
            }

            // Sin los parámetros de push «remoto» de iOS en el Contact (~130 B de más que el borde no lee)
            if (config.getInt(section, "remote_push_notification_allowed", 0) != 0) {
                config.setInt(section, "remote_push_notification_allowed", 0)
                remotePush++
            }
            // Pero el SDK solo pone pn-prid en el Contact si la cuenta tiene push O push remoto (account.cpp:641-656):
            // sin esto, las cuentas con push_notification_allowed=0 se quedaban sin llamadas con la app cerrada.
            // Una vez por instalación; después manda el interruptor de los ajustes de la cuenta.
            val identity = config.getString(section, "reg_identity", "").orEmpty()
            if (!pushFixDone && identity.contains("@$PUSH_HOST") &&
                config.getInt(section, "push_notification_allowed", 0) != 1
            ) {
                config.setInt(section, "push_notification_allowed", 1)
                pushEnabled++
            }
        }
        if (!pushFixDone) config.setInt("app", "mundosms_push_allowed_fix", 1)
        // Sin filtro de dominio en Contactos (se llegó a guardar push.mundosms.es): en la 6.2 esconde los
        // contactos de teléfono, también en Transferir, y la pantalla ya no ofrece quitarlo
        val contactsFilter = if (config.getString("ui", "contacts_filter", "").isNullOrEmpty()) 0 else 1
        if (contactsFilter > 0) config.setString("ui", "contacts_filter", "")
        // Solo se escribe si algo cambió: esto corre en cada arranque en frío por push
        if (!pushFixDone || forceRegister + port + tcp + remotePush + pushEnabled + contactsFilter > 0) config.sync()
        return "force_register_on_push on [$forceRegister], port 6060 on [$port], TCP on [$tcp], remote push off on [$remotePush], push on [$pushEnabled] value(s)"
    }

    /** Después de las migraciones de upstream. Cada paso se aplica una vez o comprueba antes de cambiar nada. */
    @WorkerThread
    fun afterCoreStart(core: Core) {
        applyMediaPolicyOnce(core)
        applyCodecOrderOnce(core)
        applyNetworkPolicyOnce(core)
        applyAudioRatePolicyOnce(core)
        undoLinphoneOrgSideEffectsOnce(core)
        removeBelledonneEndpoints(core)
        removePortFromIdentities(core)
        removeOldPushServerAuthInfosOnce(core)
        removeConferenceAndLimeServersOnce(core)
    }

    /** Lo mismo que [beforeCoreStart], para las cuentas que acaba de crear un QR con la app en marcha. */
    @WorkerThread
    fun afterProvisioning(core: Core) {
        for (account in core.accountList) {
            val params = account.params
            val server = params.serverAddress
            val userChose = identityKey(params.identityAddress?.asStringUriOnly().orEmpty()) in userTransportIdentities(core.config)
            val toTcp = !userChose && server != null && server.domain == PUSH_HOST && server.transport != TransportType.Tcp
            val enablePush = !params.pushNotificationAllowed &&
                params.identityAddress?.domain in corePreferences.pushNotificationCompatibleDomains
            if (!params.remotePushNotificationAllowed && !toTcp && !enablePush) continue

            val newParams = params.clone()
            newParams.remotePushNotificationAllowed = false
            if (enablePush) newParams.pushNotificationAllowed = true
            if (toTcp) {
                val tcpServer = server!!.clone()
                tcpServer.transport = TransportType.Tcp
                newParams.serverAddress = tcpServer
            }
            account.params = newParams
            Log.w(
                "$TAG Account [${params.identityAddress?.asStringUriOnly()}] adjusted after provisioning: remote push off, push [${newParams.pushNotificationAllowed}], transport [${newParams.serverAddress?.transport}]"
            )
        }
    }

    /**
     * El vídeo no se inicia ni se acepta solo (la respuesta sale con m=video puerto 0 y el usuario lo añade con un
     * re-INVITE) y fuera speex, GSM, H265 y AV1. Acordado con el borde el 21/10/2026. Va DESPUÉS de las migraciones de
     * upstream porque su 5→6 (la de una instalación nueva) acepta el vídeo solo.
     */
    @WorkerThread
    private fun applyMediaPolicyOnce(core: Core) {
        if (core.config.getInt("app", "mundosms_media_policy", 0) >= 1) return

        val policy = core.videoActivationPolicy.clone()
        policy.automaticallyInitiate = false
        policy.automaticallyAccept = false
        core.videoActivationPolicy = policy

        for (payloadType in core.audioPayloadTypes) {
            if (payloadType.mimeType.equals("speex", true) || payloadType.mimeType.equals("GSM", true)) {
                payloadType.enable(false)
            }
        }
        for (payloadType in core.videoPayloadTypes) {
            if (payloadType.mimeType.equals("H265", true) || payloadType.mimeType.equals("AV1", true)) {
                payloadType.enable(false)
            }
        }
        core.config.setInt("app", "mundosms_media_policy", 1)
        Log.w("$TAG Media policy applied: no automatic video, speex/GSM/H265/AV1 disabled")
    }

    /**
     * Orden de códecs en NUESTRAS ofertas (salientes, re-INVITE de vídeo, retener/reanudar): Opus, PCMA, PCMU y el
     * resto como estaba. El de fábrica del SDK pone PCMU antes que PCMA, y Asterisk respeta el orden de la oferta
     * mientras la troncal del operador va en PCMA: en una medida en el borde, 10 de 15 puentes transcodificaban
     * ulaw↔alaw, y un re-INVITE de la app pasaba la llamada de PCMA a PCMU con reinicio del flujo (registros del
     * 21/10). Una vez por instalación: después el usuario puede reordenar en ajustes.
     */
    @WorkerThread
    private fun applyCodecOrderOnce(core: Core) {
        if (core.config.getInt("app", "mundosms_codec_order", 0) >= 1) return

        val prioridad = listOf("opus", "PCMA", "PCMU")
        val actuales = core.audioPayloadTypes.toList()
        val ordenados = prioridad.mapNotNull { m -> actuales.firstOrNull { it.mimeType.equals(m, true) } } +
            actuales.filter { pt -> prioridad.none { it.equals(pt.mimeType, true) } }
        core.setAudioPayloadTypes(ordenados.toTypedArray())
        core.config.setInt("app", "mundosms_codec_order", 1)
        Log.w("$TAG Codec order applied: ${ordenados.joinToString { "${it.mimeType}/${it.clockRate}" }}")
    }

    /** Sin IPv6: push.mundosms.es solo tiene IPv4, y con IPv6 belle-sip espera el AAAA en cada arranque en frío por push. */
    @WorkerThread
    private fun applyNetworkPolicyOnce(core: Core) {
        if (core.config.getInt("app", "mundosms_network_policy", 0) >= 1) return

        core.isIpv6Enabled = false
        core.config.setInt("app", "mundosms_network_policy", 1)
        Log.w("$TAG Network policy applied: IPv6 disabled")
    }

    /**
     * Sin control adaptativo de la tasa de audio (el usuario puede reactivarlo en ajustes). Con pérdida ≥10 % en el RTCP
     * del borde baja la tasa de Opus un N % cada 5 s hasta 6 kbit/s; en 4G esa pérdida es de radio y bajar la tasa no la
     * reduce. Medido en un banco de pruebas, subida Opus+FEC con 20 %: 3,49 de PESQ apagado frente a 1,97
     * encendido; con G.711 da igual. Misma clave que la serie 22: no se repite al actualizar.
     */
    @WorkerThread
    private fun applyAudioRatePolicyOnce(core: Core) {
        if (core.config.getInt("app", "mundosms_audio_rate_policy", 0) >= 1) return

        core.isAdaptiveRateControlEnabled = false
        core.config.setInt("app", "mundosms_audio_rate_policy", 1)
        Log.w("$TAG Audio rate policy applied: adaptive rate control disabled")
    }

    /**
     * Lo que la serie 22 dejó escrito al tratar nuestras cuentas como de linphone.org: el LIME
     * c25519 sin servidor, y el filtro de contactos en nuestro dominio, que en la 6.2 esconde los contactos de teléfono.
     */
    @WorkerThread
    private fun undoLinphoneOrgSideEffectsOnce(core: Core) {
        if (core.config.getInt("app", "mundosms_not_linphone_org", 0) >= 1) return

        for (account in core.accountList) {
            val params = account.params
            if (params.identityAddress?.domain != corePreferences.defaultDomain &&
                !params.limeAlgo.isNullOrEmpty() && params.limeServerUrl.isNullOrEmpty()
            ) {
                val clone = params.clone()
                clone.limeAlgo = null
                account.params = clone
                Log.w("$TAG LIME algo removed from account [${params.identityAddress?.asStringUriOnly()}]")
            }
        }
        val filter = corePreferences.contactsFilter
        if (filter.endsWith("mundosms.es")) {
            corePreferences.contactsFilter = ""
            Log.w("$TAG Contacts filter [$filter] changed back to all contacts")
        }
        core.config.setInt("app", "mundosms_not_linphone_org", 1)
    }

    /**
     * Nada va a Belledonne (D7). La configuración de quien viene de la serie 22 conserva los servidores de linphone.org
     * de entonces, y upstream rellena el de registros con files.linphone.org si lo encuentra vacío. MundoPhone (F2):
     * versiones anteriores dejaban guardada la URL de la última subida, con lo que escribió el usuario en ?d=
     * (SupportLogsUpload ya la restaura): se vuelve a la de soporte, sin escribir la vieja en el registro.
     */
    @WorkerThread
    private fun removeBelledonneEndpoints(core: Core) {
        val logsUrl = core.logCollectionUploadServerUrl.orEmpty()
        if (logsUrl.contains('?')) {
            core.logCollectionUploadServerUrl = LOGS_UPLOAD_URL
            Log.w("$TAG Logs upload server URL had the last report's parameters, set back to [$LOGS_UPLOAD_URL]")
        } else if (logsUrl.isEmpty() || logsUrl.contains("linphone.org")) {
            core.logCollectionUploadServerUrl = LOGS_UPLOAD_URL
            Log.w("$TAG Logs upload server set to [$LOGS_UPLOAD_URL]")
        }
        if (core.fileTransferServer.orEmpty().contains("linphone.org")) {
            core.fileTransferServer = ""
            Log.w("$TAG File transfer server removed (no attachments until we have our own server)")
        }
    }

    /**
     * Las cuentas dadas de alta a mano entre abril y diciembre de 2025 guardaron las factorías
     * de conferencias de voip.mundosms.es y el servidor LIME de lime.linphone.org. Con ellas la 6.2 enseña «Reuniones» y
     * los botones de grupo, y habla con Belledonne. MundoSMS no tiene ni conferencias ni cifrado de chat: se quitan, una
     * vez por instalación.
     */
    @WorkerThread
    private fun removeConferenceAndLimeServersOnce(core: Core) {
        if (core.config.getInt("app", "mundosms_conference_lime_cleanup", 0) >= 1) return

        for (account in core.accountList) {
            val params = account.params
            val conference = params.conferenceFactoryAddress != null
            val audioVideo = params.audioVideoConferenceFactoryAddress != null
            val lime = !params.limeServerUrl.isNullOrEmpty()
            if (!conference && !audioVideo && !lime) continue
            val clone = params.clone()
            if (conference) clone.conferenceFactoryAddress = null
            if (audioVideo) clone.audioVideoConferenceFactoryAddress = null
            if (lime) clone.limeServerUrl = ""
            account.params = clone
            Log.w(
                "$TAG Account [${params.identityAddress?.asStringUriOnly()}]: removed conference factory [$conference], audio/video conference factory [$audioVideo], LIME server [$lime]"
            )
        }
        core.config.setInt("app", "mundosms_conference_lime_cleanup", 1)
    }

    /**
     * El alta manual de la serie 22 llegó a meter el puerto en la identidad (sip:usuario@push.mundosms.es:6060) y
     * acababa en el From/To. Upstream lo quita solo en su migración de la 6.0.9, que no corre para quien viene de
     * versiones más antiguas. Barato: solo cambia algo si hay puerto.
     */
    @WorkerThread
    private fun removePortFromIdentities(core: Core) {
        for (account in core.accountList) {
            val params = account.params
            val identity = params.identityAddress ?: continue
            if (identity.port == 0) continue
            val clone = params.clone()
            val newIdentity = identity.clone()
            newIdentity.port = 0
            clone.identityAddress = newIdentity
            account.params = clone
            Log.w("$TAG Port removed from identity [${identity.asStringUriOnly()}] -> [${newIdentity.asStringUriOnly()}]")
        }
    }

    /**
     * Quien viene de cuando el push iba por pushserver02.mundosms.es tiene dos auth_info de la misma cuenta: la de
     * entonces y la de push.mundosms.es (visto en configuraciones reales). La vieja no se elige nunca
     * (AuthInfo::isSuitableForChallenge da 0 si el dominio no casa, auth-info.cpp del SDK 5.5.38), pero es basura. Se
     * quita con la API del Core, que reescribe las [auth_info_N]; la decisión está en [oldPushServerAuthInfos].
     */
    @WorkerThread
    private fun removeOldPushServerAuthInfosOnce(core: Core) {
        if (core.config.getInt("app", "mundosms_auth_pushserver02_once", 0) >= 1) return

        val authInfos = core.authInfoList.toList()
        val accountDomains = core.accountList.flatMap {
            listOf(it.params.identityAddress?.domain, it.params.serverAddress?.domain)
        }
        val cleanup = oldPushServerAuthInfos(
            authInfos.map { AuthInfoKey(it.username, it.domain, it.realm) },
            accountDomains
        )
        for (index in cleanup.remove) core.removeAuthInfo(authInfos[index])
        core.config.setInt("app", "mundosms_auth_pushserver02_once", 1)

        // Sin usuarios: solo cuántas
        Log.i("$TAG Old [$OLD_PUSH_HOST] auth info: removed [${cleanup.remove.size}] of [${authInfos.size}]")
        if (cleanup.keep.isNotEmpty()) {
            val why = if (cleanup.accountUsesOldHost) {
                "an account still uses [$OLD_PUSH_HOST]"
            } else {
                "no auth info with the same username for [$PUSH_HOST]"
            }
            Log.w("$TAG Old [$OLD_PUSH_HOST] auth info: [${cleanup.keep.size}] left untouched, $why")
        }
    }

    /** Lo que mira [oldPushServerAuthInfos] de una auth_info: usuario, dominio y realm. */
    internal data class AuthInfoKey(val username: String?, val domain: String?, val realm: String?)

    /** Posiciones en la lista de auth_info: las que se quitan y las viejas que se quedan. */
    internal data class AuthInfoCleanup(val remove: List<Int>, val keep: List<Int>, val accountUsesOldHost: Boolean)

    /**
     * Qué auth_info de pushserver02.mundosms.es sobran, sin nada nativo para probarlo en la JVM. De qué servidor es una
     * auth_info lo dice su dominio y, si no tiene, su realm: la de dominio push.mundosms.es con realm de pushserver02 es
     * la de ahora (el servidor puede seguir desafiando con ese realm), no una vieja. Una vieja se quita solo si hay otra
     * con el mismo usuario para push.mundosms.es que la sustituye:
     * - si la vieja tiene el dominio de pushserver02, el SDK ya no la elige para una cuenta de push.mundosms.es;
     * - si solo tiene el realm, se elegiría con un desafío de ese realm: la nueva tiene que valer para el mismo (sin
     *   realm o con el mismo).
     * Si alguna cuenta ([accountDomains]: dominio de la identidad y del servidor) sigue en pushserver02, no se quita
     * nada.
     */
    internal fun oldPushServerAuthInfos(authInfos: List<AuthInfoKey>, accountDomains: List<String?>): AuthInfoCleanup {
        fun server(info: AuthInfoKey) = host(info.domain).takeUnless { it.isNullOrEmpty() } ?: host(info.realm)

        val old = authInfos.indices.filter { server(authInfos[it]) == OLD_PUSH_HOST }
        if (accountDomains.any { host(it) == OLD_PUSH_HOST }) return AuthInfoCleanup(emptyList(), old, true)

        val remove = old.filter { i ->
            val stale = authInfos[i]
            val staleDomain = host(stale.domain) == OLD_PUSH_HOST
            !stale.username.isNullOrEmpty() && authInfos.any { replacement ->
                val sameRealm = replacement.realm.isNullOrEmpty() || host(replacement.realm) == host(stale.realm)
                replacement.username == stale.username && server(replacement) == PUSH_HOST && (staleDomain || sameRealm)
            }
        }
        return AuthInfoCleanup(remove, old - remove.toSet(), false)
    }

    // Dominio o realm comparable: sin comillas, sin puerto y en minúsculas
    private fun host(value: String?): String? = value?.trim()?.trim('"')?.substringBefore(':')?.lowercase()

    const val LOGS_UPLOAD_URL = "https://mundophonedebug.mundosms.es/upload"
}
