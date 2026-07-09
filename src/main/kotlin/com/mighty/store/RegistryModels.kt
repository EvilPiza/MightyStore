package com.mighty.store

data class AddonRegistry(
    val schemaVersion: Int,
    val addons: List<RegistryAddon>
)

data class RegistryAddon(
    val id: String,
    val name: String,
    val author: String? = null,
    val description: String? = null,
    val repo: String? = null,
    val icon: String? = null,
    val latest: RegistryVersion?
)

data class RegistryVersion(
    val version: String,
    val cobaltVersion: String?,
    val cobaltSha256: String?,
    val downloadUrl: String,
    val sha256: String,
    val changelog: String? = null
)