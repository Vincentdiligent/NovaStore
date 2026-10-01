package com.novastore.playapi

import com.novastore.playapi.helpers.ClusterHelper
import com.novastore.playapi.helpers.TopChartsHelper
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChartsLiveTest {
    @Test
    fun anonymousChartsPage() {
        assumeTrue(System.getProperty("nova.live") == "true")
        val auth = AnonymousAuth.login(AnonymousAuth.DEFAULT_DISPENSERS, "px_10_pro.properties")
        for (chart in TopChartsHelper.Chart.values()) {
            for (type in TopChartsHelper.Type.values()) {
                val cluster = runCatching { TopChartsHelper.with(auth).getCluster(type, chart) }
                    .onFailure { println("chart $type/$chart failed: $it") }.getOrNull() ?: continue
                println("chart $type/$chart: ${cluster.appList.size} apps free=${cluster.appList.count { it.isFree }} next=${cluster.nextPageUrl.take(40)}")
                if (chart == TopChartsHelper.Chart.TOP_SELLING_FREE && type == TopChartsHelper.Type.APPLICATION && cluster.hasNext()) {
                    val next = runCatching { ClusterHelper.with(auth).next(cluster.nextPageUrl) }.onFailure { println("next failed $it") }.getOrNull()
                    println("  next page: ${next?.appList?.size} first=${next?.appList?.firstOrNull()?.displayName}")
                }
            }
        }
    }
}
