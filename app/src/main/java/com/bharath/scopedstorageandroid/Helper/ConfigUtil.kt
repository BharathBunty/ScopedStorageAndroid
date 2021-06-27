package com.bharath.scopedstorageandroid.Helper

import android.os.Build

inline fun <T> checkSdk29AndUp(onSdk29: () -> T): T?{
    return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
        onSdk29()
    }else null
}