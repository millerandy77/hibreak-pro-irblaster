package dev.ilyaask.openir.codec

// Generated from re/branddb_symbols.json (parsed from libet_jni_ir_tools.so).
// vaddr == file offset within the .so's first PT_LOAD (covers 0..0x6521b22), so these
// addresses index directly into a byte buffer of the proprietary .rodata. Strides are
// recovered for the device types whose GetBrandCount case was mechanically extracted
// (re/branddb_full.json); stride=0 means "not yet recovered — see docs §9.2".

data class Table(val vaddr: Int, val size: Int, val stride: Int = 0)

val INFO_TABLES: Map<String, Table> = mapOf(
    "TV_info" to Table(0x04fbc358, 9460912, stride=8648),
    "g_remote_arc_2_info" to Table(0x06053410, 18672, stride=0),
    "g_remote_arc_3_info" to Table(0x06057d00, 1792200, stride=0),
    "g_remote_arc_info" to Table(0x05fa87b8, 699480, stride=1608),
    "remote_Audio_2_info" to Table(0x064ec80c, 80, stride=0),
    "remote_Audio_info" to Table(0x0647150c, 504576, stride=1752),
    "remote_IPTV_2_info" to Table(0x062dc6e4, 824, stride=0),
    "remote_IPTV_info" to Table(0x062baea4, 137280, stride=960),
    "remote_Lamp_2_info" to Table(0x06521534, 696, stride=0),
    "remote_Lamp_info" to Table(0x0651f20c, 9000, stride=360),
    "remote_SLR_2_info" to Table(0x062dcfac, 48, stride=0),
    "remote_SLR_info" to Table(0x062dcc9c, 784, stride=56),
    "remote_Water_Heater_2_info" to Table(0x062e88cc, 48, stride=0),
    "remote_Water_Heater_info" to Table(0x062e84a4, 1064, stride=76),
    "remote_air_purifier_2_info" to Table(0x062e5880, 48, stride=0),
    "remote_air_purifier_info" to Table(0x062e5330, 1360, stride=80),
    "remote_dvd_2_info" to Table(0x024ee3d0, 2160, stride=0),
    "remote_dvd_info" to Table(0x021e0468, 3202920, stride=5208),
    "remote_fan_2_info" to Table(0x0021bc40, 792, stride=0),
    "remote_fan_info" to Table(0x001fab38, 135432, stride=836),
    "remote_pjt_2_info" to Table(0x000b1c54, 336, stride=0),
    "remote_pjt_info" to Table(0x0009f114, 76608, stride=448),
    "remote_robotcleaner_2_info" to Table(0x064f2988, 152, stride=0),
    "remote_robotcleaner_info" to Table(0x064f2748, 576, stride=48),
    "remote_stb_2_info" to Table(0x01bd3470, 2624, stride=0),
    "remote_stb_info" to Table(0x017e3fb0, 4125888, stride=2204),
    "remote_tv_info" to Table(0x058c2008, 30704, stride=0),
)
val DATA_TABLES: Map<String, Table> = mapOf(
    "arc_table" to Table(0x058c97f8, 7204800),
    "dvd_data_table" to Table(0x01bd3eb0, 6342070),
    "remote_Audio_table" to Table(0x062e88fc, 1608717),
    "remote_IPTV_table" to Table(0x0620d5c8, 710875),
    "remote_Lamp_table" to Table(0x064f2a20, 182250),
    "remote_SLR_table" to Table(0x062dca1c, 637),
    "remote_Water_Heater_table" to Table(0x062e58b0, 11250),
    "remote_air_purifier_table" to Table(0x062dcfdc, 33620),
    "remote_robotcleaner_table" to Table(0x064ec85c, 24299),
    "stb_data_table" to Table(0x0021bf58, 22839381),
    "stb_fan_table" to Table(0x000b1da4, 1346961),
    "stb_pjt_table" to Table(0x000073f0, 621859),
    "tv_table" to Table(0x024eec40, 44881686),
)
