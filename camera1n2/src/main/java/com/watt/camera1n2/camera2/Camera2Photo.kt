package com.watt.camera1n2.camera2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
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
import com.watt.camera1n2.ImageFileSaveDialog
import com.watt.camera1n2.R
import com.watt.camera1n2.databinding.Camera2PhotoBinding
import com.watt.camera1n2.l
import kotlinx.coroutines.*
import splitties.systemservices.layoutInflater
import splitties.toast.longToast
import splitties.toast.toast
import java.io.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates


/**
 * Created by khm on 2021-10-13.
 */

class Camera2Photo :ConstraintLayout, LifecycleObserver {
    companion object{
        private val ORIENTATIONS = SparseIntArray()
        init{
            ORIENTATIONS.append(Surface.ROTATION_0, 90);
            ORIENTATIONS.append(Surface.ROTATION_90, 0);
            ORIENTATIONS.append(Surface.ROTATION_180, 270);
            ORIENTATIONS.append(Surface.ROTATION_270, 180);
        }
    }

    private lateinit var binding:Camera2PhotoBinding
    private val defaultScope = CoroutineScope(Dispatchers.Default)
    private var jobCheckCameraIdNotNull:Job?=null
    private var jobReOpenCamera:Job?=null

    private var orientationEventListener: OrientationEventListener? = null
    private var mLastRotation = 0

    private var cameraDevice: CameraDevice?=null
    private var imageReader: ImageReader?=null
    private var previewRequestBuilder: CaptureRequest.Builder?=null
    private var image: Image?=null
    private var captureSession: CameraCaptureSession?=null
    private var cameraCharacteristics: CameraCharacteristics?=null

    private var isFlashStatus = false

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
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    //Zooming
    private var maximumZoomLevel = 0f
    private var zoom: Rect? = null


    private var imageFileSaveDialog: ImageFileSaveDialog?=null
    private var localBitmap: Bitmap?=null
    private var currentPictureData:ByteArray?=null
    private val defaultFileSaveDir = "${Environment.getExternalStorageDirectory().absolutePath}/Camera1/"
    private val defaultFileSaveDirOn11 = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.absolutePath
    private var fileName = ""
    private var fileDir:String = ""
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
    var callbackCompleteSavePhoto:((uri: String)->Unit)? = null
    var callbackCanceledSavePhoto:(()->Unit)? = null
    var callbackOnClickVideo:(()->Unit)? =null



    constructor(context: Context):super(context){
        initView()
    }

    constructor(context: Context, attrs: AttributeSet):super(context, attrs){
        initView()
    }

    constructor(context: Context, attrs: AttributeSet, defSty: Int):super(context, attrs, defSty){
        initView()
    }


    // Setting Values
    fun setSaveDir(filePath: String?){
        if(filePath.isNullOrEmpty()){
            l.e("setSaveDir is null or empty --> default save dir : $defaultFileSaveDir")
            return
        }
        fileDir = filePath
        l.d("file dir : $fileDir")
    }


    // 사진, 비디오 전환 버튼 숨김
    fun hideModeSelectButton(){
        binding.llModeSelect.visibility = View.GONE
    }

    // 사진, 비디오 전환 버튼 표시
    fun showModeSelectButton(){
        binding.llModeSelect.visibility = View.VISIBLE
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
        defaultScope.cancel()
        zoomLevel = 1
        closeCamera()
        stopBackgroundThread()
        (context as AppCompatActivity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }




    private fun initView(){
        //카메라가 사용되는 액티비티의 라이프사이클 수명주기 일치시킴
        if(context is AppCompatActivity){
            (context as AppCompatActivity).lifecycle.addObserver(this)
        }else{
            l.e("This camera1 library only works in activities.")
            return
        }

        l.d("filedir on initView : $fileDir")

        binding = Camera2PhotoBinding.inflate(layoutInflater)
        addView(binding.root)

        (context as AppCompatActivity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)


        orientationEventListener = object : OrientationEventListener(
            context,
            SensorManager.SENSOR_DELAY_NORMAL
        ) {
            override fun onOrientationChanged(orientation: Int) {
                val display = (context as AppCompatActivity).windowManager.defaultDisplay
                val rotation = display.rotation
                if ((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) && rotation != mLastRotation) {
                    Log.d("Camera2Photo", "onOrientationChanged:::::")
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
            if(it.canDetectOrientation()) it.enable()
        }



        binding.run {
            tvBack.setOnClickListener {
                callbackOnClickBack?.let{
                    it()
                }
            }

            tvCaptureBtn.setOnClickListener {
                lockFocus()
            }

            tvVideo.setOnClickListener {
                callbackOnClickVideo?.let{
                    it()
                }
            }

            tvFocus.setOnClickListener {
                l.d("onClick tvFocus")
                manualFocus()
            }

            tvFlash.setOnClickListener {
                if(!isFlashStatus){
                    isFlashStatus = true
                    previewRequestBuilder?.set(
                        CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_TORCH
                    )
                    captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, null)
                    tvFlashStatus.text = resources.getString(R.string.flash_on)
                }else{
                    isFlashStatus = false
                    previewRequestBuilder?.set(
                        CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF
                    )
                    captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, null)
                    tvFlashStatus.text = resources.getString(R.string.flash_off)
                }
            }

            zoomNumberTexts.addAll(
                llZoomLevelNumbers.children.toList().filterIsInstance<TextView>()
            )

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


    private fun setZoom(magnification: Float){
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
                captureCallback,
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
            Collections.min(bigEnough, CompareSizesByArea())
        } else if (notBigEnough.size > 0) {
            Collections.max(notBigEnough, CompareSizesByArea())
        } else {
            l.e("Couldn't find any suitable preview size")
            choices[0]
        }
    }


    private val readerListener = ImageReader.OnImageAvailableListener {
        try {
            image = it.acquireLatestImage()
            //image = it.acquireNextImage()
            val buffer: ByteBuffer = image!!.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            localBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            l.d("temp bitmap width:${localBitmap?.width}, height:${localBitmap?.height}")
            //val bitmap = cropBitmap(mCropRegion, tempBitmap)

            localBitmap?.let{ bitmap->
                showSaveImageDialog(bitmap)
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            image?.close()
        }
    }


    private fun showSaveImageDialog(bitmap: Bitmap){
        if(imageFileSaveDialog ==null){
            imageFileSaveDialog = ImageFileSaveDialog(context, bitmap){
                when(it){
                    0 -> { // save
                        saveImage()
                    }
                    1 -> { // cancel
                        localBitmap = null
                        callbackCanceledSavePhoto?.let { callback ->
                            callback()
                        }
                    }
                }
            }
        }

        imageFileSaveDialog?.setOnDismissListener {
            imageFileSaveDialog = null
            bitmap.recycle()
        }

        imageFileSaveDialog?.let{
            if(!it.isShowing)
                it.show()
        }
    }


    private fun saveImage(){
        val stream = ByteArrayOutputStream()
        if (localBitmap != null) {
            //localBitmap = cropBitmap(localBitmap!!)
            l.d("local bitmap width : ${localBitmap?.width}, height : ${localBitmap?.height}")
            localBitmap!!.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            currentPictureData = stream.toByteArray()
            l.d("current picture data size : ${currentPictureData?.size}")

            onLaunchDictation()
        }
    }


    private fun onLaunchDictation(){

        fileName = getNowDate() + ".jpg" //temp

        var outStream: FileOutputStream?=null

        var uri:String = ""

        l.e("onLaunch file dir : $fileDir")

        // Write to SD Card
        try {
            if(fileDir.isEmpty()){
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
                    fileDir = defaultFileSaveDirOn11
                }else {
                    fileDir = defaultFileSaveDir
                }
            }

            l.d("file dir : $fileDir")

            val dir = File(fileDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            if(fileName.trim().isEmpty()){
                fileName = String.format("%d", System.currentTimeMillis())
            }
            val outFile = File(dir, fileName)
            uri = outFile.path
            outStream = FileOutputStream(outFile)
            outStream.write(currentPictureData)
            outStream.flush()
            outStream.close()
            l.d("onPictureTaken - wrote bytes: " + currentPictureData?.size + " to " + outFile.absolutePath)
            refreshGallery(outFile)
            currentPictureData = null


        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            callbackCompleteSavePhoto?.let {
                it(uri)
            }
        }
    }

    private fun refreshGallery(file: File){
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaScanIntent.data = Uri.fromFile(file)
        context.sendBroadcast(mediaScanIntent)
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
            if (cameraId == null){
                l.e("cameraId is null")
                jobCheckCameraIdNotNull = defaultScope.launch {
                    delay(500)
                    setUpCameraOutputs(width, height)
                }
                return
            }
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
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
            largest = Collections.max(sizes, CompareSizesByArea())
//            imageReader = ImageReader.newInstance(largest.width, largest.height,
//                    ImageFormat.JPEG,  /*maxImages*/3)

            // 사진 해상도 설정
            imageReader = ImageReader.newInstance(
                3840, 2160,
                ImageFormat.JPEG,  /*maxImages*/3
            )

            imageReader!!.setOnImageAvailableListener(readerListener, null)

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

            configureTransform(width, height)
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

    @SuppressLint("MissingPermission")
    // openCamera() 메서드는 TextureListener 에서 SurfaceTexture 가 사용 가능하다고 판단했을 시 실행된다
    private fun openCamera(width: Int, height: Int) {
        l.e("openCamera() : openCamera()메서드가 호출되었음")

        setUpCameraOutputs(width, height)


        // 카메라의 정보를 가져와서 cameraId 와 imageDimension 에 값을 할당하고, 카메라를 열어야 하기 때문에
        // CameraManager 객체를 가져온다
        if(cameraId.isNullOrEmpty()){
            return
        }
        jobCheckCameraIdNotNull?.cancel()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        l.e("camera id : ${cameraId}")

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            l.e(e.toString())
            longToast("camera init error")
            //removeAllViews()
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
            createCameraPreviewSession()
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


    private val captureCallback: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (state) {
                STATE_PREVIEW -> {
                }
                STATE_WAITING_LOCK -> {
                    l.d("STATE_WAITING_LOCK")
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    Log.i("cameraFocus", "" + afState)
                    if (afState == null) {
                        captureStillPicture()
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_INACTIVE == afState /*add this*/) {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        ) {
                            state = STATE_PICTURE_TAKEN
                            captureStillPicture()
                        } else {
                            runPrecaptureSequence()
                        }
                    }
                }
                STATE_WAITING_PRECAPTURE -> {
                    l.d("STATE_WAITING_PRECAPTURE")
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    l.d("STATE_WAITING_NON_PRECAPTURE")
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }
    }



    private fun captureStillPicture() {
        try {
            if (null == cameraDevice) {
                return
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            // Orientation
            val rotation = (context as AppCompatActivity).windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))

            //Zoom
            if (zoom != null) {
                captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom)
            }
            val captureCallback: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    unlockFocus()
                }
            }
            captureSession!!.stopRepeating()
            captureSession!!.abortCaptures()
            captureSession!!.capture(captureBuilder.build(), captureCallback, null)
        } catch (e: CameraAccessException) {
            l.e(e.toString())
        }
    }

    private fun lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            previewRequestBuilder!!.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            // Tell #captureCallback to wait for the lock.
            state = STATE_WAITING_LOCK
            captureSession!!.capture(
                previewRequestBuilder!!.build(), captureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            l.e(e.toString())
        }
    }

    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            previewRequestBuilder!!.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            )
            captureSession!!.capture(
                previewRequestBuilder!!.build(), captureCallback,
                backgroundHandler
            )
            // After this, the camera will go back to the normal state of preview.
            state = STATE_PREVIEW
            //resume Zoom effect after taking a picture
            previewRequestBuilder!!.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            if (zoom != null) previewRequestBuilder!!.set(CaptureRequest.SCALER_CROP_REGION, zoom)
            captureSession!!.setRepeatingRequest(
                previewRequestBuilder!!.build(), captureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            l.e(e.toString())
        }
    }

    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            previewRequestBuilder!!.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            // Tell #captureCallback to wait for the precapture sequence to be set.
            state = STATE_WAITING_PRECAPTURE
            captureSession!!.capture(
                previewRequestBuilder!!.build(), captureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            l.e(e.toString())
        }
    }


    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2 Background")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
        l.d("startbackgroundthread complete")
    }

    @SuppressLint("NewApi")
    private fun stopBackgroundThread() {
        if (backgroundThread == null) {
            backgroundHandler = null
            return
        }
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            l.e(e.toString())
        }
        l.d("stopbackgroundthread complete")
    }


    // openCamera() 에 넘겨주는 stateCallback 에서 카메라가 제대로 연결되었으면
    // createCameraPreviewSession() 메서드를 호출해서 카메라 미리보기를 만들어준다
    private fun createCameraPreviewSession() {
        try {

            // 캡쳐세션을 만들기 전에 프리뷰를 위한 Surface 를 준비한다
            // 레이아웃에 선언된 textureView 로부터 surfaceTexture 를 얻을 수 있다
            val texture = binding.textureView.surfaceTexture

            // 미리보기를 위한 Surface 기본 버퍼의 크기는 카메라 미리보기크기로 구성
            l.d("image dimension width:${previewSize?.width}, height:${previewSize?.height}")
            //texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            // 미리보기 해상도 설정
            texture?.setDefaultBufferSize(3840, 2160)

            // 미리보기를 시작하기 위해 필요한 출력표면인 surface
            val surface = Surface(texture)

            // 미리보기 화면을 요청하는 RequestBuilder 를 만들어준다.
            // 이 요청은 위에서 만든 surface 를 타겟으로 한다
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            //captureRequestBuilder!!.set(CaptureRequest.SCALER_CROP_REGION, zoom)
            //captureRequestBuilder?.set(CaptureRequest.JPEG_ORIENTATION, cameraCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION))
            previewRequestBuilder?.addTarget(surface)


            // 위에서 만든 surface 에 미리보기를 보여주기 위해 createCaptureSession() 메서드를 시작한다
            // createCaptureSession 의 콜백메서드를 통해 onConfigured 상태가 확인되면
            // CameraCaptureSession 을 통해 미리보기를 보여주기 시작한다
            cameraDevice!!.createCaptureSession(
                listOf(surface, imageReader?.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        l.d("Configuration change")
                    }

                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            // 카메라가 이미 닫혀있는경우, 열려있지 않은 경우
                            return
                        }
                        // session 이 준비가 완료되면, 미리보기를 화면에 뿌려주기 시작한다
                        captureSession = session

                        previewRequestBuilder?.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )

//                            captureRequestBuilder?.set(
//                                    CaptureRequest.CONTROL_AF_MODE,
//                                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
//                            )
                        try {
                            captureSession?.setRepeatingRequest(
                                previewRequestBuilder!!.build(),
                                null,
                                null
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                },
                null
            )


        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun getOrientation(rotation: Int): Int {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS[rotation] + sensorOrientation + 270) % 360
    }


    private fun cropBitmap(rect: Rect, bitmap: Bitmap):Bitmap{
        l.d("top:${rect.top}, bottom:${rect.bottom}, left:${rect.left}, right:${rect.right}")
        l.d("bitmap width:${bitmap.width}, bitmap height:${bitmap.height}, rect width:${rect.width()}, height:${rect.height()}")
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }


    // 카메라 객체를 시스템에 반환하는 메서드
    // 카메라는 싱글톤 객체이므로 사용이 끝나면 무조건 시스템에 반환해줘야한다
    // 그래야 다른 앱이 카메라를 사용할 수 있다
    private fun closeCamera() {
        l.d("close camera")
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession!!.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
            if (null != imageReader) {
                imageReader!!.close()
                imageReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }




    private fun manualFocus(){
        //first stop the existing repeating request
        try {
            captureSession!!.stopRepeating()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        //cancel any existing AF trigger (repeated touches, etc.)
        previewRequestBuilder!!.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
        )
        previewRequestBuilder!!.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_OFF
        )
        try{
            l.d("first preview request builder")
            previewRequestBuilder!!.set(CaptureRequest.SCALER_CROP_REGION, zoom)
            captureSession!!.setRepeatingRequest(
                previewRequestBuilder!!.build(),
                captureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        previewRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        previewRequestBuilder!!.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_AUTO
        )
        previewRequestBuilder!!.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CameraMetadata.CONTROL_AF_TRIGGER_START
        )

        //then we ask for a single request (not repeating!)
        try {
            l.d("second!! preview request builder")
            previewRequestBuilder!!.set(CaptureRequest.SCALER_CROP_REGION, zoom)
            captureSession!!.setRepeatingRequest(
                previewRequestBuilder!!.build(),
                captureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }



}