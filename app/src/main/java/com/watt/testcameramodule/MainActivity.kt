package com.watt.testcameramodule

import android.Manifest
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import com.watt.camera1n2.camera1.Camera1Photo
import com.watt.camera1n2.camera2.Camera2Photo
import com.watt.camera1n2.databinding.Camera2PhotoBinding
import com.watt.camera1n2.l
import com.watt.testcameramodule.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private val binding by lazy{ ActivityMainBinding.inflate(layoutInflater)}

    private var saveUri = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        checkPermissions()
        fullScreenMode()

//        val camera1Photo: Camera1Photo = findViewById(R.id.camera1photo)
//        camera1Photo.setSaveDir("${Environment.getExternalStorageDirectory().absolutePath}/Camera1Photo/")

        binding.run {
            camera2photo.setSaveDir("${getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.absolutePath + "/"}Camera2Photo/")

            camera2photo.callbackCompleteSaveVideo = {
                l.d("complete save photo uri : $it")
                saveUri = it
            }

            tvLoadImage.setOnClickListener {
                l.d("onClick tvLoadImage")
                val file = File(saveUri)
                if(file.exists()){
                    l.d("file exists")
                }else{
                    l.d("file not found")
                }
            }
        }


    }


    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TedPermission.with(this)
                .setPermissionListener(permissionListener)
                .setPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
                .check()
        }else{
            TedPermission.with(this)
                .setPermissionListener(permissionListener)
                .setPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA
                )
                .check()
        }
    }

    private fun fullScreenMode(){
        supportActionBar?.hide()
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }


    private var permissionListener: PermissionListener = object : PermissionListener {
        override fun onPermissionGranted() {
            Log.d("MainActivity", "onPermissionGranted:::::::: ")

        }

        override fun onPermissionDenied(deniedPermissions: ArrayList<String?>?) {
            Log.d("MainActivity", "onPermissionDenied:::::::: ")
            Toast.makeText(this@MainActivity, "권한이 허용되지 않으면 앱을 실행 할 수 없습니다.", Toast.LENGTH_SHORT)
                .show()
            finish()
        }
    }
}