package com.mightystore

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint

object JarIsInWrongFolder : PreLaunchEntrypoint {
    override fun onPreLaunch() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) {
            throw JarIsInWrongFolderException()
        }
    }
}
class JarIsInWrongFolderException : RuntimeException(
    """
        !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
           ! MightyStore is an Addon of Cobalt NOT a standalone Mod !
        
                    MightyStore is in the wrong folder!
                   Move it into './config/cobalt/addons'!
                    
        If you're still having trouble contact @Bloxfriend590 on Discord!
                https://discord.com/users/924380186609328199
        
        Or you can ask about your issue in the Official Cobalt Discord!
                       https://discord.gg/GAhS8UfDyy
        !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"""
)
// From my testing I've found that PreLaunch delivers a more readable error than the more client init but it doesn't add the super cool tabs i added :(