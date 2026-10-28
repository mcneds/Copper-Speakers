package dev.mcneds.copperspeakers.bedrock;

import java.util.ArrayList;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.geyser.shaded.org.cloudburstmc.protocol.bedrock.packet.BlockPickRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;

final class BlockPickingTest {
    static void run() {
        var request = new BlockPickRequestPacket();
        request.setBlockPosition(Vector3i.from(216, 20, -20));
        var sent = new ArrayList<ServerboundPickItemFromBlockPacket>();
        for (int known : new int[]{-1, 0, 1, 35996})
            if (BlockPicking.forwardUnknown(known, 35997, request, sent::add))
                throw new AssertionError("Vanilla blocks and real air must retain Geyser's handler");
        if (!sent.isEmpty()) throw new AssertionError("A vanilla pick was forwarded twice");
        for (boolean includeData : new boolean[]{false, true}) {
            request.setAddUserData(includeData);
            if (!BlockPicking.forwardUnknown(35997, 35997, request, sent::add))
                throw new AssertionError("The first unrecognized modded state was discarded as air");
            var forwarded = sent.getLast();
            if (!forwarded.getPos().equals(request.getBlockPosition()) || forwarded.isIncludeData() != includeData)
                throw new AssertionError("Pick position or include-data request changed");
        }
        if (BlockPicking.forwardUnknown(35997, 0, request, sent::add))
            throw new AssertionError("An uninitialized registry must not intercept requests");
        System.out.println("PASS: modded block picks reach Java; vanilla and item-frame picks retain the original path");
    }
}
