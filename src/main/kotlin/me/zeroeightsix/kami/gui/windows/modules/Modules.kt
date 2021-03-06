package me.zeroeightsix.kami.gui.windows.modules

import glm_.vec4.Vec4
import imgui.Col
import imgui.ImGui
import imgui.ImGui.acceptDragDropPayload
import imgui.ImGui.collapsingHeader
import imgui.ImGui.currentWindow
import imgui.ImGui.isItemClicked
import imgui.ImGui.openPopupContextItem
import imgui.ImGui.selectable
import imgui.ImGui.treeNodeBehaviorIsOpen
import imgui.ImGui.treeNodeEx
import imgui.MouseButton
import imgui.StyleVar
import imgui.TreeNodeFlag
import imgui.WindowFlag
import imgui.dsl
import imgui.dsl.dragDropTarget
import imgui.dsl.popup
import imgui.dsl.window
import imgui.internal.sections.ItemStatusFlag
import imgui.or
import io.github.fablabsmc.fablabs.api.fiber.v1.FiberId
import io.github.fablabsmc.fablabs.api.fiber.v1.annotation.Setting
import io.github.fablabsmc.fablabs.api.fiber.v1.tree.ConfigLeaf
import io.github.fablabsmc.fablabs.impl.fiber.annotation.BackedConfigLeaf
import me.zeroeightsix.kami.feature.FeatureManager
import me.zeroeightsix.kami.feature.FindSettings
import me.zeroeightsix.kami.feature.module.Module
import me.zeroeightsix.kami.flattenedStream
import me.zeroeightsix.kami.gui.View.modulesOpen
import me.zeroeightsix.kami.gui.windows.Settings
import me.zeroeightsix.kami.gui.windows.modules.Payloads.KAMI_MODULE_PAYLOAD
import me.zeroeightsix.kami.kotlin
import me.zeroeightsix.kami.mixin.client.IBackedConfigLeaf
import me.zeroeightsix.kami.setting.getAnyRuntimeConfigType
import me.zeroeightsix.kami.setting.runnerType
import me.zeroeightsix.kami.setting.settingInterface
import me.zeroeightsix.kami.setting.visibilityType
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

@FindSettings
object Modules {

    var resize: Boolean = false
    var preferCategoryWindows = true

    @Setting(name = "Windows")
    internal var windows = getDefaultWindows()
    private val newWindows = mutableSetOf<ModuleWindow>()
    private val baseFlags =
        TreeNodeFlag.SpanFullWidth or TreeNodeFlag.OpenOnDoubleClick or TreeNodeFlag.NoTreePushOnOpen

    /**
     * Returns if this module has detached
     */
    fun module(
        module: Module,
        source: ModuleWindow,
        sourceGroup: String
    ): ModuleWindow? {
        val nodeFlags = if (!module.enabled) baseFlags else (baseFlags or TreeNodeFlag.Selected)
        val label = "${module.name}-node"
        var moduleWindow: ModuleWindow? = null

        var clickedLeft = false
        var clickedRight = false

        fun updateClicked() {
            clickedLeft = isItemClicked(if (Settings.swapModuleListButtons) MouseButton.Left else MouseButton.Right)
            clickedRight = isItemClicked(if (Settings.swapModuleListButtons) MouseButton.Right else MouseButton.Left)
        }

        if (!Settings.openSettingsInPopup) {
            // We don't want imgui to handle open/closing at all, so we hack out the behaviour
            val doubleClicked = ImGui.io.mouseDoubleClicked[0]
            ImGui.io.mouseDoubleClicked[0] = false

            val open = treeNodeEx(label, nodeFlags, module.name)
            dragDropTarget {
                acceptDragDropPayload(KAMI_MODULE_PAYLOAD)?.let {
                    val payload = it.data!! as ModulePayload
                    payload.moveTo(source, sourceGroup)
                }
            }
            if (open) {
                updateClicked()
                showModuleSettings(module)
            } else updateClicked()

            // Restore state
            ImGui.io.mouseDoubleClicked[0] = doubleClicked
        } else {
            if (selectable(module.name, module.enabled)) {
                module.enabled = !module.enabled
            }
            openPopupContextItem("module-settings-${module.name}", MouseButton.Right.i)
            popup("module-settings-${module.name}") {
                showModuleSettings(module)
            }
        }

        if (clickedLeft) {
            module.enabled = !module.enabled
        } else if (clickedRight) {
            val id = currentWindow.getID(label)
            val open = treeNodeBehaviorIsOpen(id, nodeFlags)
            val window = currentWindow
            window.dc.stateStorage[id] = !open
            window.dc.lastItemStatusFlags = window.dc.lastItemStatusFlags or ItemStatusFlag.ToggledOpen.i
        }

        return moduleWindow
    }

    operator fun invoke() {
        if (modulesOpen) {
            windows.removeIf(ModuleWindow::draw)
            if (windows.addAll(newWindows)) {
                newWindows.clear()
            }
            resize = false

            ModuleWindowsEditor()
        }
    }

    fun getDefaultWindows(): Windows {
        return if (preferCategoryWindows) { // Generate windows per-category
            var id = 0
            Windows(
                FeatureManager.modules.groupBy { it.category.getName() }.mapTo(mutableListOf()) {
                    ModuleWindow(
                        it.key,
                        mapOf(it.key to it.value.toMutableList()),
                        id++
                    )
                }
            )
        } else { // Generate one window with all modules in it
            Windows(
                mutableListOf(
                    ModuleWindow(
                        "All modules",
                        groups = FeatureManager.modules.filter { !it.hidden && !it.category.isHidden }.groupBy {
                            it.category.getName()
                        }.mapValuesTo(mutableMapOf(), { entry -> entry.value.toMutableList() }),
                        id = 0
                    )
                )
            )
        }
    }

    private fun nextId(): Int {
        var id = 0
        while (windows.map { it.id }.contains(id)) id += 1
        return id
    }

    fun reset() {
        windows = getDefaultWindows()
    }

    class ModuleWindow(
        internal var title: String,
        var groups: Map<String, MutableList<Module>> = mapOf(),
        val id: Int = nextId()
    ) {

        constructor(title: String, module: Module) : this(
            title,
            mapOf(Pair("Group 1", mutableListOf(module)))
        )

        var closed = false

        fun draw(): Boolean {
            fun iterateModules(list: MutableList<Module>, group: String): Boolean {
                var ret: Boolean = false
                dsl.withStyleVar(StyleVar.SelectableTextAlign, Settings.moduleAlignment.vecAlignment) {
                    ret = list.removeIf {
                        val moduleWindow = module(it, this, group)
                        moduleWindow?.let {
                            newWindows.add(moduleWindow)
                            return@removeIf true
                        }
                        return@removeIf false
                    }
                }
                return ret
            }

            val flags = if (resize) {
                WindowFlag.AlwaysAutoResize.i
            } else {
                0
            }

            window("$title###ModuleWindow$id", flags = flags) {
                when {
                    groups.isEmpty() -> {
                        return true // close this window
                    }
                    groups.size == 1 -> {
                        val entry = groups.entries.stream().findAny().get()
                        val group = entry.value
                        if (group.isEmpty()) {
                            return true // close this window
                        }

                        iterateModules(group, entry.key)
                    }
                    else -> {
                        for ((group, list) in groups) {
                            if (list.isEmpty()) {
                                continue
                            }

                            if (collapsingHeader(group, TreeNodeFlag.SpanFullWidth.i)) {
                                iterateModules(list, group)
                            }
                        }
                    }
                }
            }

            return closed
        }
    }

    class Windows(val backing: MutableList<ModuleWindow>) : MutableList<ModuleWindow> by backing {
        override fun equals(other: Any?): Boolean {
            return false
        }

        override fun hashCode(): Int {
            return backing.hashCode()
        }
    }
}

private fun showModuleSettings(module: Module) {
    // We introduce a generic <T> to allow displayImGui to be called even though we don't actually know the type of ConfigLeaf we have!
    // the flattenedStream() method returns a stream over ConfigLeaf<*>, where <*> is any type.
    // Because the interface (SettingInterface<T>) requires a ConfigLeaf<T> and we only have a ConfigLeaf<*> and SettingInterface<*> (and <*> != <*>), calling displayImGui is illegal.
    // Instead, we can just avoid this by introducing a generic method that 'proves' (by implicit casting) that our type of ConfigLeaf is the same as its interface.
    // Can't really do this inline (or I don't know how to), so I made a method to do it instead.
    fun <T> ConfigLeaf<T>.displayImGui() {
        val type = getAnyRuntimeConfigType() ?: return
        val value = if (this is BackedConfigLeaf<*, *>) {
            val access = this as IBackedConfigLeaf<*, *>
            access.backingField.get(access.pojo)
        } else {
            type.toRuntimeType(this.value)
        }

        // Each setting may be decorated with an "imgui extra" annotation, which is just a function that gets called after/before displaying the setting.
        getAttributeValue(FiberId("kami", "im_extra_runner_pre"), runnerType).kotlin?.run()

        type.settingInterface?.displayImGui(this.name, value)?.let {
            this.value = type.toSerializedType(it)
        }

        getAttributeValue(FiberId("kami", "im_extra_runner_post"), runnerType).kotlin?.run()
    }

    if (!Settings.hideModuleDescriptions) {
        ImGui.pushStyleColor(Col.Text, Vec4(.7f, .7f, .7f, 1f))
        ImGui.text("%s", module.description)
        ImGui.popStyleColor()
    }

    module.config.flattenedStream().filter {
        it.getAttributeValue(FiberId("kami", "setting_visibility"), visibilityType).map { vis ->
            vis.isVisible()
        }.orElse(true)
    }.forEach {
        it.displayImGui()
    }
}
