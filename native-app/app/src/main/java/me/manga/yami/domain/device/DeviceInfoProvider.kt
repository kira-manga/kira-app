package me.manga.yamiapk.domain.device

interface DeviceInfoProvider {
    /**
     * Returns a Map of device‐specific metadata, e.g.
     * manufacturer, model, osVersion, appVersion.
     */
    fun getDeviceMetadata(): Map<String, Any>
}