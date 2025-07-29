package com.mrousavy.camera.react

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable // For checking if already a bitmap
import android.graphics.drawable.Drawable // Generic drawable
import android.util.AttributeSet
import android.view.View

import com.google.mlkit.vision.text.Text // Assuming you use ML Kit's Text object

// Import traditional Android Graphics classes
import android.graphics.Path // IMPORTANT
import android.graphics.LinearGradient // IMPORTANT
import android.graphics.Shader // IMPORTANT
import androidx.core.content.ContextCompat
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuff
import kotlin.math.atan2
import kotlin.math.sqrt

class GraphicOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val lock = Any()
    private val graphics: MutableList<Graphic> = mutableListOf()

    // Paint objects for drawing
    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
        alpha = 200
    }
    // Paint objects for drawing
    // Let's make the paint for lines more visible, e.g., RED
    val linePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
        alpha = 200 // Optional: for slight transparency
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40.0f
    }

    // Transformation matrix for coordinates
    // These will be set by CameraView based on preview and image dimensions
    var imageWidth: Int = 1
    var imageHeight: Int = 1

    // Bitmap for the barcode icon
    internal val barcodeIconBitmap: Bitmap?
    internal val qrCodeIconBitmap: Bitmap? // New bitmap for QR codes

    init {
        // Load the barcode icon bitmap from VectorDrawable
        // Replace R.drawable.ic_barcode_scanner with your actual VectorDrawable resource ID
        // The R class should be automatically generated for your project.
        // If Android Studio can't find R.drawable.ic_barcode_scanner,
        // ensure you've successfully imported the vector asset and rebuilt the project.
        barcodeIconBitmap = getBitmapFromVectorDrawable(
            context,
            context.resources.getIdentifier("ic_barcode_scanner", "drawable", context.packageName)
            // Alternative, if R is directly accessible:
            // R.drawable.ic_barcode_scanner
        )

        // You might want to add error handling if it returns null
        if (barcodeIconBitmap == null) {
            // Log an error or use a fallback
            android.util.Log.e("GraphicOverlay", "Failed to load barcode icon.")
        }

        qrCodeIconBitmap = getBitmapFromVectorDrawable(
            context,
            context.resources.getIdentifier("ic_qr_code_scanner", "drawable", context.packageName) // Ensure this drawable exists
        )
        if (qrCodeIconBitmap == null) {
            android.util.Log.e("GraphicOverlay", "Failed to load QR code icon.")
        }
    }

    private fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap? {
        if (drawableId == 0) return null // Resource ID not found
        val drawable: Drawable? = ContextCompat.getDrawable(context, drawableId)

        if (drawable == null) {
            android.util.Log.e("GraphicOverlay", "Drawable not found for ID: $drawableId")
            return null
        }

        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        // Define a desired size for the bitmap. This will be the intrinsic size
        // if the vector drawable has one, otherwise you can specify.
        // For icons, a common size like 96x96 or 128x128 is often good before scaling.
        val intrinsicWidth = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val intrinsicHeight = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96

        val bitmap = Bitmap.createBitmap(
            intrinsicWidth,
            intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
    /**
     * Base class for graphical items drawn within the overlay.
     */
    abstract class Graphic(internal val overlay: GraphicOverlay) {
        abstract fun draw(canvas: Canvas)

        /**
         * Adjusts a horizontal value of the supplied value from the image scale to the view scale.
         */
        fun scaleX(horizontal: Float): Float {
            return horizontal * overlay.width.toFloat() / overlay.imageWidth.toFloat()
        }

        /**
         * Adjusts a vertical value of the supplied value from the image scale to the view scale.
         */
        fun scaleY(vertical: Float): Float {
            return vertical * overlay.height.toFloat() / overlay.imageHeight.toFloat()
        }

        /**
         * Adjusts the x coordinate from the image's coordinate system to the view's coordinate system.
         */
        fun translateX(x: Float): Float {
            // Add mirroring logic if your camera preview is mirrored
            // if (overlay.isMirrored) {
            //    return overlay.width.toFloat() - scaleX(x)
            // }
            return scaleX(x)
        }

        /**
         * Adjusts the y coordinate from the image's coordinate system to the view's coordinate system.
         */
        fun translateY(y: Float): Float {
            return scaleY(y)
        }

        fun postInvalidate() {
            overlay.postInvalidate()
        }
    }

    /**
     * Clears all graphics from the overlay.
     */
    fun clear() {
        synchronized(lock) {
            graphics.clear()
        }
        postInvalidate()
    }

    /**
     * Adds a graphic to the overlay.
     */
    fun add(graphic: Graphic) {
        synchronized(lock) {
            graphics.add(graphic)
        }
        postInvalidate()
    }

    /**
     * Draws the primitives on the canvas.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(lock) {
            for (graphic in graphics) {
                graphic.draw(canvas)
            }
        }
    }


    //class TextBlockGraphic(overlay: GraphicOverlay, private val textBlock: Text.TextBlock, type: String ) : Graphic(overlay) {
    class TextBlockGraphic(overlay: GraphicOverlay, private val points: Array<Point>, private val objectType: String, private val barcodeType: Int ) : Graphic(overlay) {

        private val iconPaint = Paint().apply {
            isAntiAlias = true // Good for bitmaps
            alpha = 220 // Slightly transparent
            // You can also set ColorFilter here if you want to tint the icon
            colorFilter = PorterDuffColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN)
        }
        // Define a fixed size for the icon (in pixels on the screen)
        private val fixedIconWidth = 96f
        private val fixedIconHeight = 96f

        override fun draw(canvas: Canvas) {
            //val cornerPoints = textBlock.cornerPoints
            val cornerPoints = points
            if (cornerPoints == null || cornerPoints.size < 4) {
                // Fallback or do nothing
                return
            }

            // 1. Translate corner points to view coordinates
            val translatedPoints = cornerPoints.map { point ->
                android.graphics.PointF( // Use PointF for precision
                    translateX(point.x.toFloat()),
                    translateY(point.y.toFloat())
                )
            }

            // 2. Create a Path from the translated corner points
            val path = Path()
            path.moveTo(translatedPoints[0].x, translatedPoints[0].y) // Move to the first point
            for (i in 1 until translatedPoints.size) {
                path.lineTo(translatedPoints[i].x, translatedPoints[i].y) // Draw lines to subsequent points
            }
            path.close() // Close the path to form a closed shape

            // 3. Define the gradient (can be based on the path's bounds or fixed coordinates)
            val pathBounds = RectF().apply { path.computeBounds(this, true) }

            if (pathBounds.isEmpty) return

            when (objectType) {
                "ocr" -> {
                    val gradientPaint = Paint().apply {
                        shader = LinearGradient(
                            pathBounds.left, pathBounds.top,
                            pathBounds.right, pathBounds.bottom,
                            Color.argb(100, 0, 255, 255),
                            Color.argb(100, 0, 255, 255),
                            Shader.TileMode.CLAMP
                        )
                        style = Paint.Style.FILL
                    }
                    canvas.drawPath(path, gradientPaint)
                }
                "barcode" -> {
                    val iconToDraw = if (barcodeType == 256) { // Use ML Kit's constant
                        overlay.qrCodeIconBitmap
                    } else {
                        overlay.barcodeIconBitmap
                    }
                    iconToDraw?.let { iconBitmap ->
                        // Calculate the center of the pathBounds
                        val centerX = pathBounds.centerX()
                        val centerY = pathBounds.centerY()

                        // --- Calculate Rotation Angle ---
                        // We'll use the angle of the longest side of the polygon.
                        // For a typical barcode, points 0-1 and 2-3 are width, 1-2 and 3-0 are height.
                        // Let's find the angle of the vector from point 0 to point 1.
                        var angleDegrees = 0f
                        if (translatedPoints.size >= 2) {
                            val p0 = translatedPoints[0]
                            val p1 = translatedPoints[1]
                            val p2 = translatedPoints[2] // For alternative side if needed

                            val dx1 = p1.x - p0.x
                            val dy1 = p1.y - p0.y
                            val length1 = sqrt(dx1 * dx1 + dy1 * dy1)

                            val dx2 = p2.x - p1.x // Side from p1 to p2
                            val dy2 = p2.y - p1.y
                            val length2 = sqrt(dx2*dx2 + dy2*dy2)

                            // Prefer the longer side for more stable angle calculation
                            // or the side that is more horizontal (smaller dy) if lengths are similar.
                            // For simplicity, let's just take the angle of the first segment (p0 to p1)
                            // or a more dominant horizontal segment.
                            // A more robust method would analyze all sides.
                            angleDegrees = Math.toDegrees(atan2(dy1.toDouble(), dx1.toDouble())).toFloat()

                            // Adjust angle if it seems more vertical based on common barcode shapes
                            // This heuristic might need tuning based on your specific barcode orientations
                            if (kotlin.math.abs(dy1) > kotlin.math.abs(dx1) && length2 > length1) { // if first segment is more vertical and second is longer
                                angleDegrees = Math.toDegrees(atan2(p2.y - p1.y.toDouble(), p2.x - p1.x.toDouble())).toFloat()
                            }
                        }


                        // --- Prepare for drawing the icon ---
                        // Define the destination rectangle for the icon AT THE ORIGIN (0,0)
                        // before rotation and translation. This makes rotation simpler.
                        val iconRectAtOrigin = RectF(
                            -fixedIconWidth / 2f,
                            -fixedIconHeight / 2f,
                            fixedIconWidth / 2f,
                            fixedIconHeight / 2f
                        )

                        canvas.save() // Save the current canvas state

                        // Translate the canvas to the center of the pathBounds
                        canvas.translate(centerX, centerY)

                        // Rotate the canvas
                        canvas.rotate(angleDegrees)

                        // Draw the bitmap centered at the (now rotated) origin
                        canvas.drawBitmap(iconBitmap, null, iconRectAtOrigin, iconPaint)

                        canvas.restore() // Restore the canvas to its previous state

                        // Optionally, draw the original detected path for debugging or visual cue
                        // canvas.drawPath(path, overlay.linePaint)
                    }
                }
            }

                // Optionally, draw a border using your original linePaint
                // for (i in translatedPoints.indices) {
                //    val startPoint = translatedPoints[i]
                //    val endPoint = translatedPoints[(i + 1) % translatedPoints.size]
                //    canvas.drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, overlay.linePaint)
                // }

            //Log.d("TextBlockGraphic", "Drawing gradient on path with bounds: $pathBounds")
        }
    }
    /*class TextBlockGraphic(overlay: GraphicOverlay, private val textBlock: Text.TextBlock) : Graphic(overlay) {
        override fun draw(canvas: Canvas) {
            //Log.d("GraphicOverLay", textBlock.boundingBox.toString())
            val cornerPoints = textBlock.cornerPoints
            // Translate all points first
            val translatedPoints = cornerPoints?.map { point ->
                Point(translateX(point.x.toFloat()).toInt(), translateY(point.y.toFloat()).toInt())
            }

            // Draw lines between consecutive points
            if (translatedPoints != null) {
                for (i in translatedPoints.indices) {
                    val startPoint = translatedPoints[i]
                    val endPoint = translatedPoints[(i + 1) % translatedPoints.size] // Connect back to the first point

                    canvas.drawLine(
                        startPoint.x.toFloat(),
                        startPoint.y.toFloat(),
                        endPoint.x.toFloat(),
                        endPoint.y.toFloat(),
                        overlay.linePaint // Use the new linePaint
                    )
                }
            }
//            Log.d("VisionCameraOcrPlugin", "cornerPoints:"+ cornerPoints.toString())
//            textBlock.boundingBox?.let { box ->
//                val rect = RectF(
//                    translateX(box.left.toFloat()),
//                    translateY(box.top.toFloat()),
//                    translateX(box.right.toFloat()),
//                    translateY(box.bottom.toFloat())
//                )
//                canvas.drawRect(rect, overlay.boxPaint)
                // You can also draw textBlock.text here if needed
                // canvas.drawText(textBlock.text, rect.left, rect.bottom, overlay.textPaint)
            }
        }*/
}


