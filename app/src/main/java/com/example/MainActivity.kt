package com.example

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import com.example.data.Folder as MusicFolder
import com.example.data.MusicRepository
import com.example.data.Song
import com.example.data.db.MusicDatabase
import com.example.data.db.PlaylistWithSongs
import com.example.playback.LoopMode
import com.example.playback.PlaybackManager
import com.example.ui.PlaybackControls
import com.example.ui.MusicViewModel
import com.example.ui.MusicViewModelFactory
import com.example.ui.SongSortOrder
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private lateinit var database: MusicDatabase
    private lateinit var repository: MusicRepository
    private lateinit var playbackManager: PlaybackManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize singletons
        database = Room.databaseBuilder(
            applicationContext,
            MusicDatabase::class.java,
            "music_player.db"
        ).fallbackToDestructiveMigration().build()

        repository = MusicRepository(applicationContext, database.playlistDao())
        playbackManager = PlaybackManager(applicationContext)

        val viewModel: MusicViewModel by viewModels {
            MusicViewModelFactory(repository, playbackManager)
        }

        setContent {
            var isDarkTheme by remember { mutableStateOf(true) }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        isDarkTheme = isDarkTheme,
                        onThemeToggle = { isDarkTheme = !isDarkTheme }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackManager.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MusicViewModel,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit
) {
    val context = LocalContext.current
    val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_AUDIO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                permissionToRequest
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            viewModel.scanDevice()
            Toast.makeText(context, "Storage Access Granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Scanning requires storage permissions", Toast.LENGTH_LONG).show()
        }
    }

    // Trigger scan on start if permissions already exist
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.scanDevice()
        }
    }

    val songs by viewModel.filteredSongs.collectAsStateWithLifecycle()
    val allSongs by viewModel.songs.collectAsStateWithLifecycle()
    val folders by viewModel.filteredFolders.collectAsStateWithLifecycle()
    val playlists by viewModel.playlistsWithSongs.collectAsStateWithLifecycle()
    val activeSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(0) } // 0: Folders, 1: Playlists, 2: Library/Search
    var selectedFolder by remember { mutableStateOf<MusicFolder?>(null) }
    var selectedPlaylist by remember { mutableStateOf<PlaylistWithSongs?>(null) }
    var isPlayerExpanded by remember { mutableStateOf(false) }

    // Playlist Dialog State
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    if (!hasPermission && songs.isEmpty()) {
        // Permissions / Onboarding Splash screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated glass-disk-note placeholder logo
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Logo",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Local Music Player",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "An offline audiophile experience. Scan your device's audio files, catalog in folder views, and manage custom playlists.",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = { launcher.launch(permissionToRequest) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("grant_permission_button")
            ) {
                Text("Scan Storage Files", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sandbox/Emulator fallback track synthesizer generator
            OutlinedButton(
                onClick = {
                    viewModel.makeSampleTracks()
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("generate_tracks_button")
            ) {
                Text(
                    "Generate Virtual Ambient Tracks",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Ideal for virtual emulators lacking audio files",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
    } else {
        Scaffold(
            topBar = {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text(
                                "Local Player",
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp
                            )
                            if (isScanning) {
                                Text(
                                    "Indexing directories...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        // Quick toggle theme mode
                        IconButton(
                            onClick = onThemeToggle,
                            modifier = Modifier.testTag("theme_toggle")
                        ) {
                            if (isDarkTheme) {
                                CustomSunnyIcon(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            } else {
                                CustomMoonIcon(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            }
                        }

                        // Re-scan device files
                        IconButton(
                            onClick = { viewModel.scanDevice() },
                            modifier = Modifier.testTag("refresh_scan")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Rescan Files"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                Column {
                    // System navigation offset safe padding holds tabs
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                            )
                    ) {
                        NavigationBarItem(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0; selectedFolder = null },
                            icon = { CustomFolderIcon(color = if (activeTab == 0) MaterialTheme.colorScheme.primary else Color.Gray, modifier = Modifier.size(24.dp)) },
                            label = { Text("Folders") },
                            modifier = Modifier.testTag("nav_tab_folders")
                        )
                        NavigationBarItem(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1; selectedPlaylist = null },
                            icon = { Icon(Icons.Default.List, contentDescription = "Playlists") },
                            label = { Text("Playlists") },
                            modifier = Modifier.testTag("nav_tab_playlists")
                        )
                        NavigationBarItem(
                            selected = activeTab == 2,
                            onClick = { activeTab = 2 },
                            icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                            label = { Text("Search") },
                            modifier = Modifier.testTag("nav_tab_search")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Tab Content Switcher
                Column(modifier = Modifier.fillMaxSize()) {
                    // Search layout inside lists if active and user has songs in their library
                    if (allSongs.isNotEmpty() || searchQuery.isNotEmpty()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text("Search title, album, artist...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.updateSearchQuery("") },
                                        modifier = Modifier.testTag("search_clear_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear search"
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("search_input")
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        when (activeTab) {
                            0 -> FolderTabContent(
                                folders = folders,
                                selectedFolder = selectedFolder,
                                onFolderClick = { selectedFolder = it },
                                onBackClick = { selectedFolder = null },
                                onSongClick = { list, song -> viewModel.playSongFromList(list, song) },
                                onPlayAllClick = { viewModel.playAllInQueue(it) },
                                activeSong = activeSong,
                                makeSampleTracks = { viewModel.makeSampleTracks() },
                                isScanning = isScanning
                            )

                            1 -> PlaylistTabContent(
                                playlists = playlists,
                                selectedPlaylist = selectedPlaylist,
                                onCreatePlaylistClick = { showCreatePlaylistDialog = true },
                                onPlaylistClick = { selectedPlaylist = it },
                                onBackClick = { selectedPlaylist = null },
                                onSongClick = { list, song -> viewModel.playSongFromList(list, song) },
                                onSongDelete = { pId, songPath -> viewModel.removeSongFromPlaylist(pId, songPath) },
                                onPlaylistDelete = { viewModel.deletePlaylist(it); selectedPlaylist = null },
                                activeSong = activeSong
                            )

                            2 -> LibraryTabContent(
                                songs = songs,
                                playlists = playlists,
                                currentSortOrder = sortOrder,
                                onSortOrderChange = { viewModel.updateSortOrder(it) },
                                onSongClick = { song -> viewModel.playSongFromList(songs, song) },
                                onAddToPlaylist = { playlistId, song -> viewModel.addSongToPlaylist(playlistId, song) },
                                onDeleteSong = { song -> viewModel.deleteSong(song) },
                                activeSong = activeSong
                            )
                        }
                    }

                    // Spacer offset for mini player so it doesn't overlap text items
                    if (activeSong != null) {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }

                // Elegant Bottom Player bar/sheet overlay
                if (activeSong != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                    ) {
                        if (!isPlayerExpanded) {
                            MiniPlayer(
                                song = activeSong!!,
                                isPlaying = viewModel.isPlaying.collectAsStateWithLifecycle().value,
                                progress = calculateProgressRatio(
                                    position = viewModel.currentPosition.collectAsStateWithLifecycle().value,
                                    duration = viewModel.duration.collectAsStateWithLifecycle().value
                                ),
                                onPlayPauseClick = { viewModel.togglePlayPause() },
                                onNextClick = { viewModel.next() },
                                onPreviousClick = { viewModel.previous() },
                                onClick = { isPlayerExpanded = true }
                            )
                        }
                    }
                }
            }
        }

        // Expanded player sheet overlay
        AnimatedVisibility(
            visible = isPlayerExpanded,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            ExpandedPlayer(
                song = activeSong ?: Song(0,"","Empty","No active song","Unknown",0L,0L,"",""),
                isPlaying = viewModel.isPlaying.collectAsStateWithLifecycle().value,
                position = viewModel.currentPosition.collectAsStateWithLifecycle().value,
                duration = viewModel.duration.collectAsStateWithLifecycle().value,
                isShuffle = viewModel.isShuffle.collectAsStateWithLifecycle().value,
                loopMode = viewModel.loopMode.collectAsStateWithLifecycle().value,
                playlists = playlists,
                audioSessionId = viewModel.audioSessionId.collectAsStateWithLifecycle().value,
                onCollapseClick = { isPlayerExpanded = false },
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onNextClick = { viewModel.next() },
                onPreviousClick = { viewModel.previous() },
                onSeek = { viewModel.seekTo(it) },
                onShuffleToggle = { viewModel.toggleShuffle() },
                onLoopModeToggle = {
                    val nextMode = when (viewModel.loopMode.value) {
                        LoopMode.NO_LOOP -> LoopMode.LOOP_ALL
                        LoopMode.LOOP_ALL -> LoopMode.LOOP_ONE
                        LoopMode.LOOP_ONE -> LoopMode.NO_LOOP
                    }
                    viewModel.setLoopMode(nextMode)
                },
                onAddToPlaylist = { playlistId, song ->
                    viewModel.addSongToPlaylist(playlistId, song)
                    Toast.makeText(context, "Added to playlist", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Custom Playlist Creation dialog
        if (showCreatePlaylistDialog) {
            Dialog(onDismissRequest = { showCreatePlaylistDialog = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Text(
                            "Create Custom Playlist",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            placeholder = { Text("e.g., Chill Beats, Synth Hits") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("playlist_name_input")
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showCreatePlaylistDialog = false }) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (newPlaylistName.isNotBlank()) {
                                        viewModel.createPlaylist(newPlaylistName)
                                        newPlaylistName = ""
                                        showCreatePlaylistDialog = false
                                        Toast.makeText(context, "Playlist created", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.testTag("create_playlist_confirm")
                            ) {
                                Text("Create")
                            }
                        }
                    }
                }
            }
        }
    }
}

fun calculateProgressRatio(position: Long, duration: Long): Float {
    if (duration <= 0) return 0f
    return (position.toFloat() / duration).coerceIn(0f, 1f)
}

/**
 * Tab: FOLDERS VIEW
 */
@Composable
fun FolderTabContent(
    folders: List<MusicFolder>,
    selectedFolder: MusicFolder?,
    onFolderClick: (MusicFolder) -> Unit,
    onBackClick: () -> Unit,
    onSongClick: (List<Song>, Song) -> Unit,
    onPlayAllClick: (List<Song>) -> Unit,
    activeSong: Song?,
    makeSampleTracks: () -> Unit,
    isScanning: Boolean
) {
    if (folders.isEmpty() && !isScanning) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CustomFolderIcon(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No Audio Folders Found",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "No tracks are stored on this device. Generate synthetic local audio tracks to play and test with ease!",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = makeSampleTracks,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("generate_samples_folders_empty")
            ) {
                Text("Generate Ambient Tracks")
            }
        }
        return
    }

    AnimatedContent(
        targetState = selectedFolder,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "FolderTransition"
    ) { folder ->
        if (folder == null) {
            // Folders List view
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(folders) { f ->
                    Card(
                        onClick = { onFolderClick(f) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .testTag("folder_card_${f.name}")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                CustomFolderIcon(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = f.name,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "${f.songs.size} tracks",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Folder Detail view
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            folder.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${folder.songs.size} tracks in folder",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { onPlayAllClick(folder.songs) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("play_all_folder")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All", fontSize = 12.sp)
                    }
                }

                Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                ) {
                    items(folder.songs) { song ->
                        TrackRow(
                            song = song,
                            isActive = activeSong?.absolutePath == song.absolutePath,
                            onClick = { onSongClick(folder.songs, song) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab: PLAYLISTS VIEW
 */
@Composable
fun PlaylistTabContent(
    playlists: List<PlaylistWithSongs>,
    selectedPlaylist: PlaylistWithSongs?,
    onPlaylistClick: (PlaylistWithSongs) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onBackClick: () -> Unit,
    onSongClick: (List<Song>, Song) -> Unit,
    onSongDelete: (Int, String) -> Unit,
    onPlaylistDelete: (Int) -> Unit,
    activeSong: Song?
) {
    AnimatedContent(
        targetState = selectedPlaylist,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "PlaylistTransition"
    ) { playlist ->
        if (playlist == null) {
            // Playlist List screen
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Your Custom Playlists", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = onCreatePlaylistClick,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("create_playlist_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "No playlists created yet",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(playlists) { p ->
                            Card(
                                onClick = { onPlaylistClick(p) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            p.playlist.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            "${p.songs.size} tracks",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Display songs of a playlist
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            playlist.playlist.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            "${playlist.songs.size} tracks",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { onPlaylistDelete(playlist.playlist.id) },
                        modifier = Modifier.testTag("delete_playlist_button")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Playlist", tint = MaterialTheme.colorScheme.error)
                    }
                }

                Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                if (playlist.songs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "This playlist is empty. Add songs from your Folders or Library!",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    val songsList = playlist.songs.map { entity ->
                        Song(
                            id = entity.id.toLong(),
                            absolutePath = entity.songPath,
                            title = entity.title,
                            artist = entity.artist,
                            album = "Playlist Item",
                            duration = entity.duration,
                            size = 0L,
                            folderName = "",
                            folderPath = ""
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                    ) {
                        items(playlist.songs) { entity ->
                            val s = Song(
                                id = entity.id.toLong(),
                                absolutePath = entity.songPath,
                                title = entity.title,
                                artist = entity.artist,
                                album = "Playlist Item",
                                duration = entity.duration,
                                size = 0L,
                                folderName = "",
                                folderPath = ""
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    TrackRow(
                                        song = s,
                                        isActive = activeSong?.absolutePath == s.absolutePath,
                                        onClick = { onSongClick(songsList, s) }
                                    )
                                }
                                IconButton(
                                    onClick = { onSongDelete(playlist.playlist.id, s.absolutePath) },
                                    modifier = Modifier.testTag("delete_song_from_playlist_${s.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab: LIBRARY / ALL TRACKS VIEW
 */
/**
 * Tab: LIBRARY / ALL TRACKS VIEW
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryTabContent(
    songs: List<Song>,
    playlists: List<PlaylistWithSongs>,
    currentSortOrder: SongSortOrder,
    onSortOrderChange: (SongSortOrder) -> Unit,
    onSongClick: (Song) -> Unit,
    onAddToPlaylist: (Int, Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    activeSong: Song?
) {
    var songToAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var selectedSongForMetadata by remember { mutableStateOf<Song?>(null) }
    var songToDeleteConfirmation by remember { mutableStateOf<Song?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Horizontal scrollable sorting chips row for responsive widths
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sort by:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 4.dp)
            )

            SongSortOrder.values().forEach { order ->
                FilterChip(
                    selected = currentSortOrder == order,
                    onClick = { onSortOrderChange(order) },
                    label = {
                        Text(
                            text = order.displayName,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("sort_chip_${order.name.lowercase()}"),
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }

        if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No songs matched",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(songs) { song ->
                    TrackRow(
                        song = song,
                        isActive = activeSong?.absolutePath == song.absolutePath,
                        onClick = { onSongClick(song) },
                        onAddToPlaylistClick = { songToAddToPlaylist = song },
                        onRemoveClick = { songToDeleteConfirmation = song },
                        onViewMetadataClick = { selectedSongForMetadata = song }
                    )
                }
            }
        }
    }

    // Modal select playlist target dialog
    if (songToAddToPlaylist != null) {
        val song = songToAddToPlaylist!!
        Dialog(onDismissRequest = { songToAddToPlaylist = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Add Track to Playlist",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    if (playlists.isEmpty()) {
                        Text(
                            "No playlists available. Please create a playlist first in the Playlists tab.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        Box(modifier = Modifier.heightIn(max = 240.dp)) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(playlists) { p ->
                                    Button(
                                        onClick = {
                                            onAddToPlaylist(p.playlist.id, song)
                                            songToAddToPlaylist = null
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Text(p.playlist.name)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = { songToAddToPlaylist = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    // Viewing Metadata Dialog
    if (selectedSongForMetadata != null) {
        val song = selectedSongForMetadata!!
        Dialog(onDismissRequest = { selectedSongForMetadata = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Track Metadata",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    MetadataItem(label = "Title", value = song.title)
                    MetadataItem(label = "Artist", value = song.artist)
                    MetadataItem(label = "Album", value = song.album)
                    MetadataItem(label = "Duration", value = formatDuration(song.duration))
                    MetadataItem(label = "File Size", value = formatFileSize(song.size))
                    MetadataItem(label = "File Path", value = song.absolutePath)

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = { selectedSongForMetadata = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // Confirm Deletion dialog
    if (songToDeleteConfirmation != null) {
        val song = songToDeleteConfirmation!!
        AlertDialog(
            onDismissRequest = { songToDeleteConfirmation = null },
            title = {
                Text(
                    text = "Delete Track permanently?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently delete \"${song.title}\" from your library and storage?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSong(song)
                        songToDeleteConfirmation = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDeleteConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun MetadataItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    song: Song,
    isActive: Boolean,
    onClick: () -> Unit,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
    onViewMetadataClick: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val hasActions = onAddToPlaylistClick != null || onRemoveClick != null || onViewMetadataClick != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (hasActions) {
                        showMenu = true
                    }
                }
            )
            .pointerInput(song) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isPress = event.changes.any { it.pressed }
                        if (isPress && event.buttons.isSecondaryPressed) {
                            if (hasActions) {
                                event.changes.forEach { it.consume() }
                                showMenu = true
                            }
                        }
                    }
                }
            }
            .padding(8.dp)
            .testTag("track_row_${song.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glowing miniature vinyl circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatDuration(song.duration),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        if (hasActions) {
            Spacer(modifier = Modifier.width(8.dp))

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("track_menu_button_${song.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Song options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    onAddToPlaylistClick?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Add to Playlist") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                action()
                            }
                        )
                    }
                    onRemoveClick?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                action()
                            }
                        )
                    }
                    onViewMetadataClick?.let { action ->
                        DropdownMenuItem(
                            text = { Text("View Metadata") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                action()
                            }
                        )
                    }
                }
            }
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups < 0) return "0 B"
    return String.format(java.util.Locale.US, "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatDuration(durationMs: Long): String {
    val sec = (durationMs / 1000) % 60
    val min = (durationMs / 1000) / 60
    return String.format("%d:%02d", min, sec)
}

/**
 * Sticky Bottom Mini Player
 */
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .testTag("mini_player")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LinearProgressIndicator(
                progress = progress,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Spinning mini record visualization
                val infiniteTransition = rememberInfiniteTransition()
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(4000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    )
                )

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .rotate(if (isPlaying) rotation else 0f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color.White, CircleShape)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artist,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                PlaybackControls(
                    isPlaying = isPlaying,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onPreviousClick = onPreviousClick,
                    isCompact = true,
                    modifier = Modifier.width(160.dp),
                    controlColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    primaryContainerColor = MaterialTheme.colorScheme.primary,
                    onPrimaryColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * Dynamic Ambient Audio Visualizer Composable
 */
@Composable
fun AudioVisualizer(
    audioSessionId: Int?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 32
    val frequencies = remember { mutableStateListOf<Float>().apply { addAll(List(barCount) { 0.05f }) } }
    val context = LocalContext.current
    val hasRecordAudioPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Use DisposableEffect to handle setup/teardown of the actual Android Visualizer API
    DisposableEffect(audioSessionId, isPlaying, hasRecordAudioPermission) {
        if (audioSessionId != null && audioSessionId != 0 && isPlaying && hasRecordAudioPermission) {
            var mVisualizer: android.media.audiofx.Visualizer? = null
            try {
                mVisualizer = android.media.audiofx.Visualizer(audioSessionId).apply {
                    captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1].coerceAtMost(256)
                    setDataCaptureListener(
                        object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                v: android.media.audiofx.Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int
                            ) {}

                            override fun onFftDataCapture(
                                v: android.media.audiofx.Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int
                            ) {
                                if (fft != null && fft.isNotEmpty()) {
                                    val n = fft.size / 2
                                    val bands = FloatArray(barCount)
                                    val binsPerBand = (n / barCount).coerceAtLeast(1)
                                    
                                    for (i in 0 until barCount) {
                                        var sum = 0f
                                        val startBin = i * binsPerBand
                                        val endBin = (startBin + binsPerBand).coerceAtMost(n)
                                        for (bin in startBin until endBin) {
                                            val rIndex = bin * 2
                                            val iIndex = bin * 2 + 1
                                            if (rIndex < fft.size && iIndex < fft.size) {
                                                val real = fft[rIndex].toFloat()
                                                val imag = fft[iIndex].toFloat()
                                                val mag = kotlin.math.sqrt(real * real + imag * imag)
                                                sum += mag
                                            }
                                        }
                                        val avg = sum / binsPerBand
                                        var normalized = (avg / 128f).coerceIn(0f, 1f)
                                        if (i > barCount / 2) {
                                            normalized *= (1f + (i - barCount / 2) * 0.1f)
                                        }
                                        bands[i] = normalized.coerceIn(0.02f, 1f)
                                    }
                                    
                                    for (i in 0 until barCount) {
                                        val current = frequencies[i]
                                        val target = bands[i]
                                        val multiplier = if (target > current) 0.6f else 0.2f
                                        frequencies[i] = current + (target - current) * multiplier
                                    }
                                }
                            }
                        },
                        android.media.audiofx.Visualizer.getMaxCaptureRate() / 2,
                        false,
                        true
                    )
                    enabled = true
                }
            } catch (e: Exception) {
                android.util.Log.d("AudioVisualizer", "Visualizer API initialization bypassed: ${e.message}")
            }

            onDispose {
                try {
                    mVisualizer?.enabled = false
                    mVisualizer?.release()
                } catch (e: Exception) {}
            }
        } else {
            onDispose {}
        }
    }

    if (!isPlaying) {
        LaunchedEffect(Unit) {
            while (true) {
                var allResting = true
                for (i in 0 until barCount) {
                    val cur = frequencies[i]
                    if (cur > 0.05f) {
                        frequencies[i] = cur - 0.05f
                        allResting = false
                    } else {
                        frequencies[i] = 0.03f
                    }
                }
                if (allResting) break
                kotlinx.coroutines.delay(30)
            }
        }
    } else {
        LaunchedEffect(isPlaying) {
            var tick = 0f
            while (isPlaying) {
                tick += 0.15f
                for (i in 0 until barCount) {
                    val bassRhythm = if (i < 6) {
                        (kotlin.math.sin(tick * 1.8f) * 0.4f + 0.5f) * (1f + kotlin.math.sin(tick * 0.3f) * 0.2f)
                    } else 0f

                    val midActivity = if (i in 6..20) {
                        (kotlin.math.sin(tick * 2.5f + i * 0.4f) * 0.3f + 0.4f) * (0.8f + kotlin.math.cos(tick * 0.7f) * 0.2f)
                    } else 0f

                    val highShimmer = if (i > 20) {
                        (kotlin.math.sin(tick * 4.0f + i * 0.8f) * 0.2f + 0.25f) * (0.5f + Math.random().toFloat() * 0.5f)
                    } else 0f

                    var target = (bassRhythm + midActivity + highShimmer).coerceIn(0.05f, 1f)
                    target += (Math.random().toFloat() * 0.08f - 0.04f)
                    target = target.coerceIn(0.03f, 1f)

                    val current = frequencies[i]
                    val realDataIsActive = frequencies.any { it > 0.15f && it != 0.03f } && (audioSessionId != null && audioSessionId != 0) && hasRecordAudioPermission
                    if (!realDataIsActive) {
                        val multiplier = if (target > current) 0.5f else 0.2f
                        frequencies[i] = current + (target - current) * multiplier
                    }
                }
                kotlinx.coroutines.delay(40)
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val gap = 3.dp.toPx()
        val totalGaps = (barCount - 1) * gap
        val barWidth = (width - totalGaps) / barCount

        val brush = Brush.verticalGradient(
            colors = listOf(primaryColor, tertiaryColor, secondaryColor),
            startY = height,
            endY = 0f
        )

        for (i in 0 until barCount) {
            val amplitude = frequencies[i]
            val currentBarHeight = height * amplitude
            val x = i * (barWidth + gap)
            val y = (height - currentBarHeight) / 2f

            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, y),
                size = Size(barWidth, currentBarHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}

/**
 * Expanded full-screen playing engine
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedPlayer(
    song: Song,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    isShuffle: Boolean,
    loopMode: LoopMode,
    playlists: List<PlaylistWithSongs>,
    audioSessionId: Int?,
    onCollapseClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffleToggle: () -> Unit,
    onLoopModeToggle: () -> Unit,
    onAddToPlaylist: (Int, Song) -> Unit
) {
    var showPlaylistSelectDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val isLandscape = maxWidth > maxHeight

        if (isLandscape) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onCollapseClick,
                        modifier = Modifier.testTag("expand_player_collapse")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Collapse", modifier = Modifier.size(24.dp).rotate(-90f))
                    }
                    Text(
                        "Now Playing",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    IconButton(
                        onClick = { showPlaylistSelectDialog = true },
                        modifier = Modifier.testTag("expand_player_add_playlist")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add to Playlist", modifier = Modifier.size(24.dp))
                    }
                }

                // Two columns layout: Left side spinning disk & details, Right side controls & visualizer (Grid/Flex behavior)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Column
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(15000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            )
                        )

                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(CircleShape)
                                .background(Color.Black)
                                .rotate(if (isPlaying) rotation else 0f)
                                .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val radiusStep = size.width / 12
                                for (i in 1..5) {
                                    drawCircle(
                                        color = Color.White.copy(alpha = 0.1f),
                                        radius = radiusStep * i,
                                        style = Stroke(width = 1.dp.toPx())
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.secondary
                                            )
                                        ),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(Color.White, CircleShape)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = song.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = song.artist,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "GAPLESS",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Right Column
                    Column(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceAround,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AudioVisualizer(
                            audioSessionId = audioSessionId,
                            isPlaying = isPlaying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("fluid_visualizer")
                        )

                        PlaybackControls(
                            isPlaying = isPlaying,
                            onPlayPauseClick = onPlayPauseClick,
                            onNextClick = onNextClick,
                            onPreviousClick = onPreviousClick,
                            isShuffle = isShuffle,
                            onShuffleClick = onShuffleToggle,
                            loopMode = loopMode,
                            onLoopModeClick = onLoopModeToggle,
                            showShuffleRepeat = true,
                            positionMs = position,
                            durationMs = duration,
                            onSeek = onSeek,
                            showProgress = true,
                            modifier = Modifier.fillMaxWidth(),
                            controlColor = MaterialTheme.colorScheme.onBackground,
                            primaryContainerColor = MaterialTheme.colorScheme.primary,
                            onPrimaryColor = Color(0xFF381E72)
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Expand/Collapse header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onCollapseClick,
                        modifier = Modifier.testTag("expand_player_collapse")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Collapse", modifier = Modifier.size(28.dp).rotate(-90f))
                    }
                    Text(
                        "Now Playing",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    IconButton(
                        onClick = { showPlaylistSelectDialog = true },
                        modifier = Modifier.testTag("expand_player_add_playlist")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add to Playlist", modifier = Modifier.size(28.dp))
                    }
                }

                // Spinning vinyl record platter
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    val infiniteTransition = rememberInfiniteTransition()
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(15000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        )
                    )

                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(CircleShape)
                            .background(Color.Black)
                            .rotate(if (isPlaying) rotation else 0f)
                            .border(4.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // Vinyl grooves rings
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val radiusStep = size.width / 12
                            for (i in 1..5) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.1f),
                                    radius = radiusStep * i,
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        }

                        // Vinyl Center Label with neon cyan/magenta styling
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    ),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(Color.White, CircleShape)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Text Title + Subtitle
                    Text(
                        text = song.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = song.artist,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "GAPLESS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Custom Soundwave Equalizer Canvas
                AudioVisualizer(
                    audioSessionId = audioSessionId,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(vertical = 8.dp)
                        .testTag("fluid_visualizer")
                )

                // Unified Playback Controls (incorporating seekable progress slider and modern controls)
                PlaybackControls(
                    isPlaying = isPlaying,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onPreviousClick = onPreviousClick,
                    isShuffle = isShuffle,
                    onShuffleClick = onShuffleToggle,
                    loopMode = loopMode,
                    onLoopModeClick = onLoopModeToggle,
                    showShuffleRepeat = true,
                    positionMs = position,
                    durationMs = duration,
                    onSeek = onSeek,
                    showProgress = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    controlColor = MaterialTheme.colorScheme.onBackground,
                    primaryContainerColor = MaterialTheme.colorScheme.primary,
                    onPrimaryColor = Color(0xFF381E72)
                )
            }
        }
    }

    // Modal select playlist target dialog
    if (showPlaylistSelectDialog) {
        Dialog(onDismissRequest = { showPlaylistSelectDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        "Add current song to:",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (playlists.isEmpty()) {
                        Text(
                            "No playlists available. Please create a playlist first in the Playlists tab.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                        ) {
                            items(playlists) { p ->
                                Card(
                                    onClick = {
                                        onAddToPlaylist(p.playlist.id, song)
                                        showPlaylistSelectDialog = false
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                            .testTag("playlist_item_selectable_${p.playlist.id}"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(p.playlist.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    TextButton(
                        onClick = { showPlaylistSelectDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

// Custom High-Quality Vector Style Canvas Icons for Zero-Dependency Compilation
@Composable
fun CustomSunnyIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val term = size.width * 0.28f
        // Inner sun body
        drawCircle(color = color, radius = term, center = center)
        // Radiant flares
        val numRays = 8
        val rayLength = size.width * 0.16f
        val rayStart = size.width * 0.38f
        for (i in 0 until numRays) {
            val angle = i * (2.0 * Math.PI / numRays)
            val dx = Math.cos(angle).toFloat()
            val dy = Math.sin(angle).toFloat()
            val start = Offset(center.x + dx * rayStart, center.y + dy * rayStart)
            val end = Offset(center.x + dx * (rayStart + rayLength), center.y + dy * (rayStart + rayLength))
            drawLine(
                color = color,
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

@Composable
fun CustomMoonIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.35f, h * 0.2f)
            quadraticTo(w * 0.85f, h * 0.5f, w * 0.35f, h * 0.8f)
            quadraticTo(w * 0.58f, h * 0.5f, w * 0.35f, h * 0.2f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun CustomPauseIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barWidth = w * 0.18f
        val spacing = w * 0.16f
        val startX1 = (w - (barWidth * 2 + spacing)) / 2f
        val startY = h * 0.24f
        val barHeight = h * 0.52f
        
        drawRoundRect(
            color = color,
            topLeft = Offset(startX1, startY),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(startX1 + barWidth + spacing, startY),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

@Composable
fun CustomSkipNextIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.25f)
            lineTo(w * 0.60f, h * 0.5f)
            lineTo(w * 0.22f, h * 0.75f)
            close()
        }
        drawPath(path, color)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.66f, h * 0.25f),
            size = Size(w * 0.12f, h * 0.5f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

@Composable
fun CustomSkipPreviousIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.22f, h * 0.25f),
            size = Size(w * 0.12f, h * 0.5f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
        val path = Path().apply {
            moveTo(w * 0.78f, h * 0.25f)
            lineTo(w * 0.40f, h * 0.5f)
            lineTo(w * 0.78f, h * 0.75f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun CustomLoopIcon(color: Color, loopOne: Boolean, modifier: Modifier = Modifier) {
    val bgColor = MaterialTheme.colorScheme.background
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2, h / 2)
            val radius = w * 0.28f
            drawCircle(
                color = color,
                radius = radius,
                style = Stroke(width = 2.dp.toPx())
            )
            
            // Draw gap in circle to look like loop arrows
            drawArc(
                color = bgColor,
                startAngle = -45f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx())
            )

            // Draw a neat arrow tip representing looping motion
            val arrowPath = Path().apply {
                moveTo(center.x + radius + 4.dp.toPx(), center.y - 4.dp.toPx())
                lineTo(center.x + radius, center.y + 2.dp.toPx())
                lineTo(center.x + radius - 4.dp.toPx(), center.y - 4.dp.toPx())
                close()
            }
            drawPath(arrowPath, color)
        }
        if (loopOne) {
            Text(
                "1",
                fontSize = 10.sp,
                color = color,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.offset(y = (-1).dp)
            )
        }
    }
}

@Composable
fun CustomShuffleIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        
        // Parallel diagonal threads with crossing lines
        drawLine(color = color, start = Offset(w * 0.2f, h * 0.3f), end = Offset(w * 0.5f, h * 0.3f), strokeWidth = 2.dp.toPx())
        drawLine(color = color, start = Offset(w * 0.5f, h * 0.3f), end = Offset(w * 0.8f, h * 0.7f), strokeWidth = 2.dp.toPx())
        val arrow1 = Path().apply {
            moveTo(w * 0.8f, h * 0.7f)
            lineTo(w * 0.74f, h * 0.6f)
            lineTo(w * 0.82f, h * 0.6f)
            close()
        }
        drawPath(arrow1, color)

        drawLine(color = color, start = Offset(w * 0.2f, h * 0.7f), end = Offset(w * 0.5f, h * 0.7f), strokeWidth = 2.dp.toPx())
        drawLine(color = color, start = Offset(w * 0.5f, h * 0.7f), end = Offset(w * 0.8f, h * 0.3f), strokeWidth = 2.dp.toPx())
        val arrow2 = Path().apply {
            moveTo(w * 0.8f, h * 0.3f)
            lineTo(w * 0.74f, h * 0.4f)
            lineTo(w * 0.82f, h * 0.4f)
            close()
        }
        drawPath(arrow2, color)
    }
}

@Composable
fun CustomFolderIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            // Folder background tab
            moveTo(w * 0.12f, h * 0.22f)
            lineTo(w * 0.44f, h * 0.22f)
            lineTo(w * 0.54f, h * 0.34f)
            lineTo(w * 0.88f, h * 0.34f)
            lineTo(w * 0.88f, h * 0.78f)
            lineTo(w * 0.12f, h * 0.78f)
            close()
        }
        drawPath(path, color)
    }
}
