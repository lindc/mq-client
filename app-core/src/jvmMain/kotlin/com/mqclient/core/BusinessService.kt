package com.mqclient.core

/**
 * Pure (non-UI) descriptor of a business module, discovered automatically via
 * Java SPI ([java.util.ServiceLoader]). Each `*-service` module provides one
 * implementation and registers it under `META-INF/services/com.mqclient.core.BusinessService`.
 */
interface BusinessService {
    /** Stable identifier, e.g. "ibm-mq". */
    val id: String

    /** Human readable name shown in the UI. */
    val name: String

    /** Short description of what the module does. */
    val description: String

    /**
     * Wire protocol the module speaks, e.g. "AMQP 0.9.1" or "Kafka Protocol".
     * Surfaced in the About dialog so users know which transports are bundled
     * without the UI needing to know about individual service modules.
     */
    val protocol: String
}
