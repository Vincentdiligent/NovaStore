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
 */

package com.novastore.playapi.helpers

import com.novastore.playapi.GooglePlayApi
import com.novastore.playapi.SingletonHolder
import com.novastore.playapi.data.builders.AppBuilder
import com.novastore.playapi.data.models.App
import com.novastore.playapi.data.models.AuthData
import com.novastore.playapi.data.providers.HeaderProvider.getDefaultHeaders
import com.novastore.playapi.exceptions.ApiException
import com.novastore.playapi.network.HttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*

class AppDetailsHelper private constructor(authData: AuthData) : BaseHelper(authData) {

    companion object : SingletonHolder<AppDetailsHelper, AuthData>(::AppDetailsHelper)

    @Throws(Exception::class)
    fun getAppByPackageName(packageName: String): App? {
        val headers: Map<String, String> = getDefaultHeaders(authData)
        val params: MutableMap<String, String> = HashMap()
        params["doc"] = packageName

        val playResponse = HttpClient.get(GooglePlayApi.URL_DETAILS, headers, params)

        if (playResponse.isSuccessful) {
            val detailsResponse = getDetailsResponseFromBytes(playResponse.responseBytes)
            return AppBuilder.build(detailsResponse.item)
        } else {
            throw ApiException.AppNotFound(playResponse.errorString)
        }
    }

    @Throws(Exception::class)
    fun getAppByPackageName(packageList: List<String>): List<App> {
        val appList: MutableList<App> = ArrayList()
        val headers: MutableMap<String, String> = getDefaultHeaders(authData)
        val request = getBulkDetailsBytes(packageList)

        if (!headers.containsKey("Content-Type")) {
            headers["Content-Type"] = "application/x-protobuf"
        }

        val requestBody = request.toRequestBody("application/x-protobuf".toMediaType())
        val playResponse = HttpClient.post(GooglePlayApi.URL_BULK_DETAILS, headers, requestBody)

        if (playResponse.isSuccessful) {
            val payload = getPayLoadFromBytes(playResponse.responseBytes)
            if (payload.hasBulkDetailsResponse()) {
                val bulkDetailsResponse = payload.bulkDetailsResponse
                for (entry in bulkDetailsResponse.entryList) {
                    val app = AppBuilder.build(entry.item)
                    //System.out.printf("%s -> %s\n", app.displayName, app.packageName);
                    appList.add(app)
                }
            }
            return appList
        } else {
            throw ApiException.Server(playResponse.errorString)
        }
    }
}