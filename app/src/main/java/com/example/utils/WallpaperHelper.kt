package com.example.utils

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.db.WallpaperConfigEntity
import com.example.db.WallpaperLogEntity
import com.example.db.WallpaperRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * WallpaperHelper is a Singleton Object in Kotlin.
 *
 * In Object-Oriented Programming (OOP) and Software Architecture:
 * 1. Singleton Pattern: Kotlin's `object` keyword guarantees that only one instance of
 *    `WallpaperHelper` is ever created during the application's runtime. This centralizes
 *    helper logic, avoiding the memory overhead of instantiating new objects repeatedly.
 * 2. Separation of Concerns: It separates pure helper data extraction and digital image processing
 *    operations (loading streams, decoding bounds, mathematical scaling, panning crops) from
 *    user interface components (Composables) or lifecycle containers (Services).
 * 3. Resource Management: It highlights safe memory management. Images/Bitmaps on mobile devices
 *    require significant RAM. This class demonstrates explicit resource recycling (`bitmap.recycle()`)
 *    and automated file handle management (`use` blocks and `finally` clauses) to prevent memory leaks and Out-Of-Memory (OOM) errors.
 */
object WallpaperHelper {
    private const val TAG = "WallpaperHelper"

    /**
     * Queries the Android Document Provider (SAF) to resolve image file pointers.
     * Demonstrates encapsulation by containing all complex cursor queries inside this single method.
     *
     * @param context Android context containing the Application environment.
     * @param treeUriStr String representing the selected folder URI location.
     * @return List of file URIs matched with their display names.
     */
    suspend fun getImageUrisFromFolder(context: Context, treeUriStr: String): List<Pair<Uri, String>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Pair<Uri, String>>()
        try {
            val treeUri = Uri.parse(treeUriStr)
            val buildId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, buildId)

            // ContentResolver acts as an abstraction layer (Adapter pattern) supporting data access
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor -> // '.use' automatically closes the Cursor stream upon completion, preventing leak side effects
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx)
                    val mime = cursor.getString(mimeIdx) ?: ""
                    val name = cursor.getString(nameIdx) ?: "Photo_${System.currentTimeMillis()}"

                    // Polymorphism / File categorization processing
                    if (mime.startsWith("image/")) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        result.add(Pair(fileUri, name))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SAF tree uri: $treeUriStr", e)
        }
        return@withContext result
    }

    /**
     * Rebuilds the randomized folder image queue.
     * Demonstrates decoupling by updating the configuration object state in an immutable, OOP-friendly way.
     *
     * @param context Application environment Context.
     * @param config The current WallpaperConfigEntity database configuration data object.
     * @return A new instance of WallpaperConfigEntity containing the updated queue data.
     */
    suspend fun rebuildQueue(context: Context, config: WallpaperConfigEntity): WallpaperConfigEntity = withContext(Dispatchers.IO) {
        val folderUriStr = config.localFolderUri
        val list = if (folderUriStr.isNullOrEmpty()) {
            emptyList()
        } else {
            getImageUrisFromFolder(context, folderUriStr).map { it.first.toString() }
        }
        val shuffled = list.shuffled() // Ensures random selection without complicated custom index arithmetic
        val queueStr = if (shuffled.isEmpty()) null else shuffled.joinToString("|")
        
        // Returns an immutable copy of the configuration data model object, a best practice in modern OOP
        return@withContext config.copy(shuffledQueue = queueStr, queueIndex = 0)
    }

    /**
     * Resolves the visual display name of a file from its system URI pointer.
     */
    fun getDisplayNameFromUri(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (displayNameIdx != -1) {
                        return cursor.getString(displayNameIdx) ?: "Local Photo"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving display name for uri: $uri", e)
        }
        return uri.lastPathSegment ?: "Local Photo"
    }

    /**
     * Entry point to advance the system wallpaper.
     * Picks the next image URI from the randomized list, decodes it efficiently, scales it,
     * and sets it as the Android wallpaper using system API handlers.
     *
     * In OOP terminology:
     * - "High Cohesion": Consolidates all database/file read steps and platform API calls into one workflow.
     * - "Resource Safety": Guarantees stream closure and releases unneeded bitmap resources immediately.
     */
    suspend fun performWallpaperChange(context: Context, config: WallpaperConfigEntity, repository: WallpaperRepository): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting wallpaper change sequence...")
        var imageStream: InputStream? = null
        var selectedName = "Unknown Wallpaper"
        var selectedUriStr = ""
        var rawBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null

        try {
            // Retrieve configuration object state. In OOP, using immutable models prevents state corruption.
            var currentConfig = config
            var queue = currentConfig.shuffledQueue?.split("|")?.filter { it.isNotEmpty() }
            if (queue.isNullOrEmpty()) {
                currentConfig = rebuildQueue(context, currentConfig)
                queue = currentConfig.shuffledQueue?.split("|")?.filter { it.isNotEmpty() }
            }

            if (queue.isNullOrEmpty()) {
                logChange(repository, "None", "No images found in local folder", config.changeTarget, "ERROR", "Local folder is empty or not selected")
                return@withContext false
            }

            // Get the current queue position using the index
            val idx = if (currentConfig.queueIndex >= queue.size || currentConfig.queueIndex < 0) 0 else currentConfig.queueIndex
            selectedUriStr = queue[idx]

            val metrics = context.resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            // Open stream to SAF file pointer. Acts as a polymorphic input stream.
            if (selectedUriStr.startsWith("content://")) {
                val uri = Uri.parse(selectedUriStr)
                selectedName = getDisplayNameFromUri(context, uri)
                imageStream = context.contentResolver.openInputStream(uri)
            } else {
                logChange(repository, selectedUriStr, selectedName, config.changeTarget, "ERROR", "Unsupported cloud URI or invalid protocol. Local storage is required.")
                return@withContext false
            }

            if (imageStream == null) {
                logChange(repository, selectedUriStr, selectedName, config.changeTarget, "ERROR", "Failed to access image input stream")
                return@withContext false
            }

            // --- STEP 1: Safe memory-conserving bitmap loading via bounds inspection ---
            // In OOP / Graphic Systems, loading a raw 24-megapixel photo into RAM can instantly crash the app.
            // First we decode ONLY the width/height (bounds) without allocating pixel memory.
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            try {
                BitmapFactory.decodeStream(imageStream, null, options)
            } finally {
                imageStream.close() // Close the stream immediately to prevent file-handle resource leaks.
            }

            val srcWidth = options.outWidth
            val srcHeight = options.outHeight

            // Optimal maximum buffer: up to double the screen width for horizontal scrolling/panning
            val maxTargetWidth = screenW * 2
            val maxTargetHeight = screenH

            // Calculate the power-of-two "sub-sampling" factor (e.g. 2, 4, 8) to load a down-scaled version of the photo.
            var inSampleSize = 1
            if (srcHeight > maxTargetHeight || srcWidth > maxTargetWidth) {
                val halfHeight = srcHeight / 2
                val halfWidth = srcWidth / 2
                while (halfHeight / inSampleSize >= maxTargetHeight && halfWidth / inSampleSize >= maxTargetWidth) {
                    inSampleSize *= 2
                }
            }

            // --- STEP 2: Decode the actual bitmap size with safe memory constraints ---
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }

            val uri = Uri.parse(selectedUriStr)
            val finalStream = context.contentResolver.openInputStream(uri)
            if (finalStream == null) {
                logChange(repository, selectedUriStr, selectedName, config.changeTarget, "ERROR", "Failed to re-open image stream for sampling")
                return@withContext false
            }

            // Decode the actual downscaled bitmap using the stream
            rawBitmap = try {
                BitmapFactory.decodeStream(finalStream, null, decodeOptions)
            } finally {
                finalStream.close()
            }

            if (rawBitmap == null) {
                logChange(repository, selectedUriStr, selectedName, config.changeTarget, "ERROR", "Failed to decode bitmap image")
                return@withContext false
            }

            Log.d(TAG, "Image loaded size (sub-sampled with factor $inSampleSize): ${rawBitmap.width}x${rawBitmap.height}. Screen target size: ${screenW}x${screenH}")

            // --- STEP 3: Scale/process the bitmap according to the user's display choice ---
            processedBitmap = processBitmap(rawBitmap, screenW, screenH, config.scaleMode)

            // Release pixel memory of the raw intermediate Bitmap if the processed one is a different instance.
            if (processedBitmap != rawBitmap) {
                rawBitmap.recycle()
                rawBitmap = null
            }

            // --- STEP 4: Interact with Android OS services to apply the wallpaper ---
            // WallpaperManager is a system service (acting as a Facade to Android's window manager).
            val wpManager = WallpaperManager.getInstance(context)
            val target = config.changeTarget

            if (target == "BOTH" || target == "HOME_SCREEN") {
                wpManager.setBitmap(processedBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            }
            if (target == "BOTH" || target == "LOCK_SCREEN") {
                wpManager.setBitmap(processedBitmap, null, true, WallpaperManager.FLAG_LOCK)
            }

            // Recycle the processed bitmap since WallPaperManager has cached its pixels internally.
            processedBitmap.recycle()
            processedBitmap = null

            // --- STEP 5: Update the database state incrementally ---
            var updatedConfig = if (idx + 1 >= queue.size) {
                Log.i(TAG, "Reached the end of the wallpaper queue list. Re-shuffling and starting over.")
                val rebuilt = rebuildQueue(context, currentConfig)
                rebuilt.copy(lastChangedTimestamp = System.currentTimeMillis())
            } else {
                val nextIndex = idx + 1
                currentConfig.copy(
                    lastChangedTimestamp = System.currentTimeMillis(),
                    queueIndex = nextIndex
                )
            }
            // If TIMER change, set hasSeenLastChange to false to ensure we don't rotate again until user has seen it
            if (config.triggerType == "TIMER") {
                updatedConfig = updatedConfig.copy(hasSeenLastChange = false)
            }
            repository.saveConfig(updatedConfig)

            // Record success state in database logs
            logChange(repository, selectedUriStr, selectedName, target, "SUCCESS", null)
            Log.i(TAG, "Wallpaper successfully modified.")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Exception during wallpaper change sequence", e)
            logChange(repository, selectedUriStr, selectedName, config.changeTarget, "ERROR", e.localizedMessage ?: "Unknown error")
            return@withContext false
        } finally {
            // Guarantee that decoded and processed bitmaps are always recycled, even on exceptions
            try {
                rawBitmap?.let {
                    if (!it.isRecycled) {
                        it.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling rawBitmap in finally block", e)
            }
            try {
                processedBitmap?.let {
                    if (!it.isRecycled) {
                        it.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling processedBitmap in finally block", e)
            }
        }
    }

    private suspend fun logChange(
        repository: WallpaperRepository,
        uri: String,
        name: String,
        target: String,
        status: String,
        error: String?
    ) {
        val log = WallpaperLogEntity(
            imageUri = uri,
            imageName = name,
            targetScreen = target,
            status = status,
            errorMessage = error
        )
        repository.insertLog(log)
    }

    /**
     * Implements customizable image sizing and placement rules.
     *
     * In OOP architecture, this function acts as a "Transformer" or a pipeline component:
     * - Takes an input image (source) as immutable state.
     * - Returns a transformed, perfectly scaled, and padded intermediate Bitmap.
     * - Protects system memory safety by actively freeing intermediary/temporary bitmaps via recycle().
     */
    fun processBitmap(source: Bitmap, screenW: Int, screenH: Int, mode: String): Bitmap {
        // Output canvas: the target "virtual paper" matching device dimensions
        val output = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK) // Solid black background for unused border fills

        // Anti-aliasing paint ensures scaled borders stay smooth instead of jagged/pixelated.
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val srcW = source.width
        val srcH = source.height

        when (mode) {
            "CENTER" -> {
                // --- CASE 1: CENTER ---
                // Grabs a centered pixel segment of the exact device dimensions. No scaling.
                // Demonstrates geometric coordinate translations (offsets).
                val srcX = (srcW - screenW) / 2
                val srcY = (srcH - screenH) / 2

                if (srcW >= screenW && srcH >= screenH) {
                    // Extract center slice from high-res image
                    val cropped = Bitmap.createBitmap(source, srcX, srcY, screenW, screenH)
                    canvas.drawBitmap(cropped, 0f, 0f, paint)
                    cropped.recycle() // Important memory conservation step
                } else {
                    // Draw original image intact at the center calculation offsets
                    val destX = (screenW - srcW) / 2f
                    val destY = (screenH - srcH) / 2f
                    canvas.drawBitmap(source, destX, destY, paint)
                }
            }
            "FIT", "TOUCH_BORDER" -> {
                // --- CASE 2: FIT / ASPECT CONTAIN ---
                // Fits the full photo inside the screen bounds with letterbox stripes if necessary.
                // Formula: Math.min (screenW / srcW, screenH / srcH) restricts image inside bounds.
                val scaleW = screenW.toFloat() / srcW
                val scaleH = screenH.toFloat() / srcH
                val scale = Math.min(scaleW, scaleH)

                val destW = (srcW * scale).toInt()
                val destH = (srcH * scale).toInt()
                val destX = (screenW - destW) / 2f
                val destY = (screenH - destH) / 2f

                val scaled = Bitmap.createScaledBitmap(source, destW.coerceAtLeast(1), destH.coerceAtLeast(1), true)
                canvas.drawBitmap(scaled, destX, destY, paint)
                scaled.recycle()
            }
            else -> { // "FILL" / "SCALE_TO_FIT" -> ASPECT COVER
                // --- CASE 3: FILL / ASPECT COVER ---
                // Fills the screen area entirely, possibly cropping parts of the photograph.
                // Formula: Math.max (screenW / srcW, screenH / srcH) expands image to cover screen.
                val scaleW = screenW.toFloat() / srcW
                val scaleH = screenH.toFloat() / srcH
                val scale = Math.max(scaleW, scaleH)

                val destW = (srcW * scale).toInt().coerceAtLeast(1)
                val destH = (srcH * scale).toInt().coerceAtLeast(1)

                if (destW > screenW) {
                    // --- SCROLLING / PANNING LAUNCHER SUPPORT ---
                    // Supports modern launchers where changing swipe pages shifts/scrolls the wallpaper.
                    // To prevent massive memory usage, we limit the maximum scrolling width to 2x screen size.
                    val maxPanningW = screenW * 2
                    val targetW: Int
                    val scaledPhoto: Bitmap

                    if (destW > maxPanningW) {
                        // Image is extremely wide: perform a centered pre-crop to avoid stretching.
                        val targetRatio = maxPanningW.toFloat() / screenH
                        val cropW = (srcH * targetRatio).toInt().coerceIn(1, srcW)
                        val cropX = (srcW - cropW) / 2

                        val croppedSource = Bitmap.createBitmap(source, cropX, 0, cropW, srcH)
                        scaledPhoto = Bitmap.createScaledBitmap(croppedSource, maxPanningW, screenH, true)
                        if (croppedSource != source) {
                            croppedSource.recycle()
                        }
                        targetW = maxPanningW
                    } else {
                        // Image fits comfortably within panning boundaries
                        scaledPhoto = Bitmap.createScaledBitmap(source, destW, destH, true)
                        targetW = destW
                    }

                    // --- HORIZONTAL CENTERING ON THE FIRST SCREEN ---
                    // By default, Android launches on the LEFTMOST home screen page.
                    // If the wallpaper's center matches the canvas's center, the leftmost screen
                    // would show the right-hand half of the image.
                    //
                    // Correction Formula: Align the photograph's center with the first screen's center.
                    // Screen center: screenW / 2
                    // Photo center in target scrolling viewport: targetW / 2
                    // Shift the photo draw start position to: (screenW - targetW) / 2
                    //
                    // We allocate a canvas of size 'wNew' (where wNew = (screenW + targetW) / 2) to anchor the scroll bounds.
                    val wNew = (screenW + targetW) / 2
                    val scrollingOutput = Bitmap.createBitmap(wNew, screenH, Bitmap.Config.ARGB_8888)
                    val scrollingCanvas = Canvas(scrollingOutput)
                    scrollingCanvas.drawColor(Color.BLACK)

                    val drawX = (screenW - targetW) / 2f
                    scrollingCanvas.drawBitmap(scaledPhoto, drawX, 0f, paint)

                    if (scaledPhoto != source) {
                        scaledPhoto.recycle()
                    }
                    output.recycle()
                    return scrollingOutput
                } else {
                    // No horizontal panning required. Draw centered on the screen.
                    val destX = (screenW - destW) / 2f
                    val destY = (screenH - destH) / 2f

                    val scaled = Bitmap.createScaledBitmap(source, destW, destH, true)
                    canvas.drawBitmap(scaled, destX, destY, paint)
                    scaled.recycle()
                }
            }
        }

        return output
    }
}
