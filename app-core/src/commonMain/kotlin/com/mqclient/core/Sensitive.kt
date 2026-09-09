package com.mqclient.core

/**
 * Marks a config-model property whose value must be encrypted at rest.
 *
 * ConfigManager currently identifies sensitive fields by **name matching**
 * (password / apiKey / accessToken / … keywords) because generic field
 * recognition in commonMain can't rely on reflection. This annotation is
 * retained so the schema carries the intent explicitly — once a
 * serializer-driven encryption pass (annotations read from the descriptor)
 * lands, models don't need to change.
 *
 * Keep it on the property AND the backing field so both future descriptor
 * walks and potential reflection-based passes see it.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Sensitive
