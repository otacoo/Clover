/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo https://github.com/otacoo/Clover/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.otacoo.chan.ui.theme;

import org.otacoo.chan.core.settings.ChanSettings;

import java.time.MonthDay;

// Holiday themes only show in the picker during their season
// (or when forced from the developer options).
public final class SeasonalThemes {
    public static final String HALLOWEEN = "pumpkin_spice";
    public static final String CHRISTMAS = "jingle_bells";

    private static final MonthDay HALLOWEEN_START = MonthDay.of(10, 25);
    private static final MonthDay HALLOWEEN_END = MonthDay.of(11, 5);
    private static final MonthDay CHRISTMAS_START = MonthDay.of(12, 5);
    private static final MonthDay CHRISTMAS_END = MonthDay.of(12, 31);

    private SeasonalThemes() {
    }

    public static boolean isAvailable(String themeName) {
        if (HALLOWEEN.equals(themeName)) {
            return isHalloweenSeason();
        }
        if (CHRISTMAS.equals(themeName)) {
            return isChristmasSeason();
        }
        return true;
    }

    public static boolean isHalloweenSeason() {
        return ChanSettings.forceHalloweenTheme.get() || inRange(HALLOWEEN_START, HALLOWEEN_END);
    }

    public static boolean isChristmasSeason() {
        return ChanSettings.forceChristmasTheme.get() || inRange(CHRISTMAS_START, CHRISTMAS_END);
    }

    private static boolean inRange(MonthDay start, MonthDay end) {
        MonthDay now = MonthDay.now();
        return !now.isBefore(start) && !now.isAfter(end);
    }
}
