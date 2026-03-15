package com.company.upload.network

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun createHttpEngine(): HttpClientEngineFactory<HttpClientEngineConfig> = Darwin
