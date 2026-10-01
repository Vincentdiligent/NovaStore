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
import com.novastore.playapi.ResponseWrapper
import com.novastore.playapi.SingletonHolder
import com.novastore.playapi.data.models.AuthData
import com.novastore.playapi.data.providers.HeaderProvider.getDefaultHeaders
import com.novastore.playapi.network.HttpClient
import java.io.IOException
import java.util.*

class BrowseHelper private constructor(authData: AuthData) : BaseHelper(authData) {

    companion object : SingletonHolder<BrowseHelper, AuthData>(::BrowseHelper)

    @Throws(IOException::class)
    fun getAllCategoriesList(type: Type) {
        val headers: MutableMap<String, String> = getDefaultHeaders(authData)
        val params: MutableMap<String, String> = HashMap()
        params["c"] = "3"
        params["cat"] = if (type == Type.GAME) "GAME" else "APPLICATION"
        val responseBody = HttpClient.get(GooglePlayApi.TOP_CHART_URL, headers, params)
        val responseWrapper = ResponseWrapper.parseFrom(responseBody.responseBytes)
        val payload = responseWrapper.payload
    }

    enum class Type {
        APPLICATION, GAME
    }
}