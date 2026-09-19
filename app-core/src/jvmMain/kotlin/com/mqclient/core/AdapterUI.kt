package com.mqclient.core

import androidx.compose.runtime.Composable

/**
 * UI contribution of a business module, discovered automatically via Java SPI.
 * Each `*-ui` module provides one implementation and registers it under
 * `META-INF/services/com.mqclient.core.AdapterUI`.
 *
 * [serviceId] must match the [ServiceAdapter.id] of the related service module
 * so the UI can be attached to its metadata.
 */
interface AdapterUI {
    /** Must match the related [ServiceAdapter.id]. */
    val serviceId: String

    /** Title shown for this module's content. */
    val title: String

    /** The composable content rendered when this module is selected. */
    val content: @Composable () -> Unit
}
