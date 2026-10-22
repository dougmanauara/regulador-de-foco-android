package br.reguladordefoco

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** "Óculos de tela" na barra de notificações: um toque liga o último modo, outro volta ao Normal. */
class RegulatorTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val store = Store(this)
        if (store.profile != Profile.NORMAL) {
            Regulator.apply(this, Profile.NORMAL)
        } else if (store.chosenThreshold() == null) {
            openApp()
            return
        } else {
            Regulator.apply(this, store.lastActiveProfile)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val profile = Store(this).profile
        tile.state = if (profile == Profile.NORMAL) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = when (profile) {
            Profile.NORMAL -> "Desligado"
            Profile.NO_GLASSES -> "Sem óculos"
            Profile.NIGHT -> "Antes de dormir"
        }
        tile.updateTile()
    }

    /** Sem teste feito ainda: abre o app para fazer. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
