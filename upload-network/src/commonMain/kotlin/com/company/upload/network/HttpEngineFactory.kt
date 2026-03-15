package com.company.upload.network

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

expect fun createHttpEngine(): HttpClientEngineFactory<HttpClientEngineConfig>
