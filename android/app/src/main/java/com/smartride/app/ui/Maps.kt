package com.smartride.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

data class MapPoint(val lat: Double, val lon: Double, val speedKmh: Double = 0.0)

/**
 * OpenStreetMap view (osmdroid) showing a speed-coloured route, start/end pins
 * and an optional position marker. Used for both the live ride and history.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun RouteMap(
    path: List<MapPoint>,
    th: AppTheme,
    modifier: Modifier = Modifier,
    position: MapPoint? = null,
    followPosition: Boolean = false,
    showEndpoints: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { MapHolder() }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(15.0)
            // The map lives inside a scrolling page: keep gestures on the map while touched.
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onDetach()
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) { map ->
            // Dark themes: invert tiles for a night map.
            map.overlayManager.tilesOverlay.setColorFilter(if (th.isLight) null else TilesOverlay.INVERT_COLORS)

            val pathKey = path.size to path.lastOrNull()
            if (pathKey != holder.pathKey || th != holder.theme) {
                holder.pathKey = pathKey
                holder.theme = th
                map.overlays.removeAll { it is Polyline || (it is Marker && it.id != POSITION_ID) }
                addSpeedColouredRoute(map, path)
                if (showEndpoints && path.size >= 2) {
                    map.overlays += pin(map, path.first(), th.accent.toColor().toArgb(), th, "Start")
                    map.overlays += pin(map, path.last(), th.textMain.toColor().toArgb(), th, "End")
                }
                if (!followPosition && path.size >= 2) {
                    val box = BoundingBox.fromGeoPoints(path.map { GeoPoint(it.lat, it.lon) })
                    map.post { map.zoomToBoundingBox(box.increaseByScale(1.25f), false) }
                }
            }

            val marker = holder.position
            if (position != null) {
                val gp = GeoPoint(position.lat, position.lon)
                if (marker == null) {
                    holder.position = Marker(map).apply {
                        id = POSITION_ID
                        icon = positionIcon(th).toDrawable(map.resources)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        this.position = gp
                        title = "Now"
                    }.also { map.overlays += it }
                } else {
                    marker.position = gp
                    map.overlays.remove(marker); map.overlays += marker  // keep on top
                }
                if (followPosition) map.controller.animateTo(gp)
            } else if (marker != null) {
                map.overlays.remove(marker)
                holder.position = null
            }
            map.invalidate()
        }
    }
}

private const val POSITION_ID = "position"

private class MapHolder {
    var pathKey: Any? = null
    var theme: AppTheme? = null
    var position: Marker? = null
}

/** One polyline per run of equal speed band, so colour changes along the route. */
private fun addSpeedColouredRoute(map: MapView, path: List<MapPoint>) {
    if (path.size < 2) return
    var run = mutableListOf(GeoPoint(path[0].lat, path[0].lon))
    var runColor = speedColor(path[0].speedKmh)
    fun flush() {
        if (run.size < 2) return
        map.overlays += Polyline(map).apply {
            setPoints(run)
            outlinePaint.color = runColor.toArgb()
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.isAntiAlias = true
            infoWindow = null
        }
    }
    for (i in 1 until path.size) {
        val c = speedColor(path[i - 1].speedKmh)
        val gp = GeoPoint(path[i].lat, path[i].lon)
        if (c == runColor) {
            run += gp
        } else {
            flush()
            run = mutableListOf(GeoPoint(path[i - 1].lat, path[i - 1].lon), gp)
            runColor = c
        }
    }
    flush()
}

private fun pin(map: MapView, p: MapPoint, color: Int, th: AppTheme, label: String) = Marker(map).apply {
    position = GeoPoint(p.lat, p.lon)
    icon = pinIcon(color, th.panel.toColor().toArgb()).toDrawable(map.resources)
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    title = label
}

private fun pinIcon(outer: Int, inner: Int): Bitmap {
    val w = 72
    val h = 96
    val pad = 4.5f
    val bmp = createBitmap(w, h)
    val c = Canvas(bmp)
    val path = android.graphics.Path().apply {
        moveTo(w / 2f, h.toFloat())
        cubicTo(w - pad, h * 0.65f, w - pad, w / 2f, w - pad, w / 2f)
        arcTo(pad, pad, w - pad, w - pad, 0f, -180f, false)
        cubicTo(pad, w / 2f, pad, h * 0.65f, w / 2f, h.toFloat())
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawPath(path, paint.apply { color = outer })
    c.drawCircle(w / 2f, w / 2f, (w - 2 * pad) / 2.5f, paint.apply { color = inner })
    c.drawCircle(w / 2f, w / 2f, (w - 2 * pad) / 4.5f, paint.apply { color = outer })
    return bmp
}

private fun positionIcon(th: AppTheme): Bitmap {
    val s = 64
    val bmp = createBitmap(s, s)
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawCircle(s / 2f, s / 2f, s / 2f, paint.apply { color = th.accent.toColor().copy(alpha = 0.3f).toArgb() })
    c.drawCircle(s / 2f, s / 2f, s / 3.2f, paint.apply { color = android.graphics.Color.WHITE })
    c.drawCircle(s / 2f, s / 2f, s / 4.6f, paint.apply { color = th.accent.toColor().toArgb() })
    return bmp
}
