/*
 * Copyright (C) MohistMC.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mohistmc.youer.eventhandler;

import com.mohistmc.youer.Youer;
import com.mohistmc.youer.eventhandler.dispatcher.BlockEventDispatcher;
import com.mohistmc.youer.eventhandler.dispatcher.EntityEventDispatcher;
import com.mohistmc.youer.eventhandler.dispatcher.PlayerEventDispatcher;
import net.neoforged.neoforge.common.NeoForge;

public class EventDispatcherRegistry {

    public static void init() {
        NeoForge.EVENT_BUS.register(new PlayerEventDispatcher());
        // WorldEventDispatcher removed: Paper fires WorldLoadEvent itself; re-firing it from LevelEvent.Load would deliver it twice
        // NeoForge.EVENT_BUS.register(new ItemEventDispatcher());
        NeoForge.EVENT_BUS.register(new EntityEventDispatcher());
        NeoForge.EVENT_BUS.register(new BlockEventDispatcher());
        Youer.LOGGER.info("EventDispatcherRegistry initialized");
    }
}
