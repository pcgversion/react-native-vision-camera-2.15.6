package com.mrousavy.camera.react

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.common.Barcode
import com.mrousavy.camera.core.CameraConfiguration
import com.mrousavy.camera.core.CameraSession
import com.mrousavy.camera.core.CodeScannerFrame
import com.mrousavy.camera.core.types.CameraDeviceFormat
import com.mrousavy.camera.core.types.CodeScannerOptions
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.types.OutputOrientation
import com.mrousavy.camera.core.types.PixelFormat
import com.mrousavy.camera.core.types.PreviewViewType
import com.mrousavy.camera.core.types.QualityBalance
import com.mrousavy.camera.core.types.ResizeMode
import com.mrousavy.camera.core.types.ShutterType
import com.mrousavy.camera.core.types.Torch
import com.mrousavy.camera.core.types.VideoStabilizationMode
import com.mrousavy.camera.frameprocessors.Frame
import com.mrousavy.camera.frameprocessors.FrameProcessor
import com.mrousavy.camera.react.extensions.installHierarchyFitter
import com.mrousavy.camera.react.GraphicOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

//
// TODOs for the CameraView which are currently too hard to implement either because of CameraX' limitations, or my brain capacity.
//
// TODO: High-speed video recordings (export in CameraViewModule::getAvailableVideoDevices(), and set in CameraView::configurePreview()) (120FPS+)
// TODO: Better startRecording()/stopRecording() (promise + callback, wait for TurboModules/JSI)
// TODO: takePhoto() depth data
// TODO: takePhoto() raw capture
// TODO: takePhoto() return with jsi::Value Image reference for faster capture
// TODO: Support videoCodec on Android

@SuppressLint("ClickableViewAccessibility", "ViewConstructor", "MissingPermission")
class CameraView(context: Context) :
  FrameLayout(context),
  CameraSession.Callback,
  FpsSampleCollector.Callback {
  companion object {
    const val TAG = "CameraView"
    // Keep these if your plugins absolutely need a static way to get the *active* overlay.
    // However, it's better if plugins get a direct reference if possible.
    // These will point to the overlays of the *currently attached and active* CameraView.
    // This implies only one CameraView should be actively processing OCR/Barcodes at a time
    // if relying solely on these static getters.
    // These are the properties holding the state
    private var _activeOcrGraphicOverlay: GraphicOverlay? = null
    private var _activeBarcodeGraphicOverlay: GraphicOverlay? = null
    // Public static getters (and setters if needed) for Java and Kotlin
    @JvmStatic
    fun getActiveOcrGraphicOverlay(): GraphicOverlay? {
      return _activeOcrGraphicOverlay
    }
    @JvmStatic
    fun setActiveOcrGraphicOverlay(overlay: GraphicOverlay?) {
      _activeOcrGraphicOverlay = overlay
    }
    @JvmStatic
    fun getActiveBarcodeGraphicOverlay(): GraphicOverlay? {
      return _activeBarcodeGraphicOverlay
    }
    @JvmStatic
    fun setActiveBarcodeGraphicOverlay(overlay: GraphicOverlay?) {
      _activeBarcodeGraphicOverlay = overlay
    }
  }

  // Instance-specific GraphicOverlays
  internal var ocrGraphicOverlay: GraphicOverlay? = null
  internal var barcodeGraphicOverlay: GraphicOverlay? = null
  // react properties
  // props that require reconfiguring
  var cameraId: String? = null
  var enableDepthData = false
  var enablePortraitEffectsMatteDelivery = false
  var isMirrored = false

  // use-cases
  var photo = false
  var video = false
  var audio = false
  var enableFrameProcessor = false
  var pixelFormat: PixelFormat = PixelFormat.YUV
  var enableLocation = false
  var preview = true
    set(value) {
      field = value
      updatePreview()
    }

  // props that require format reconfiguring
  var format: CameraDeviceFormat? = null
  var minFps: Int? = null
  var maxFps: Int? = null
  var videoStabilizationMode: VideoStabilizationMode? = null
  var videoHdr = false
  var photoHdr = false
  var videoBitRateOverride: Double? = null
  var videoBitRateMultiplier: Double? = null

  // TODO: Use .BALANCED once CameraX fixes it https://issuetracker.google.com/issues/337214687
  var photoQualityBalance = QualityBalance.SPEED
  var lowLightBoost = false

  // other props
  var isActive = false
  var torch: Torch = Torch.OFF
  var zoom: Float = 1f // in "factor"
  var exposure: Double = 0.0
  var outputOrientation: OutputOrientation = OutputOrientation.DEVICE
  var androidPreviewViewType: PreviewViewType = PreviewViewType.SURFACE_VIEW
    set(value) {
      field = value
      updatePreview()
    }
  var enableZoomGesture = false
    set(value) {
      field = value
      updateZoomGesture()
    }
  var resizeMode: ResizeMode = ResizeMode.COVER
    set(value) {
      field = value
      updatePreview()
    }

  // code scanner
  var codeScannerOptions: CodeScannerOptions? = null

  // private properties
  private var isMounted = false
  private val mainCoroutineScope = CoroutineScope(Dispatchers.Main)

  // session
  internal val cameraSession: CameraSession
  internal var frameProcessor: FrameProcessor? = null
  internal var previewView: PreviewView? = null
  private var currentConfigureCall: Long = System.currentTimeMillis()
  private val fpsSampleCollector = FpsSampleCollector(this)

  init {
    clipToOutline = true
    cameraSession = CameraSession(context, this)
    this.installHierarchyFitter()
    updatePreview()
  }

  override fun onAttachedToWindow() {
    Log.i(TAG, "CameraView attached to window!")
    super.onAttachedToWindow()
    // Update active static references if plugins rely on them
    setActiveOcrGraphicOverlay(ocrGraphicOverlay)
    setActiveBarcodeGraphicOverlay(barcodeGraphicOverlay)
    if (!isMounted) {
      // Notifies JS view that the native view is now available
      isMounted = true
      invokeOnViewReady()
    }
    // start collecting FPS samples
    fpsSampleCollector.start()
  }

  override fun onDetachedFromWindow() {
    Log.i(TAG, "CameraView detached from window!")
    super.onDetachedFromWindow()
    // Clear active static references if they point to this instance's overlays
    if (getActiveOcrGraphicOverlay() == ocrGraphicOverlay) {
      setActiveOcrGraphicOverlay(null)
    }
    if (getActiveBarcodeGraphicOverlay() == barcodeGraphicOverlay) {
      setActiveBarcodeGraphicOverlay(null)
    }
    // stop collecting FPS samples
    fpsSampleCollector.stop()
  }

  fun destroy() {
    cameraSession.close()
  }

  fun update() {
    Log.i(TAG, "Updating CameraSession...")
    val now = System.currentTimeMillis()
    currentConfigureCall = now

    mainCoroutineScope.launch {
      cameraSession.configure { config ->
        if (currentConfigureCall != now) {
          // configure waits for a lock, and if a new call to update() happens in the meantime we can drop this one.
          // this works similar to how React implemented concurrent rendering, the newer call to update() has higher priority.
          Log.i(TAG, "A new configure { ... } call arrived, aborting this one...")
          throw CameraConfiguration.AbortThrow()
        }

        // Input Camera Device
        config.cameraId = cameraId

        // Preview
        val previewView = previewView
        if (previewView != null) {
          config.preview = CameraConfiguration.Output.Enabled.create(CameraConfiguration.Preview(previewView.surfaceProvider))
        } else {
          config.preview = CameraConfiguration.Output.Disabled.create()
        }

        // Photo
        if (photo) {
          config.photo = CameraConfiguration.Output.Enabled.create(CameraConfiguration.Photo(isMirrored, photoHdr, photoQualityBalance))
        } else {
          config.photo = CameraConfiguration.Output.Disabled.create()
        }

        // Video
        if (video || enableFrameProcessor) {
          config.video =
            CameraConfiguration.Output.Enabled.create(
              CameraConfiguration.Video(isMirrored, videoHdr, videoBitRateOverride, videoBitRateMultiplier)
            )
        } else {
          config.video = CameraConfiguration.Output.Disabled.create()
        }

        // Frame Processor
        if (enableFrameProcessor) {
          config.frameProcessor = CameraConfiguration.Output.Enabled.create(CameraConfiguration.FrameProcessor(isMirrored, pixelFormat))
        } else {
          config.frameProcessor = CameraConfiguration.Output.Disabled.create()
        }

        // Audio
        if (audio) {
          config.audio = CameraConfiguration.Output.Enabled.create(CameraConfiguration.Audio(Unit))
        } else {
          config.audio = CameraConfiguration.Output.Disabled.create()
        }

        // Location
        config.enableLocation = enableLocation && this@CameraView.isActive

        // Code Scanner
        val codeScanner = codeScannerOptions
        if (codeScanner != null) {
          config.codeScanner = CameraConfiguration.Output.Enabled.create(
            CameraConfiguration.CodeScanner(codeScanner.codeTypes)
          )
        } else {
          config.codeScanner = CameraConfiguration.Output.Disabled.create()
        }

        // Orientation
        config.outputOrientation = outputOrientation

        // Format
        config.format = format

        // Side-Props
        config.minFps = minFps
        config.maxFps = maxFps
        config.enableLowLightBoost = lowLightBoost
        config.torch = torch
        config.exposure = exposure

        // Zoom
        config.zoom = zoom

        // isActive
        config.isActive = this@CameraView.isActive
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun updateZoomGesture() {
    if (enableZoomGesture) {
      val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
          override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom *= detector.scaleFactor
            update()
            return true
          }
        }
      )
      setOnTouchListener { _, event ->
        scaleGestureDetector.onTouchEvent(event)
      }
    } else {
      setOnTouchListener(null)
    }
  }

  private fun createGraphicOverlay(): GraphicOverlay {
    return GraphicOverlay(context, null).apply {
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
  }
  private fun updatePreview() {
    mainCoroutineScope.launch {
      var previewWasAdded = false
      if (preview && previewView == null) {
        // User enabled Preview, add the PreviewView
        previewView = createPreviewView()
        addView(previewView)
        previewWasAdded = true
      } else if (!preview && previewView != null) {
        // User disabled Preview, remove the PreviewView
        removeView(previewView)
        previewView = null
      }
      // Manage OCR GraphicOverlay
      if (preview && ocrGraphicOverlay == null) { // Create if preview is active and not yet created
        ocrGraphicOverlay = createGraphicOverlay()
        addView(ocrGraphicOverlay) // Add on top of PreviewView
        Log.d(TAG, "OCR GraphicOverlay added.")
        // If this CameraView becomes the active one, update the static reference
        setActiveOcrGraphicOverlay(ocrGraphicOverlay)
      } else if (!preview && ocrGraphicOverlay != null) { // Remove if preview is not active and exists
        if (getActiveOcrGraphicOverlay() == ocrGraphicOverlay) {
          setActiveOcrGraphicOverlay(null)
        }
        removeView(ocrGraphicOverlay)
        ocrGraphicOverlay = null
        Log.d(TAG, "OCR GraphicOverlay removed.")
      }
      // Manage Barcode GraphicOverlay
      if (preview && barcodeGraphicOverlay == null) { // Create if preview is active and not yet created
        barcodeGraphicOverlay = createGraphicOverlay()
        addView(barcodeGraphicOverlay) // Add on top
        Log.d(TAG, "Barcode GraphicOverlay added.")
        setActiveBarcodeGraphicOverlay(barcodeGraphicOverlay)
      } else if (!preview && barcodeGraphicOverlay != null) { // Remove if preview is not active and exists
        if (getActiveBarcodeGraphicOverlay() == barcodeGraphicOverlay) {
          setActiveBarcodeGraphicOverlay(null)
        }
        removeView(barcodeGraphicOverlay)
        barcodeGraphicOverlay = null
        Log.d(TAG, "Barcode GraphicOverlay removed.")
      }
      previewView?.let {
        // Update implementation type from React
        it.implementationMode = androidPreviewViewType.toPreviewImplementationMode()
        // Update scale type from React
        it.scaleType = resizeMode.toScaleType()
      }
      // If preview was just added, it might take a moment for dimensions to be available.
      // Consider updating overlay dimensions after view layout.
      if (previewWasAdded) {
        post { // Wait for layout pass
          updateGraphicOverlayDimensions()
        }
      } else {
        updateGraphicOverlayDimensions()
      }
      update()
    }
  }
  // Method to update GraphicOverlay with dimensions from the Frame for coordinate transformation
  // This needs to be called when the frame dimensions are known from the FrameProcessor
  fun updateGraphicOverlayWithFrameMetadata(imageWidth: Int, imageHeight: Int, isMirrored: Boolean, targetOverlayType: String? = null) {
    val updateOverlay = { overlay: GraphicOverlay? ->
      overlay?.let {
        it.imageWidth = imageWidth
        it.imageHeight = imageHeight
        // it.isMirrored = isMirrored // If GraphicOverlay needs this
        it.clear() // Clear previous drawings
      }
    }
    if (targetOverlayType == "ocr" || targetOverlayType == null) {
      updateOverlay(ocrGraphicOverlay)
    }
    if (targetOverlayType == "barcode" || targetOverlayType == null) {
      updateOverlay(barcodeGraphicOverlay)
    }
  }
  private fun updateGraphicOverlayDimensions() {
    previewView?.let { pv ->
      val updateOverlayDim = { go: GraphicOverlay? ->
        go?.let {
          if (pv.width > 0 && pv.height > 0) {
            // Only set if not already set by frame metadata (which is preferred)
            if (it.imageWidth == 0 || it.imageHeight == 0 || it.imageWidth == 1 && it.imageHeight == 1 ) { // Check if default/uninitialized
              Log.d(TAG, "Updating overlay ${System.identityHashCode(it)} dimensions from PreviewView: ${pv.width}x${pv.height}")
              it.imageWidth = pv.width
              it.imageHeight = pv.height
            }
          }
        }
      }
      updateOverlayDim(ocrGraphicOverlay)
      updateOverlayDim(barcodeGraphicOverlay)
    }
  }

  private fun createPreviewView(): PreviewView =
    PreviewView(context).also {
      it.installHierarchyFitter()
      it.implementationMode = androidPreviewViewType.toPreviewImplementationMode()
      it.layoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
        Gravity.CENTER
      )
      var lastIsPreviewing = false
      it.previewStreamState.observe(cameraSession) { state ->
        Log.i(TAG, "PreviewView Stream State changed to $state")

        val isPreviewing = state == PreviewView.StreamState.STREAMING
        if (isPreviewing != lastIsPreviewing) {
          // Notify callback
          if (isPreviewing) {
            invokeOnPreviewStarted()
          } else {
            invokeOnPreviewStopped()
          }
          lastIsPreviewing = isPreviewing
        }
      }
    }

  override fun onFrame(frame: Frame) {
    // Update average FPS samples
    fpsSampleCollector.onTick()
    // Update the GraphicOverlay with the frame's dimensions
    // This is crucial for correct coordinate mapping
    // Assuming 'frame' has width, height, and orientation/mirroring info
    // You might need to adjust how you get these based on the `Frame` object structure
    val imageWidth = frame.imageProxy.width // Or frame.width if it's already rotated
    val imageHeight = frame.imageProxy.height // Or frame.height
    // TODO: Determine correct imageWidth/imageHeight based on frame.orientation
    // The ML Kit InputImage is usually created with corrected rotation,
    // so the bitmap passed to the plugin will have dimensions that match the upright image.
    // Your VisionCameraOcrPlugin's bitmap will have these "upright" dimensions.
    // It's these dimensions that the GraphicOverlay needs for `imageWidth` and `imageHeight`.
    // Pass necessary info to FrameProcessor so it can update the GraphicOverlay
    // One way is to make graphicOverlay accessible or provide a callback
    frameProcessor?.let { fp ->
      // Option A: Make graphicOverlay directly accessible (simpler, but couples them)
      // if (fp is YourPluginType && fp.graphicOverlay == null) {
      //     (fp as YourPluginType).graphicOverlay = this.graphicOverlay
      // }
      // Option B: Pass it as a parameter if your plugin supports it (cleaner)
      // This requires modifying your plugin's `call` method or how it's initialized.
      // For now, let's assume the plugin gets the overlay reference some other way,
      // or the data is returned and CameraView handles drawing.
      // The most direct way from the plugin is if the plugin itself holds a reference
      // to the GraphicOverlay.
    }

    // Call JS Frame Processor
    frameProcessor?.call(frame)
  }

  override fun onError(error: Throwable) {
    invokeOnError(error)
  }

  override fun onInitialized() {
    invokeOnInitialized()
  }

  override fun onStarted() {
    invokeOnStarted()
  }

  override fun onStopped() {
    invokeOnStopped()
  }

  override fun onShutter(type: ShutterType) {
    invokeOnShutter(type)
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
  }

  override fun onOutputOrientationChanged(outputOrientation: Orientation) {
    invokeOnOutputOrientationChanged(outputOrientation)
  }

  override fun onPreviewOrientationChanged(previewOrientation: Orientation) {
    invokeOnPreviewOrientationChanged(previewOrientation)
  }

  override fun onCodeScanned(codes: List<Barcode>, scannerFrame: CodeScannerFrame) {
    invokeOnCodeScanned(codes, scannerFrame)
  }

  override fun onAverageFpsChanged(averageFps: Double) {
    invokeOnAverageFpsChanged(averageFps)
  }
}
