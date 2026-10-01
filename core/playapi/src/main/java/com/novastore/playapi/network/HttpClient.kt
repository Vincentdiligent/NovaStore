/*
 *     GPlayApi
 *     Copyright (C) 2020  Aurora OSS
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *  Vendored into Nova Store (:core:playapi) — network layer rewritten on plain
 *  OkHttp 4 (no Retrofit, no Gson). Public API kept source-compatible with the
 *  original library.
 */

package com.novastore.playapi.network

import com.novastore.playapi.data.models.PlayResponse
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Play throttled this session (HTTP 429); a different session may succeed. */
class RateLimitedException(message: String) : IOException(message)

object HttpClient {

    private val okHttpClient: OkHttpClient = OkHttpClient().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, requestBody: RequestBody): PlayResponse {
        val request = newBuilder(url, headers)
                .post(requestBody)
                .build()
        return execute(request)
    }

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, params: Map<String, String>): PlayResponse {
        val formBody = FormBody.Builder().apply {
            params.forEach { (name, value) -> add(name, value) }
        }.build()
        return post(url, headers, formBody as RequestBody)
    }

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): PlayResponse {
        val request = newBuilder(url, headers)
                .get()
                .build()
        return execute(request)
    }

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>, params: Map<String, String>): PlayResponse {
        val targetUrl = url.toHttpUrlOrNull()
                ?.newBuilder()
                ?.apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
                ?.build()
                ?.toString()
                ?: url
        return get(targetUrl, headers)
    }

    @Throws(IOException::class)
    fun getX(url: String, headers: Map<String, String>, paramString: String): PlayResponse {
        return get(url + paramString, headers)
    }

    private fun newBuilder(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    @Throws(IOException::class)
    private fun execute(request: Request): PlayResponse {
        okHttpClient.newCall(request).execute().use { response ->
            // An expired/revoked Play token must surface as AuthException so
            // callers rebuild the session instead of parsing an error page.
            if (response.code == 401 && request.url.encodedPath.startsWith("/fdfe/")) {
                throw com.novastore.playapi.exceptions.AuthException("Play session rejected (HTTP 401)").apply {
                    code = 401
                }
            }
            if (response.code == 429 && request.url.encodedPath.startsWith("/fdfe/")) {
                throw RateLimitedException("Play rate limit (HTTP 429)")
            }
            return buildPlayResponse(response)
        }
    }

    private fun buildPlayResponse(response: okhttp3.Response): PlayResponse {
        val bodyBytes: ByteArray = response.body?.bytes() ?: ByteArray(0)
        return PlayResponse().apply {
            responseBytes = bodyBytes
            isSuccessful = response.isSuccessful
            code = response.code
            if (!response.isSuccessful) {
                errorBytes = bodyBytes
                errorString = if (bodyBytes.isNotEmpty()) String(bodyBytes)
                else "HTTP ${response.code} ${response.message}"
            }
        }
    }
}
