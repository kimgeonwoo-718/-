package com.google.mlkit.common.model

class DownloadConditions {
    class Builder {
        fun requireWifi(): Builder = this
        fun build(): DownloadConditions = DownloadConditions()
    }
}
