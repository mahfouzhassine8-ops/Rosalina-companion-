package com.rosalina.unified

internal enum class ShellSection {
    COMPANION, PHOTO, ANIMATE, SETTINGS_MODELS;
    companion object {
        fun parse(saved:String?,legacy:String="Chat"):ShellSection =
            entries.firstOrNull{it.name==saved} ?: when(legacy){
                "Create","Edit"->PHOTO
                "Animate"->ANIMATE
                else->COMPANION
            }
    }
}
internal enum class CompanionMode {
    CHAT,LIVE;
    companion object { fun parse(value:String?):CompanionMode=entries.firstOrNull{it.name==value} ?:CHAT }
}
internal enum class PhotoMode {
    CREATE,EDIT;
    companion object { fun parse(value:String?):PhotoMode=entries.firstOrNull{it.name==value} ?:CREATE }
}
