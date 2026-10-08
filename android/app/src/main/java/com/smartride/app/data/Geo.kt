package com.smartride.app.data

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Distance between nearby WGS-84 points: local projection with ellipsoid scale
 * factors at the mean latitude. Same method as firmware/lib/core/src/geo.cpp;
 * < 0.0001 % from an exact geodesic at ride scales, unlike spherical Haversine
 * (0.1-0.5 % off), and cheaper.
 */
object Geo {
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val c = cos(Math.toRadians((lat1 + lat2) / 2))
        val c2 = 2 * c * c - 1
        val c3 = (4 * c * c - 3) * c
        val c4 = 2 * c2 * c2 - 1
        val c5 = 2 * c2 * c3 - c
        val mLat = 111132.92 - 559.82 * c2 + 1.175 * c4
        val mLon = 111412.84 * c - 93.5 * c3 + 0.118 * c5
        val dy = (lat2 - lat1) * mLat
        val dx = (lon2 - lon1) * mLon
        return sqrt(dx * dx + dy * dy)
    }

    fun distanceM(a: RoutePoint, b: RoutePoint) = distanceM(a.lat, a.lon, b.lat, b.lon)
}
