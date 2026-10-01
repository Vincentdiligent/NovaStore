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

import java.io.FileNotFoundException
import java.util.*

object DeviceManager {

    /**
     * All device configurations bundled with this module
     * (original GPlayApi devices + Nova Store additions).
     */
    private val knownDevices = listOf(
            // Nova Store additions
            "px_10_pro.properties",
            "px_10.properties",
            "px_8_pro.properties",
            "px_xl.properties",
            "sm_s24_ultra.properties",
            "px_tablet.properties",
            // Original GPlayApi devices
            "ad_g3_pro.properties",
            "bravia_atv2.properties",
            "fp_2.properties",
            "hw_h9.properties",
            "hw_mate20.properties",
            "mi_8_se.properties",
            "mi_a1.properties",
            "mi_mix2.properties",
            "moto_g5.properties",
            "nk_8.properties",
            "nk_9.properties",
            "nk_drx.properties",
            "op_3.properties",
            "op_5.properties",
            "op_5t.properties",
            "op_6.properties",
            "op_7t.properties",
            "op_7t_pro.properties",
            "op_8_pro.properties",
            "op_x.properties",
            "poco_f1.properties",
            "px_3a.properties",
            "rm_4.properties",
            "rm_5.properties",
            "rm_5_plus.properties",
            "rm_5_pro.properties",
            "rm_5i.properties",
            "rm_7.properties",
            "rm_k20_pro.properties",
            "rm_note_5.properties",
            "sm_a3.properties",
            "sm_feel.properties",
            "sm_s7.properties",
            "sm_s8.properties",
            "sm_s9_plus.properties",
            "tw_e.properties",
            "xp_5_dual.properties"
    )

    fun listDevices(): List<String> = knownDevices.toList()

    fun loadProperties(deviceName: String?): Properties? {
        return try {
            val properties = Properties()
            val inputStream = javaClass
                    .classLoader
                    .getResourceAsStream(deviceName)
            if (inputStream != null) {
                properties.load(inputStream)
            } else {
                throw FileNotFoundException("Device config file not found")
            }
            properties
        } catch (e: Exception) {
            null
        }
    }
}
