package com.example.filesystem

import android.widget.PopupWindow

interface MainReceiver {
    fun getCurrentAction(): String?
    fun getActionState(action: String): HashMap<String, String>
    fun getActionState(action: String, key: String): String?
    fun setCurrentAction(action: String?)
    fun setActionState(action: String, key: String, value: String?)
    fun getMimeType(key: String): String
    //fun getPopup(key: String): PopupWindow
}