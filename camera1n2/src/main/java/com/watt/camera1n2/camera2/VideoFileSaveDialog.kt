package com.watt.camera1n2.camera2

import android.app.Dialog
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.View
import android.widget.MediaController
import android.widget.TextView
import com.watt.camera1n2.R
import com.watt.camera1n2.databinding.DialogVideoFileSaveBinding
import com.watt.camera1n2.l
import kotlinx.coroutines.*

/**
 * Created by khm on 2021-10-19.
 */

class VideoFileSaveDialog(context: Context): Dialog(context, R.style.DialogCustomTheme) {
    private val binding = DialogVideoFileSaveBinding.inflate(layoutInflater)

    private var jobProgressBar: Job? = null
    private var duration:Int = -1

    init {
        setContentView(binding.root)

        setVolumeButton()

        val mediaController = MediaController(context)
        mediaController.setAnchorView(binding.videoView)

        mediaController.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)


        binding.run{
            videoView.setMediaController(mediaController)

            videoView.setOnPreparedListener{
                binding.videoView.seekTo(1)
                duration = it.duration
                l.d("setOnPreparedListner duration : ${it.duration}")

                binding.tvTotalTime.text = convertPastTimeMillsToHHMMSSColon(duration.toLong()/1000)
            }

            videoView.setOnErrorListener { mediaPlayer, i, i2 ->
                Log.d("VideoFileSaveDialog", "setOnErrorListener : $i")
                Log.d("VideoFileSaveDialog", "setOnErrorListener : $i2")
                true
            }

            videoView.setOnCompletionListener {
                progressBar.progress = 1000
                jobProgressBar?.cancel()
                showPlayBtn()
                setButtonOriginColor()
            }


            playBtn.setOnClickListener {
                if(binding.videoView.isPlaying){
                    return@setOnClickListener
                }
                binding.stopBtn.visibility = View.VISIBLE
                binding.videoView.seekTo(0)
                binding.videoView.start()
                startProgressBar()
                hideBlur()
                setButtonRed(binding.playBtn)
            }

            stopBtn.setOnClickListener {
                binding.videoView.pause()
                binding.videoView.seekTo(0)
                binding.progressBar.progress = 0
                binding.tvPastTime.text = "00:00"
                stopProgressBar()
                showStopBtn()
                setButtonRed(binding.stopBtn)
            }
        }


    }


    private fun stopProgress(){
        binding.videoView.pause()
        binding.videoView.seekTo(0)
        binding.progressBar.progress = 0
        binding.tvPastTime.text = "00:00"
        stopProgressBar()
        showPlayBtn()
        setButtonOriginColor()
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


    fun showDialog(filePath:String, isSelectSave:(Boolean)->Unit){
        binding.videoView.setVideoPath(filePath)
        binding.videoView.keepScreenOn = true

        binding.videoView.setOnCompletionListener {
            binding.videoView.seekTo(1)
        }

        if(isShowing)
            dismiss()

        binding.tvCancel.setOnClickListener {
            stopProgress()
            isSelectSave(false)
            dismiss()
        }

        binding.tvSave.setOnClickListener {
            stopProgress()
            isSelectSave(true)
            dismiss()
        }

        show()

    }


    private fun startProgressBar(){
        if(jobProgressBar == null || !jobProgressBar?.isActive!!){
            jobProgressBar = CoroutineScope(Dispatchers.Default).launch {
                while (isActive){
                    delay(300)
                    if(duration > 0){
                        val current = binding.videoView.currentPosition
                        val percent:Double = (current.toDouble() / duration.toDouble()) * 1000.0
                        Log.d("VideoFileSaveDialog",  "duration : ${binding.videoView.duration.toString()}")
                        Log.d("VideoFileSaveDialog",  "current position : ${binding.videoView.currentPosition.toString()}")
                        Log.d("VideoFileSaveDialog","percent : $percent")
                        binding.progressBar.progress = percent.toInt()
                        if(percent > 995){
                            Log.d("getCurrentPosition","percent > 995")
                            withContext(Dispatchers.Main){
                                stopProgress()
                            }
                            jobProgressBar?.cancel()
                        }
                        withContext(Dispatchers.Main){
                            binding.tvPastTime.text = convertPastTimeMillsToHHMMSSColon(binding.videoView.currentPosition.toLong()/1000)
                        }
                        if(binding.videoView.currentPosition == 1){
                            withContext(Dispatchers.Main){
                                stopProgress()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopProgressBar(){
        jobProgressBar?.cancel()
    }





    private fun showPlayBtn(){
        binding.stopBtn.visibility = View.GONE
        binding.llBlur.visibility = View.VISIBLE
        binding.iconPlay.visibility = View.VISIBLE
        binding.iconPause.visibility = View.GONE
        binding.iconStop.visibility = View.GONE
    }

    private fun hideBlur(){
        binding.llBlur.visibility = View.GONE
    }

    private fun showPauseBtn(){
        binding.llBlur.visibility = View.VISIBLE
        binding.iconPlay.visibility = View.GONE
        binding.iconPause.visibility = View.VISIBLE
        binding.iconStop.visibility = View.GONE
    }

    private fun showStopBtn(){
        binding.llBlur.visibility = View.VISIBLE
        binding.iconPlay.visibility = View.GONE
        binding.iconPause.visibility = View.GONE
        binding.iconStop.visibility = View.VISIBLE
    }

    private fun setButtonRed(tv: TextView){

        when(tv.text){
            context.resources.getString(R.string.video_play)->{
                binding.playBtn.setBackgroundResource(R.drawable.btn_bg_round_line)
                binding.stopBtn.setBackgroundResource(R.drawable.btn_bg_round)
            }
            context.resources.getString(R.string.video_stop)->{
                binding.playBtn.setBackgroundResource(R.drawable.btn_bg_round)
                binding.stopBtn.setBackgroundResource(R.drawable.btn_bg_round_line)
            }
        }
    }

    private fun setButtonOriginColor(){
        binding.playBtn.setBackgroundResource(R.drawable.btn_bg_round)
        binding.stopBtn.setBackgroundResource(R.drawable.btn_bg_round)
    }

    private fun setVolumeButton(){
        binding.volume1.setOnClickListener {
            setStreamVolume(1)
        }
        binding.volume2.setOnClickListener {
            setStreamVolume(2)
        }
        binding.volume3.setOnClickListener {
            setStreamVolume(3)
        }
        binding.volume4.setOnClickListener {
            setStreamVolume(5)
        }
        binding.volume5.setOnClickListener {
            setStreamVolume(7)
        }
        binding.volume6.setOnClickListener {
            setStreamVolume(9)
        }
        binding.volume7.setOnClickListener {
            setStreamVolume(11)
        }
        binding.volume8.setOnClickListener {
            setStreamVolume(13)
        }
        binding.volume9.setOnClickListener {
            setStreamVolume(14)
        }
        binding.volume10.setOnClickListener {
            setStreamVolume(15)
        }
        binding.mute.setOnClickListener {
            setStreamVolume(0)
        }
    }


    private fun setStreamVolume(volume:Int){
        val am: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if(volume == 0){
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_PLAY_SOUND)
        }else{
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, AudioManager.FLAG_PLAY_SOUND)
        }
    }
}