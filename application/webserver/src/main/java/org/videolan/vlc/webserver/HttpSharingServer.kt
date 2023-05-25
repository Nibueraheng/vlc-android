/*
 * ************************************************************************
 *  HttpSharingServer.kt
 * *************************************************************************
 * Copyright © 2023 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.webserver

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.media.AudioManager
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.text.format.Formatter
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.files
import io.ktor.server.http.content.static
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.Album
import org.videolan.medialibrary.interfaces.media.Artist
import org.videolan.medialibrary.interfaces.media.Genre
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.Storage
import org.videolan.resources.AndroidDevices
import org.videolan.resources.PLAYLIST_TYPE_AUDIO
import org.videolan.resources.PLAYLIST_TYPE_VIDEO
import org.videolan.resources.util.await
import org.videolan.resources.util.getFromMl
import org.videolan.resources.util.observeLiveDataUntil
import org.videolan.tools.AppScope
import org.videolan.tools.KEY_ARTISTS_SHOW_ALL
import org.videolan.tools.KEY_WEB_SERVER_AUTH
import org.videolan.tools.KEY_WEB_SERVER_PASSWORD
import org.videolan.tools.KEY_WEB_SERVER_USER
import org.videolan.tools.NetworkMonitor
import org.videolan.tools.Settings
import org.videolan.tools.SingletonHolder
import org.videolan.tools.WEB_SERVER_FILE_BROWSER_CONTENT
import org.videolan.tools.WEB_SERVER_NETWORK_BROWSER_CONTENT
import org.videolan.tools.WEB_SERVER_PLAYBACK_CONTROL
import org.videolan.tools.awaitAppIsForegroung
import org.videolan.tools.livedata.LiveDataset
import org.videolan.tools.resIdByName
import org.videolan.vlc.ArtworkProvider
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.BitmapUtil
import org.videolan.vlc.gui.helpers.getBitmapFromDrawable
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.media.PlaylistManager
import org.videolan.vlc.providers.BrowserProvider
import org.videolan.vlc.providers.FileBrowserProvider
import org.videolan.vlc.providers.NetworkProvider
import org.videolan.vlc.providers.StorageProvider
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.TextUtils
import org.videolan.vlc.util.generateResolutionClass
import org.videolan.vlc.util.getFilesNumber
import org.videolan.vlc.util.getFolderNumber
import org.videolan.vlc.util.isSchemeFile
import org.videolan.vlc.util.isSchemeSMB
import org.videolan.vlc.util.isSchemeSupported
import org.videolan.vlc.util.slugify
import org.videolan.vlc.util.toByteArray
import org.videolan.vlc.viewmodels.browser.FavoritesProvider
import org.videolan.vlc.viewmodels.browser.IPathOperationDelegate
import org.videolan.vlc.viewmodels.browser.PathOperationDelegate
import org.videolan.vlc.webserver.utils.MediaZipUtils
import org.videolan.vlc.webserver.utils.serveAudios
import org.videolan.vlc.webserver.utils.servePlaylists
import org.videolan.vlc.webserver.utils.serveSearch
import org.videolan.vlc.webserver.utils.serveVideos
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.DateFormat
import java.time.Duration
import java.util.Collections
import java.util.Locale

private const val TAG = "HttpSharingServer"

class HttpSharingServer(private val context: Context) : PlaybackService.Callback, IPathOperationDelegate by PathOperationDelegate() {
    private var settings: SharedPreferences
    private lateinit var engine: NettyApplicationEngine
    private var websocketSession: ArrayList<DefaultWebSocketServerSession> = arrayListOf()
    private var service: PlaybackService? = null
    private val format by lazy {
        object : ThreadLocal<DateFormat>() {
            override fun initialValue() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
        }
    }
    private val networkSharesLiveData = LiveDataset<MediaLibraryItem>()


    private val _serverStatus = MutableLiveData(ServerStatus.NOT_INIT)
    val serverStatus: LiveData<ServerStatus>
        get() = _serverStatus

    private val _serverConnections = MutableLiveData(listOf<WebServerConnection>())
    val serverConnections: LiveData<List<WebServerConnection>>
        get() = _serverConnections

    private val otgDevice = context.getString(org.videolan.vlc.R.string.otg_device_title)

    private val miniPlayerObserver = androidx.lifecycle.Observer<Boolean> { playing ->
        AppScope.launch {
            websocketSession.forEach {
                val playerStatus = PlayerStatus(playing)
                val gson = Gson()
                it.send(Frame.Text(gson.toJson(playerStatus)))
            }
        }
    }

    private val scope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, throwable.message, throwable)
            })

    private var auth = false
    private var user = ""
    private var password = ""

    private val downloadFolder by lazy { "${context.getExternalFilesDir(null)!!.absolutePath}/downloads" }

    init {
        copyWebServer()
        PlaybackService.serviceFlow.onEach { onServiceChanged(it) }
                .onCompletion { service?.removeCallback(this@HttpSharingServer) }
                .launchIn(AppScope)
        _serverStatus.postValue(ServerStatus.STOPPED)
        settings = Settings.getInstance(context)
    }


    /**
     * Start the server. Refresh the authentication settings before
     * Also start monitoring the network shares for the web browser
     */
    suspend fun start() {
        AppScope.launch(Dispatchers.IO) {
            //clean download dir if not cleaned by download
            val downloadDir = File(downloadFolder)
            if (downloadDir.isDirectory) downloadDir.listFiles()?.forEach { it.delete() }
        }
        auth = Settings.getInstance(context).getBoolean(KEY_WEB_SERVER_AUTH, false)
        user = Settings.getInstance(context).getString(KEY_WEB_SERVER_USER, "") ?: ""
        password = Settings.getInstance(context).getString(KEY_WEB_SERVER_PASSWORD, "")
                ?: ""
        _serverStatus.postValue(ServerStatus.CONNECTING)
        withContext(Dispatchers.IO) {
            engine = generateServer()
            engine.start()
        }

        withContext(Dispatchers.Main) {
            //keep track of the network shares as they are highly asynchronous
            val provider = NetworkProvider(context, networkSharesLiveData, null)
            NetworkMonitor.getInstance(context).connectionFlow.onEach {
                if (it.connected) provider.refresh()
                else networkSharesLiveData.clear()
            }.launchIn(AppScope)
        }
    }

    /**
     * Stop the server and all the websocket connections
     *
     */
    suspend fun stop() {
        AppScope.launch(Dispatchers.IO) {
            //clean download dir if not cleaned by download
            val downloadDir = File(downloadFolder)
            if (downloadDir.isDirectory) downloadDir.listFiles()?.forEach { it.delete() }
        }
        _serverStatus.postValue(ServerStatus.STOPPING)
        withContext(Dispatchers.IO) {
            websocketSession.forEach {
                it.close()
            }
            engine.stop()
        }
    }

    /**
     * Get the server files path
     *
     * @return the server file poath
     */
    private fun getServerFiles(): String {
        return "${context.filesDir.path}/server/"
    }

    /**
     * Listen to the [PlaybackService] connection
     *
     * @param service the service to listen
     */
    private fun onServiceChanged(service: PlaybackService?) {
        if (service !== null) {
            this.service = service
            service.addCallback(this)
        } else this.service?.let {
            it.removeCallback(this)
            this.service = null
        }
    }

    /**
     * Copy the web server files to serve
     *
     */
    private fun copyWebServer() {
        File(getServerFiles()).mkdirs()
        FileUtils.copyAssetFolder(context.assets, "dist", "${context.filesDir.path}/server", true)
    }

    /**
     * Ktor plugin intercepting all the requests
     * Used to manage a connected device list
     */
    private val InterceptorPlugin = createApplicationPlugin(name = "VLCInterceptorPlugin") {
        onCall { call ->
            call.request.origin.apply {
                val oldConnections = _serverConnections.value
                if ((oldConnections?.filter { it.ip == remoteHost }?.size ?: 0) == 0) {
                    val connection = WebServerConnection(remoteHost)
                    withContext(Dispatchers.Main) {
                        try {
                            _serverConnections.value = oldConnections!!.toMutableList().apply { add(connection) }
                        } catch (e: Exception) {
                            Log.e("InterceptorPlugin", e.message, e)
                        }
                    }
                }
            }

        }
    }

    /**
     * Generate the server
     *
     * @return the server engine
     */
    private fun generateServer() = scope.embeddedServer(Netty, 8080) {
        install(InterceptorPlugin)
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        if (auth && user.isNotEmpty() && password.isNotEmpty()) install(Authentication) {
            basic("auth-basic") {
                realm = "Access to the '/' path"
                validate { credentials ->
                    if (credentials.name == user && credentials.password == password) {
                        UserIdPrincipal(credentials.name)
                    } else {
                        null
                    }
                }
            }
        }
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowHeader(HttpHeaders.AccessControlAllowOrigin)
            allowHeader(HttpHeaders.ContentType)
            anyHost()
        }
        install(PartialContent)
        routing {
            if (auth) authenticate("auth-basic") {
                setupRouting()
            } else setupRouting()
            webSocket("/echo", protocol = "player") {
                websocketSession.add(this)
                // Handle a WebSocket session
                for (frame in incoming) {
                    try {
                        frame as? Frame.Text ?: continue
                        val message = frame.readText()
                        val gson = Gson()
                        val incomingMessage = gson.fromJson(message, WSIncomingMessage::class.java)
                        when (incomingMessage.message) {
                            "play" -> if (playbackControlAllowedOrSend()) service?.play()
                            "pause" -> if (playbackControlAllowedOrSend()) service?.pause()
                            "previous" -> if (playbackControlAllowedOrSend()) service?.previous(false)
                            "next" -> if (playbackControlAllowedOrSend()) service?.next()
                            "previous10" -> if (playbackControlAllowedOrSend()) service?.let { it.seek((it.getTime() - 10000).coerceAtLeast(0), fromUser = true) }
                            "next10" -> if (playbackControlAllowedOrSend()) service?.let { it.seek((it.getTime() + 10000).coerceAtMost(it.length), fromUser = true) }
                            "shuffle" -> if (playbackControlAllowedOrSend()) service?.shuffle()
                            "repeat" -> if (playbackControlAllowedOrSend()) service?.let {
                                when (it.repeatType) {
                                    PlaybackStateCompat.REPEAT_MODE_NONE -> {
                                        it.repeatType = PlaybackStateCompat.REPEAT_MODE_ONE
                                    }
                                    PlaybackStateCompat.REPEAT_MODE_ONE -> if (it.hasPlaylist()) {
                                        it.repeatType = PlaybackStateCompat.REPEAT_MODE_ALL
                                    } else {
                                        it.repeatType = PlaybackStateCompat.REPEAT_MODE_NONE
                                    }
                                    PlaybackStateCompat.REPEAT_MODE_ALL -> {
                                        it.repeatType = PlaybackStateCompat.REPEAT_MODE_NONE
                                    }
                                }
                            }
                            "get-volume" -> {
                                AppScope.launch { websocketSession.forEach { it.send(Frame.Text(getVolumeMessage())) } }
                            }
                            "set-volume" -> {
                                if (playbackControlAllowedOrSend()) (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let {
                                    val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    it.setStreamVolume(AudioManager.STREAM_MUSIC, ((incomingMessage.id!!.toFloat() / 100) * max).toInt(), AudioManager.FLAG_SHOW_UI)

                                }

                            }
                            "set-progress" -> {
                                if (playbackControlAllowedOrSend()) incomingMessage.id?.let {
                                    service?.setTime(it.toLong())
                                }
                            }
                            "play-media" -> {
                                if (playbackControlAllowedOrSend()) service?.playIndex(incomingMessage.id!!)

                            }
                            "delete-media" -> {
                                if (playbackControlAllowedOrSend()) service?.remove(incomingMessage.id!!)

                            }
                            "move-media-bottom" -> {
                               if (playbackControlAllowedOrSend()) {
                                   val index = incomingMessage.id!!
                                   if (index < (service?.playlistManager?.getMediaListSize()
                                                   ?: 0) - 1)
                                       service?.moveItem(index, index + 2)
                               }

                            }
                            "move-media-top" -> {
                                if (playbackControlAllowedOrSend()) {
                                    val index = incomingMessage.id!!
                                    if (index > 0)
                                        service?.moveItem(index, index - 1)
                                }

                            }
                            else -> Log.w(TAG, "Unrecognized message", IllegalStateException("Unrecognized message: $message"))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, e.message, e)
                    }
                }
                websocketSession.remove(this)
            }
        }
    }.apply {
        environment.monitor.subscribe(ApplicationStarted) {
            _serverStatus.postValue(ServerStatus.STARTED)
            AppScope.launch(Dispatchers.Main) { PlaylistManager.playingState.observeForever(miniPlayerObserver) }
        }
        environment.monitor.subscribe(ApplicationStopped) {
            AppScope.launch(Dispatchers.Main) { PlaylistManager.playingState.removeObserver(miniPlayerObserver) }
            _serverStatus.postValue(ServerStatus.STOPPED)
        }
    }

    /**
     * Setup the server routing
     *
     */
    private fun Route.setupRouting() {
        val appContext = this@HttpSharingServer.context
        static("") {
            files(getServerFiles())
        }
        // Main end point redirect to index.html
        get("/") {
            call.respondRedirect("index.html", permanent = true)
        }
        get("/index.html") {
            try {
                val html = FileUtils.getStringFromFile("${getServerFiles()}index.html")
                call.respondText(html, ContentType.Text.Html)
            } catch (e: Exception) {
                call.respondText("Failed to load index.html")
            }
        }
        // Upload a file to the device
        post("/upload-media") {
            var fileDescription = ""
            var fileName = ""
            val multipartData = call.receiveMultipart()

            multipartData.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        fileDescription = part.value
                    }
                    is PartData.FileItem -> {
                        File("${AndroidDevices.MediaFolders.EXTERNAL_PUBLIC_DOWNLOAD_DIRECTORY_URI.path}/uploads").mkdirs()
                        fileName = part.originalFileName as String
                        var fileBytes = part.streamProvider().readBytes()
                        File("${AndroidDevices.MediaFolders.EXTERNAL_PUBLIC_DOWNLOAD_DIRECTORY_URI.path}/uploads/$fileName").writeBytes(fileBytes)
                    }
                    else -> {}
                }
            }
            call.respondText("$fileDescription is uploaded to 'uploads/$fileName'")
        }
        // Download a log file
        get("/download-logfile") {
            call.request.queryParameters["file"]?.let { filePath ->
                val file = File(filePath)
                if (file.exists()) {
                    call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString())
                    call.respondFile(File(filePath))
                }
            }
            call.respond(HttpStatusCode.NotFound, "")
        }
        // List all log files
        get("/list-logfiles") {
            val logs = getLogsFiles().sortedBy { File(it).lastModified() }.reversed()

            val jsonArray = JSONArray()
            for (log in logs) {
                val json = JSONObject()
                json.put("path", log)
                json.put("date", format.get()?.format(File(log).lastModified()))
                jsonArray.put(json)
            }
            call.respondText(jsonArray.toString())
        }
        // List of all the videos
        get("/video-list") {
            if (!settings.serveVideos(appContext)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val videos = appContext.getFromMl { getVideos(Medialibrary.SORT_DEFAULT, false, false, false) }

            val list = ArrayList<PlayQueueItem>()
            videos.forEach { video ->
                list.add(video.toPlayQueueItem())
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        // List of all the albums
        get("/album-list") {
            if (!settings.serveAudios(appContext)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val albums = appContext.getFromMl { getAlbums(false, false) }

            val list = ArrayList<PlayQueueItem>()
            albums.forEach { album ->
                list.add(album.toPlayQueueItem())
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        // List of all the artists
        get("/artist-list") {
            if (!settings.serveAudios(appContext)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val artists = appContext.getFromMl { getArtists(settings.getBoolean(KEY_ARTISTS_SHOW_ALL, false), false, false) }

            val list = ArrayList<PlayQueueItem>()
            artists.forEach { artist ->
                list.add(artist.toPlayQueueItem(appContext))
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        // List of all the audio tracks
        get("/track-list") {
            if (!settings.serveAudios(appContext)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val tracks = appContext.getFromMl { getAudio(Medialibrary.SORT_DEFAULT, false, false, false) }

            val list = ArrayList<PlayQueueItem>()
            tracks.forEach { track ->
                list.add(track.toPlayQueueItem())
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        // List of all the audio genres
        get("/genre-list") {
            if (!settings.serveAudios(appContext)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val genres = appContext.getFromMl { getGenres(false, false) }

            val list = ArrayList<PlayQueueItem>()
            genres.forEach { genre ->
                list.add(genre.toPlayQueueItem(appContext))
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        // List of all the playlists
        get("/playlist-list") {
            if (!settings.servePlaylists(appContext)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val playlists = appContext.getFromMl { getPlaylists(Playlist.Type.All, false) }

            val list = ArrayList<PlayQueueItem>()
            playlists.forEach { playlist ->
                list.add(playlist.toPlayQueueItem(appContext))
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        // Search media
        get("/search") {
            if (!settings.serveSearch(appContext)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            call.request.queryParameters["search"]?.let { query ->
                val searchAggregate = appContext.getFromMl { search(query, Settings.includeMissing, false) }

                searchAggregate?.let { result ->
                    val results = SearchResults(
                            result.albums?.filterNotNull()?.map { it.toPlayQueueItem() }
                                    ?: listOf(),
                            result.artists?.filterNotNull()?.map { it.toPlayQueueItem(appContext) }
                                    ?: listOf(),
                            result.genres?.filterNotNull()?.map { it.toPlayQueueItem(appContext) }
                                    ?: listOf(),
                            result.playlists?.filterNotNull()?.map { it.toPlayQueueItem(appContext) }
                                    ?: listOf(),
                            result.videos?.filterNotNull()?.map { it.toPlayQueueItem() }
                                    ?: listOf(),
                            result.tracks?.filterNotNull()?.map { it.toPlayQueueItem() }
                                    ?: listOf(),
                    )
                    val gson = Gson()
                    call.respondText(gson.toJson(results))
                }

            }
            val gson = Gson()
            call.respondText(gson.toJson(SearchResults(listOf(), listOf(), listOf(), listOf(), listOf(), listOf())))
        }
        // List of all the file storages
        get("/storage-list") {
            if (!settings.getBoolean(WEB_SERVER_FILE_BROWSER_CONTENT, false)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val dataset = LiveDataset<MediaLibraryItem>()
            val provider = withContext(Dispatchers.Main) {
                StorageProvider(appContext, dataset, null)
            }
            val list = try {
                getProviderContent(provider, dataset, 1000L)
            } catch (e: Exception) {
                Log.e(this::class.java.simpleName, e.message, e)
                call.respond(HttpStatusCode.InternalServerError)
                return@get
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        // List of all the file favorites
        get("/favorite-list") {
            if (!settings.getBoolean(WEB_SERVER_FILE_BROWSER_CONTENT, false)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val dataset = LiveDataset<MediaLibraryItem>()
            val provider = withContext(Dispatchers.Main) {
                FavoritesProvider(appContext, dataset, AppScope)
            }
            val list = try {
                getProviderContent(provider, dataset, 2000L)
            } catch (e: Exception) {
                Log.e(this::class.java.simpleName, e.message, e)
                call.respond(HttpStatusCode.InternalServerError)
                return@get
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        // List of all the network shares
        get("/network-list") {
            if (!settings.getBoolean(WEB_SERVER_NETWORK_BROWSER_CONTENT, false)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val list = ArrayList<PlayQueueItem>()
            networkSharesLiveData.getList().forEachIndexed { index, mediaLibraryItem ->
                list.add(PlayQueueItem(3000L + index, mediaLibraryItem.title, "", 0, mediaLibraryItem.artworkMrl
                        ?: "", false, "", (mediaLibraryItem as MediaWrapper).uri.toString(), true))
            }
            val gson = Gson()
            call.respondText(gson.toJson(list))
        }
        //list of folders and files in a path
        get("/browse-list") {
            if (!settings.getBoolean(WEB_SERVER_FILE_BROWSER_CONTENT, false)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val path = call.request.queryParameters["path"] ?: kotlin.run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val decodedPath = Uri.decode(path)

            val dataset = LiveDataset<MediaLibraryItem>()
            val provider = withContext(Dispatchers.Main) {
                FileBrowserProvider(appContext, dataset, decodedPath, false, false, Medialibrary.SORT_FILENAME, false)
            }
            val list = try {
                getProviderContent(provider, dataset, 1000L)
            } catch (e: Exception) {
                Log.e(this::class.java.simpleName, e.message, e)
                call.respond(HttpStatusCode.InternalServerError)
                return@get
            }

            //segments
            PathOperationDelegate.storages.put(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY, makePathSafe(appContext.getString(org.videolan.vlc.R.string.internal_memory)))
            val breadcrumbItems = if (!isSchemeSupported(Uri.parse(decodedPath).scheme))
                listOf(BreadcrumbItem(appContext.getString(R.string.home), "root"))
            else
                prepareSegments(Uri.parse(decodedPath)).map {
                    BreadcrumbItem(it.first, it.second)
                }.toMutableList().apply {
                    add(0, BreadcrumbItem(appContext.getString(R.string.home), "root"))
                }


            val result = BrowsingResult(list, breadcrumbItems)
            val gson = Gson()
            call.respondText(gson.toJson(result))
        }
        // Resume playback
        get("/resume-playback") {
            val audio = call.request.queryParameters["audio"] == "true"
            MediaUtils.loadlastPlaylist(appContext, if (audio) PLAYLIST_TYPE_AUDIO else PLAYLIST_TYPE_VIDEO)
            call.respond(HttpStatusCode.OK)
        }
        // Play a media
        get("/play") {
            val type = call.request.queryParameters["type"] ?: "media"
            val append = call.request.queryParameters["append"] == "true"
            val asAudio = call.request.queryParameters["audio"] == "true"
            val path = call.request.queryParameters["path"]
            call.request.queryParameters["id"]?.let { id ->

                val medias = appContext.getFromMl {
                    if (path?.isNotBlank() == true) {
                        arrayOf(MLServiceLocator.getAbstractMediaWrapper(Uri.parse(path)))
                    } else when (type) {
                        "album" -> getAlbum(id.toLong()).tracks
                        "artist" -> getArtist(id.toLong()).tracks
                        "genre" -> getGenre(id.toLong()).tracks
                        "playlist" -> getPlaylist(id.toLong(), false, false).tracks
                        else -> arrayOf(getMedia(id.toLong()))
                    }
                }
                if (medias.isEmpty()) call.respond(HttpStatusCode.NotFound)
                else {

                    if (asAudio) medias[0].addFlags(MediaWrapper.MEDIA_FORCE_AUDIO)
                    if (medias[0].type == MediaWrapper.TYPE_VIDEO && !appContext.awaitAppIsForegroung()) {
                        call.respond(HttpStatusCode.Forbidden, appContext.getString(R.string.ns_not_in_foreground))
                    }
                    when {
                        append -> MediaUtils.appendMedia(appContext, medias)
                        else -> MediaUtils.openList(appContext, medias.toList(), 0)
                    }
                    call.respond(HttpStatusCode.OK)
                }
            }
            call.respond(HttpStatusCode.NotFound)
        }
        // Download a media file
        get("/prepare-download") {
            val type = call.request.queryParameters["type"] ?: "media"
            call.request.queryParameters["id"]?.let { id ->
                when (type) {
                    "album" -> {
                        val album = appContext.getFromMl { getAlbum(id.toLong()) }
                        val dst = MediaZipUtils.generateAlbumZip(album, downloadFolder)
                        call.respondText(dst)
                        return@get
                    }
                    "artist" -> {
                        val artist = appContext.getFromMl { getArtist(id.toLong()) }
                        val dst = MediaZipUtils.generateArtistZip(artist, downloadFolder)
                        call.respondText(dst)
                        return@get
                    }
                    "genre" -> {
                        val genre = appContext.getFromMl { getGenre(id.toLong()) }
                        val dst = MediaZipUtils.generateGenreZip(genre, downloadFolder)
                        call.respondText(dst)
                        return@get
                    }
                    "playlist" -> {
                        val playlist = appContext.getFromMl { getPlaylist(id.toLong(), false, false) }
                        val dst = MediaZipUtils.generatePlaylistZip(playlist, downloadFolder)
                        call.respondText(dst)
                        return@get
                    }
                    else -> {
                        //simple media. It's a direct download
                        appContext.getFromMl { getMedia(id.toLong()) }?.let { media ->
                            media.uri.path?.let { path ->
                                val file = File(path)
                                val name = media.title.slugify("_") + media.uri.toString().substring(media.uri.toString().lastIndexOf("."))
                                call.response.header(
                                        HttpHeaders.ContentDisposition,
                                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name)
                                                .toString()
                                )
                                call.respondFile(file)
                            }
                        }
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
            call.respond(HttpStatusCode.NotFound)
        }
        //Download a file previously prepared
        get("/download") {
            call.request.queryParameters["file"]?.let {
                val dst = File("$downloadFolder/$it")
                call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, dst.toUri().lastPathSegment
                                ?: "")
                                .toString()
                )
                call.respondFile(dst)
                dst.delete()
            }
            call.respond(HttpStatusCode.NotFound)
        }
        // Sends an icon
        get("/icon") {
            val idString = call.request.queryParameters["id"]
            val width = call.request.queryParameters["width"]?.toInt() ?: 32

            val id = try {
                appContext.resIdByName(idString, "drawable")
            } catch (e: Resources.NotFoundException) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            if (id == 0) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            BitmapUtil.encodeImage(BitmapUtil.vectorToBitmap(appContext, id, width, width), true)?.let {

                call.respondBytes(ContentType.Image.PNG) { it }
                return@get
            }

            call.respond(HttpStatusCode.NotFound)

        }
        // Get the translation string list
        get("/translation") {
            call.respondText(TranslationMapping.generateTranslations(appContext))
        }
        // Get a media artwork
        get("/artwork") {
            if (call.request.queryParameters["type"] in arrayOf("folder", "network")) {
                BitmapUtil.encodeImage(BitmapUtil.vectorToBitmap(appContext, R.drawable.ic_menu_folder, 256, 256), true)?.let {
                    call.respondBytes(ContentType.Image.PNG) { it }
                    return@get
                }
            }
            if (call.request.queryParameters["type"] == "file") {
                BitmapUtil.encodeImage(BitmapUtil.vectorToBitmap(appContext, R.drawable.ic_browser_unknown_normal, 256, 256), true)?.let {
                    call.respondBytes(ContentType.Image.PNG) { it }
                    return@get
                }
            }
            try {
                val artworkMrl = call.request.queryParameters["artwork"] ?: service?.coverArt
                //check by id and use the ArtworkProvider if provided
                call.request.queryParameters["id"]?.let {
                    val cr = appContext.contentResolver
                    val mediaType = when (call.request.queryParameters["type"]) {
                        "video" -> ArtworkProvider.VIDEO
                        "album" -> ArtworkProvider.ALBUM
                        "artist" -> ArtworkProvider.ARTIST
                        "genre" -> ArtworkProvider.GENRE
                        "playlist" -> ArtworkProvider.PLAYLIST
                        else -> ArtworkProvider.MEDIA
                    }
                    cr.openInputStream(Uri.parse("content://${appContext.applicationContext.packageName}.artwork/$mediaType/0/$it"))?.let { inputStream ->
                        call.respondBytes(ContentType.Image.JPEG) { inputStream.toByteArray() }
                        inputStream.close()
                        return@get
                    }
                }

                //id is not provided, use the artwork query and fallback on the current playing media
                artworkMrl?.let { coverArt ->
                    AudioUtil.readCoverBitmap(Uri.decode(coverArt), 512)?.let { bitmap ->
                        BitmapUtil.convertBitmapToByteArray(bitmap)?.let {

                            call.respondBytes(ContentType.Image.JPEG) { it }
                            return@get
                        }
                    }
                }
                // nothing found . Falling back on the no media bitmap
                appContext.getBitmapFromDrawable(R.drawable.ic_no_media, 512, 512)?.let {

                    BitmapUtil.encodeImage(it, true)?.let {

                        call.respondBytes(ContentType.Image.PNG) { it }
                        return@get
                    }
                }
            } catch (e: Exception) {
                Log.e("networkShareReplace", e.message, e)
            }
            call.respond(HttpStatusCode.NotFound, "")
        }
    }

    /**
     * The the list of all the log files
     *
     * @return a list of file paths
     */
    private suspend fun getLogsFiles(): List<String> = withContext(Dispatchers.IO) {
        val result = ArrayList<String>()
        val folder = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY)
        val files = folder.listFiles()
        files.forEach {
            if (it.isFile && it.name.startsWith("vlc_logcat_")) result.add(it.path)
        }

        return@withContext result

    }

    /**
     * update callback of the [PlaybackService]
     *
     */
    override fun update() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Send now playing from update")
        generateNowPlaying()?.let { nowPlaying ->
            AppScope.launch { websocketSession.forEach { it.send(Frame.Text(nowPlaying)) } }
        }
        generatePlayQueue()?.let { playQueue ->
            AppScope.launch { websocketSession.forEach { it.send(Frame.Text(playQueue)) } }
        }
    }

    /**
     * onMediaEvent callback of the [PlaybackService]
     *
     * @param event the event sent
     */
    override fun onMediaEvent(event: IMedia.Event) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Send now playing from onMediaEvent")
        generateNowPlaying()?.let { nowPlaying ->
            AppScope.launch { websocketSession.forEach { it.send(Frame.Text(nowPlaying)) } }
        }
        generatePlayQueue()?.let { playQueue ->
            AppScope.launch { websocketSession.forEach { it.send(Frame.Text(playQueue)) } }
        }
    }

    /**
     * onMediaPlayerEvent callback of the [PlaybackService]
     *
     * @param event the event sent
     */
    override fun onMediaPlayerEvent(event: MediaPlayer.Event) {
        if (event.type != MediaPlayer.Event.TimeChanged) return
        generateNowPlaying()?.let { nowPlaying ->
            AppScope.launch {
                if (BuildConfig.DEBUG) Log.d("DebugPlayer", "onMediaPlayerEvent $nowPlaying")
                websocketSession.forEach { it.send(Frame.Text(nowPlaying)) }
            }
        }
        generatePlayQueue()?.let { playQueue ->
            AppScope.launch {
                websocketSession.forEach { it.send(Frame.Text(playQueue)) }
            }
        }
    }

    /**
     * Get the content from a [BrowserProvider]
     * Gracefully (or not) waits for the [LiveData] to be set before sending the result back
     *
     * @param provider the [BrowserProvider] to use
     * @param dataset the [LiveDataset] to listen to
     * @param idPrefix a prefix allowing to generate an unique id
     * @return a populated list
     */
    private suspend fun getProviderContent(provider: BrowserProvider, dataset: LiveDataset<MediaLibraryItem>, idPrefix: Long): ArrayList<PlayQueueItem> {
        dataset.await()
        val descriptions = ArrayList<Pair<Int, String>>()
        observeLiveDataUntil(1500, provider.descriptionUpdate) { pair ->
            descriptions.add(pair)
            val block = descriptions.size < dataset.getList().size
            block
        }
        val list = ArrayList<PlayQueueItem>()
        dataset.getList().forEachIndexed { index, mediaLibraryItem ->
            val description = try {
                if (mediaLibraryItem is MediaWrapper && mediaLibraryItem.type != MediaWrapper.TYPE_DIR) {
                    if (mediaLibraryItem.uri.scheme.isSchemeFile()) {
                        mediaLibraryItem.uri.path?.let {
                            Formatter.formatFileSize(context, File(it).length())
                        } ?: ""
                    }else
                    ""
                } else {
                    val unparsedDescription = descriptions.firstOrNull { it.first == index }?.second
                    val folders = unparsedDescription.getFolderNumber()
                    val files = unparsedDescription.getFilesNumber()
                    "${context.resources.getQuantityString(org.videolan.vlc.R.plurals.subfolders_quantity, folders, folders)} ${TextUtils.separator} ${context.resources.getQuantityString(org.videolan.vlc.R.plurals.mediafiles_quantity, files, files)}"
                }
            } catch (e: Exception) {
                Log.e(this::class.java.simpleName, e.message, e)
                ""
            }
            val path = when (mediaLibraryItem) {
                is MediaWrapper -> mediaLibraryItem.uri.toString()
                is Storage -> mediaLibraryItem.uri.toString()
                else -> throw IllegalStateException("Unrecognised media type")

            }
            val title = if (provider is FileBrowserProvider
                    && (provider.url == null || Uri.parse(provider.url).scheme.isSchemeFile())
                    && mediaLibraryItem is MediaWrapper) mediaLibraryItem.fileName else mediaLibraryItem.title
            val isFolder = if (mediaLibraryItem is MediaWrapper) mediaLibraryItem.type == MediaWrapper.TYPE_DIR else true
            list.add(PlayQueueItem(idPrefix + index, title, description, 0, mediaLibraryItem.artworkMrl
                    ?: "", false, "", path, isFolder))
        }
        return list
    }


    private fun playbackControlAllowedOrSend():Boolean {
        val allowed = settings.getBoolean(WEB_SERVER_PLAYBACK_CONTROL, true)
        val message = Gson().toJson(PlaybackControlForbidden())
        if (!allowed) AppScope.launch { websocketSession.forEach { it.send(Frame.Text(message)) } }
        return allowed
    }

    /**
     * Generate the now playing data to be sent to the client
     *
     * @return a [String] describing the now playing
     */
    private fun generateNowPlaying(): String? {
        service?.let { service ->
            service.currentMediaWrapper?.let { media ->
                val gson = Gson()
                val nowPlaying = NowPlaying(media.title ?: "", media.artist
                        ?: "", service.isPlaying, service.getTime(), service.length, media.id, media.artworkURL
                        ?: "", media.uri.toString(), getVolume(), service.isShuffling, service.repeatType)
                return gson.toJson(nowPlaying)

            }
        }
        return null
    }

    /**
     * Generate the play queue data to be sent to the client
     *
     * @return a [String] describing the play queue
     */
    private fun generatePlayQueue(): String? {
        service?.let { service ->
            val list = ArrayList<PlayQueueItem>()
            service.playlistManager.getMediaList().forEachIndexed { index, mediaWrapper ->
                list.add(PlayQueueItem(mediaWrapper.id, mediaWrapper.title, mediaWrapper.artist
                        ?: "", mediaWrapper.length, mediaWrapper.artworkMrl
                        ?: "", service.playlistManager.currentIndex == index))
            }
            val gson = Gson()
            return gson.toJson(PlayQueue(list))
        }
        return null
    }

    /**
     * Get the volume to be sent to the client
     *
     * @return an [Int] describing the volume
     */
    private fun getVolume(): Int = when {
        service?.isVideoPlaying == true && service!!.volume > 100 -> service!!.volume
        else -> {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let {
                val vol = it.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                (vol.toFloat() / max * 100).toInt()
            }
                    ?: 0
        }
    }

    /**
     * Get the volume message to be sent to the client
     *
     * @return an [String] describing the volume message
     */
    private fun getVolumeMessage(): String {
        val gson = Gson()
        val volume = when {
            service?.isVideoPlaying == true && service!!.volume > 100 -> service!!.volume
            else -> {
                (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let {
                    val vol = it.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    (vol.toFloat() / max * 100).toInt()
                }
                        ?: 0
            }
        }
        return gson.toJson(Volume(volume))
    }

    /**
     * Returns the server address
     *
     * @return the server address
     */
    fun serverInfo(): String = buildString {
        getIPAddresses(true).forEach {
            append("http://")
            append(it)
            append(":")
            append(engine.environment.connectors[0].port)
        }
    }

    /**
     * Get IP address from first non-localhost interface
     * @param useIPv4   true=return ipv4, false=return ipv6
     * @return  address or empty string
     */
    private fun getIPAddresses(useIPv4: Boolean): List<String> {
        val results = arrayListOf<String>()
        try {
            val interfaces: List<NetworkInterface> =
                    Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val inetAddresses: List<InetAddress> =
                        Collections.list(networkInterface.inetAddresses)
                for (inetAddress in inetAddresses) {
                    if (!inetAddress.isLoopbackAddress) {
                        val hostAddress = inetAddress.hostAddress ?: continue
                        val isIPv4 = hostAddress.indexOf(':') < 0
                        if (useIPv4) {
                            if (isIPv4) results.add(hostAddress)
                        } else {
                            if (!isIPv4) {
                                val delim = hostAddress.indexOf('%')
                                if (delim < 0)
                                    results.add(hostAddress.uppercase(Locale.getDefault()))
                                else
                                    results.add(
                                            hostAddress.substring(0, delim)
                                                    .uppercase(Locale.getDefault())
                                    )
                            }
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return results
    }

    /**
     * Splits an [Uri] in a list of string used as the adapter items
     * Each item is a string representing a valid path
     *
     * @param uri the [Uri] that has to be split
     * @return a list of strings representing the items
     */
    private fun prepareSegments(uri: Uri): MutableList<Pair<String, String>> {
        val path = Uri.decode(uri.path)
        val isOtg = path.startsWith("/tree/")
        val string = when {
            isOtg -> if (path.endsWith(':')) "" else path.substringAfterLast(':')
            else -> replaceStoragePath(path)
        }
        val list: MutableList<Pair<String, String>> = mutableListOf()
        if (isOtg) list.add(Pair(otgDevice, "root"))
        if (uri.scheme.isSchemeSMB()) {
            networkSharesLiveData.getList().forEach {
                (it as? MediaWrapper)?.let { share ->
                    if (share.uri.scheme == uri.scheme && share.uri.authority == uri.authority) {
                        list.add(Pair(share.title, Uri.Builder().scheme(uri.scheme).encodedAuthority(uri.authority).build().toString()))
                    }
                }
            }
        }

        //list of all the path chunks
        val pathParts = string.split('/').filter { it.isNotEmpty() }
        for (index in pathParts.indices) {
            //start creating the Uri
            val currentPathUri = Uri.Builder().scheme(uri.scheme).encodedAuthority(uri.authority)
            //append all the previous paths and the current one
            for (i in 0..index) appendPathToUri(pathParts[i], currentPathUri)
            val currentUri = currentPathUri.build()
            val text: String = when {
                //substitute a storage path to its name. See [replaceStoragePath]
                PathOperationDelegate.storages.containsKey(currentUri.path) -> retrieveSafePath(PathOperationDelegate.storages.valueAt(PathOperationDelegate.storages.indexOfKey(currentUri.path)))
                else -> currentUri.lastPathSegment ?: "root"
            }
            list.add(Pair(text, currentPathUri.toString()))
        }
        return list
    }

    abstract class WSMessage(val type: String)
    data class NowPlaying(val title: String, val artist: String, val playing: Boolean, val progress: Long, val duration: Long, val id: Long, val artworkURL: String, val uri: String, val volume: Int, val shuffle: Boolean, val repeat: Int, val shouldShow: Boolean = PlaylistManager.playingState.value
            ?: false) : WSMessage("now-playing")

    data class PlayQueue(val medias: List<PlayQueueItem>) : WSMessage("play-queue")
    data class PlayQueueItem(val id: Long, val title: String, val artist: String, val length: Long, val artworkURL: String, val playing: Boolean, val resolution: String = "", val path: String = "", val isFolder: Boolean = false)
    data class Volume(val volume: Int) : WSMessage("volume")
    data class PlayerStatus(val playing: Boolean) : WSMessage("player-status")
    data class PlaybackControlForbidden(val forbidden: Boolean = true): WSMessage("playback-control-forbidden")
    data class SearchResults(val albums: List<PlayQueueItem>, val artists: List<PlayQueueItem>, val genres: List<PlayQueueItem>, val playlists: List<PlayQueueItem>, val videos: List<PlayQueueItem>, val tracks: List<PlayQueueItem>)
    data class BreadcrumbItem(val title: String, val path: String)
    data class BrowsingResult(val content: List<PlayQueueItem>, val breadcrumb: List<BreadcrumbItem>)

    fun Album.toPlayQueueItem() = PlayQueueItem(id, title, albumArtist ?: "", duration, artworkMrl
            ?: "", false, "")

    fun Artist.toPlayQueueItem(appContext: Context) = PlayQueueItem(id, title, appContext.resources.getQuantityString(R.plurals.albums_quantity, albumsCount, albumsCount), 0, artworkMrl
            ?: "", false, "")

    fun Genre.toPlayQueueItem(appContext: Context) = PlayQueueItem(id, title, appContext.resources.getQuantityString(R.plurals.track_quantity, tracksCount, tracksCount), 0, artworkMrl
            ?: "", false, "")

    fun Playlist.toPlayQueueItem(appContext: Context) = PlayQueueItem(id, title, appContext.resources.getQuantityString(R.plurals.track_quantity, tracksCount, tracksCount), 0, artworkMrl
            ?: "", false, "")

    fun MediaWrapper.toPlayQueueItem() = PlayQueueItem(id, title, artist
            ?: "", length, artworkMrl
            ?: "", false, generateResolutionClass(width, height) ?: "")


    companion object : SingletonHolder<HttpSharingServer, Context>({ HttpSharingServer(it.applicationContext) })

    data class WebServerConnection(val ip: String)

    data class WSIncomingMessage(
            val message: String,
            val id: Int?
    )
}