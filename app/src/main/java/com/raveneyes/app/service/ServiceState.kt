package com.raveneyes.app.service

enum class ServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class ServiceStatus(
    val state: ServiceState,
    val faceDetected: Boolean = false,
    val eyesDetected: Boolean = false,
    val lastAction: String = "NONE",
    val lastActionAtMs: Long? = null,
    val errorMessage: String? = null
)