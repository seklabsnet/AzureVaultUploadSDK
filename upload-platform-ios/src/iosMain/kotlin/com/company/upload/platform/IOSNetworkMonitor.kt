package com.company.upload.platform

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Network.*
import platform.darwin.dispatch_get_main_queue

internal class IOSNetworkMonitor {
    private val monitor = nw_path_monitor_create()

    val isConnected: Boolean
        get() {
            // Check current path status
            var connected = false
            nw_path_monitor_set_update_handler(monitor) { path ->
                connected = nw_path_get_status(path) == nw_path_status_satisfied
            }
            return connected
        }

    fun observeConnectivity(): Flow<Boolean> = callbackFlow {
        val monitor = nw_path_monitor_create()

        nw_path_monitor_set_update_handler(monitor) { path ->
            val isConnected = nw_path_get_status(path) == nw_path_status_satisfied
            trySend(isConnected)
        }

        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)

        awaitClose {
            nw_path_monitor_cancel(monitor)
        }
    }
}
