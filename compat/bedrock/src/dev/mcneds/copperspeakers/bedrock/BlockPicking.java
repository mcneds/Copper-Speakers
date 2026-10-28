package dev.mcneds.copperspeakers.bedrock;

import java.util.function.Consumer;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.shaded.org.cloudburstmc.protocol.bedrock.packet.BlockPickRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;

/** Let the Java server resolve blocks absent from Geyser's vanilla registry. */
final class BlockPicking {
    @SuppressWarnings("unchecked")
    static void install() {
        var delegate = (PacketTranslator<BlockPickRequestPacket>) Registries.BEDROCK_PACKET_TRANSLATORS.get(BlockPickRequestPacket.class);
        Registries.BEDROCK_PACKET_TRANSLATORS.register(BlockPickRequestPacket.class, new PacketTranslator<BlockPickRequestPacket>() {
            @Override public void translate(GeyserSession session, BlockPickRequestPacket packet) {
                if (session.protocolVersion() == 2225) {
                    var position = packet.getBlockPosition();
                    int javaId = session.getGeyser().getWorldManager().getBlockAt(session,
                            position.getX(), position.getY(), position.getZ());
                    // Geyser-Fabric reads the actual Polymer block here, not its
                    // vanilla carrier. BlockState.of maps an unknown ID to air,
                    // causing the normal translator to discard the pick request.
                    if (forwardUnknown(javaId, BlockRegistries.BLOCK_STATES.get().size(), packet,
                            session::sendDownstreamGamePacket)) return;
                }
                if (delegate != null) delegate.translate(session, packet);
            }
        });
    }

    static boolean forwardUnknown(int javaId, int registeredStates, BlockPickRequestPacket packet,
                                  Consumer<ServerboundPickItemFromBlockPacket> send) {
        if (registeredStates <= 0 || javaId < registeredStates) return false;
        // Range, loaded chunks, creative permissions and include-data checks
        // remain in Minecraft's normal pick handler; no inventory is granted here.
        send.accept(new ServerboundPickItemFromBlockPacket(packet.getBlockPosition(), packet.isAddUserData()));
        return true;
    }
}
