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
import com.novastore.playapi.ListResponse
import com.novastore.playapi.SingletonHolder
import com.novastore.playapi.data.models.AuthData
import com.novastore.playapi.data.models.StreamBundle
import com.novastore.playapi.data.models.editor.EditorChoiceBundle
import com.novastore.playapi.data.providers.HeaderProvider.getDefaultHeaders
import com.novastore.playapi.network.HttpClient
import java.util.*

class StreamHelper private constructor(authData: AuthData) : BaseHelper(authData) {

    companion object : SingletonHolder<StreamHelper, AuthData>(::StreamHelper)

    @Throws(Exception::class)
    fun getNavStream(type: Type, category: Category): StreamBundle {
        val listResponse = getListResponse(type, category)
        return getStreamBundle(listResponse)
    }

    fun getEditorChoiceStream(category: Category): List<EditorChoiceBundle> {
        val listResponse = getListResponse(Type.EDITOR_CHOICE, category)
        return getEditorChoiceBundles(listResponse)
    }

    @Throws(Exception::class)
    fun next(nextPageUrl: String): StreamBundle {
        val listResponse = getNextStreamResponse(nextPageUrl)
        return getStreamBundle(listResponse)
    }

    @Throws(Exception::class)
    fun getListResponse(type: Type, category: Category): ListResponse {
        val headers: MutableMap<String, String> = getDefaultHeaders(authData)
        val params: MutableMap<String, String> = HashMap()
        params["c"] = "3"

        if (type == Type.EARLY_ACCESS) {
            params["ct"] = "1"
        } else {
            if (category != Category.NONE)
                params["cat"] = category.value
        }

        val playResponse = HttpClient.get(GooglePlayApi.URL_FDFE + "/" + type.value, headers, params)

        return if (playResponse.isSuccessful)
            getListResponseFromBytes(playResponse.responseBytes)
        else
            ListResponse.getDefaultInstance()
    }

    enum class Category(var value: String) {
        APPLICATION("APPLICATION"),
        GAME("GAME"),
        NONE("NONE");
    }

    enum class Type(var value: String) {
        EARLY_ACCESS("appsEarlyAccessStream"),
        EDITOR_CHOICE("getAppsEditorsChoiceStream"),
        HOME("getHomeStream"),
        MY_APPS_LIBRARY("myAppsStream?tab=LIBRARY"),
        PREMIUM_GAMES("getAppsPremiumGameStream"),
        SUB_NAV("subnavHome"),
        TOP_CHART("topChartsStream");
    }
}