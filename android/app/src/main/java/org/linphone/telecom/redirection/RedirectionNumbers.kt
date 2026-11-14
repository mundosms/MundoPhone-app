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
package org.linphone.telecom.redirection

import androidx.annotation.AnyThread

/**
 * MundoPhone (E1): la parte de [CallRedirection] que solo mira números, sin Android ni el SDK, para poder probarla en
 * la JVM (app/src/test). [CallRedirection] la usa tal cual; su objeto no se puede cargar en una prueba (crea un Handler
 * del hilo principal al iniciarse).
 */
object RedirectionNumbers {
    /** Para el log: nunca el número entero. Se dejan los separadores y los dos últimos dígitos («+xxxxxxxxx56»). */
    @AnyThread
    fun mask(number: String): String {
        val digits = number.count { it.isDigit() }
        var seen = 0
        val masked = StringBuilder()
        for (c in number) {
            if (c.isDigit()) {
                seen++
                masked.append(if (seen > digits - 2) c else 'x')
            } else {
                masked.append(c)
            }
        }
        return masked.toString()
    }

    /** Códigos de operadora, desvíos, `*#06#`…: siempre por la SIM. */
    @AnyThread
    fun isOperatorCode(number: String): Boolean {
        val trimmed = number.trim()
        return trimmed.startsWith("*") || trimmed.startsWith("#")
    }

    /**
     * El número limpio para MundoPhone («+» y dígitos), o null si no es un número normal: con pausas o esperas
     * («,» «;»: tonos tras descolgar, que MundoPhone no mandaría), letras o nada. Esos salen por la SIM.
     */
    @AnyThread
    fun dialableNumber(number: String): String? {
        val withoutSeparators = number.trim().filterNot {
            it == ' ' || it == '-' || it == '.' || it == '(' || it == ')' || it == '/'
        }
        val plus = withoutSeparators.startsWith("+")
        val digits = if (plus) withoutSeparators.substring(1) else withoutSeparators
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        return if (plus) "+$digits" else digits
    }

    /** ¿El mismo número? Tal cual o, con y sin prefijo de país, por sus 9 últimos dígitos. */
    @AnyThread
    fun sameNumber(a: String, b: String): Boolean {
        val cleanA = dialableNumber(a) ?: return false
        val cleanB = dialableNumber(b) ?: return false
        if (cleanA == cleanB) return true
        // El mismo número con y sin prefijo de país: basta con que casen los 9 últimos dígitos
        val digitsA = cleanA.removePrefix("+")
        val digitsB = cleanB.removePrefix("+")
        return digitsA.length >= 9 && digitsB.length >= 9 && digitsA.takeLast(9) == digitsB.takeLast(9)
    }

    // ---- «Recordar para este número»: «<número>=sim;<número>=app» ----

    const val RULE_SIM = "sim"
    const val RULE_APP = "app"

    /** Cómo se guarda un número: sin prefijo de país (sus 9 últimos dígitos) o, si es más corto, tal cual; null si no es un número. */
    @AnyThread
    fun ruleKey(number: String): String? {
        val digits = dialableNumber(number)?.removePrefix("+") ?: return null
        return if (digits.length >= 9) digits.takeLast(9) else digits
    }

    @AnyThread
    fun parseRules(raw: String): Map<String, String> {
        val rules = linkedMapOf<String, String>()
        for (entry in raw.split(';')) {
            val number = entry.substringBefore('=', "").trim()
            val rule = entry.substringAfter('=', "").trim()
            if (number.isNotEmpty() && number.all { it.isDigit() } && (rule == RULE_SIM || rule == RULE_APP)) {
                rules[number] = rule
            }
        }
        return rules
    }

    @AnyThread
    fun serializeRules(rules: Map<String, String>): String = rules.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** La elección guardada para [number] (escrito como sea: con o sin prefijo, con espacios), o null. */
    @AnyThread
    fun ruleFor(rules: Map<String, String>, number: String): String? =
        rules.entries.firstOrNull { sameNumber(it.key, number) }?.value

    /** Las elecciones con la de [number] cambiada; [rule] null la quita. */
    @AnyThread
    fun withRule(rules: Map<String, String>, number: String, rule: String?): Map<String, String> {
        val result = LinkedHashMap(rules.filterKeys { !sameNumber(it, number) })
        if (rule != null) ruleKey(number)?.let { result[it] = rule }
        return result
    }
}

/**
 * MundoPhone (E1): la marca de la llamada que relanzamos por la SIM. La siguiente llamada a ese número que pase por el
 * servicio, antes de que caduque, sale por la SIM sin preguntar; vale una sola vez.
 *
 * [now] es el reloj en milisegundos (en la app, SystemClock.elapsedRealtime). No se sincroniza sola: [CallRedirection]
 * la usa desde métodos @Synchronized.
 */
class SimRelaunchMark(private val now: () -> Long) {
    private class Mark(val number: String, val expiresAt: Long)

    @Volatile
    private var mark: Mark? = null

    /** Deja la marca para [number] durante [ttlMs]; sustituye a la que hubiera. */
    fun mark(number: String, ttlMs: Long) {
        mark = Mark(number, now() + ttlMs)
    }

    /**
     * ¿Es la que relanzamos nosotros? Si lo es, la marca se gasta. Una marca caducada se borra (sea cual sea el número
     * que llega) y se avisa con [onExpired] y el número que llevaba.
     */
    fun consume(number: String, onExpired: (String) -> Unit = {}): Boolean {
        val current = mark ?: return false
        if (now() > current.expiresAt) {
            mark = null
            onExpired(current.number)
            return false
        }
        if (!RedirectionNumbers.sameNumber(current.number, number)) return false
        mark = null
        return true
    }

    fun clear() {
        mark = null
    }
}
