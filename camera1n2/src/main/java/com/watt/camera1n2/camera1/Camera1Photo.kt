package com.watt.camera1n2.camera1

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.Camera
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.watt.camera1n2.ImageFileSaveDialog
import com.watt.camera1n2.R
import com.watt.camera1n2.databinding.Camera1PhotoBinding
import com.watt.camera1n2.l
import kotlinx.coroutines.*
import splitties.systemservices.layoutInflater
import splitties.systemservices.windowManager
import splitties.toast.toast
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.properties.Delegates

/**
 * Created by khm on 2021-09-28.
 */

class Camera1Photo : ConstraintLayout, LifecycleObserver{
    private lateinit var binding: Camera1PhotoBinding
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private val defaultFileSaveDir = "${Environment.getExternalStorageDirectory().absolutePath}/Camera1/"
    private val CameraSettingDir = "CameraSettingSharedPreference"
    private val CameraMenuHide = "CameraSettingMenuHide"

    private var camera: Camera?=null
    private var parameters: Camera.Parameters?=null
    private var preview: Preview?=null

    private var orientationEventListener: OrientationEventListener?=null
    private val CAMERA_FACING = Camera.CameraInfo.CAMERA_FACING_BACK

    private var imageFileSaveDialog: ImageFileSaveDialog?=null

    private var lastRotation = 0

    private var isFirst = true
    private var isFlashStatus = false

    private var fileName = ""
    private var fileDir:String = ""

    private var localBitmap: Bitmap?=null

    private val zoomNumberTexts = ArrayList<TextView>()

    private var zoomLevel:Int by Delegates.observable(1){ _,oldValue,newValue->
        if(oldValue >0)
            zoomNumberTexts[oldValue-1].setTextColor(Color.WHITE)
        if(newValue>0)
            zoomNumberTexts[newValue-1].setTextColor(context.getColor(R.color.text_yellow))

        when(newValue){
            2->{
                parameters?.setZoom(10)
            }
            3->{
                parameters?.setZoom(30)
            }
            4->{
                parameters?.setZoom(60)
            }
            5->{
                parameters?.setZoom(88)
            }
            else->{ // == 1
                parameters?.zoom = 0
            }
        }

        camera!!.parameters = parameters
        camera?.autoFocus { success, _ ->
            if (!success) toast(resources.getString(R.string.fail_focusing))
        }
    }


    private var hideMenu:Boolean by Delegates.observable(false){ _, _, newValue ->
        l.d("hideMenu : $newValue")
        if(newValue)
            hideMenu()
        else
            showMenu()

        context.getSharedPreferences(CameraSettingDir, 0).edit().putBoolean(
            CameraMenuHide, newValue).apply()
    }


    companion object{
        /**
         * @param activity
         * @param cameraId Camera.CameraInfo.CAMERA_FACING_FRONT,
         *                 Camera.CameraInfo.CAMERA_FACING_BACK
         * @param camera   Camera Orientation
         *                 reference by https://developer.android.com/reference/android/hardware/Camera.html
         */
        private fun setCameraDisplayOrientation(activity: Activity, cameraId: Int, camera: Camera):Int{
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)
            val rotation = activity.windowManager.defaultDisplay
                    .rotation
            var degrees = 0
            when (rotation) {
                Surface.ROTATION_0 -> degrees = 0
                Surface.ROTATION_90 -> degrees = 90
                Surface.ROTATION_180 -> degrees = 180
                Surface.ROTATION_270 -> degrees = 270
            }

            var result: Int
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360
                result = (360 - result) % 360 // compensate the mirror
            } else {  // back-facing
                result = (info.orientation - degrees + 360) % 360
            }

            return result
        }
    }


    // Callback Listeners
    var callbackOnClickBack:(()->Unit)? = null
    var callbackCompleteSavePhoto:((uri:String)->Unit)? = null
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


    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private fun onResume(){
        startCamera()
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private fun onPause(){
        l.d("onPause")
        //ioScope.cancel()

        if(camera != null){
            if(zoomLevel != 1){
                zoomLevel =1
            }
            camera?.stopPreview()
            preview?.setCamera(null)
            camera?.release()
            camera = null
            isFirst = false
        }

        binding.layout.removeView(preview)
        preview = null
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private fun onDestroy(){
        ioScope.cancel()

        if(camera != null){
            camera?.stopPreview()
            preview?.setCamera(null)
            camera?.release()
            camera = null
            isFirst = false
        }

        binding.layout.removeView(preview)
        preview = null
        orientationEventListener?.disable()
    }



    // Setting Values
    fun setSaveDir(filePath:String?){
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





    private fun initView(){
        //카메라가 사용되는 액티비티의 라이프사이클 수명주기 일치시킴
        if(context is AppCompatActivity){
            (context as AppCompatActivity).lifecycle.addObserver(this)
        }else{
            l.e("This camera1 library only works in activities.")
            return
        }

        binding = Camera1PhotoBinding.inflate(layoutInflater)
        addView(binding.root)

        binding.run{
            hideMenu = context.getSharedPreferences(CameraSettingDir, 0).getBoolean(CameraMenuHide, false)

            zoomNumberTexts.addAll(llZoomLevelNumbers.children.toList().filterIsInstance<TextView>())

            // for test
//            for(i in zoomNumberTexts.indices){
//                zoomNumberTexts[i].setOnClickListener {
//                    l.d("onClick zoomNumberText : $i")
//                    zoomLevel = i+1
//                }
//            }

            tvBack.setOnClickListener { _->
                callbackOnClickBack?.let{
                    it()
                }
            }

            tvCaptureBtn.setOnClickListener {
                setScreenShot()
            }

            tvFocus.setOnClickListener {
                camera?.autoFocus { success, _ ->
                    if (!success) toast(resources.getString(R.string.fail_focusing))
                }
            }

            tvFlash.setOnClickListener {
                if (!isFlashStatus) {
                    parameters?.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH)
                    camera!!.parameters = parameters
                    isFlashStatus = true
                    binding.tvFlashStatus.text = resources.getString(R.string.flash_on)
                } else {
                    parameters?.setFlashMode(Camera.Parameters.FLASH_MODE_OFF)
                    camera!!.parameters = parameters
                    isFlashStatus = false
                    binding.tvFlashStatus.setText(resources.getString(R.string.flash_off))
                }
            }

            tvMenu.setOnClickListener {
                hideMenu = !hideMenu
            }

            tvVideo.setOnClickListener {
                callbackOnClickVideo?.let{ callback->
                    callback()
                }
            }

        }

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


        orientationEventListener = object : OrientationEventListener(
                context,
                SensorManager.SENSOR_DELAY_UI
        ) {
            override fun onOrientationChanged(orientation: Int) {
                val display: Display = windowManager.defaultDisplay
                val rotation = display.rotation
                if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
                    if (rotation != lastRotation) {
                        lastRotation = rotation
                        Log.d("camera1","onOrientationChanged: :::::::::$rotation")
                        resetCam()
                    }
                }
            }
        }

        orientationEventListener?.let{
            if(it.canDetectOrientation()) it.enable()
        }


    }



    private fun startCamera(){
        l.d("startCamera::::::::::: ")
        if(preview == null){
            l.e("preview is null")
            preview =
                Preview(context, binding.surfaceView)
            preview?.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            )
            binding.layout.addView(preview)
            preview?.keepScreenOn = true
        }

        preview?.setCamera(null)
        if(camera != null){
            l.e("camera is not null")
            camera?.release()
            camera = null
        }

        l.d("before get number of cameras")
        val numCams = Camera.getNumberOfCameras()
        if(numCams > 0){
            try {
                camera =
                        Camera.open(CAMERA_FACING)
                // camera orientation
                camera?.setDisplayOrientation(
                        setCameraDisplayOrientation(
                                context as AppCompatActivity, CAMERA_FACING,
                                camera!!
                        )
                )
                // get Camera parameters
                val params = camera?.getParameters()

                val previewSizeList = params?.supportedPreviewSizes

                for(size in previewSizeList!!){
                    l.d("Noah pictureSize : " + size.width + "/" + size.height)
                }
                params.setRotation(
                        setCameraDisplayOrientation(
                                context as AppCompatActivity,
                                CAMERA_FACING,
                                camera!!
                        )
                )

                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                    params.setPreviewSize(1920,1080)
                }else{
                    params.setPictureSize(3840, 2160)
                }
                camera?.setParameters(params)
                camera?.startPreview()
                parameters = camera?.getParameters()
                if (isFirst) {
                    parameters?.setZoom(0)
                    camera?.setParameters(parameters)
                } else {
                    parameters?.setZoom(0)
                    camera?.setParameters(parameters)
                }
            } catch (ex: RuntimeException) {
                toast("camera_not_found " + ex.message.toString())
                l.d("camera_not_found " + ex.message.toString())
                return
            }

            preview?.setCamera(camera)

        }else{
            l.e("number of cameras == 0")
        }


    }


    private fun resetCam(){
        isFirst = false
        startCamera()
        l.d("resetCam::::::::::: ")
    }



    private fun setScreenShot() {
        camera!!.autoFocus { success, camera ->
            if (success) {
                camera.takePicture(shutterCallback, rawCallback, jpegCallback)
            } else {
                toast(resources.getString(R.string.fail_focusing))
            }
        }
    }

    private val shutterCallback = Camera.ShutterCallback {
        l.d("onShutter'd")
    }

    private val rawCallback = Camera.PictureCallback{ data, camera ->
        l.d("onPictureTaken - raw")
    }

    private val jpegCallback = Camera.PictureCallback{ data, camera ->
        l.d("onJpegCallback")
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)

        localBitmap = cropBitmap(bitmap)
        localBitmap?.let {
            l.d("localbitmap width : ${it.width}, height : ${it.height}")
            showSaveImageDialog(it)
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
                        callbackCanceledSavePhoto?.let { callback->
                            callback()
                        }
                        localBitmap = null
                        resetCam()
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

    var currentPictureData: ByteArray?=null

    private fun saveImage(){
        val orientation: Int =
                setCameraDisplayOrientation(
                        context as AppCompatActivity,
                        CAMERA_FACING, camera!!
                )

        val matrix = Matrix()
        matrix.postRotate(orientation.toFloat())
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

    private fun cropBitmap(bitmap:Bitmap):Bitmap{
        val originalWidth = bitmap.width
        l.d("bitmap width : $originalWidth, height : ${bitmap.height}")
        if(originalWidth != 4608){
            return bitmap
        }
        return Bitmap.createBitmap(bitmap, 384, 648, 3840, 2160)
    }



    private fun onLaunchDictation(){

        fileName = getNowDate() + ".jpg" //temp

        ioScope.launch {
            var outStream: FileOutputStream?=null

            var uri:String = ""
            // Write to SD Card
            try {
                if(fileDir.isEmpty()){
                    fileDir = defaultFileSaveDir
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
                // 카메라 초기화
                withContext(Dispatchers.Main){
                    callbackCompleteSavePhoto?.let{
                        it(uri)
                    }
                    resetCam()
                }
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


    private fun showMenu() {
        binding.run {
            llGoBack.visibility = View.VISIBLE
            llZoom.visibility = View.VISIBLE
            tvFocus.visibility = View.VISIBLE
            llFlash.visibility = View.VISIBLE
            tvMenu.text = resources.getString(R.string.hide_menu)
        }
    }

    private fun hideMenu() {
        binding.run {
            llGoBack.visibility = View.GONE
            llZoom.visibility = View.GONE
            tvFocus.visibility = View.GONE
            llFlash.visibility = View.GONE
            tvMenu.text = resources.getString(R.string.show_menu)
        }
    }





}