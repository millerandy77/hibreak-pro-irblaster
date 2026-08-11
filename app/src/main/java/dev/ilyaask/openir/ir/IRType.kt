package dev.ilyaask.openir.ir

/**
 * Device-type codes and per-class key catalog, ported from the original app's
 * `et.song.remotestar.hxd.ir.IRType`. A key code is `deviceType | index` (low byte).
 */
object IRType {
    const val TV     = 0x2000
    const val IPTV   = 0x2100
    const val DC     = 0x2300
    const val BOX    = 0x2500
    const val AP     = 0x2700
    const val AUDIO  = 0x2900
    const val POWER  = 0x2B00
    const val SLR    = 0x2D00
    const val HW     = 0x2F00
    const val ROBOT  = 0x3100
    const val STB    = 0x4000
    const val DVD    = 0x6000
    const val FANS   = 0x8000
    const val PJT    = 0xA000
    const val AIR    = 0xC000
    const val LIGHT  = 0xE000
    const val CUSTOM = 0xFE000000.toInt()

    /** Learn-mode "select" codes used by ActivityMain.StudyTask. */
    const val LEARN_SELECT_SHORT = 0
    const val LEARN_SELECT_LONG  = 1

    /** Magic 3-byte app-level commands that the BLE transport turns into 4-byte handshakes. */
    val CMD_LEARN_SHORT = byteArrayOf(0x30, 0x10, 0x40)
    val CMD_LEARN_LONG  = byteArrayOf(0x30, 0x20, 0x50)
}

enum class DeviceClass(
    val code: Int,
    val display: String,
    val keys: List<KeyDef>,
) {
    TV(IRType.TV, "TV", listOf(
        KeyDef("Power", IRType.TV or 0x0B),
        KeyDef("Menu", IRType.TV or 0x05),
        KeyDef("Vol+", IRType.TV or 0x09),
        KeyDef("Vol-", IRType.TV or 0x01),
        KeyDef("Ch+",  IRType.TV or 0x03),
        KeyDef("Ch-",  IRType.TV or 0x07),
        KeyDef("Mute", IRType.TV or 0x0D),
        KeyDef("Up",   IRType.TV or 0x2B),
        KeyDef("Down", IRType.TV or 0x31),
        KeyDef("Left", IRType.TV or 0x2D),
        KeyDef("Right",IRType.TV or 0x2F),
        KeyDef("OK",   IRType.TV or 0x29),
        KeyDef("Back", IRType.TV or 0x17),
        KeyDef("Home", IRType.TV or 0x33),
    )),
    STB(IRType.STB, "Set-top box", listOf(
        KeyDef("Power", IRType.STB or 0x15),
        KeyDef("Menu", IRType.STB or 0x2D),
        KeyDef("OK",   IRType.STB or 0x1F),
        KeyDef("Up",   IRType.STB or 0x1B),
        KeyDef("Down", IRType.STB or 0x23),
        KeyDef("Left", IRType.STB or 0x1D),
        KeyDef("Right",IRType.STB or 0x21),
        KeyDef("Back", IRType.STB or 0x19),
        KeyDef("Guide",IRType.STB or 0x15),
        KeyDef("Ch+",  IRType.STB or 0x29),
        KeyDef("Ch-",  IRType.STB or 0x2B),
        KeyDef("Vol+", IRType.STB or 0x25),
        KeyDef("Vol-", IRType.STB or 0x27),
    )),
    AIR(IRType.AIR, "Air conditioner", listOf(
        KeyDef("Power", IRType.AIR or 0x01),
        KeyDef("Mode",  IRType.AIR or 0x03),
        KeyDef("Temp+", IRType.AIR or 0x0B),
        KeyDef("Temp-", IRType.AIR or 0x0D),
        KeyDef("Fan",   IRType.AIR or 0x05),
        KeyDef("Swing", IRType.AIR or 0x07),
        KeyDef("Cool",  IRType.AIR or 0x17),
        KeyDef("Heat",  IRType.AIR or 0x11),
        KeyDef("Sleep", IRType.AIR or 0x0F),
    )),
    FANS(IRType.FANS, "Fan", listOf(
        KeyDef("Power", IRType.FANS or 0x01),
        KeyDef("Speed", IRType.FANS or 0x03),
        KeyDef("Mode",  IRType.FANS or 0x07),
        KeyDef("Swing", IRType.FANS or 0x05),
        KeyDef("Cool",  IRType.FANS or 0x23),
        KeyDef("Timer", IRType.FANS or 0x09),
        KeyDef("Sleep", IRType.FANS or 0x21),
    )),
    DVD(IRType.DVD, "DVD", listOf(
        KeyDef("Power", IRType.DVD or 0x0B),
        KeyDef("Play",  IRType.DVD or 0x11),
        KeyDef("Pause", IRType.DVD or 0x1D),
        KeyDef("Stop",  IRType.DVD or 0x17),
        KeyDef("Up",    IRType.DVD or 0x03),
        KeyDef("Down",  IRType.DVD or 0x17),
        KeyDef("Left",  IRType.DVD or 0x01),
        KeyDef("Right", IRType.DVD or 0x09),
        KeyDef("OK",    IRType.DVD or 0x05),
        KeyDef("Menu",  IRType.DVD or 0x1F),
    )),
    PJT(IRType.PJT, "Projector", listOf(
        KeyDef("On",   IRType.PJT or 0x01),
        KeyDef("Off",  IRType.PJT or 0x03),
        KeyDef("Menu", IRType.PJT or 0x13),
        KeyDef("OK",   IRType.PJT or 0x15),
        KeyDef("Up",   IRType.PJT or 0x17),
        KeyDef("Down", IRType.PJT or 0x1D),
        KeyDef("Left", IRType.PJT or 0x19),
        KeyDef("Right",IRType.PJT or 0x1B),
        KeyDef("Vol+", IRType.PJT or 0x21),
        KeyDef("Vol-", IRType.PJT or 0x23),
    )),
    LIGHT(IRType.LIGHT, "Light", listOf(
        KeyDef("On",   IRType.LIGHT or 0x01),
        KeyDef("Off",  IRType.LIGHT or 0x03),
        KeyDef("Bright+", IRType.LIGHT or 0x05),
        KeyDef("Bright-", IRType.LIGHT or 0x07),
        KeyDef("Mode", IRType.LIGHT or 0x09),
        KeyDef("Timer+", IRType.LIGHT or 0x0B),
        KeyDef("Timer-", IRType.LIGHT or 0x0D),
    )),
    AUDIO(IRType.AUDIO, "Audio", listOf(
        KeyDef("Power", IRType.AUDIO or 0x0B),
        KeyDef("Mute",  IRType.AUDIO or 0x0F),
        KeyDef("Vol+",  IRType.AUDIO or 0x0D),
        KeyDef("Vol-",  IRType.AUDIO or 0x11),
        KeyDef("Play",  IRType.AUDIO or 0x15),
        KeyDef("Pause", IRType.AUDIO or 0x1B),
        KeyDef("Next",  IRType.AUDIO or 0x19),
        KeyDef("Prev",  IRType.AUDIO or 0x1D),
    ));

    companion object {
        fun fromCode(code: Int): DeviceClass? = entries.firstOrNull { it.code == code }
    }
}

data class KeyDef(val name: String, val code: Int)
