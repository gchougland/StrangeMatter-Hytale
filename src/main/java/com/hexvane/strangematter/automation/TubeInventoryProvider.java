package com.hexvane.strangematter.automation;

import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3i;
import java.util.List;
import java.util.UUID;

public interface TubeInventoryProvider {
    List<TubePort> ports(World world,Vector3i origin);
    default boolean mayAccess(World world,Vector3i origin,UUID owner){return true;}
}
