package com.smartride.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reference distances are exact WGS-84 geodesics from geographiclib (Karney). */
class GeoTest {
    @Test fun `matches ellipsoid geodesic`() {
        assertEquals(110601.48, Geo.distanceM(8.5, 76.0, 9.5, 76.0), 0.1)
        assertEquals(109957.94, Geo.distanceM(9.0, 76.0, 9.0, 77.0), 0.1)
        assertEquals(2728.168, Geo.distanceM(8.9950, 76.6960, 8.9795, 76.7153), 0.01)
        assertEquals(15.596, Geo.distanceM(8.99, 76.69, 8.9901, 76.6901), 0.001)
    }
}
