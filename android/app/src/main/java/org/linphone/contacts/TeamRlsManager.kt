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
package org.linphone.contacts

import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Event
import org.linphone.core.EventListenerStub
import org.linphone.core.Friend
import org.linphone.core.FriendList
import org.linphone.core.PresenceActivity
import org.linphone.core.PresenceBasicStatus
import org.linphone.core.RegistrationState
import org.linphone.core.SubscriptionState
import org.linphone.core.tools.Log

/**
 * MundoPhone: Equipo desde la lista del borde (RLS, RFC 4662), y si no la hay, como hasta ahora (compañeros de la
 * agenda con un SUBSCRIBE cada uno). Contrato y formato del NOTIFY en [TeamRls].
 *
 * - Una sola suscripción a `sip:rls@<dominio de la cuenta>` por cuenta por defecto registrada. Es propia de la app (no la
 *   FriendList «bodyless» del SDK): las suscripciones internas del SDK no avisan a la app ni de los NOTIFY ni de los
 *   errores, y el SDK solo crea los compañeros que tienen estado activo (los desconectados no saldrían).
 * - Los compañeros van a una FriendList solo en memoria, sin suscripciones propias; el estado se aplica a mano (también al
 *   contacto de la agenda con la misma dirección) y se refrescan las pantallas, porque fijarlo a mano no avisa.
 * - Sin lista (cualquier 4xx/5xx, o sin NOTIFY completo en 5 s): modo «sin RLS», como hasta ahora. Tras 403/404/421/489
 *   (el borde no tiene la lista o esta empresa no la tiene) no se reintenta hasta el próximo arranque; tras cualquier otro
 *   fallo (481 incluido: SUBSCRIBE inicial nuevo, sin To-tag), solo y al volver a registrarse, como mucho una vez por minuto.
 * - La última lista se guarda ([app] mundosms_team_rls_cache) para que Equipo salga al momento en el siguiente arranque.
 * - Ocultar de Equipo es local ([app] mundosms_team_hidden): se ven aparte («Ver ocultos») y se devuelven uno a uno.
 */
class TeamRlsManager {
    companion object {
        private const val TAG = "[Team RLS]"

        const val LIST_NAME = "MundoPhone Equipo (RLS)"

        private const val EXPIRES = 600
        private const val NOTIFY_TIMEOUT_MS = 5000L
        private const val RETRY_MS = 60_000L

        // El borde sin la ruta (hoy: 403), sin la cabecera eventlist (421), empresa sin lista (404), evento no admitido
        private val PERMANENT_CODES = setOf(403, 404, 421, 489)

        private const val KEY_ENABLED = "mundosms_team_rls"

        // Encendido. El interruptor es el borde: mientras no tenga el RLS activo contesta 403 y la app sigue como
        // siempre (una petición por arranque). Se apaga con [app] mundosms_team_rls=0
        private const val ENABLED_DEFAULT = 1
        private const val KEY_URI = "mundosms_team_rls_uri"
        private const val KEY_CACHE = "mundosms_team_rls_cache"
        private const val KEY_HIDDEN = "mundosms_team_hidden"
    }

    enum class Modo { APAGADO, ESPERANDO, ACTIVO, NO_DISPONIBLE }

    interface Listener {
        @WorkerThread
        fun onTeamRlsChanged()
    }

    @Volatile
    var modo = Modo.APAGADO
        private set

    private var core: Core? = null
    private var event: Event? = null
    private var friendList: FriendList? = null
    private val lista = TeamRlsLista()
    private val listeners = arrayListOf<Listener>()

    // La lista de la última vez (URIs), mientras no llega la de ahora
    private var cache: List<String> = emptyList()

    // Usuario (extensión, en minúsculas) → miembro, de la lista de ahora o de la última vez mientras llega
    @Volatile
    private var actuales: Map<String, TeamRls.Miembro> = emptyMap()

    @Volatile
    private var ocultos: Set<String> = emptySet()

    private var permanente = false
    private var ultimoIntento = 0L
    private var generacion = 0

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState?, message: String) {
            if (state == RegistrationState.Ok && account == core.defaultAccount) subscribeIfNeeded(core, "cuenta registrada")
        }

        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            Log.i("$TAG Default account changed, starting over")
            stop()
            lista.reiniciar()
            permanente = false
            ultimoIntento = 0L
            actuales = emptyMap()
            syncFriends(core, emptyList())
            modo = Modo.ESPERANDO
            notifyListeners()
            subscribeIfNeeded(core, "cambio de cuenta por defecto")
        }
    }

    private val eventListener = object : EventListenerStub() {
        @WorkerThread
        override fun onNotifyReceived(ev: Event, content: Content?) {
            if (ev != event) {
                soltar(ev, "NOTIFY de una suscripción que ya no es la actual")
                return
            }
            if (content == null) return
            onNotify(ev, content)
        }

        @WorkerThread
        override fun onSubscribeStateChanged(ev: Event, state: SubscriptionState) {
            if (ev != event) {
                if (state != SubscriptionState.Terminated) soltar(ev, "suscripción que ya no es la actual pasa a [$state]")
                return
            }
            onState(ev, state)
        }
    }

    /**
     * Cierra una suscripción que ya no es la actual. Ante un 481 a un refresco, belle-sip vuelve a suscribirse solo (diálogo
     * nuevo, mismo from-tag: retry_later_on_io_error) aunque al evento le llegue «Error»; como la app soltaba el evento sin
     * cerrarlo y a los 5 s creaba otro, la vieja revivía y quedaban 2 o 3 suscripciones a la misma lista (NOTIFY
     * por duplicado en el mismo milisegundo, más carga y más 408). Cerrarla para el refresco y da de baja el diálogo.
     */
    @WorkerThread
    private fun soltar(ev: Event, motivo: String) {
        Log.w("$TAG Closing a team list subscription that is not the current one: $motivo")
        ev.removeListener(eventListener)
        try {
            ev.terminate()
        } catch (e: Exception) {
            Log.w("$TAG Can't terminate the old team list subscription: $e")
        }
    }

    // ---- Para la lista de Equipo y las suscripciones sueltas ----

    /** ¿Manda la lista del borde? Activa, o esperándola con la de la última vez. */
    @AnyThread
    fun isActive(): Boolean = modo == Modo.ACTIVO || (modo == Modo.ESPERANDO && actuales.isNotEmpty())

    /** Los usuarios (extensiones, en minúsculas) de la lista, o los de la última vez mientras llega. */
    @AnyThread
    fun usuarios(): Set<String> = actuales.keys

    /** El estado de un compañero de la lista del borde, o null si la lista no manda o no es de ella. */
    @WorkerThread
    fun estadoDe(friend: Friend): TeamRls.Estado? = miembroDe(friend)?.estado

    /** El compañero de la lista del borde con alguna de las direcciones de este contacto, o null. */
    @WorkerThread
    fun miembroDe(friend: Friend): TeamRls.Miembro? {
        if (!isActive()) return null
        val miembros = actuales
        return friend.addresses.firstNotNullOfOrNull { a -> a.username?.lowercase(Locale.ROOT)?.let { miembros[it] } }
    }

    /** El compañero con esta extensión, o null (para poner nombre al «con quién» de una llamada). */
    @AnyThread
    fun miembroPorUsuario(usuario: String): TeamRls.Miembro? = actuales[usuario.lowercase(Locale.ROOT)]

    @AnyThread
    fun isHidden(usuario: String?): Boolean = usuario != null && usuario.lowercase(Locale.ROOT) in ocultos

    @AnyThread
    fun ocultosCount(): Int = ocultos.size

    @WorkerThread
    fun isOwnFriend(friend: Friend): Boolean = friend.friendList?.displayName == LIST_NAME

    @WorkerThread
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @WorkerThread
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ---- Ciclo de vida ----

    @WorkerThread
    fun onCoreStarted(core: Core) {
        this.core = core
        val config = core.config
        ocultos = config.getString("app", KEY_HIDDEN, "").orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
        if (config.getInt("app", KEY_ENABLED, ENABLED_DEFAULT) == 0) {
            Log.i("$TAG Disabled by [app] $KEY_ENABLED=0")
            modo = Modo.APAGADO
            return
        }
        core.addListener(coreListener)
        // «<identidad> <uri> <uri>…»: solo vale para la cuenta con la que se guardó
        val guardada = config.getString("app", KEY_CACHE, "").orEmpty().split(' ').filter { it.isNotBlank() }
        val identidad = identidad(core)
        cache = if (guardada.size > 1 && identidad != null && guardada[0] == identidad) guardada.drop(1) else emptyList()
        if (cache.isNotEmpty()) {
            Log.i("$TAG [${cache.size}] team member(s) from the last list, until the new one arrives")
            val miembros = cache.map { TeamRls.Miembro(it, TeamRls.usuario(it), TeamRls.Estado.SIN_DATO, null) }
            actuales = miembros.associateBy { it.usuario }
            syncFriends(core, miembros)
        }
        modo = Modo.ESPERANDO
        subscribeIfNeeded(core, "arranque")
    }

    @WorkerThread
    fun onCoreStopped(core: Core) {
        stop()
        core.removeListener(coreListener)
        this.core = null
    }

    /** Tirar hacia abajo en Equipo: pide la lista otra vez (y vuelve a intentarlo aunque antes no la hubiera). */
    @WorkerThread
    fun resync(core: Core) {
        if (core.config.getInt("app", KEY_ENABLED, ENABLED_DEFAULT) == 0) return
        permanente = false
        ultimoIntento = 0L
        val ev = event
        if (ev != null && ev.subscriptionState == SubscriptionState.Active) {
            Log.i("$TAG Refreshing the team list subscription")
            ev.refreshSubscribe()
        } else {
            stop()
            subscribeIfNeeded(core, "sincronizar")
        }
        notifyListeners()
    }

    @WorkerThread
    fun unhide(core: Core, usuario: String) {
        ocultos = ocultos - usuario.lowercase(Locale.ROOT)
        core.config.setString("app", KEY_HIDDEN, ocultos.joinToString(" "))
        Log.i("$TAG [$usuario] shown again in the team ([${ocultos.size}] hidden)")
        notifyListeners()
    }

    @WorkerThread
    fun hide(core: Core, usuario: String) {
        ocultos = ocultos + usuario.lowercase(Locale.ROOT)
        core.config.setString("app", KEY_HIDDEN, ocultos.joinToString(" "))
        Log.i("$TAG [$usuario] hidden from the team ([${ocultos.size}] hidden)")
        notifyListeners()
    }

    @WorkerThread
    private fun identidad(core: Core): String? =
        core.defaultAccount?.params?.identityAddress?.asStringUriOnly()?.lowercase(Locale.ROOT)

    // ---- Suscripción ----

    @WorkerThread
    private fun subscribeIfNeeded(core: Core, por: String) {
        if (core.config.getInt("app", KEY_ENABLED, ENABLED_DEFAULT) == 0) return
        if (event != null) return
        if (permanente) {
            Log.i("$TAG Not subscribing ($por): the edge has no team list for us, until next app start")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (ultimoIntento != 0L && now - ultimoIntento < RETRY_MS) return
        val account = core.defaultAccount ?: return
        if (account.state != RegistrationState.Ok) return
        val domain = account.params.identityAddress?.domain ?: return
        val uri = core.config.getString("app", KEY_URI, "").orEmpty().ifEmpty { "sip:rls@$domain" }
        val address = core.interpretUrl(uri, false) ?: run {
            Log.e("$TAG Invalid team list URI [$uri]")
            return
        }
        ultimoIntento = now
        val ev = core.createSubscribe(address, "presence", EXPIRES)
        if (ev == null) {
            Log.e("$TAG Can't create the team list subscription")
            return
        }
        ev.addCustomHeader("Supported", "eventlist")
        ev.addCustomHeader("Accept", "multipart/related, application/rlmi+xml, application/pidf+xml")
        ev.addListener(eventListener)
        event = ev
        lista.reiniciar()
        if (modo != Modo.ACTIVO) modo = Modo.ESPERANDO
        val miGeneracion = ++generacion
        Log.i("$TAG Subscribing to team list [$uri] ($por)")
        ev.sendSubscribe(null)
        coreContext.postOnCoreThreadDelayed({
            if (miGeneracion == generacion && !lista.completa && modo == Modo.ESPERANDO) {
                Log.w("$TAG No full team list after [$NOTIFY_TIMEOUT_MS] ms, using the team from the address book meanwhile")
                setUnavailable(core)
            }
        }, NOTIFY_TIMEOUT_MS)
    }

    @WorkerThread
    private fun stop() {
        generacion++
        val ev = event ?: return
        event = null
        ev.removeListener(eventListener)
        try {
            ev.terminate()
        } catch (e: Exception) {
            Log.w("$TAG Can't terminate the team list subscription: $e")
        }
    }

    @WorkerThread
    private fun onState(ev: Event, state: SubscriptionState) {
        val code = ev.errorInfo?.protocolCode ?: 0
        Log.i("$TAG Team list subscription is [$state] (code [$code], reason [${ev.reason}])")
        if (state != SubscriptionState.Error && state != SubscriptionState.Terminated) return
        event = null
        generacion++
        // La que ha fallado se cierra del todo: si no, belle-sip la vuelve a suscribir por su cuenta y, con la nueva que
        // se pide más abajo, quedaban dos a la misma lista
        if (state == SubscriptionState.Error) soltar(ev, "ha fallado ([$code])")
        val core = core ?: return
        if (code in PERMANENT_CODES) {
            permanente = true
            Log.w("$TAG The edge has no team list for us ([$code]): team from the address book, no retry until next app start")
            setUnavailable(core)
            return
        }
        if (modo == Modo.ACTIVO) {
            // Ya funcionaba (corte de red, refresco perdido): se queda la lista y se vuelve a pedir; volver a las
            // suscripciones sueltas sería una ráfaga de SUBSCRIBE para nada. Si no llega en 5 s, ahí sí se cae
            Log.w("$TAG Team list subscription lost ([$code]), keeping the list and subscribing again")
            modo = Modo.ESPERANDO
            ultimoIntento = 0L
            coreContext.postOnCoreThreadDelayed({ subscribeIfNeeded(core, "reintento") }, NOTIFY_TIMEOUT_MS)
            return
        }
        setUnavailable(core)
        // Antes, sin lista todavía, solo se volvía a pedir al registrarse otra vez: un 481 (el borde ya no conoce el
        // diálogo) antes de la primera lista completa dejaba Equipo con la agenda hasta el siguiente REGISTER (visto en
        // el borde: 23 respuestas 481 en 24 h a un mismo login). Ahora se pide sola, con un intento por minuto como
        // mucho
        val espera = TeamRls.esperaReintento(SystemClock.elapsedRealtime(), ultimoIntento, NOTIFY_TIMEOUT_MS, RETRY_MS)
        val miGeneracion = generacion
        Log.i("$TAG Asking for the team list again in [$espera] ms")
        coreContext.postOnCoreThreadDelayed({
            if (miGeneracion == generacion) subscribeIfNeeded(core, "reintento tras [$code]")
        }, espera)
    }

    @WorkerThread
    private fun setUnavailable(core: Core) {
        if (modo == Modo.NO_DISPONIBLE) return
        modo = Modo.NO_DISPONIBLE
        actuales = emptyMap()
        syncFriends(core, emptyList())
        coreContext.contactsManager.updateTeamPresenceSubscriptions(core)
        notifyListeners()
    }

    // ---- NOTIFY ----

    @WorkerThread
    private fun onNotify(ev: Event, content: Content) {
        val core = core ?: return
        val partes = if (content.isMultipart) {
            content.parts.map { part ->
                val cabeceras = linkedMapOf("Content-Type" to "${part.type}/${part.subtype}")
                (part.getCustomHeader("Content-Id") ?: part.getCustomHeader("Content-ID"))?.let { cabeceras["Content-ID"] = it }
                TeamRls.Parte(cabeceras, part.utf8Text.orEmpty())
            }
        } else {
            // Por si el SDK no lo ha troceado: el cuerpo en texto
            TeamRls.partesMultipart(content.utf8Text.orEmpty())
        }
        val (rlmi, pidfs) = TeamRls.leerNotify(partes)
        if (rlmi == null) {
            Log.w("$TAG NOTIFY without a readable RLMI ([${content.type}/${content.subtype}]), ignored")
            return
        }
        val resultado = lista.aplicar(rlmi, pidfs)
        Log.i("$TAG NOTIFY version [${rlmi.version}] fullState [${rlmi.fullState}] with [${rlmi.recursos.size}] resource(s): [$resultado]")
        if (resultado == TeamRlsLista.Resultado.PIDE_COMPLETO) {
            Log.w("$TAG Missing a full state (or version gap), refreshing the subscription")
            ev.refreshSubscribe()
        }
        if (!lista.completa) return
        // Sin cambios y ya activo: nada que hacer. Si no estaba activo (p. ej. llegó tras los 5 s), hay que volver a
        // crear a los compañeros aunque la lista no haya cambiado
        val sinNovedad = resultado == TeamRlsLista.Resultado.VIEJO || resultado == TeamRlsLista.Resultado.SIN_CAMBIO
        if (sinNovedad && modo == Modo.ACTIVO) return
        val miembros = lista.miembros()
        actuales = miembros.associateBy { it.usuario }
        syncFriends(core, miembros)
        if (rlmi.fullState) {
            identidad(core)?.let { id ->
                core.config.setString(
                    "app", KEY_CACHE,
                    (
                        listOf(id) + miembros.map {
                it.uri
            }
                    ).joinToString(" ")
                )
            }
        }
        if (modo != Modo.ACTIVO) becomeActive(core) else notifyListeners()
    }

    @WorkerThread
    private fun becomeActive(core: Core) {
        Log.i("$TAG Team list is active with [${lista.miembros().size}] member(s): no individual SUBSCRIBE anymore")
        modo = Modo.ACTIVO
        coreContext.contactsManager.updateTeamPresenceSubscriptions(core)
        notifyListeners()
    }

    // ---- Compañeros y su estado ----

    @WorkerThread
    private fun ownList(core: Core): FriendList {
        friendList?.let { return it }
        val existing = core.getFriendListByName(LIST_NAME)
        val list = existing ?: core.createFriendList().also {
            it.displayName = LIST_NAME
            it.isDatabaseStorageEnabled = false
            it.isSubscriptionsEnabled = false
            core.addFriendList(it)
        }
        friendList = list
        return list
    }

    /** Deja la lista propia con estos miembros y les aplica su estado (y al contacto de la agenda con la misma dirección). */
    @WorkerThread
    private fun syncFriends(core: Core, miembros: List<TeamRls.Miembro>) {
        val list = ownList(core)
        val porUsuario = miembros.associateBy { it.usuario }
        for (friend in list.friends) {
            val usuario = friend.address?.username?.lowercase(Locale.ROOT)
            if (usuario == null || usuario !in porUsuario) list.removeFriend(friend)
        }
        for (miembro in miembros) {
            val address = core.interpretUrl(miembro.uri, false) ?: continue
            // El nombre de la centralita («(111) - Ana») o, sin él, la extensión
            val nombre = miembro.nombre ?: miembro.usuario
            var friend = list.findFriendByAddress(address)
            if (friend == null) {
                friend = core.createFriend()
                friend.edit()
                friend.address = address
                friend.name = nombre
                friend.isSubscribesEnabled = false
                friend.done()
                list.addLocalFriend(friend)
            } else if (friend.name != nombre) {
                friend.edit()
                friend.name = nombre
                friend.done()
                coreContext.contactsManager.refreshAvatarPresence(friend)
            }
            aplicarEstado(core, friend, address, miembro)
            // El mismo compañero en la agenda: que también tenga el estado (historial, ficha, conversaciones)
            val lookup = MundoSmsDomain.forLookup(address)
            for (other in core.friendsLists) {
                if (other == list) continue
                val native = other.findFriendByAddress(lookup) ?: other.findFriendByAddress(address) ?: continue
                aplicarEstado(core, native, address, miembro)
            }
        }
    }

    @WorkerThread
    private fun aplicarEstado(core: Core, friend: Friend, address: Address, miembro: TeamRls.Miembro) {
        // Sin dato no se pinta nada: ni «Desconectado» ni el estado de antes se tocan hasta que llegue el de verdad
        val linea = TeamRls.linea(miembro) ?: return
        // El modelo es solo local y decide el color del punto: abierto sin actividad = verde, abierto con actividad = rojo,
        // cerrado con actividad = naranja (así pinta el SDK el «no disponible») y cerrado sin nada = sin punto
        val model = core.createPresenceModel()
        model.basicStatus = when (linea.situacion) {
            TeamRls.Situacion.DISPONIBLE, TeamRls.Situacion.SONANDO, TeamRls.Situacion.MARCANDO,
            TeamRls.Situacion.EN_LLAMADA -> PresenceBasicStatus.Open
            else -> PresenceBasicStatus.Closed
        }
        when (linea.situacion) {
            TeamRls.Situacion.SONANDO, TeamRls.Situacion.MARCANDO, TeamRls.Situacion.EN_LLAMADA,
            TeamRls.Situacion.EN_ESPERA -> model.setActivity(PresenceActivity.Type.OnThePhone, null)
            TeamRls.Situacion.NO_MOLESTAR -> model.setActivity(PresenceActivity.Type.Busy, null)
            TeamRls.Situacion.AUSENTE -> model.setActivity(PresenceActivity.Type.Away, null)
            else -> {}
        }
        miembro.nota?.let { model.addNote(it, null) }
        friend.setPresenceModelForUriOrTel(address.asStringUriOnly(), model)
        coreContext.contactsManager.refreshAvatarPresence(friend)
    }

    @WorkerThread
    private fun notifyListeners() {
        for (listener in listeners.toList()) listener.onTeamRlsChanged()
    }
}
