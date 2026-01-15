package com.dfsek.terra.biometool

import com.dfsek.terra.api.Platform
import com.dfsek.terra.api.config.ConfigPack
import com.dfsek.terra.api.registry.key.RegistryKey
import com.dfsek.terra.biometool.console.TextAreaOutputStream
import com.dfsek.terra.biometool.logback.OutputStreamAppender
import com.dfsek.terra.biometool.logback.ReloadLogAppender
import com.dfsek.terra.biometool.map.MapView
import com.dfsek.terra.biometool.util.currentThread
import com.dfsek.terra.biometool.util.mapview
import com.dfsek.terra.biometool.util.processors
import com.dfsek.terra.biometool.util.runtime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import javafx.application.Platform.exit
import javafx.application.Platform.runLater
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TabPane.TabClosingPolicy
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.slf4j.kotlin.getLogger
import org.slf4j.kotlin.info
import tornadofx.View
import tornadofx.action
import tornadofx.button
import tornadofx.combobox
import tornadofx.filterInput
import tornadofx.fitToParentHeight
import tornadofx.fitToParentSize
import tornadofx.hbox
import tornadofx.importStylesheet
import tornadofx.isLong
import tornadofx.item
import tornadofx.label
import tornadofx.menu
import tornadofx.menubar
import tornadofx.select
import tornadofx.selectedItem
import tornadofx.singleAssign
import tornadofx.stackpane
import tornadofx.tab
import tornadofx.tabpane
import tornadofx.textarea
import tornadofx.textfield
import tornadofx.toObservable
import tornadofx.vbox
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.exitProcess


class BiomeToolView : View("Biome Tool") {
    private val logger by getLogger()
    
    private val platform: Platform
    
    private var toolWindows by singleAssign<ToolWindows>()
    
    private var seed by singleAssign<TextField>()
    
    private var packSelection by singleAssign<ComboBox<RegistryKey>>()
    
    private var renderTabs by singleAssign<TabPane>()
    
    private var consoleTextArea by singleAssign<TextArea>()
    
    private var biomeID by singleAssign<Label>()
    
    private var coordsLabel by singleAssign<Label>()
    
    private val tabStates = mutableMapOf<Tab, TabState>()
    
    private var loadingOverlay by singleAssign<StackPane>()
    
    private var loadingLogView by singleAssign<LoadingLogView>()
    
    init {
        logger.info { "Initializing Terra platform..." }
        platform = BiomeToolPlatform
        platform.reload()
        logger.info { "Terra platform initialized successfully" }
    }
    
    override val root = stackpane {
        importStylesheet("/javafx-darktheme.css")
        
        vbox {
            minHeight = 720.0
            minWidth = 1280.0
            
            menubar {
                menu("File") {
                    item("Reload") {
                        action(::reload)
                    }
                    item("Quit") {
                        action(::exitApplication)
                    }
                }
                menu("View") {
                    menu("Tool Windows") {
                        item("World Preview") {
                            action {
                                toolWindows.worldPreview.select()
                            }
                        }
                        item("Console") {
                            action {
                                toolWindows.console.select()
                            }
                        }
                        item("Performance") {
                            action {
                                toolWindows.console.select()
                            }
                        }
                    }
                    menu("Appearance") {
                        isDisable = true
                        item("Change Theme") {
                            isDisable = true
                        }
                    }
                    menu("Overlay") {
                        isDisable = true
                        item("Chunk Borders") {
                            isDisable = true
                        }
                    }
                }
                menu("Tools") {
                    item("Reload Packs") {
                        action(::reload)
                    }
                }
                menu("Help") {
                    item("About") {
                        isDisable = true
                    }
                    item("License") {
                        isDisable = true
                    }
                }
            }
            
            tabpane {
                tabClosingPolicy = TabClosingPolicy.UNAVAILABLE
                fitToParentSize()
                
                val worldPreview = tab("World Preview") {
                    vbox {
                        val top = hbox(6) {
                            alignment = Pos.CENTER_LEFT
                            padding = Insets(4.0, 8.0, 4.0, 8.0)
                            
                            label("Pack")
                            
                            packSelection = combobox {
                                val configs = platform.configRegistry.keys().toList()
                                
                                items = configs.toObservable()
                                selectionModel.selectFirst()
                            }
                            
                            button("Rerender") {
                                action {
                                    addBiomeViewTab()
                                }
                            }
                            
                            button("Reload Packs") {
                                action(::reload)
                            }
                            
                            label("Seed") {
                                padding = Insets(0.0, 0.0, 0.0, 16.0)
                            }
                            
                            seed = textfield {
                                text = "0"
                                filterInput { it.controlNewText.isLong() }
                            }
                            
                            button("Random Seed") {
                                action {
                                    seed.text = random.nextLong().toString()
                                    
                                    addBiomeViewTab(seedLong = random.nextLong())
                                }
                            }
                            
                            label("Coordinates:") {
                                padding = Insets(0.0, 0.0, 0.0, 16.0)
                            }
                            
                            coordsLabel = label("0, 0")
                            
                            label("Biome:") {
                                padding = Insets(0.0, 0.0, 0.0, 16.0)
                            }
                            
                            biomeID = label("")
                        }
                        
                        renderTabs = tabpane {
                            tabClosingPolicy = TabClosingPolicy.ALL_TABS
                            fitToParentSize()
                        }
                        
                        if (packSelection.selectedItem != null) {
                            addBiomeViewTab(selectedPack = packSelection.selectedItem!!, seedLong = random.nextLong())
                        }
                    }
                }
                val performance = tab("Performance") {
                    textarea {
                        font = Font(30.0)
                        text = """
                            TODO
                            
                            Will be finished later.
                        """.trimIndent()
                    }
                    fitToParentSize()
                    
                }
                val console = tab("Console") {
                    vbox {
                        styleClass += "console"
                        consoleTextArea = textarea {
                            OutputStreamAppender.outputStream = TextAreaOutputStream(this)
                        }
                        consoleTextArea.fitToParentSize()
                        
                        fitToParentSize()
                    }
                    fitToParentSize()
                }
                
                toolWindows = ToolWindows(worldPreview, performance, console)
            }
        }
        
        loadingOverlay = stackpane {
            style = "-fx-background-color: rgba(0, 0, 0, 0.7);"
            isVisible = false
            
            vbox(10.0) {
                alignment = Pos.CENTER
                padding = Insets(0.0, 0.0, 50.0, 0.0)
                
                add(ProgressIndicator().apply {
                    maxWidth = 50.0
                    maxHeight = 50.0
                })
                label("Reloading packs...") {
                    style = "-fx-text-fill: white; -fx-font-size: 14px;"
                }
                loadingLogView = LoadingLogView().apply {
                    padding = Insets(20.0, 0.0, 0.0, 0.0)
                }
                add(loadingLogView)
            }
        }
    }
    
    private fun reload() {
        val selectedTabIndex = renderTabs.selectionModel.selectedIndex
        
        val savedStates = tabStates.values.map { state ->
            val mapView = state.mapView
            state.copy(x = mapView.x, y = mapView.y, zoomLevel = mapView.zoomLevel)
        }
        
        tabStates.values.forEach { it.mapView.close() }
        tabStates.clear()
        renderTabs.tabs.clear()
        
        loadingLogView.clear()
        loadingOverlay.isVisible = true
        
        ReloadLogAppender.listener = { message ->
            loadingLogView.addLog(message)
        }
        
        thread {
            platform.reload()
            
            runLater {
                ReloadLogAppender.listener = null
                
                val configs = platform.configRegistry.keys().toList()
                packSelection.items = configs.toObservable()
                packSelection.selectionModel.selectFirst()
                
                for (state in savedStates) {
                    if (platform.configRegistry.contains(state.packKey)) {
                        val pack = platform.configRegistry[state.packKey].get()
                        addBiomeViewTab(
                            selectedPack = state.packKey,
                            pack = pack,
                            seedLong = state.seed,
                            initialX = state.x,
                            initialY = state.y,
                            initialZoom = state.zoomLevel
                        )
                    }
                }
                
                if (selectedTabIndex >= 0 && selectedTabIndex < renderTabs.tabs.size) {
                    renderTabs.selectionModel.select(selectedTabIndex)
                }
                
                loadingOverlay.isVisible = false
            }
        }
    }
    
    private fun exitApplication() {
        exit()
        exitProcess(0)
    }
    
    private fun addBiomeViewTab(
        selectedPack: RegistryKey = packSelection.selectedItem!!,
        pack: ConfigPack = platform.configRegistry[selectedPack].get(),
        seedLong: Long = seed.text.toLong(),
        initialX: Double = 0.0,
        initialY: Double = 0.0,
        initialZoom: Double = 0.0,
                               ): Tab {
        return renderTabs.tab("$selectedPack:$seedLong") {
            select()
            
            val mapView = mapview(BiomeToolView.scope, TerraBiomeImageGenerator(seedLong, pack), 128) {
                fitToParentSize()
                if (initialX != 0.0 || initialY != 0.0 || initialZoom != 0.0) {
                    setPosition(initialX, initialY, initialZoom)
                }
            }
            
            tabStates[this] = TabState(selectedPack, seedLong, mapView, initialX, initialY, initialZoom)
            
            mapView.setOnMouseMoved {
                val worldX = (mapView.x + it.x / mapView.zoom).roundToInt()
                val worldZ = (mapView.y + it.y / mapView.zoom).roundToInt()
                
                coordsLabel.text = "$worldX, $worldZ"
                biomeID.text = mapView.configPack
                    .biomeProvider
                    .getBiome(worldX, 0, worldZ, mapView.seed)
                    .id
            }
            
            setOnClosed {
                tabStates.remove(this)?.mapView?.close()
            }
        }
    }
    
    init {
        primaryStage.setOnCloseRequest {
            exitApplication()
        }
    }
    
    object BiomeToolThreadFactory : ThreadFactory {
        private val threadGroup: ThreadGroup = currentThread.threadGroup
        private var threadCount: Int = 0
        
        override fun newThread(runnable: Runnable): Thread = Thread(
            threadGroup,
            runnable,
            "BiomeTool-Worker-${threadCount++}",
            0
                                                                   )
    }
    
    companion object {
        private val random = Random(Random.nextLong())
        private val scheduledThreadPool: ScheduledExecutorService =
            Executors.newScheduledThreadPool((runtime.processors).coerceAtLeast(1), BiomeToolThreadFactory)
        
        private val coroutineDispatcher: ExecutorCoroutineDispatcher = scheduledThreadPool.asCoroutineDispatcher()
        
        val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    }
    
    internal data class ToolWindows(
        val worldPreview: Tab,
        val performance: Tab,
        val console: Tab,
                                   )
    
    private data class TabState(
        val packKey: RegistryKey,
        val seed: Long,
        val mapView: MapView,
        val x: Double = 0.0,
        val y: Double = 0.0,
        val zoomLevel: Double = 0.0,
                                )
    
}
