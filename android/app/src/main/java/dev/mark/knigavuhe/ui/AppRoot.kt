package dev.mark.knigavuhe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.mark.knigavuhe.App
import dev.mark.knigavuhe.data.remote.BookCard
import dev.mark.knigavuhe.data.remote.KnigavuheApi
import dev.mark.knigavuhe.ui.book.BookScreen
import dev.mark.knigavuhe.ui.book.BookViewModel
import dev.mark.knigavuhe.ui.favorites.FavoritesScreen
import dev.mark.knigavuhe.ui.favorites.FavoritesViewModel
import dev.mark.knigavuhe.ui.library.LibraryScreen
import dev.mark.knigavuhe.ui.library.LibraryViewModel
import dev.mark.knigavuhe.ui.player.MiniPlayer
import dev.mark.knigavuhe.ui.player.PlayerScreen
import dev.mark.knigavuhe.ui.player.PlayerViewModel
import dev.mark.knigavuhe.ui.search.SearchScreen
import dev.mark.knigavuhe.ui.search.SearchViewModel
import dev.mark.knigavuhe.ui.series.SeriesScreen
import dev.mark.knigavuhe.ui.series.SeriesViewModel
import dev.mark.knigavuhe.ui.storage.StorageScreen
import dev.mark.knigavuhe.ui.storage.StorageViewModel
import kotlinx.coroutines.launch

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_PLAYER = "player"
private const val ROUTE_BOOK = "book/{bookId}"
private const val ROUTE_FAVORITES = "favorites"
private const val ROUTE_STORAGE = "storage"
private const val ROUTE_SERIES = "series/{slug}?name={name}"

@Composable
fun AppRoot(incomingLink: String?, onLinkHandled: () -> Unit) {
    val navController = rememberNavController()
    val container = (LocalContext.current.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val playerViewModel: PlayerViewModel = appViewModel("player") { PlayerViewModel(it) }
    val libraryViewModel: LibraryViewModel = appViewModel("library") { LibraryViewModel(it) }
    val playbackState by playerViewModel.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    var nextUp by remember { mutableStateOf<BookCard?>(null) }

    val miniPlayerVisible = playbackState.bookId != null && currentRoute != ROUTE_PLAYER

    // A link shared from the browser opens the book screen directly.
    //
    // The link is cleared only after the lookup finishes: clearing it first changes this effect's
    // key, which cancels the effect mid-request. Links carrying the id in the path used to sneak
    // through, slug-only ones never did.
    LaunchedEffect(incomingLink) {
        val link = incomingLink ?: return@LaunchedEffect
        val extracted = KnigavuheApi.extractLink(link) ?: link
        val resolved = runCatching { container.api.resolveBookId(KnigavuheApi.normalizeUrl(extracted)) }
        onLinkHandled()
        resolved
            .onSuccess { bookId -> navController.navigate("book/$bookId") }
            .onFailure { failure ->
                snackbar.showSnackbar(failure.message ?: "Не удалось открыть ссылку")
            }
    }

    // Restore the last book into the player on a cold start, paused and ready for one tap.
    LaunchedEffect(Unit) { playerViewModel.restoreIfEmpty() }

    // Книга доиграна: если это цикл, предлагаем следующую. Отметку снимаем после поиска —
    // иначе смена ключа отменит эффект на середине запроса.
    LaunchedEffect(playbackState.finishedBookId) {
        val finished = playbackState.finishedBookId ?: return@LaunchedEffect
        val next = runCatching { container.bookRepository.nextInSeries(finished) }.getOrNull()
        playerViewModel.clearFinished()
        if (next != null && !next.isLitres) nextUp = next
    }

    Scaffold(
        // The mini player is a real bottom bar, not an overlay: the content area ends above it,
        // so the last row of any list stays reachable however long the list gets.
        bottomBar = {
            if (miniPlayerVisible) {
                MiniPlayer(
                    state = playbackState,
                    onPlayPause = playerViewModel::playPause,
                    onOpen = { navController.navigate(ROUTE_PLAYER) },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // Screens bring their own top bars and insets; only the bottom bar is ours to reserve.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
    NavHost(navController = navController, startDestination = ROUTE_LIBRARY) {
                    composable(ROUTE_LIBRARY) {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onOpenBook = { navController.navigate("book/$it") },
                            onOpenSearch = { navController.navigate(ROUTE_SEARCH) },
                            onOpenPlayer = { navController.navigate(ROUTE_PLAYER) },
                            onOpenFavorites = { navController.navigate(ROUTE_FAVORITES) },
                            onOpenStorage = { navController.navigate(ROUTE_STORAGE) },
                            onOpenSeries = { slug, name ->
                                navController.navigate("series/$slug?name=" + Uri.encode(name))
                            },
                        )
                    }
                    composable(ROUTE_SEARCH) {
                        val searchViewModel: SearchViewModel = appViewModel("search") { SearchViewModel(it) }
                        SearchScreen(
                            viewModel = searchViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenBook = { path ->
                                val known = KnigavuheApi.idFromUrl(path)
                                if (known != null) {
                                    navController.navigate("book/$known")
                                } else {
                                    // Slug-only links carry no id; it lives in the page itself.
                                    scope.launch {
                                        runCatching {
                                            container.api.resolveBookId(KnigavuheApi.normalizeUrl(path))
                                        }.onSuccess { navController.navigate("book/$it") }
                                    }
                                }
                            },
                        )
                    }
                    composable(
                        route = ROUTE_BOOK,
                        arguments = listOf(navArgument("bookId") { type = NavType.IntType }),
                    ) { entry ->
                        val bookId = entry.arguments?.getInt("bookId") ?: 0
                        val bookViewModel: BookViewModel = appViewModel("book-$bookId") { BookViewModel(it, bookId) }
                        BookScreen(
                            viewModel = bookViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenPlayer = { navController.navigate(ROUTE_PLAYER) },
                            onOpenSeries = { slug, name ->
                                navController.navigate("series/$slug?name=" + Uri.encode(name))
                            },
                        )
                    }
                    composable(ROUTE_FAVORITES) {
                    val favoritesViewModel: FavoritesViewModel =
                        appViewModel("favorites") { FavoritesViewModel(it) }
                    FavoritesScreen(
                        viewModel = favoritesViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenBook = { navController.navigate("book/$it") },
                        onOpenPlayer = { navController.navigate(ROUTE_PLAYER) },
                    )
                }
                composable(ROUTE_STORAGE) {
                    val storageViewModel: StorageViewModel =
                        appViewModel("storage") { StorageViewModel(it) }
                    StorageScreen(
                        viewModel = storageViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenBook = { navController.navigate("book/$it") },
                    )
                }
                composable(
                    route = ROUTE_SERIES,
                    arguments = listOf(
                        navArgument("slug") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val slug = entry.arguments?.getString("slug").orEmpty()
                    val name = entry.arguments?.getString("name").orEmpty()
                    val seriesViewModel: SeriesViewModel =
                        appViewModel("series-$slug") { SeriesViewModel(it, slug, name) }
                    SeriesScreen(
                        viewModel = seriesViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenBook = { navController.navigate("book/$it") },
                    )
                }
                composable(ROUTE_PLAYER) {
                        PlayerScreen(
                            viewModel = playerViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
        }
    }

    nextUp?.let { card ->
        AlertDialog(
            onDismissRequest = { nextUp = null },
            title = { Text("Книга дослушана") },
            text = { Text("Следующая в цикле: «${card.title}»") },
            confirmButton = {
                TextButton(onClick = {
                    val card1 = card
                    nextUp = null
                    scope.launch {
                        val id = card1.bookId ?: runCatching {
                            container.api.resolveBookId(KnigavuheApi.normalizeUrl(card1.path))
                        }.getOrNull()
                        if (id != null) navController.navigate("book/$id")
                    }
                }) { Text("Открыть") }
            },
            dismissButton = {
                TextButton(onClick = { nextUp = null }) { Text("Позже") }
            },
        )
    }
}
