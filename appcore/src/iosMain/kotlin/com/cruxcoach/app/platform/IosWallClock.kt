package com.cruxcoach.app.platform

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

class IosWallClock : WallClock {
    override fun epochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()
    override fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
}
