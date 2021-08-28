package com.example.facemaskdetectionprototype

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.facemaskdetectionprototype.ml.FackMaskDetection
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.support.model.Model
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.activation.DataHandler
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

typealias CameraBitmapOutputListener = (bitmap: Bitmap) -> Unit

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private var i=0
    private var store:MutableList<Bitmap> = mutableListOf()
    private lateinit var appExecutors: AppExecutors
    private val htmlBody :String  = "<html>Images of people who were denied access to the building are - </html>"
    private var b=true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        start()
        setupML()
        setupCameraThread()
        appExecutors = AppExecutors()
        setupCameraControllers()
        if (!allPermissionsGranted) {
            requireCameraPermission()
        } else {
            setupCamera()
        }
    }
    object Credentials {
        const val EMAIL = "email_id"
        const val PASSWORD = "password"
    }

    private fun start(){
        val handler = Handler()
        handler.postDelayed(object : Runnable {
            override fun run() {
                start.background = ContextCompat.getDrawable(applicationContext, R.drawable.get_ready)
                handler.postDelayed(this, 4000L)
            }
        }, 4000L)

        handler.postDelayed(object : Runnable {
            override fun run() {
                supportActionBar?.show()
                start.background = null
                preview_view.visibility = View.VISIBLE
                btn_camera_lens_face.visibility = View.VISIBLE
                content.visibility = View.VISIBLE
                overlay.visibility = View.VISIBLE
                handler.postDelayed(this, 6000L)
            }
        }, 6000L)
    }
    private fun setupCameraThread() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }
    private fun setupCameraControllers() {
        btn_camera_lens_face.setOnClickListener {
            b=false
            store=store.subList(0, i)
            sendEmail(htmlBody, store)
            Toast.makeText(this, "Email sent", Toast.LENGTH_SHORT).show()
            finishAffinity()
        }
    }
    private fun sendEmail(htmlBody: String, store: MutableList<Bitmap>){
        appExecutors.diskIO().execute {
            val props = System.getProperties()
            props["mail.smtp.host"] = "smtp.gmail.com"
            props["mail.smtp.socketFactory.port"] = "465"
            props["mail.smtp.socketFactory.class"] = "javax.net.ssl.SSLSocketFactory"
            props["mail.smtp.auth"] = "true"
            props["mail.smtp.port"] = "465"

            val session =  Session.getInstance(
                    props,
                    object : javax.mail.Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication {
                            return PasswordAuthentication(Credentials.EMAIL, Credentials.PASSWORD)
                        }
                    }
            )

            try {
                val mm = MimeMessage(session)
                val emailId = "y.krishnanlyb@gmail.com"
                mm.setFrom(InternetAddress(Credentials.EMAIL))
                mm.addRecipient(
                        Message.RecipientType.TO,
                        InternetAddress(emailId)
                )
                mm.subject = "Face-Mask Detection App"
                mm.setText("Images of people who were denied access to the building are- ")
                val messageBodyPart = MimeBodyPart()
                messageBodyPart.setContent(htmlBody, "text/html")
                val multipart: Multipart = MimeMultipart()
                multipart.addBodyPart(messageBodyPart)
                var j=0
                while(j<i){
                    val baos = ByteArrayOutputStream()
                    store[j++].compress(Bitmap.CompressFormat.WEBP, 0, baos)
                    val imageInByte: ByteArray = baos.toByteArray()
                    val imageBodyPart = MimeBodyPart()
                    val bds = ByteArrayDataSource(imageInByte, "image/jpeg")
                    imageBodyPart.dataHandler = DataHandler(bds)
                    imageBodyPart.setHeader("Content-ID", "<image>")
                    imageBodyPart.fileName = "$j.jpg"
                    multipart.addBodyPart(imageBodyPart)

                }
                mm.setContent(multipart)
                Transport.send(mm)
                appExecutors.mainThread().execute {

                }
            } catch (e: MessagingException) {
                e.printStackTrace()
            }
        }
    }
    private fun requireCameraPermission() {
        ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
        )
    }
    private fun grantedCameraPermission(requestCode: Int) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted) {
                setupCamera()
            } else {
                Toast.makeText(
                        this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
    private fun setupCameraUseCases() {
        val cameraSelector: CameraSelector =
            CameraSelector.Builder().requireLensFacing(lensFacing)
                .build()
        val metrics: DisplayMetrics =
            DisplayMetrics().also { preview_view.display.getRealMetrics(it) }
        val rotation: Int = preview_view.display.rotation
        val screenAspectRatio: Int = aspectRatio(metrics.widthPixels, metrics.heightPixels)

        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, BitmapOutputAnalysis(applicationContext) { bitmap ->
                    setupMLOutput(bitmap)
                })
            }
        cameraProvider?.unbindAll()
        try {
            camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
            )
            preview?.setSurfaceProvider(preview_view.createSurfaceProvider())
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }
    private fun setupCamera() {
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {
            cameraProvider = cameraProviderFuture.get()
            lensFacing = when {
                hasFrontCamera -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("CAMERA IS NOT WORKING")
            }
            setupCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private val allPermissionsGranted: Boolean
        get() {
            return REQUIRED_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(
                        baseContext, it
                ) == PackageManager.PERMISSION_GRANTED
            }
        }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults:
            IntArray
    ) {
        grantedCameraPermission(requestCode)
    }

    private val hasFrontCamera: Boolean
        get() {
            return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
        }
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio: Double = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private lateinit var faceMaskDetection: FackMaskDetection

    private fun setupML() {
            val options= Model.Options.Builder().setDevice(Model.Device.GPU).build()
//        val options = Model.Options.Builder().setNumThreads(5).build()
        faceMaskDetection = FackMaskDetection.newInstance(applicationContext, options)
    }

    private fun setupMLOutput(bitmap: Bitmap) {
        val tensorImage: TensorImage = TensorImage.fromBitmap(bitmap)
        val result: FackMaskDetection.Outputs = faceMaskDetection.process(tensorImage)
        val output: List<Category> =
            result.probabilityAsCategoryList.apply {
                sortByDescending { res -> res.score }
            }
        lifecycleScope.launch(Dispatchers.Main) {
            output.firstOrNull()?.let { category ->
                tv_output.text = if (category.label == "without_mask") "Access Denied" else "Access Granted"
                tv_output.setTextColor(
                        ContextCompat.getColor(
                                applicationContext,
                                if (category.label == "without_mask") R.color.red else R.color.green
                        )
                )
                overlay.background = getDrawable(
                        if (category.label == "without_mask") R.drawable.red_border else R.drawable.green_border
                )
                pb_output.progressTintList = AppCompatResources.getColorStateList(
                        applicationContext,
                        if (category.label == "without_mask") R.color.red else R.color.green
                )
                pb_output.progress = (category.score * 100).toInt()
                reason.visibility= if (category.label == "without_mask") View.VISIBLE else View.GONE
                if(i<30&&b) {
                    if (category.label == "without_mask" && i + 20 > store.size) store.add(bitmap)
                    else if (category.label == "without_mask") {
                        store = store.subList(0, i + 1)
                        i++
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "Face-Mask-Detection"
        private const val REQUEST_CODE_PERMISSIONS = 0x98
        private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(Manifest.permission.CAMERA)
        private const val RATIO_4_3_VALUE: Double = 4.0 / 3.0
        private const val RATIO_16_9_VALUE: Double = 16.0 / 9.0
    }
}

private class BitmapOutputAnalysis(
        context: Context,
        private val listener: CameraBitmapOutputListener
) :
    ImageAnalysis.Analyzer {

    private val yuvToRgbConverter = YuvToRgbConverter(context)
    private lateinit var bitmapBuffer: Bitmap
    private lateinit var rotationMatrix: Matrix

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun ImageProxy.toBitmap(): Bitmap? {

        val image: Image = this.image ?: return null

        if (!::bitmapBuffer.isInitialized) {
            rotationMatrix = Matrix()
            rotationMatrix.postRotate(this.imageInfo.rotationDegrees.toFloat())
            bitmapBuffer = Bitmap.createBitmap(
                    this.width, this.height, Bitmap.Config.ARGB_8888
            )
        }

        yuvToRgbConverter.yuvToRgb(image, bitmapBuffer)

        return Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                rotationMatrix,
                false
        )
    }

    override fun analyze(imageProxy: ImageProxy) {
        imageProxy.toBitmap()?.let {
            listener(it)
        }
        imageProxy.close()
    }
}