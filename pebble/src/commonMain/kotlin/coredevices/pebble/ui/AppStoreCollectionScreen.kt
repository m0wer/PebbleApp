package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.filter
import co.touchlab.kermit.Logger
import coredevices.database.HEARTED_COLLECTION_SLUG
import coredevices.database.AppstoreSourceDao
import coredevices.pebble.Platform
import coredevices.pebble.services.AppstoreCollectionSort
import coredevices.pebble.services.AppstoreService
import coredevices.pebble.services.isRebbleFeed
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

class AppStoreCollectionScreenViewModel(
    val libPebble: LibPebble,
    val platform: Platform,
    val appstoreSourceDao: AppstoreSourceDao,
    appstoreSourceId: Int,
    val path: String,
    val appType: AppType?,
): ViewModel(), KoinComponent {
    val logger = Logger.withTag("AppStoreCollectionScreenVM")
    var loadedApps by mutableStateOf<Flow<PagingData<CommonApp>>?>(null)
    var selectedSort by mutableStateOf(AppstoreCollectionSort.Default)
    var supportsMostLikedSorting by mutableStateOf(false)
    private var loadedAppsWatchType: WatchType? = null
    val appstoreService = viewModelScope.async {
        val source = appstoreSourceDao.getSourceById(appstoreSourceId)!!
        get<AppstoreService> { parametersOf(source) }
    }

    private fun load(watchType: WatchType) {
        viewModelScope.launch {
            val service = appstoreService.await()
            supportsMostLikedSorting = service.source.isRebbleFeed()
            val appTypeForFetch = when {
                path.contains("category") -> null
                else -> appType
            }
            loadedApps = Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                pagingSourceFactory = {
                    service.fetchAppStoreCollection(
                        path,
                        appTypeForFetch,
                        watchType,
                        selectedSort,
                    )
                },
            ).flow.cachedIn(viewModelScope)
        }
    }

    fun maybeLoad(watchType: WatchType) {
        if (loadedApps == null || loadedAppsWatchType != watchType) {
            loadedAppsWatchType = watchType
            load(watchType)
        }
    }

    fun selectSort(sort: AppstoreCollectionSort) {
        if (selectedSort != sort) {
            selectedSort = sort
            loadedAppsWatchType?.let(::load)
        }
    }
}

@Composable
fun AppStoreCollectionScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams,
    sourceId: Int,
    path: String,
    title: String,
    appType: AppType?,
) {
    val viewModel = koinViewModel<AppStoreCollectionScreenViewModel> {
        parametersOf(
            sourceId,
            path,
            appType
        )
    }
    val sharedViewModel: SharedLockerViewModel = koinInject()
    sharedViewModel.Init()
    LaunchedEffect(sharedViewModel.watchType.value) {
        viewModel.maybeLoad(sharedViewModel.watchType.value)
    }
    val apps = remember(viewModel.loadedApps, sharedViewModel.showScaled.value, sharedViewModel.showIncompatible.value) {
        viewModel.loadedApps?.map { pagingData ->
            val seenIds = mutableSetOf<String>()
            pagingData.filter { app ->
                if (!seenIds.add("${app.storeId}-${app.uuid}")) {
                    false
                } else if (!sharedViewModel.showScaled.value && !app.isNativelyCompatible) {
                    false
                } else if (!sharedViewModel.showIncompatible.value && !app.isCompatible) {
                    false
                } else {
                    true
                }
            }
        }
    }?.collectAsLazyPagingItems()
    LaunchedEffect(title) {
        topBarParams.title(title)
        topBarParams.actions {}
        topBarParams.searchAvailable(null)
    }
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).fillMaxSize()) {
        Column {
            AppsFilterRow(
                selectedType = null,
                sharedLockerViewModel = sharedViewModel,
                showWatchfaceOrderSetting = false,
            )
            if (
                viewModel.supportsMostLikedSorting &&
                path != "collection/$HEARTED_COLLECTION_SLUG"
            ) {
                AppstoreCollectionSortFilter(
                    selectedSort = viewModel.selectedSort,
                    onSortSelected = viewModel::selectSort,
                )
            }
            if (apps == null || apps.loadState.refresh is LoadState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                when (appType) {
                    AppType.Watchface, null -> {
                        LazyVerticalGrid(
                            columns = GridCells.FixedSize(120.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            items(
                                count = apps.itemCount,
                                key = apps.itemKey { "${it.storeId}-${it.uuid}" }
                            ) { index ->
                                val entry = apps[index]!!
                                NativeWatchfaceCard(
                                    entry,
                                    navBarNav,
                                    width = 120.dp,
                                    topBarParams = topBarParams,
                                    highlightInLocker = true,
                                )
                            }
                        }
                    }
                    AppType.Watchapp -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                        ) {
                            items(
                                count = apps.itemCount,
                                key = apps.itemKey { "${it.storeId}-${it.uuid}" }
                            ) { index ->
                                val entry = apps[index]!!
                                NativeWatchfaceListItem(
                                    entry,
                                    onClick = {
                                        navBarNav.navigateTo(
                                            PebbleNavBarRoutes.LockerAppRoute(
                                                uuid = entry.uuid.toString(),
                                                storedId = entry.storeId,
                                                storeSource = entry.appstoreSource?.id,
                                            )
                                        )
                                    },
                                    topBarParams = topBarParams,
                                    highlightInLocker = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppstoreCollectionSortFilter(
    selectedSort: AppstoreCollectionSort,
    onSortSelected: (AppstoreCollectionSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        FilterChip(
            selected = selectedSort != AppstoreCollectionSort.Default,
            onClick = { expanded = !expanded },
            label = { Text(selectedSort.displayName) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AppstoreCollectionSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.displayName) },
                    onClick = {
                        onSortSelected(sort)
                        expanded = false
                    },
                    leadingIcon = if (sort == selectedSort) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Done,
                                contentDescription = "Selected",
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private val AppstoreCollectionSort.displayName: String
    get() = when (this) {
        AppstoreCollectionSort.Default -> "Default"
        AppstoreCollectionSort.MostLiked -> "Most liked"
    }
