package com.watt.camera1n2.camera2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.watt.camera1n2.BuildConfig
import com.watt.camera1n2.R
import com.watt.camera1n2.databinding.Camera2VideoBinding
import com.watt.camera1n2.l
import kotlinx.coroutines.*
import splitties.systemservices.layoutInflater
import splitties.systemservices.windowManager
import splitties.toast.toast
import java.io.File
import java.io.IOException
import java.lang.Runnable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

/**
 * Created by khm on 2021-10-18.
 */

class Camera2Video : ConstraintLayout, LifecycleObserver {
    companion object{
        private val ORIENTATIONS = SparseIntArray()
        init{
            ORIENTATIONS.append(Surface.ROTATION_0, 90);
            ORIENTATIONS.append(Surface.ROTATION_90, 0);
            ORIENTATIONS.append(Surface.ROTATION_180, 270);
            ORIENTATIONS.append(Surface.ROTATION_270, 180);
        }
    }

    private lateinit var binding : Camera2VideoBinding
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var orientationEventListener: OrientationEventListener? = null
    private var mLastRotation = 0

    private var cameraDevice: CameraDevice? = null
    private var mMediaRecorder: MediaRecorder?=null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraCharacteristics: CameraCharacteristics?=null

    //camera setting value
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var aspectRatio = 0.75f
    private val aspectRatioThreshold = 0.05f
    private var sensorOrientation = 0
    private var previewSize: Size?=null
    private var cameraId: String? = null

    //Lock
    private val STATE_PREVIEW = 0
    private val STATE_WAITING_LOCK = 1
    private val STATE_WAITING_PRECAPTURE = 2
    private val STATE_WAITING_NON_PRECAPTURE = 3
    private val STATE_PICTURE_TAKEN = 4
    private val cameraOpenCloseLock = Semaphore(1)
    private var state = STATE_PREVIEW


    // thread
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null


    //Zooming
    private var maximumZoomLevel = 0f
    private var zoom: Rect? = null


    private var videoFileSaveDialog: VideoFileSaveDialog? = null
    private val defaultFileSaveDir = "${Environment.getExternalStorageDirectory().absolutePath}/Camera1/"
    private val defaultFileSaveDirOn11 = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.absolutePath
    private var fileDir:String = ""
    private var fileName:String = ""


    private val zoomNumberTexts = ArrayList<TextView>()

    private var zoomLevel:Int by Delegates.observable(1){ _, oldValue, newValue->
        if(oldValue >0)
            zoomNumberTexts[oldValue - 1].setTextColor(Color.WHITE)
        if(newValue>0)
            zoomNumberTexts[newValue - 1].setTextColor(context.getColor(R.color.text_yellow))

        when(newValue){
            2 -> {
                // 카메라1기준 zoom == 1.5f
                setZoom(1.25f)
            }
            3 -> {
                // 카메라1기준 zoom == 2.2f
                setZoom(1.84f)
            }
            4 -> {
                // 카메라1기준 zoom == 2.9f
                setZoom(2.42f)
            }
            5 -> {
                // 카메라1기준 zoom == 3.3f
                setZoom(2.75f)
            }
            else->{ // == 1
                // 카메라1기준 zoom == 1.2f
                setZoom(1.0f)
            }
        }
    }

    // Callback Listeners
    var callbackOnClickBack:(()->Unit)? = null
    var callbackCompleteSaveVideo:((uri: String)->Unit)? = null
    var callbackCanceledSaveVideo:(()->Unit)? = null
    var callbackOnClickPhoto:(()->Unit)? =null






    //const values
    private val CameraSettingDir = "camerasettingdir"
    private val CameraBitrate = "camerabitrate"
    private val VIDEO_RECORDING_START = 1
    private val VIDEO_RECORDING_STOP = 2
    private val fhdResolution = Size(1920, 1080)
    private val hdResolution = Size(1280, 720)
    private val fhdBitrate = 4*1024*1024  //4mbps
    private val hdBitrate = 2621440   //2.5mbps
    private var selectedResolution = fhdResolution


    private var isRecording = false


    private var selectedBitRate:Int by Delegates.observable(fhdBitrate){ _, _, newValue ->
        // save sharedpreferences camera bitrate
        context.getSharedPreferences(CameraSettingDir, 0).edit().putInt(
                CameraBitrate, newValue).apply()
    }






    constructor(context: Context):super(context){
        initView()
    }

    constructor(context: Context, attrs: AttributeSet):super(context, attrs){
        initView()
    }

    constructor(context: Context, attrs: AttributeSet, defSty: Int):super(context, attrs, defSty){
        initView()
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private fun onResume(){
        l.d("onResume")
        zoom = null
        startBackgroundThread()
        if(binding.textureView.isAvailable){
            if(cameraDevice == null){
                openCamera(binding.textureView.width, binding.textureView.height)
            }

        }else{
            binding.textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private fun onPause(){
        l.d("onPause")
        if (isRecording) {
            stopRecordingVideo()
            stopRecordTime()
            binding.ibRecordeStart.visibility = VISIBLE
            binding.ibRecordeStop.visibility = GONE
            isRecording = false
        }
        zoomLevel = 1
        ioScope.cancel()
        closeCamera()
        stopBackgroundThread()

        (context as AppCompatActivity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private fun onDestroy(){
        orientationEventListener!!.disable()
        orientationEventListener = null
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun initView(){
        //카메라가 사용되는 액티비티의 라이프사이클 수명주기 일치시킴
        if(context is AppCompatActivity){
            (context as AppCompatActivity).lifecycle.addObserver(this)
        }else{
            l.e("This camera1 library only works in activities.")
            return
        }

        binding = Camera2VideoBinding.inflate(layoutInflater)
        addView(binding.root)

        (context as AppCompatActivity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        orientationEventListener =
                object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
                    override fun onOrientationChanged(orientation: Int) {
                        val display = (context as AppCompatActivity).windowManager.defaultDisplay
                        val rotation = display.rotation
                        if ((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) && rotation != mLastRotation) {
                            Log.d("Camera2Video", "onOrientationChanged:::::")
                            closeCamera()
                            stopBackgroundThread()
                            startBackgroundThread()
                            if (binding.textureView.isAvailable) {
                                openCamera(binding.textureView.width, binding.textureView.height)
                            } else {
                                binding.textureView.surfaceTextureListener = surfaceTextureListener
                            }
                            mLastRotation = rotation
                        }
                    }
                }
        orientationEventListener?.let{
            if(it.canDetectOrientation())
                it.enable()
        }




        binding.run {
            tvBack.setOnClickListener {
                callbackOnClickBack?.let{ callback->
                    callback()
                }
            }

            tvPhoto.setOnClickListener {
                callbackOnClickPhoto?.let{ callback->
                    callback()
                }
            }


            tvFHD.setOnClickListener {
                if(isRecording)
                    return@setOnClickListener
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round, null)
                selectedBitRate = fhdBitrate
                selectedResolution = fhdResolution
            }

            tvHD.setOnClickListener {
                if(isRecording)
                    return@setOnClickListener
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round, null)
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                selectedBitRate = hdBitrate
                selectedResolution = hdResolution
            }


            ibRecordeStart.setOnClickListener {
                eventHandler.sendEmptyMessage(VIDEO_RECORDING_START)
                startRecordTime()
            }

            ibRecordeStop.setOnClickListener {
                eventHandler.sendEmptyMessage(VIDEO_RECORDING_STOP)
                stopRecordTime()
            }



            zoomNumberTexts.addAll(binding.llZoomLevelNumbers.children.toList().filterIsInstance<TextView>())

            addCommand(resources.getString(R.string.zoom) + " 1"){
                zoomLevel = 1
            }
            addCommand(resources.getString(R.string.zoom) + " 2"){
                zoomLevel = 2
            }
            addCommand(resources.getString(R.string.zoom) + " 3"){
                zoomLevel = 3
            }
            addCommand(resources.getString(R.string.zoom) + " 4"){
                zoomLevel = 4
            }
            addCommand(resources.getString(R.string.zoom) + " 5"){
                zoomLevel = 5
            }


            // for test
//            if(BuildConfig.DEBUG){
//                for(i in zoomNumberTexts.indices){
//                    zoomNumberTexts[i].setOnClickListener {
//                        l.d("onClick zoomNumberText : $i")
//                        zoomLevel = i+1
//                    }
//                }
//            }


        }



    }


    private fun setZoom(magnification: Float){
        l.d("setZoom into -- maximumZoomLevel : $maximumZoomLevel")
        try{
            if(magnification >maximumZoomLevel)
                return
            val rect = cameraCharacteristics!!.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?: return

            val ratio = 1.toFloat() / magnification
            l.d("ratio : $ratio")
            val croppedWidth = rect.width() - Math.round(rect.width().toFloat() * ratio)
            val croppedHeight = rect.height() - Math.round(rect.height().toFloat() * ratio)
            zoom = Rect(
                    croppedWidth / 2, croppedHeight / 2,
                    rect.width() - croppedWidth / 2, rect.height() - croppedHeight / 2
            )
            l.d("zoom left:${zoom?.left}, top:${zoom?.top}, right:${zoom?.right}, bottom:${zoom?.bottom}")
            previewRequestBuilder!!.set(CaptureRequest.SCALER_CROP_REGION, zoom)
            captureSession!!.setRepeatingRequest(
                    previewRequestBuilder!!.build(),
                    null,
                    backgroundHandler
            )
        }catch (e: Exception){
            l.e(e.toString())
        }
    }



    private var surfaceTextureListener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, w: Int, h: Int) {
            l.d("onSurfaceTextureSizeChanged")
        }

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
            //l.d("onSurfaceTextureUpdated")
        }

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
            // 지정된 SurfaceTexture 를 파괴하고자 할 때 호출된다
            // true 를 반환하면 메서드를 호출한 후 SurfaceTexture 에서 랜더링이 발생하지 않는다
            // 대부분의 응용프로그램은 true 를 반환한다
            // false 를 반환하면 SurfaceTexture#release() 를 호출해야 한다
            l.d("onSurfaceTextureDestroyed")
            return false
        }

        override fun onSurfaceTextureAvailable(p0: SurfaceTexture, width: Int, height: Int) {
            // TextureListener 에서 SurfaceTexture 가 사용가능한 경우, openCamera() 메서드를 호출한다
            l.d("onSurfaceTextureAvailable, open camera")
            openCamera(width, height)
        }

    }


    private fun chooseOptimalSize(
            choices: Array<Size>, textureViewWidth: Int,
            textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough: MutableList<Size> = java.util.ArrayList()
        // Collect the supported resolutions that are smaller than the preview Surface
        val notBigEnough: MutableList<Size> = java.util.ArrayList()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight && option.height == option.width * h / w) {
                if (option.width >= textureViewWidth &&
                        option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        return if (bigEnough.size > 0) {
            Collections.min(bigEnough, Camera2Photo.CompareSizesByArea())
        } else if (notBigEnough.size > 0) {
            Collections.max(notBigEnough, Camera2Photo.CompareSizesByArea())
        } else {
            l.e("Couldn't find any suitable preview size")
            choices[0]
        }
    }


    private fun showVideoSaveDialog(filePath: String) {
        l.d("show video save dialog - filePath : $filePath")
        if (videoFileSaveDialog == null) {

            videoFileSaveDialog = VideoFileSaveDialog(context)
            val window = videoFileSaveDialog!!.window
            window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        if (!videoFileSaveDialog!!.isShowing){
            videoFileSaveDialog!!.showDialog(filePath){ success->
                if(success){
                    l.d("on Save Succeed")
                    callbackCompleteSaveVideo?.let{ callback->
                        callback(filePath)
                    }
                }else{
                    initDataDelete()
                    callbackCanceledSaveVideo?.let{ callback->
                        callback()
                    }
                }
            }
        }
        videoFileSaveDialog!!.setOnDismissListener { videoFileSaveDialog = null }
    }


    @SuppressLint("SimpleDateFormat")
    private fun getNowDate():String{
        val now = System.currentTimeMillis()
        val date = Date(now)
        val sdf = SimpleDateFormat("yyyyMMddHHmmssSSS")

        return sdf.format(date)
    }


    internal class CompareSizesByArea : Comparator<Size?> {
        override fun compare(lhs: Size?, rhs: Size?): Int {
            return java.lang.Long.signum(
                    lhs!!.width.toLong() * lhs.height -
                            rhs!!.width.toLong() * rhs.height
            )
        }
    }


    private fun setUpCameraOutputs(width: Int, height: Int) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null
        try {
            for (each in manager.cameraIdList) {
                if (facing == manager.getCameraCharacteristics(each).get(CameraCharacteristics.LENS_FACING)) {
                    cameraId = each
                    break
                }
            }
            if (cameraId == null) throw Exception("No correct facing camera is found.")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            cameraCharacteristics = characteristics
            maximumZoomLevel = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)!!
            val map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
                    ?: throw Exception("configuration map is null.")

            // For still image captures, we use the largest available size.
            val largest: Size
            val sizes = java.util.ArrayList<Size>()
            for (each in map.getOutputSizes(ImageFormat.JPEG)) {
                val thisAspect = each.height.toFloat() / each.width
                if (Math.abs(thisAspect - aspectRatio) < aspectRatioThreshold) {
                    sizes.add(each)
                }
            }
            if (sizes.size == 0) return
            largest = Collections.max(sizes, Camera2Photo.CompareSizesByArea())

            // Find out if we need to swap dimension to get the preview size relative to sensor
            // coordinate.
            val displayRotation = (context as Activity).windowManager.defaultDisplay.rotation
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
            var swappedDimensions = false
            when (displayRotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
                Surface.ROTATION_90, Surface.ROTATION_270 -> if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
                else -> l.e("Display rotation is invalid: $displayRotation")
            }
            val displaySize = Point()
            (context as AppCompatActivity).windowManager.defaultDisplay.getSize(displaySize)
            var rotatedPreviewWidth = width
            var rotatedPreviewHeight = height
            var maxPreviewWidth = displaySize.x
            var maxPreviewHeight = displaySize.y
            if (swappedDimensions) {
                rotatedPreviewWidth = height
                rotatedPreviewHeight = width
                maxPreviewWidth = displaySize.y
                maxPreviewHeight = displaySize.x
            }

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                    maxPreviewHeight, largest
            )
            this.cameraId = cameraId
        } catch (e: Exception) {
            l.e(e.toString())
        }
    }


    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (null == previewSize) {
            return
        }
        val rotation = (context as AppCompatActivity).windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(
                0f,
                0f,
                previewSize!!.height.toFloat(),
                previewSize!!.width.toFloat()
        )
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        l.d("configureTransform rotation : $rotation")
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize!!.height,
                    viewWidth.toFloat() / previewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        binding.textureView.setTransform(matrix)
    }


    @SuppressWarnings("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        l.e("openCamera() : openCamera()메서드가 호출되었음")

        setUpCameraOutputs(width, height)
        configureTransform(width, height)

        // CameraManager 객체를 가져온다
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            l.d("before mediarecorder")
            mMediaRecorder = MediaRecorder()

            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            l.e(e.toString())
        }
    }



    // openCamera() 메서드에서 CameraManager.openCamera() 를 실행할때 인자로 넘겨주어야하는 콜백메서드
    // 카메라가 제대로 열렸으면, cameraDevice 에 값을 할당해주고, 카메라 미리보기를 생성한다
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            l.d("stateCallback : onOpened")
            cameraOpenCloseLock.release()
            // MainActivity 의 cameraDevice 에 값을 할당해주고, 카메라 미리보기를 시작한다
            // 나중에 cameraDevice 리소스를 해지할때 해당 cameraDevice 객체의 참조가 필요하므로,
            // 인자로 들어온 camera 값을 전역변수 cameraDevice 에 넣어 준다
            cameraDevice = camera

            // createCameraPreview() 메서드로 카메라 미리보기를 생성해준다
            startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            l.d("stateCallback : onDisconnected")
            cameraOpenCloseLock.release()
            // 연결이 해제되면 cameraDevice 를 닫아준다
            cameraDevice!!.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            l.d("stateCallback : onError")
            cameraOpenCloseLock.release()
            // 에러가 뜨면, cameraDevice 를 닫고, 전역변수 cameraDevice 에 null 값을 할당해 준다
            cameraDevice!!.close()
            cameraDevice = null
        }

    }



    // Setting Values
    fun setSaveDir(filePath: String?){
        if(filePath.isNullOrEmpty()){
            l.e("setSaveDir is null or empty --> default save dir : $defaultFileSaveDir")
            return
        }
        fileDir = filePath
    }


    // 사진, 비디오 전환 버튼 숨김
    fun hideModeSelectButton(){
        binding.llModeSelect.visibility = View.GONE
    }

    // 사진, 비디오 전환 버튼 표시
    fun showModeSelectButton(){
        binding.llModeSelect.visibility = View.VISIBLE
    }




    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2VideoBackground")
        backgroundThread?.let{
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }


    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }






    @SuppressLint("HandlerLeak")
    var eventHandler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VIDEO_RECORDING_START -> if (!isRecording) {
                    (context as AppCompatActivity).setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED)
                    binding.ibRecordeStart.visibility = View.GONE
                    binding.ibRecordeStop.visibility = View.VISIBLE
                    startRecordingVideo()
                    isRecording = true
                }
                VIDEO_RECORDING_STOP -> if (isRecording) {
                    (context as AppCompatActivity).setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                    binding.ibRecordeStart.visibility = View.VISIBLE
                    binding.ibRecordeStop.visibility = View.GONE
                    stopRecordingVideo()
                    isRecording = false
                }

            }
        }
    }










    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
            if (null != mMediaRecorder) {
                mMediaRecorder!!.release()
                mMediaRecorder = null
            }
        } catch (e: InterruptedException) {
            throw java.lang.RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            cameraOpenCloseLock.release()
        }
    }


    /**
     * Start the camera preview.
     */
    private fun startPreview() {
        if (null == cameraDevice || !binding.textureView.isAvailable || null == previewSize) {
            return
        }
        try {
            closePreviewSession()
            val texture = binding.textureView.surfaceTexture!!
            l.d("startPreview preview.width:${previewSize?.width}, preview.height:${previewSize?.height}")
            texture.setDefaultBufferSize(selectedResolution.width, selectedResolution.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val previewSurface = Surface(texture)
            previewRequestBuilder!!.addTarget(previewSurface)

            cameraDevice!!.createCaptureSession(
                    listOf(previewSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            updatePreview()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            toast("Failed")
                        }
                    }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    /**
     * Update the camera preview. [.startPreview] needs to be called in advance.
     */
    private fun updatePreview() {
        if (null == cameraDevice) {
            return
        }
        try {
            setUpCaptureRequestBuilder(previewRequestBuilder!!)
            val thread = HandlerThread("CameraPreview")
            thread.start()

            if (zoom != null) previewRequestBuilder!!.set(CaptureRequest.SCALER_CROP_REGION, zoom)

            captureSession!!.setRepeatingRequest(
                    previewRequestBuilder!!.build(),
                    null,
                    backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }


    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        Log.d(
                "Camera1Video",
                "setUpMediaRecorder:=============$sensorOrientation"
        )

        mMediaRecorder?.let{ mediaRecorder ->
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            fileName = getNowDate() + ".mp4"
            if(fileDir.isEmpty()){
                l.e("fileDir is empty")
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
                    fileDir = defaultFileSaveDirOn11
                }else{
                    fileDir = defaultFileSaveDir
                }
            }
            val dir = File(fileDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            mediaRecorder.setOutputFile(fileDir + fileName)
            mediaRecorder.setVideoEncodingBitRate(selectedBitRate)
            mediaRecorder.setVideoFrameRate(30)
            //        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mideoSize.getHeight());
            mediaRecorder.setVideoSize(selectedResolution.width, selectedResolution.height)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            when (val rotation = context.windowManager.defaultDisplay.rotation) {
                0 -> mediaRecorder.setOrientationHint(0)
                2 -> mediaRecorder.setOrientationHint(180)
                180 -> mediaRecorder.setOrientationHint(getInverseOrientation(rotation))
            }
            mediaRecorder.prepare()
            l.d("before setmediascnner")
            setMediaScanner(fileDir + fileName)
        }


    }

    private fun startRecordingVideo() {
        if (null == cameraDevice || !binding.textureView.isAvailable || null == previewSize) {
            return
        }
        try {
            closePreviewSession()
            setUpMediaRecorder()
            val texture = binding.textureView.surfaceTexture!!
            texture.setDefaultBufferSize(selectedResolution.width, selectedResolution.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val surfaces: MutableList<Surface> = ArrayList()

            // Set up Surface for the camera preview
            val previewSurface = Surface(texture)
            surfaces.add(previewSurface)
            previewRequestBuilder!!.addTarget(previewSurface)

            // Set up Surface for the MediaRecorder
            val recorderSurface = mMediaRecorder!!.surface
            surfaces.add(recorderSurface)
            previewRequestBuilder!!.addTarget(recorderSurface)

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            cameraDevice!!.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            captureSession = cameraCaptureSession
                            updatePreview()
                            (context as AppCompatActivity).runOnUiThread(Runnable { mMediaRecorder!!.start() })
                        }

                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                            toast("Failed")
                        }
                    },
                    backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun closePreviewSession() {
        if (captureSession != null) {
            captureSession?.close()
            captureSession = null
        }
    }

    private fun stopRecordingVideo() {
        mMediaRecorder?.stop()
        mMediaRecorder?.reset()
        startPreview()
        showVideoSaveDialog(fileDir + fileName)
    }





    private fun setMediaScanner(targetFileName: String) {
        object : MediaScannerConnection.MediaScannerConnectionClient {
            private var msc: MediaScannerConnection? = null
            override fun onMediaScannerConnected() {
                msc!!.scanFile(targetFileName, null)
            }

            override fun onScanCompleted(path: String, uri: Uri) {
                msc!!.disconnect()
            }

            init {
                msc = MediaScannerConnection(context, this)
                msc?.connect()
            }
        }
    }




    private fun getInverseOrientation(rotation: Int):Int{
        return when(rotation){
            Surface.ROTATION_0 -> 270
            Surface.ROTATION_90 -> 180
            Surface.ROTATION_180 -> 90
            Surface.ROTATION_270 -> 0
            else-> 0
        }
    }





    private fun initDataDelete() {
        val file = File(fileDir + fileName)
        if (file.exists()) {
            file.delete()
            setMediaScanner(fileDir)
        }
    }


    private fun addCommand(commandText: String, onClickCallback: () -> Unit){
        val textView = TextView(context)
        textView.text = commandText
        textView.contentDescription = "hf_no_number"
        val lp = LinearLayout.LayoutParams(1, 1)
        textView.layoutParams = lp
        textView.setOnClickListener {
            onClickCallback()
        }

        binding.llCommand.addView(textView)
    }





    private var baseTime:Long = 0L
    private var jobRecordTime: Job? =null
    private var availableRecordSeconds:Long =0L

    @SuppressLint("RestrictedApi")
    private fun startRecordTime(){
        getCurrentRemainRecordTime()
        if(availableRecordSeconds <= 0){
            toast(resources.getString(R.string.not_enough_storage))
            return
        }



        baseTime = System.currentTimeMillis()
        binding.llRecordTime.visibility = View.VISIBLE
        jobRecordTime?.cancel()

        jobRecordTime = CoroutineScope(Dispatchers.Default).launch {
            repeat(availableRecordSeconds.toInt()){
                val pastTime = (System.currentTimeMillis() - baseTime)/1000
                withContext(Dispatchers.Main){
                    binding.tvAvailableTimeBottom.text = "/ ${convertPastTimeMillsToHHMMSSColon(availableRecordSeconds)}"
                    binding.tvRecordTime.text = convertPastTimeMillsToHHMMSSColon(pastTime)
                }
                if(pastTime >= availableRecordSeconds){
                    l.d("pastTime >= availableRecordSeconds")
                    withContext(Dispatchers.Main){
                        l.d("stop recording process")
                        showButtonOnReady()
                        isRecording = false
                        //animateRecord.cancel()
                        binding.ibRecordeStop.visibility = GONE
                        binding.ibRecordeStart.visibility = VISIBLE
                        stopRecordingVideo()
                        stopRecordTime()
                    }
                }

                delay(1000)
            }
            withContext(Dispatchers.Main){
                l.d("exit repeat and stop recording")
                showButtonOnReady()
                isRecording = false
                //animateRecord.cancel()
                binding.ibRecordeStop.visibility = GONE
                binding.ibRecordeStart.visibility = VISIBLE
                stopRecordingVideo()
                stopRecordTime()
            }
        }
    }


    private fun stopRecordTime(){
        jobRecordTime?.cancel()
        binding.llRecordTime.visibility = View.GONE
        binding.ibRecordeStop.visibility = View.GONE
        binding.ibRecordeStart.visibility = View.VISIBLE
    }


    private fun getCurrentRemainRecordTime():String {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        val blockSize = stat.blockSizeLong;
        val availableBlocks = stat.availableBlocksLong;

        val availableMemory = availableBlocks * blockSize - 1073741824  //- 1073741824 // -1gb ( 1024 * 1024 * 1024 ) 안드로이드 여유 내부 용량 확보 - 다른앱 고려

        l.d("available Memory : $availableMemory")

        //fhd일때 초당 620000 byte, hd일때 초당 380000
        availableRecordSeconds = if(selectedBitRate == fhdBitrate){
            availableMemory / 620000
        }else{
            availableMemory / 380000
        }

        l.d("available record seconds at getCurrentRemainRecordTime : $availableRecordSeconds")

        if(availableRecordSeconds < 0){
            availableRecordSeconds = 0
        }

        if(availableRecordSeconds > 3600){
            availableRecordSeconds = 3600
        }

//        if(availableRecordSeconds > 120){
//            availableRecordSeconds = 120
//        }



        //return resources.getString(R.string.available_record_time) + " : " + convertPastTimeMillsToHHMMSS(availableRecordSeconds)
        return convertPastTimeMillsToHHMMSS(availableRecordSeconds)
        //return formatMemorySize(availableBlocks * blockSize);
    }



    private fun convertPastTimeMillsToHHMMSS(pastTime:Long):String{
        var hours = 0
        var minutes = 0
        var seconds = 0

        minutes = (pastTime / 60).toInt()
        hours = minutes / 60
        seconds = (pastTime % 60).toInt()
        minutes %= 60

        var availableRecordTime = ""

        if(hours > 0){
            availableRecordTime += if(hours < 10)
                "0${hours}:"
            else
                "${hours}:"

        }


        availableRecordTime += if(minutes < 10){
            "0${minutes}:"
        }else{
            "${minutes}:"
        }

        availableRecordTime += if(seconds<10){
            "0${seconds}"
        }else{
            "${seconds}"
        }

        return availableRecordTime
    }

    private fun convertPastTimeMillsToHHMMSSColon(pastTime:Long):String{
        var minutes = 0
        var seconds = 0

        if(pastTime == 3600L){
            return "60:00"
        }

        minutes = (pastTime / 60).toInt()
        seconds = (pastTime % 60).toInt()
        minutes %= 60




        var availableRecordTime = ""

        availableRecordTime += if(minutes < 10){
            "0${minutes}:"
        }else{
            "${minutes}:"
        }

        availableRecordTime += if(seconds<10){
            "0${seconds}"
        }else{
            "${seconds}"
        }

        return availableRecordTime
    }


    private fun showButtonOnReady(){
        binding.run {
            //llAvailableTime.visibility = View.VISIBLE
            if(selectedBitRate == fhdBitrate){
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                tvHD.visibility = View.VISIBLE
            }else{
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                tvFHD.visibility = View.VISIBLE
            }
            llBack.visibility = View.VISIBLE
            llModeSelect.visibility = View.VISIBLE
        }
    }

}