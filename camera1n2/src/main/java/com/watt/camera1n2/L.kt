package com.watt.camera1n2

import android.util.Log
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.LogStrategy
import com.orhanobut.logger.Logger
import com.orhanobut.logger.PrettyFormatStrategy


class l{
    companion object{
        private const val LOG_TAG = "logger"
        private var isDebug = true
        private var isInit = false

        fun hideLog(){
            isDebug = false
        }

        fun e(log:String?){
            if(isDebug){
                init()
                Logger.e(log?:"null")
            }
        }

        fun d(log:String?){
            if(isDebug){
                init()
                Logger.d(log?:"null")
            }
        }

        fun json(log:String?){
            if(isDebug){
                init()
                Logger.json(log?:"null")
            }
        }

        fun xml(log:String?){
            if(isDebug){
                init()
                Logger.xml(log?:"null")
            }
        }


        private fun init(){
            if(!isInit){
                val formatStrategy = PrettyFormatStrategy.newBuilder().logStrategy(LogStrategy{
                    priority, tag, message ->
                    var last = (10 + Math.random()).toInt()

                    fun randomKey():String{
                        var random = (10 + Math.random()).toInt()

                        if(random == last){
                            random = (random + 1)%10
                        }
                        last = random
                        return random.toString()
                    }
                    Log.println(priority, randomKey() + tag, message)
                }).tag(LOG_TAG).methodOffset(1).methodCount(2).build()

                Logger.addLogAdapter(AndroidLogAdapter(formatStrategy))
                isInit = true
            }
        }

    }
}
