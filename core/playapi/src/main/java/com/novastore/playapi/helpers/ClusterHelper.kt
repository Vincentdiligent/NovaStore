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
import com.novastore.playapi.data.models.AuthData
import com.novastore.playapi.data.models.StreamCluster
import com.novastore.playapi.data.providers.HeaderProvider.getDefaultHeaders
import com.novastore.playapi.network.HttpClient
import java.util.*

class ClusterHelper private constructor(authData: AuthData) : BaseHelper(authData) {

    companion object : SingletonHolder<ClusterHelper, AuthData>(::ClusterHelper)

    @Throws(Exception::class)
    fun next(nextPageUrl: String): StreamCluster {
        val listResponse = getNextStreamResponse(nextPageUrl)
        return getStreamCluster(listResponse)
    }

    @Throws(Exception::class)
    fun getCluster(type: Type): StreamCluster {
        val headers: MutableMap<String, String> = getDefaultHeaders(authData)
        val params: MutableMap<String, String> = HashMap()
        params["n"] = "15"
        params["tab"] = type.value

        val responseBody = HttpClient.get(GooglePlayApi.URL_FDFE + "/myAppsStream", headers, params)

        return if (responseBody.isSuccessful) {
            val listResponse = getListResponseFromBytes(responseBody.responseBytes)
            getStreamCluster(listResponse)
        } else
            StreamCluster()
    }

    enum class Type(var value: String) {
        MY_APPS_INSTALLED("INSTALLED"),
        MY_APPS_LIBRARY("LIBRARY"),
        MY_APPS_UPDATES("UPDATES");
    }
}