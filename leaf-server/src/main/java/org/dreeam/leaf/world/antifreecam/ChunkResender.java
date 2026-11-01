package org.dreeam.leaf.world.antifreecam;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkResender {

    private ChunkResender() {}

    /**
     * Sends a fresh packet of a chunk the player already has, with the sections hidden that the manager wants hidden now.
     * The client replaces its copy of the chunk. No chunk load event is fired. Tick thread only.
     */
    public static void resend(final ServerPlayer player, final LevelChunk chunk) {
        if (player.connection == null || player.hasDisconnected()) {
            return;
        }
        final ServerLevel level = (ServerLevel)chunk.getLevel();
        if (player.level() != level) {
            return;
        }
        final boolean shouldModify = level.chunkPacketBlockController.shouldModify(player, chunk);
        final SendMasks masks = AntiFreecamHooks.onChunkSend(player, chunk);
        player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, shouldModify, AntiFreecamHooks.hidden(masks), AntiFreecamHooks.partial(masks)));
    }
}
