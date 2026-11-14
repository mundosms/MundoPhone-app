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
package org.linphone.ui.main.team.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R

/** MundoPhone: la línea «Ver ocultos (N)» al final de Equipo (nada si no hay ocultos). */
class TeamHiddenFooterAdapter(private val onClick: () -> Unit) : RecyclerView.Adapter<TeamHiddenFooterAdapter.ViewHolder>() {
    class ViewHolder(val label: AppCompatTextView) : RecyclerView.ViewHolder(label)

    private var count = 0
    private var expanded = false

    @UiThread
    fun update(count: Int, expanded: Boolean) {
        val had = this.count > 0
        this.count = count
        this.expanded = expanded
        when {
            had && count == 0 -> notifyItemRemoved(0)
            !had && count > 0 -> notifyItemInserted(0)
            count > 0 -> notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (count > 0) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val label = LayoutInflater.from(parent.context).inflate(R.layout.team_hidden_footer_cell, parent, false) as AppCompatTextView
        return ViewHolder(label)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.label.context
        holder.label.text = context.getString(
            if (expanded) R.string.team_hidden_expanded else R.string.team_hidden_collapsed,
            count
        )
        holder.label.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (expanded) R.drawable.caret_up else R.drawable.caret_down,
            0
        )
        holder.label.setOnClickListener { onClick() }
    }
}
