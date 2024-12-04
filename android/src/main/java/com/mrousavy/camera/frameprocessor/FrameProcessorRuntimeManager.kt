package com.mrousavy.camera.frameprocessor

import android.util.Log
import androidx.annotation.Keep
import com.facebook.jni.HybridData
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.turbomodule.core.CallInvokerHolderImpl
import com.facebook.react.uimanager.UIManagerHelper
import com.mrousavy.camera.CameraView
import com.mrousavy.camera.ViewNotFoundError
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import com.facebook.react.modules.core.DeviceEventManagerModule
import android.media.AudioManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.app.Activity

@Suppress("KotlinJniMissingFunction") // I use fbjni, Android Studio is not smart enough to realize that.
class FrameProcessorRuntimeManager(context: ReactApplicationContext, frameProcessorThread: ExecutorService) {
  companion object {
    const val TAG = "FrameProcessorRuntime"
    val Plugins: ArrayList<FrameProcessorPlugin> = ArrayList()
    var enableFrameProcessors = true

    init {
      try {
        System.loadLibrary("reanimated")
        System.loadLibrary("VisionCamera")
      } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "Failed to load Reanimated/VisionCamera C++ library. Frame Processors are disabled!")
        enableFrameProcessors = false
      }
    }
  }

  @DoNotStrip
  private var mHybridData: HybridData? = null
  private var mContext: WeakReference<ReactApplicationContext>? = null
  private var mScheduler: VisionCameraScheduler? = null
  //code to detect single, double, triple or multi volume key press events
  private var lastVolumeUpPressTime: Long = 0
  private var lastVolumeDownPressTime: Long = 0
  private var volumeUpPressCount: Int = 0
  private var volumeDownPressCount: Int = 0
  private var isVolumeUpHeld = false
  private var isVolumeDownHeld = false
  private val doubleClickThreshold: Long = 300  // Time threshold in milliseconds for multiple clicks
  private val holdThreshold: Long = 800         // Time threshold in milliseconds for hold
  private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var originalVolume: Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) 
  // Handler to reset click counts after some time
  private val resetHandler = Handler(Looper.getMainLooper())
  //end of code

  init {
    if (enableFrameProcessors) {
      val holder = context.catalystInstance.jsCallInvokerHolder as CallInvokerHolderImpl
      mScheduler = VisionCameraScheduler(frameProcessorThread)
      mContext = WeakReference(context)
      mHybridData = initHybrid(context.javaScriptContextHolder.get(), holder, mScheduler!!)
      initializeRuntime()

      Log.i(TAG, "Installing Frame Processor Plugins...")
      Plugins.forEach { plugin ->
        registerPlugin(plugin)
      }
      Log.i(TAG, "Successfully installed ${Plugins.count()} Frame Processor Plugins!")

      Log.i(TAG, "Installing JSI Bindings on JS Thread...")
      context.runOnJSQueueThread {
        installJSIBindings()
      }
    }
  }

  @Suppress("unused")
  @DoNotStrip
  @Keep
  fun findCameraViewById(viewId: Int): CameraView {
    Log.d(TAG, "Finding view $viewId...")
    val ctx = mContext?.get()
    val view = if (ctx != null) UIManagerHelper.getUIManager(ctx, viewId)?.resolveView(viewId) as CameraView? else null
    //Log.d(TAG,  if (view != null) "Found view $viewId!" else "Couldn't find view $viewId!")
    //return view ?: throw ViewNotFoundError(viewId)
      if (view != null) {
            Log.d(TAG, "Found view $viewId!")
            // Ensure this runs on the main thread
            Handler(Looper.getMainLooper()).post {
                view.setFocusable(true)
                view.setFocusableInTouchMode(true)
                view.requestFocus()
                view.setOnKeyListener { v, keyCode, event ->
                    val currentTime = System.currentTimeMillis()
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            // Save the current volume level at the start
                            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            when (keyCode) {
                                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                    if (!isVolumeDownHeld) {
                                        if (currentTime - lastVolumeDownPressTime < doubleClickThreshold) {
                                            volumeDownPressCount++
                                        } else {
                                            volumeDownPressCount = 1
                                        }
                                        lastVolumeDownPressTime = currentTime
                                        // Start hold detection
                                        isVolumeDownHeld = true
                                        resetHandler.postDelayed({
                                            if (isVolumeDownHeld) {
                                                Log.d(TAG, "Volume Down Held")
                                                sendEventToJS("volumeDownHold")
                                                isVolumeDownHeld = false
                                            }
                                        }, holdThreshold)
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_VOLUME_UP -> {
                                    if (!isVolumeUpHeld) {
                                        if (currentTime - lastVolumeUpPressTime < doubleClickThreshold) {
                                            volumeUpPressCount++
                                        } else {
                                            volumeUpPressCount = 1
                                        }
                                        lastVolumeUpPressTime = currentTime
                                        // Start hold detection
                                        isVolumeUpHeld = true
                                        resetHandler.postDelayed({
                                            if (isVolumeUpHeld) {
                                                Log.d(TAG, "Volume Up Held")
                                                sendEventToJS("volumeUpHold")
                                                isVolumeUpHeld = false
                                            }
                                        }, holdThreshold)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            when (keyCode) {
                                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                    if (isVolumeDownHeld) {
                                        isVolumeDownHeld = false
                                        resetHandler.removeCallbacksAndMessages(null)
                                    }
                                    // Determine single, double, or multiple clicks
                                    resetHandler.postDelayed({
                                        when (volumeDownPressCount) {
                                            1 -> sendEventToJS("volumeDownSingle")
                                            2 -> sendEventToJS("volumeDownDouble")
                                            3 -> sendEventToJS("volumeDownTriple")
                                        }
                                        volumeDownPressCount = 0  // Reset count
                                    }, doubleClickThreshold)
                                    true
                                }
                                KeyEvent.KEYCODE_VOLUME_UP -> {
                                    if (isVolumeUpHeld) {
                                        isVolumeUpHeld = false
                                        resetHandler.removeCallbacksAndMessages(null)
                                    }
                                    // Determine single, double, or multiple clicks
                                    resetHandler.postDelayed({
                                        when (volumeUpPressCount) {
                                            1 -> sendEventToJS("volumeUpSingle")
                                            2 -> sendEventToJS("volumeUpDouble")
                                            3 -> sendEventToJS("volumeUpTriple")
                                        }
                                        volumeUpPressCount = 0  // Reset count
                                    }, doubleClickThreshold)
                                    true
                                }
                                else -> false
                            }
                        }
                        else -> false
                    }
                }
            }
            return view
        } else {
            Log.d(TAG, "Couldn't find view $viewId!")
            throw ViewNotFoundError(viewId)
        }
  }
  //Helper function to pass events to main activity dispatchKeyEvent
  private fun passKeyEventToActivity(view: View, event: KeyEvent) {
      val activity = view.context as? Activity
      activity?.dispatchKeyEvent(event)
  }
  // Helper function to send events to JavaScript
  private fun sendEventToJS(eventName: String) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        mContext?.get()?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
          ?.emit("VolumeKeyEvent", eventName)
  }

  // private C++ funcs
  private external fun initHybrid(
    jsContext: Long,
    jsCallInvokerHolder: CallInvokerHolderImpl,
    scheduler: VisionCameraScheduler
  ): HybridData
  private external fun initializeRuntime()
  private external fun registerPlugin(plugin: FrameProcessorPlugin)
  private external fun installJSIBindings()
}
