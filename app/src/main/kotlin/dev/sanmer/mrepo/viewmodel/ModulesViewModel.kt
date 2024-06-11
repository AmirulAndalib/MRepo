package dev.sanmer.mrepo.viewmodel

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sanmer.mrepo.Compat
import dev.sanmer.mrepo.datastore.ModulesMenuCompat
import dev.sanmer.mrepo.datastore.Option
import dev.sanmer.mrepo.model.json.UpdateJson
import dev.sanmer.mrepo.model.local.LocalModule
import dev.sanmer.mrepo.model.local.State
import dev.sanmer.mrepo.model.online.VersionItem
import dev.sanmer.mrepo.repository.LocalRepository
import dev.sanmer.mrepo.repository.ModulesRepository
import dev.sanmer.mrepo.repository.UserPreferencesRepository
import dev.sanmer.mrepo.service.DownloadService
import dev.sanmer.mrepo.stub.IModuleOpsCallback
import dev.sanmer.mrepo.utils.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ModulesViewModel @Inject constructor(
    private val localRepository: LocalRepository,
    private val modulesRepository: ModulesRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {
    private val mm get() = Compat.moduleManager
    val isProviderAlive get() = Compat.isAlive

    private val modulesMenu get() = userPreferencesRepository.data
        .map { it.modulesMenu }

    var isSearch by mutableStateOf(false)
        private set
    private val keyFlow = MutableStateFlow("")

    private val cacheFlow = MutableStateFlow(listOf<LocalModule>())
    private val localFlow = MutableStateFlow(listOf<LocalModule>())
    val local get() = localFlow.asStateFlow()

    var isLoading by mutableStateOf(true)
        private set

    private val versionItemCache = mutableStateMapOf<String, VersionItem?>()

    private val opsTasks = mutableStateListOf<String>()
    private val opsCallback = object : IModuleOpsCallback.Stub() {
        override fun onSuccess(id: String) {
            viewModelScope.launch {
                modulesRepository.getLocal(id)
                opsTasks.remove(id)
            }
        }

        override fun onFailure(id: String, msg: String?) {
            opsTasks.remove(id)
            Timber.w("$id: $msg")
        }
    }

    init {
        Timber.d("ModulesViewModel init")
        providerObserver()
        dataObserver()
        keyObserver()
    }

    private fun providerObserver() {
        Compat.isAliveFlow
            .onEach {
                if (it) getLocalAll()

            }.launchIn(viewModelScope)
    }

    private fun dataObserver() {
        combine(
            localRepository.getLocalAllAsFlow(),
            modulesMenu
        ) { list, menu ->
            if (list.isEmpty()) return@combine

            cacheFlow.value  = list.sortedWith(
                comparator(menu.option, menu.descending)
            ).let { v ->
                if (menu.pinEnabled) {
                    v.sortedByDescending { it.state == State.ENABLE }
                } else {
                    v
                }
            }

            isLoading = false

        }.launchIn(viewModelScope)
    }

    private fun keyObserver() {
        combine(
            keyFlow,
            cacheFlow
        ) { key, source ->
            localFlow.value = source
                .filter {
                    if (key.isNotBlank()) {
                        it.name.contains(key, ignoreCase = true)
                                || it.author.contains(key, ignoreCase = true)
                                || it.description.contains(key, ignoreCase = true)
                    } else {
                        true
                    }
                }

        }.launchIn(viewModelScope)
    }

    private fun comparator(
        option: Option,
        descending: Boolean
    ): Comparator<LocalModule> = if (descending) {
        when (option) {
            Option.Name -> compareByDescending { it.name.lowercase() }
            Option.UpdatedTime -> compareBy { it.lastUpdated }
            else -> compareByDescending { null }
        }

    } else {
        when (option) {
            Option.Name -> compareBy { it.name.lowercase() }
            Option.UpdatedTime -> compareByDescending { it.lastUpdated }
            else -> compareByDescending { null }
        }
    }

    fun search(key: String) {
        keyFlow.value = key
    }

    fun openSearch() {
        isSearch = true
    }

    fun closeSearch() {
        isSearch = false
        keyFlow.value = ""
    }

    private fun getLocalAll() {
        viewModelScope.launch {
            modulesRepository.getLocalAll()
        }
    }

    fun setModulesMenu(value: ModulesMenuCompat) {
        viewModelScope.launch {
            userPreferencesRepository.setModulesMenu(value)
        }
    }

    fun createModuleOps(module: LocalModule) = when (module.state) {
        State.ENABLE -> ModuleOps(
            isOpsRunning = opsTasks.contains(module.id),
            toggle = {
                opsTasks.add(module.id)
                mm.disable(module.id, opsCallback)
            },
            change = {
                opsTasks.add(module.id)
                mm.remove(module.id, opsCallback)
            }
        )

        State.DISABLE -> ModuleOps(
            isOpsRunning = opsTasks.contains(module.id),
            toggle = {
                opsTasks.add(module.id)
                mm.enable(module.id, opsCallback)
            },
            change = {
                opsTasks.add(module.id)
                mm.remove(module.id, opsCallback)
            }
        )

        State.REMOVE -> ModuleOps(
            isOpsRunning = opsTasks.contains(module.id),
            toggle = {},
            change = {
                opsTasks.add(module.id)
                mm.enable(module.id, opsCallback)
            }
        )

        State.UPDATE -> ModuleOps(
            isOpsRunning = opsTasks.contains(module.id),
            toggle = {},
            change = {}
        )
    }

    @Composable
    fun getVersionItem(module: LocalModule): VersionItem? {
        val item by remember {
            derivedStateOf { versionItemCache[module.id] }
        }

        LaunchedEffect(module) {
            if (!localRepository.hasUpdatableTag(module.id)) {
                versionItemCache.remove(module.id)
                return@LaunchedEffect
            }

            if (versionItemCache.containsKey(module.id)) {
                return@LaunchedEffect
            }

            val versionItem = if (module.updateJson.isNotBlank()) {
                UpdateJson.load(module.updateJson)
            } else {
                localRepository.getVersionById(module.id).firstOrNull()
            }

            versionItemCache[module.id] = versionItem
        }

        return item
    }

    fun downloader(
        context: Context,
        module: LocalModule,
        item: VersionItem,
        onSuccess: (File) -> Unit
    ) {
        viewModelScope.launch {
            val downloadPath = userPreferencesRepository.data
                .first().downloadPath

            val filename = Utils.getFilename(
                name = module.name,
                version = item.version,
                versionCode = item.versionCode,
                extension = "zip"
            )

            val task = DownloadService.TaskItem(
                key = item.toString(),
                url = item.zipUrl,
                filename = filename,
                title = module.name,
                desc = item.versionDisplay
            )

            val listener = object : DownloadService.IDownloadListener {
                override fun getProgress(value: Float) {}
                override fun onSuccess() {
                    onSuccess(downloadPath.resolve(filename))
                }

                override fun onFailure(e: Throwable) {
                    Timber.d(e)
                }
            }

            DownloadService.start(
                context = context,
                task = task,
                listener = listener
            )
        }
    }

    @Composable
    fun getProgress(item: VersionItem?): Float {
        val progress by DownloadService.getProgressByKey(item.toString())
            .collectAsStateWithLifecycle(initialValue = 0f)

        return progress
    }

    data class ModuleOps(
        val isOpsRunning: Boolean,
        val toggle: (Boolean) -> Unit,
        val change: () -> Unit
    )
}