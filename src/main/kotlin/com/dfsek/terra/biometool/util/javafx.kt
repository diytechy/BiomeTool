package com.dfsek.terra.biometool.util

import com.dfsek.terra.biometool.BiomeImageGenerator
import com.dfsek.terra.biometool.map.MapView
import javafx.event.EventTarget
import kotlinx.coroutines.CoroutineScope
import tornadofx.attachTo

fun EventTarget.mapview(
    scope: CoroutineScope,
    tileGenerator: BiomeImageGenerator,
    op: MapView.() -> Unit = {},
                       ) = MapView(scope, tileGenerator).attachTo(this, op) {
}