package com.dany.presence.shell

import com.dany.presence.core.Bus

/**
 * The Activity's Bus, reachable from services that Android instantiates itself
 * (the notification listener). MainActivity sets it in onCreate and may clear it in onDestroy.
 */
object BusHolder {
    @Volatile var bus: Bus? = null
}
