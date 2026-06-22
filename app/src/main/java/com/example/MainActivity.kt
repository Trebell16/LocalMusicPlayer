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
import androidx.compose.foundation.lazy.LazyRow
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
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.example.data.Folder as MusicFolder
import com.example.data.MusicRepository
import com.example.data.Song
import com.example.data.db.MusicDatabase
import com.example.data.db.PlaylistWithSongs
import com.example.playback.LoopMode
import com.example.playback.PlaybackManager
import com.example.playback.AudioEqualizerManager
import com.example.ui.PlaybackControls
import com.example.ui.MusicViewModel
import com.example.ui.MusicViewModelFactory
import com.example.ui.SongSortOrder
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.AnimatedLiquidBackground
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassClickable
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

        repository = MusicRepository(applicationContext, database.playlistDao(), database.recentlyPlayedDao())
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
    val permissionsToRequest = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            list.add(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            list.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        list.toTypedArray()
    }

    var hasPermission by remember {
        val storagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, storagePerm) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val storagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val storageGranted = map[storagePerm] == true
        hasPermission = storageGranted
        if (storageGranted) {
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
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
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

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedLiquidBackground(isDarkTheme = isDarkTheme)

        if (!hasPermission && songs.isEmpty()) {
            // Permissions / Onboarding Splash screen in a luxurious floating glass container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassCard(cornerRadius = 28.dp, isDarkTheme = isDarkTheme)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Animated glass-disk-note placeholder logo with glowing liquid circle
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF8B5CF6),
                                        Color(0xFF06B6D4)
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
                            modifier = Modifier.size(54.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Trebell Player",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "An offline audiophile experience. Scan your device's audio files, catalog in folder views, and manage custom playlists.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Liquid Glass CTA Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .liquidGlassCard(cornerRadius = 16.dp, isDarkTheme = isDarkTheme)
                            .liquidGlassClickable { launcher.launch(permissionsToRequest) }
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF8B5CF6).copy(alpha = 0.5f),
                                        Color(0xFF06B6D4).copy(alpha = 0.5f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Scan Storage Files",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sandbox/Emulator fallback track synthesizer generator in liquid glass
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .liquidGlassCard(cornerRadius = 16.dp, isDarkTheme = isDarkTheme)
                            .liquidGlassClickable { viewModel.makeSampleTracks() }
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Generate Virtual Songs",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Ideal for virtual emulators lacking audio files",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = Color.Transparent,
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
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassCard(cornerRadius = 24.dp, isDarkTheme = isDarkTheme)
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
                            icon = { Icon(Icons.Default.List, contentDescription = "Playlists", tint = if (activeTab == 1) MaterialTheme.colorScheme.primary else Color.Gray) },
                            label = { Text("Playlists") },
                            modifier = Modifier.testTag("nav_tab_playlists")
                        )
                        NavigationBarItem(
                            selected = activeTab == 2,
                            onClick = { activeTab = 2 },
                            icon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = if (activeTab == 2) MaterialTheme.colorScheme.primary else Color.Gray) },
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
                                isScanning = isScanning,
                                isDarkTheme = isDarkTheme
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
                                activeSong = activeSong,
                                isDarkTheme = isDarkTheme
                            )

                            2 -> LibraryTabContent(
                                songs = songs,
                                recentlyPlayed = recentlyPlayed,
                                playlists = playlists,
                                currentSortOrder = sortOrder,
                                onSortOrderChange = { viewModel.updateSortOrder(it) },
                                onSongClick = { song -> viewModel.playSongFromList(songs, song) },
                                onAddToPlaylist = { playlistId, song -> viewModel.addSongToPlaylist(playlistId, song) },
                                onDeleteSong = { song -> viewModel.deleteSong(song) },
                                activeSong = activeSong,
                                isDarkTheme = isDarkTheme
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
                                onClick = { isPlayerExpanded = true },
                                isDarkTheme = isDarkTheme
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
                equalizerManager = viewModel.equalizerManager,
                playbackSpeed = viewModel.playbackSpeed.collectAsStateWithLifecycle().value,
                onPlaybackSpeedChange = { viewModel.setPlaybackSpeed(it) },
                audioSessionId = viewModel.audioSessionId.collectAsStateWithLifecycle().value,
                sleepTimerRemaining = viewModel.sleepTimerRemaining.collectAsStateWithLifecycle().value,
                onStartSleepTimer = { viewModel.startSleepTimer(it) },
                onStartSleepTimerSeconds = { viewModel.startSleepTimerWithSeconds(it) },
                onCancelSleepTimer = { viewModel.cancelSleepTimer() },
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
                },
                isDarkTheme = isDarkTheme
            )
        }

        // Custom Playlist Creation dialog
        if (showCreatePlaylistDialog) {
            Dialog(onDismissRequest = { showCreatePlaylistDialog = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .liquidGlassCard(cornerRadius = 16.dp, isDarkTheme = isDarkTheme)
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
    isScanning: Boolean,
    isDarkTheme: Boolean = true
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .liquidGlassCard(cornerRadius = 20.dp, isDarkTheme = isDarkTheme)
                            .liquidGlassClickable { onFolderClick(f) }
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
                    items(folder.songs, key = { it.id }) { song ->
                        TrackRow(
                            song = song,
                            isActive = activeSong?.absolutePath == song.absolutePath,
                            onClick = { onSongClick(folder.songs, song) },
                            isDarkTheme = isDarkTheme,
                            modifier = Modifier.animateItem()
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
    activeSong: Song?,
    isDarkTheme: Boolean = true
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .liquidGlassCard(cornerRadius = 16.dp, isDarkTheme = isDarkTheme)
                                    .liquidGlassClickable { onPlaylistClick(p) }
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
                        items(playlist.songs, key = { it.id }) { entity ->
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
                                    .padding(vertical = 4.dp)
                                    .animateItem(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    TrackRow(
                                        song = s,
                                        isActive = activeSong?.absolutePath == s.absolutePath,
                                        onClick = { onSongClick(songsList, s) },
                                        isDarkTheme = isDarkTheme
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
    recentlyPlayed: List<Song>,
    playlists: List<PlaylistWithSongs>,
    currentSortOrder: SongSortOrder,
    onSortOrderChange: (SongSortOrder) -> Unit,
    onSongClick: (Song) -> Unit,
    onAddToPlaylist: (Int, Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    activeSong: Song?,
    isDarkTheme: Boolean = true
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
                if (recentlyPlayed.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                CustomHistoryIcon(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Recently Played",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(recentlyPlayed) { song ->
                                    RecentlyPlayedCard(
                                        song = song,
                                        isActive = activeSong?.absolutePath == song.absolutePath,
                                        isDarkTheme = isDarkTheme,
                                        onClick = { onSongClick(song) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                items(songs, key = { it.id }) { song ->
                    TrackRow(
                        song = song,
                        isActive = activeSong?.absolutePath == song.absolutePath,
                        onClick = { onSongClick(song) },
                        onAddToPlaylistClick = { songToAddToPlaylist = song },
                        onRemoveClick = { songToDeleteConfirmation = song },
                        onViewMetadataClick = { selectedSongForMetadata = song },
                        isDarkTheme = isDarkTheme,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    // Modal select playlist target dialog
    if (songToAddToPlaylist != null) {
        val song = songToAddToPlaylist!!
        Dialog(onDismissRequest = { songToAddToPlaylist = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .liquidGlassCard(cornerRadius = 16.dp, isDarkTheme = isDarkTheme)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .liquidGlassCard(cornerRadius = 16.dp, isDarkTheme = isDarkTheme)
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
        Dialog(onDismissRequest = { songToDeleteConfirmation = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .liquidGlassCard(cornerRadius = 24.dp, isDarkTheme = isDarkTheme)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Delete Track permanently?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Are you sure you want to permanently delete \"${song.title}\" from your library and storage?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { songToDeleteConfirmation = null }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                onDeleteSong(song)
                                songToDeleteConfirmation = null
                            }
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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
    onViewMetadataClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true
) {
    var showMenu by remember { mutableStateOf(false) }
    val hasActions = onAddToPlaylistClick != null || onRemoveClick != null || onViewMetadataClick != null

    val animatedTextColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 300),
        label = "track_text_color"
    )
    val animatedFallbackBoxBg by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 300),
        label = "track_fallback_box_color"
    )
    val animatedFallbackIconTint by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
        animationSpec = tween(durationMillis = 300),
        label = "track_fallback_icon_tint"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isActive) {
                    Modifier.liquidGlassCard(cornerRadius = 12.dp, isDarkTheme = isDarkTheme)
                } else {
                    Modifier
                }
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
            .padding(10.dp)
            .testTag("track_row_${song.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glowing miniature album art/vinyl circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(animatedFallbackBoxBg),
            contentAlignment = Alignment.Center
        ) {
            val painter = rememberAsyncImagePainter(model = song.albumArtUri)
            val painterState = painter.state
            
            Image(
                painter = painter,
                contentDescription = "Album Artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            if (painterState is AsyncImagePainter.State.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (painterState is AsyncImagePainter.State.Error) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(animatedFallbackBoxBg),
                    contentAlignment = Alignment.Center
                ) {
                    CustomFallbackArtIcon(
                        color = animatedFallbackIconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = animatedTextColor,
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
    onClick: () -> Unit,
    isDarkTheme: Boolean = true
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .height(76.dp)
            .liquidGlassCard(cornerRadius = 24.dp, isDarkTheme = isDarkTheme)
            .liquidGlassClickable { onClick() }
            .testTag("mini_player")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LinearProgressIndicator(
                progress = progress,
                color = Color(0xFF06B6D4), // Cyan liquid progress glow
                trackColor = Color.White.copy(alpha = 0.1f),
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

                AnimatedContent(
                    targetState = song,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220, delayMillis = 80)) + 
                         slideInHorizontally(animationSpec = tween(220, delayMillis = 80), initialOffsetX = { it / 3 }))
                            .togetherWith(fadeOut(animationSpec = tween(90)) + 
                             slideOutHorizontally(animationSpec = tween(90), targetOffsetX = { -it / 3 }))
                    },
                    label = "MiniPlayerSongTransition"
                ) { currentSong ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black)
                                .rotate(if (isPlaying) rotation else 0f),
                            contentAlignment = Alignment.Center
                        ) {
                            val painter = rememberAsyncImagePainter(model = currentSong.albumArtUri)
                            val painterState = painter.state
                            
                            Image(
                                painter = painter,
                                contentDescription = "Album Artwork",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            
                            if (painterState is AsyncImagePainter.State.Error || painterState is AsyncImagePainter.State.Loading) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CustomFallbackArtIcon(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color.White, CircleShape)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentSong.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentSong.artist,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
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

data class PresetOption(val label: String, val value: Long, val isSeconds: Boolean)

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
    equalizerManager: AudioEqualizerManager,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    audioSessionId: Int?,
    sleepTimerRemaining: Long?,
    onStartSleepTimer: (Int) -> Unit,
    onStartSleepTimerSeconds: (Long) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onCollapseClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffleToggle: () -> Unit,
    onLoopModeToggle: () -> Unit,
    onAddToPlaylist: (Int, Song) -> Unit,
    isDarkTheme: Boolean = true
) {
    var showPlaylistSelectDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        AnimatedLiquidBackground(isDarkTheme = isDarkTheme)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sleepTimerRemaining != null) {
                            Text(
                                text = formatRemainingTime(sleepTimerRemaining),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 4.dp).testTag("sleep_timer_countdown_text")
                            )
                        }
                        IconButton(
                            onClick = { showSleepTimerDialog = true },
                            modifier = Modifier.testTag("expand_player_sleep_timer")
                        ) {
                            CustomScheduleIcon(
                                color = if (sleepTimerRemaining != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = { showEqualizerDialog = true },
                            modifier = Modifier.testTag("expand_player_equalizer")
                        ) {
                            CustomEqualizerIcon(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = { showSpeedDialog = true },
                            modifier = Modifier.testTag("expand_player_speed")
                        ) {
                            CustomSpeedIcon(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = { showPlaylistSelectDialog = true },
                            modifier = Modifier.testTag("expand_player_add_playlist")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add to Playlist", modifier = Modifier.size(24.dp))
                        }
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
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                val painter = rememberAsyncImagePainter(model = song.albumArtUri)
                                val painterState = painter.state
                                
                                Image(
                                    painter = painter,
                                    contentDescription = "Album Artwork",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                
                                if (painterState is AsyncImagePainter.State.Error || painterState is AsyncImagePainter.State.Loading) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.secondary
                                                    )
                                                )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sleepTimerRemaining != null) {
                            Text(
                                text = formatRemainingTime(sleepTimerRemaining),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 4.dp).testTag("sleep_timer_countdown_text")
                            )
                        }
                        IconButton(
                            onClick = { showSleepTimerDialog = true },
                            modifier = Modifier.testTag("expand_player_sleep_timer")
                        ) {
                            CustomScheduleIcon(
                                color = if (sleepTimerRemaining != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = { showEqualizerDialog = true },
                            modifier = Modifier.testTag("expand_player_equalizer")
                        ) {
                            CustomEqualizerIcon(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = { showSpeedDialog = true },
                            modifier = Modifier.testTag("expand_player_speed")
                        ) {
                            CustomSpeedIcon(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = { showPlaylistSelectDialog = true },
                            modifier = Modifier.testTag("expand_player_add_playlist")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add to Playlist", modifier = Modifier.size(28.dp))
                        }
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
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            val painter = rememberAsyncImagePainter(model = song.albumArtUri)
                            val painterState = painter.state
                            
                            Image(
                                painter = painter,
                                contentDescription = "Album Artwork",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            
                            if (painterState is AsyncImagePainter.State.Error || painterState is AsyncImagePainter.State.Loading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.secondary
                                                )
                                            )
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .liquidGlassCard(cornerRadius = 16.dp, isDarkTheme = isDarkTheme)
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
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .liquidGlassCard(cornerRadius = 10.dp, isDarkTheme = isDarkTheme)
                                        .liquidGlassClickable {
                                            onAddToPlaylist(p.playlist.id, song)
                                            showPlaylistSelectDialog = false
                                        }
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

    // Sleep Timer Setup Dialog
    if (showSleepTimerDialog) {
        Dialog(onDismissRequest = { showSleepTimerDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .liquidGlassCard(cornerRadius = 24.dp, isDarkTheme = isDarkTheme)
                    .testTag("sleep_timer_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CustomScheduleIcon(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Sleep Timer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (sleepTimerRemaining != null) {
                        Text(
                            text = "Active: ${formatRemainingTime(sleepTimerRemaining)} remaining",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("sleep_timer_dialog_active_text")
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                onCancelSleepTimer()
                                showSleepTimerDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("sleep_timer_stop_button")
                        ) {
                            Text("Turn Off Timer", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            text = "Select a duration to automatically pause music when the countdown finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Presets",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Preset buttons Grid rows
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            PresetOption("10 Secs", 10L, isSeconds = true),
                            PresetOption("5 Mins", 5L, isSeconds = false),
                            PresetOption("15 Mins", 15L, isSeconds = false)
                        ).forEach { preset ->
                            Box(modifier = Modifier.weight(1f)) {
                                Button(
                                    onClick = {
                                        if (preset.isSeconds) {
                                            onStartSleepTimerSeconds(preset.value)
                                        } else {
                                            onStartSleepTimer(preset.value.toInt())
                                        }
                                        showSleepTimerDialog = false
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(40.dp).testTag("preset_button_${preset.label.replace(" ", "_").lowercase()}")
                                ) {
                                    Text(preset.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            PresetOption("30 Mins", 30L, isSeconds = false),
                            PresetOption("45 Mins", 45L, isSeconds = false),
                            PresetOption("60 Mins", 60L, isSeconds = false)
                        ).forEach { preset ->
                            Box(modifier = Modifier.weight(1f)) {
                                Button(
                                    onClick = {
                                        if (preset.isSeconds) {
                                            onStartSleepTimerSeconds(preset.value)
                                        } else {
                                            onStartSleepTimer(preset.value.toInt())
                                        }
                                        showSleepTimerDialog = false
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(40.dp).testTag("preset_button_${preset.label.replace(" ", "_").lowercase()}")
                                ) {
                                    Text(preset.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Custom input slider logic
                    var customMinutes by remember { mutableStateOf(30f) }
                    Text(
                        text = "Custom Duration: ${customMinutes.toInt()} Mins",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = customMinutes,
                        onValueChange = { customMinutes = it },
                        valueRange = 1f..120f,
                        steps = 119,
                        modifier = Modifier.testTag("sleep_timer_custom_slider")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            onStartSleepTimer(customMinutes.toInt())
                            showSleepTimerDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("sleep_timer_custom_set_button")
                    ) {
                        Text("Set Custom Timer", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showSleepTimerDialog = false },
                        modifier = Modifier.align(Alignment.End).testTag("sleep_timer_dialog_cancel")
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    if (showEqualizerDialog) {
        EqualizerDialog(
            equalizerManager = equalizerManager,
            onDismissRequest = { showEqualizerDialog = false }
        )
    }

    if (showSpeedDialog) {
        PlaybackSpeedDialog(
            playbackSpeed = playbackSpeed,
            onPlaybackSpeedChange = onPlaybackSpeedChange,
            onDismissRequest = { showSpeedDialog = false }
        )
    }
}

@Composable
fun EqualizerDialog(
    equalizerManager: AudioEqualizerManager,
    onDismissRequest: () -> Unit
) {
    val isEnabled by equalizerManager.isEnabled.collectAsStateWithLifecycle()
    val currentPreset by equalizerManager.currentPreset.collectAsStateWithLifecycle()
    val bandLevels by equalizerManager.bandLevels.collectAsStateWithLifecycle()

    val presets = listOf("Flat", "Bass Boost", "Vocal")

    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .liquidGlassCard(cornerRadius = 24.dp, isDarkTheme = true)
                .testTag("equalizer_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CustomEqualizerIcon(
                            color = if (isEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Equalizer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { equalizerManager.setEnabled(it) },
                        modifier = Modifier.testTag("eq_enable_switch")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Presets",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val allPresets = if (currentPreset == "Custom") presets + "Custom" else presets

                    allPresets.forEach { preset ->
                        val isSelected = currentPreset == preset
                        val baseColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                        val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(baseColor.copy(alpha = if (isEnabled) 1f else 0.5f))
                                .clickable(enabled = isEnabled && preset != "Custom") {
                                    equalizerManager.setPreset(preset)
                                }
                                .padding(vertical = 10.dp)
                                .testTag("eq_preset_btn_$preset"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = textColor.copy(alpha = if (isEnabled) 1f else 0.5f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Frequency Bands",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    bandLevels.forEachIndexed { index, level ->
                        val freq = equalizerManager.bandFrequencies.getOrNull(index) ?: 0
                        val freqText = if (freq >= 1000) "${freq / 1000.0f} kHz" else "$freq Hz"

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = freqText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else Color.Gray,
                                modifier = Modifier.width(68.dp)
                            )

                            Slider(
                                value = level,
                                onValueChange = { newValue ->
                                    equalizerManager.setBandLevel(index, newValue)
                                },
                                valueRange = -15f..15f,
                                enabled = isEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("eq_band_slider_$index"),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            val dbText = if (level > 0) "+${String.format(java.util.Locale.US, "%.1f", level)} dB" else "${String.format(java.util.Locale.US, "%.1f", level)} dB"
                            Text(
                                text = dbText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.width(52.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("eq_dialog_done_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Done")
                }
            }
        }
    }
}

fun formatRemainingTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }
}

@Composable
fun CustomScheduleIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2, h / 2)
        val radius = w * 0.45f
        
        // Draw outer circle
        drawCircle(
            color = color,
            radius = radius,
            style = Stroke(width = 2.dp.toPx())
        )
        
        // Center piece
        drawCircle(
            color = color,
            radius = 2.dp.toPx()
        )
        
        // Hour hand: pointing up-right
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x + radius * 0.4f, center.y - radius * 0.2f),
            strokeWidth = 2.2.dp.toPx()
        )
        
        // Minute hand: pointing up
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x, center.y - radius * 0.65f),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
fun CustomEqualizerIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        
        // Draw 3 sliders with control dots
        // Bar 1 (Left)
        val x1 = w * 0.25f
        drawLine(
            color = color.copy(alpha = 0.4f),
            start = Offset(x1, h * 0.15f),
            end = Offset(x1, h * 0.85f),
            strokeWidth = 2.dp.toPx()
        )
        drawCircle(
            color = color,
            center = Offset(x1, h * 0.45f),
            radius = 4.dp.toPx()
        )
        
        // Bar 2 (Middle)
        val x2 = w * 0.5f
        drawLine(
            color = color.copy(alpha = 0.4f),
            start = Offset(x2, h * 0.15f),
            end = Offset(x2, h * 0.85f),
            strokeWidth = 2.dp.toPx()
        )
        drawCircle(
            color = color,
            center = Offset(x2, h * 0.7f),
            radius = 4.dp.toPx()
        )
        
        // Bar 3 (Right)
        val x3 = w * 0.75f
        drawLine(
            color = color.copy(alpha = 0.4f),
            start = Offset(x3, h * 0.15f),
            end = Offset(x3, h * 0.85f),
            strokeWidth = 2.dp.toPx()
        )
        drawCircle(
            color = color,
            center = Offset(x3, h * 0.3f),
            radius = 4.dp.toPx()
        )
    }
}

@Composable
fun PlaybackSpeedDialog(
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    onDismissRequest: () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .liquidGlassCard(cornerRadius = 24.dp, isDarkTheme = true)
                .testTag("speed_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CustomSpeedIcon(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Playback Speed",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "${String.format(java.util.Locale.US, "%.2f", playbackSpeed)}x",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Slider(
                    value = playbackSpeed,
                    onValueChange = onPlaybackSpeedChange,
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("speed_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(0.5f, 1.0f, 1.5f, 2.0f)
                    presets.forEach { preset ->
                        val isSelected = Math.abs(playbackSpeed - preset) < 0.05f
                        val baseColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                        val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(baseColor)
                                .clickable {
                                    onPlaybackSpeedChange(preset)
                                }
                                .padding(vertical = 10.dp)
                                .testTag("speed_preset_btn_$preset"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (preset == 1.0f) "Normal" else "${preset}x",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("speed_dialog_done_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
fun CustomSpeedIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        
        // Draw speedometer arc
        drawArc(
            color = color.copy(alpha = 0.4f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            style = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
        
        val cx = w / 2f
        val cy = h * 0.75f
        
        // Needle pointer pointing upper right
        val needleLength = w * 0.45f
        val angleRad = Math.toRadians(-45.0).toFloat()
        val endX = cx + needleLength * Math.cos(angleRad.toDouble()).toFloat()
        val endY = cy + needleLength * Math.sin(angleRad.toDouble()).toFloat()
        
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(endX, endY),
            strokeWidth = 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        
        drawCircle(
            color = color,
            radius = 3.5.dp.toPx(),
            center = Offset(cx, cy)
        )
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

@Composable
fun CustomFallbackArtIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Draw 3 vertical wave bars
        val barWidth = w * 0.12f
        val gap = w * 0.1f
        val startX = w * 0.25f
        
        // Bar 1
        drawRoundRect(
            color = color,
            topLeft = Offset(startX, h * 0.3f),
            size = Size(barWidth, h * 0.4f),
            cornerRadius = CornerRadius(barWidth/2, barWidth/2)
        )
        // Bar 2 (center, taller)
        drawRoundRect(
            color = color,
            topLeft = Offset(startX + barWidth + gap, h * 0.15f),
            size = Size(barWidth, h * 0.7f),
            cornerRadius = CornerRadius(barWidth/2, barWidth/2)
        )
        // Bar 3
        drawRoundRect(
            color = color,
            topLeft = Offset(startX + (barWidth + gap) * 2, h * 0.4f),
            size = Size(barWidth, h * 0.3f),
            cornerRadius = CornerRadius(barWidth/2, barWidth/2)
        )
    }
}

@Composable
fun CustomHistoryIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.width * 0.45f
        
        // Draw circle
        drawCircle(
            color = color,
            radius = r,
            style = Stroke(width = 2.dp.toPx())
        )
        // Clock hands
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx, cy - r * 0.5f),
            strokeWidth = 2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx + r * 0.4f, cy),
            strokeWidth = 2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
fun RecentlyPlayedCard(
    song: Song,
    isActive: Boolean,
    isDarkTheme: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(130.dp)
            .liquidGlassCard(cornerRadius = 16.dp, isDarkTheme = isDarkTheme)
            .liquidGlassClickable { onClick() }
            .testTag("recently_played_card_${song.id}")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                val painter = rememberAsyncImagePainter(model = song.albumArtUri)
                val painterState = painter.state

                Image(
                    painter = painter,
                    contentDescription = "Album Artwork",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                if (painterState is AsyncImagePainter.State.Error || painterState is AsyncImagePainter.State.Loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(24.dp)) {
                            val w = size.width
                            val h = size.height
                            val color = Color.White
                            drawCircle(color, radius = 4.dp.toPx(), center = Offset(w * 0.35f, h * 0.7f))
                            drawCircle(color, radius = 4.dp.toPx(), center = Offset(w * 0.7f, h * 0.6f))
                            drawLine(color, start = Offset(w * 0.35f + 4.dp.toPx(), h * 0.7f), end = Offset(w * 0.35f + 4.dp.toPx(), h * 0.3f), strokeWidth = 2.dp.toPx())
                            drawLine(color, start = Offset(w * 0.7f + 4.dp.toPx(), h * 0.6f), end = Offset(w * 0.7f + 4.dp.toPx(), h * 0.2f), strokeWidth = 2.dp.toPx())
                            drawLine(color, start = Offset(w * 0.35f + 4.dp.toPx(), h * 0.3f), end = Offset(w * 0.7f + 4.dp.toPx(), h * 0.2f), strokeWidth = 2.dp.toPx())
                        }
                    }
                }

                if (isActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.height(16.dp)
                        ) {
                            repeat(3) { index ->
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight(fraction = if (index == 1) 0.8f else 0.5f)
                                        .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(1.dp))
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }
    }
}
