package com.smartride.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class MapPoint(val lat: Double, val lon: Double, val speedKmh: Double = 0.0)

/*
 * Vector maps: MapLibre Native rendering OpenFreeMap tiles (OpenStreetMap data).
 * Roads and labels are drawn as geometry on the GPU, so the map is sharp at any
 * screen density and zoom level -- unlike raster tiles stretched to phone DPI --
 * and no API key is needed.
 */
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/fiord"

private const val SRC_ROUTE = "sr-route"
private const val SRC_PINS = "sr-pins"
private const val SRC_POS = "sr-pos"

/** Speed-coloured route with start/end pins and an optional live position marker. */
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
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    val state = remember { RenderState() }

    val mapView = remember {
        // Texture mode: the map composites like a normal view, so it clips to rounded
        // corners and moves correctly inside scrolling / sliding Compose content.
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .logoEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .attributionGravity(Gravity.BOTTOM or Gravity.START)
        MapView(context, options).apply {
            onCreate(null)
            // Keep pan/zoom gestures on the map while the page around it scrolls.
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            getMapAsync { map = it }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)  // replays START/RESUME if already resumed
        onDispose {
            lifecycle.removeObserver(obs)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // (Re)load the style when the map is ready or the theme changes.
    val styleUrl = if (th.isLight) STYLE_LIGHT else STYLE_DARK
    DisposableEffect(map, styleUrl, th.name) {
        val m = map
        if (m != null) {
            loadedStyle = null
            m.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                addOverlayLayers(style, th)
                state.reset()
                loadedStyle = style
            }
        }
        onDispose { }
    }

    AndroidView(factory = { mapView }, modifier = modifier) {
        val m = map ?: return@AndroidView
        val style = loadedStyle ?: return@AndroidView
        state.render(m, mapView, style, path, position, followPosition, showEndpoints)
    }
}

/** Remembers what is already drawn so each update only touches what changed. */
private class RenderState {
    private var pathKey: Any? = null
    private var hasCamera = false

    fun reset() {
        pathKey = null
        hasCamera = false
    }

    fun render(
        m: MapLibreMap, view: MapView, style: Style, path: List<MapPoint>, position: MapPoint?,
        follow: Boolean, endpoints: Boolean,
    ) {
        val key = Triple(path.size, path.firstOrNull(), path.lastOrNull())
        if (key != pathKey) {
            pathKey = key
            style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeFeatures(path))
            style.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(
                if (endpoints && path.size >= 2) FeatureCollection.fromFeatures(listOf(
                    pinFeature(path.first(), "sr-pin-start"),
                    pinFeature(path.last(), "sr-pin-end"),
                )) else FeatureCollection.fromFeatures(emptyList()),
            )
            if (!follow && path.size >= 2) {
                val pts = path.map { LatLng(it.lat, it.lon) }.distinct()
                if (pts.size >= 2) {
                    val bounds = LatLngBounds.Builder().includes(pts).build()
                    val pad = (36 * view.resources.displayMetrics.density).toInt()
                    view.post { m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad)) }
                    hasCamera = true
                }
            }
        }

        style.getSourceAs<GeoJsonSource>(SRC_POS)?.setGeoJson(
            if (position == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat))),
        )
        if (position != null) {
            val ll = LatLng(position.lat, position.lon)
            when {
                !hasCamera -> { m.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, 15.5)); hasCamera = true }
                follow -> m.easeCamera(CameraUpdateFactory.newLatLng(ll), 800)
            }
        }
    }
}

private fun addOverlayLayers(style: Style, th: AppTheme) {
    style.addImage("sr-pin-start", pinIcon(th.accent.toColor().toArgb(), th.panel.toColor().toArgb()))
    style.addImage("sr-pin-end", pinIcon(th.textMain.toColor().toArgb(), th.panel.toColor().toArgb()))
    style.addImage("sr-pos", positionIcon(th))

    style.addSource(GeoJsonSource(SRC_ROUTE))
    style.addSource(GeoJsonSource(SRC_PINS))
    style.addSource(GeoJsonSource(SRC_POS))

    val casing = if (th.isLight) "#FFFFFF" else "#101010"
    style.addLayer(LineLayer("sr-route-casing", SRC_ROUTE).withProperties(
        PropertyFactory.lineColor(casing), PropertyFactory.lineWidth(8f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    style.addLayer(LineLayer("sr-route", SRC_ROUTE).withProperties(
        PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(5f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    style.addLayer(SymbolLayer("sr-pins", SRC_PINS).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
    ))
    style.addLayer(SymbolLayer("sr-pos", SRC_POS).withProperties(
        PropertyFactory.iconImage("sr-pos"), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
    ))
}

/** One LineString per run of equal speed band, each carrying its colour. */
private fun routeFeatures(path: List<MapPoint>): FeatureCollection {
    if (path.size < 2) return FeatureCollection.fromFeatures(emptyList())
    val features = ArrayList<Feature>()
    var run = arrayListOf(Point.fromLngLat(path[0].lon, path[0].lat))
    var color = speedColor(path[0].speedKmh)
    fun flush() {
        if (run.size < 2) return
        features += Feature.fromGeometry(LineString.fromLngLats(run)).apply {
            addStringProperty("color", "#%06X".format(color.toArgb() and 0xFFFFFF))
        }
    }
    for (i in 1 until path.size) {
        val c = speedColor(path[i - 1].speedKmh)
        val p = Point.fromLngLat(path[i].lon, path[i].lat)
        if (c == color) run += p
        else {
            flush()
            run = arrayListOf(Point.fromLngLat(path[i - 1].lon, path[i - 1].lat), p)
            color = c
        }
    }
    flush()
    return FeatureCollection.fromFeatures(features)
}

private fun pinFeature(p: MapPoint, icon: String) =
    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("icon", icon) }

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
