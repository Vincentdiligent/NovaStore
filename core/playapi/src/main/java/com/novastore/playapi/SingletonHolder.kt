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

package com.novastore.playapi

open class SingletonHolder<out T, in A>(private val constructor: (A) -> T) {

    @Volatile
    private var instance: T? = null

    @Volatile
    private var instanceArg: A? = null

    /**
     * Nova Store change: the cached instance is rebuilt whenever a DIFFERENT
     * argument (AuthData) is passed. Upstream kept the first instance forever,
     * so a helper silently kept using a revoked/expired session after the
     * account or the anonymous session changed.
     */
    fun with(arg: A): T {
        val current = instance
        if (current != null && instanceArg === arg) return current
        return synchronized(this) {
            val again = instance
            if (again != null && instanceArg === arg) {
                again
            } else {
                constructor(arg).also {
                    instance = it
                    instanceArg = arg
                }
            }
        }
    }

    /** Nova Store addition: drops the cached instance so a new AuthData takes effect. */
    fun clear() {
        synchronized(this) {
            instance = null
            instanceArg = null
        }
    }
}