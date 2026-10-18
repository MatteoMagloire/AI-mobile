package com.matteomagloire.aimobile.update

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sha256Url: String?,
    val pageUrl: String,
)
