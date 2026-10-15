package com.amitjha.kosh.tools

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that opens the add-expense screen (behind the app lock, if it's on). */
class QuickAddTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val open = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(QuickLaunch.pending(this, QuickLaunch.ROUTE_ADD_EXPENSE, 7_301))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(QuickLaunch.intent(this, QuickLaunch.ROUTE_ADD_EXPENSE))
            }
        }
        // On a locked device, ask for the screen unlock first; the app's own lock then applies as usual.
        if (isLocked) unlockAndRun(open) else open()
    }
}
