package net.scarletphantasy.gensouMarket.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.scarletphantasy.gensouMarket.velocity.listener.PluginMessageListener;
import net.scarletphantasy.gensouMarket.velocity.protocol.OutgoingPacket;
import net.scarletphantasy.gensouMarket.velocity.protocol.PacketCodec;
import net.scarletphantasy.gensouMarket.velocity.protocol.PacketType;
import net.scarletphantasy.gensouMarket.velocity.service.BackendRegistry;
import net.scarletphantasy.gensouMarket.velocity.service.BroadcastService;
import net.scarletphantasy.gensouMarket.velocity.service.PlayerRouteService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageBoundaryTest {
    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from("gensoumarket:main");
    private final Logger logger = mock(Logger.class);

    @Test void clientPacketsAreHandledBeforeAnyDecodingOrForwarding() {
        var event = new PluginMessageEvent(mock(Player.class), mock(RegisteredServer.class), channel, new byte[0]);
        new PluginMessageListener(logger, channel, null, null, null).onPluginMessage(event);
        assertFalse(event.getResult().isAllowed());
    }

    @Test void otherChannelsAreUnaffected() {
        var event = new PluginMessageEvent(mock(Player.class), mock(RegisteredServer.class),
                MinecraftChannelIdentifier.from("other:channel"), new byte[0]);
        new PluginMessageListener(logger, channel, null, null, null).onPluginMessage(event);
        assertTrue(event.getResult().isAllowed());
    }

    @Test void backendCannotSpoofAnotherRegisteredSource() {
        var registry = new BackendRegistry(logger);
        var server = server("paper-a");
        registry.register("a", server);
        var broadcast = mock(BroadcastService.class);
        var connection = mock(ServerConnection.class);
        when(connection.getServer()).thenReturn(server);
        var serverInfo = server.getServerInfo();
        when(connection.getServerInfo()).thenReturn(serverInfo);
        var forged = new OutgoingPacket(PacketType.MARKET_LISTING_CHANGED, "request", "b");
        var event = new PluginMessageEvent(connection, mock(Player.class), channel, PacketCodec.encode(forged));
        new PluginMessageListener(logger, channel, registry, broadcast, null).onPluginMessage(event);
        assertFalse(event.getResult().isAllowed());
        verifyNoInteractions(broadcast);
        var genuine = new OutgoingPacket(PacketType.MARKET_LISTING_CHANGED, "request", "a");
        new PluginMessageListener(logger, channel, registry, broadcast, null).onPluginMessage(
                new PluginMessageEvent(connection, mock(Player.class), channel, PacketCodec.encode(genuine)));
        verify(broadcast).broadcast(any());
    }

    @Test void firstBusinessMessageCanBindBeforeDelayedHello() {
        var registry = new BackendRegistry(logger);
        var server = server("paper-a");
        var info = server.getServerInfo();
        var connection = mock(ServerConnection.class);
        when(connection.getServer()).thenReturn(server);
        when(connection.getServerInfo()).thenReturn(info);
        var broadcast = mock(BroadcastService.class);
        var packet = new OutgoingPacket(PacketType.MARKET_LISTING_CHANGED, "request", "a");
        new PluginMessageListener(logger, channel, registry, broadcast, null).onPluginMessage(
                new PluginMessageEvent(connection, mock(Player.class), channel, PacketCodec.encode(packet)));
        assertSame(server, registry.getServer("a"));
        verify(broadcast).broadcast(any());
    }

    @Test void duplicateAndReservedIdsCannotReplaceExistingBackend() {
        var registry = new BackendRegistry(logger);
        var a = server("paper-a");
        var b = server("paper-b");
        registry.register("a", a);
        registry.register("a", b);
        registry.register("velocity", b);
        registry.register(" ", b);
        assertSame(a, registry.getServer("a"));
        assertNull(registry.getServerId("paper-b"));
        registry.register("renamed", a);
        assertNull(registry.getServer("a"));
        assertSame(a, registry.getServer("renamed"));
    }

    @Test void failedTransportIsReportedForBothTargetRoutes() {
        var registry = new BackendRegistry(logger);
        var server = server("paper-a");
        when(server.getPlayersConnected()).thenReturn(List.of(mock(Player.class)));
        when(server.sendPluginMessage(eq(channel), any(byte[].class))).thenReturn(false);
        registry.register("a", server);
        var service = new BroadcastService(logger, registry, channel);
        var packet = new OutgoingPacket(PacketType.REMOTE_GIVE_ITEM, "request", "b");
        assertFalse(service.sendTo("a", packet));
        var route = mock(PlayerRouteService.class);
        var target = UUID.randomUUID();
        when(route.locatePlayer(target)).thenReturn(Optional.of(server));
        assertFalse(service.sendToPlayer(route, target, packet));
    }

    private RegisteredServer server(String name) {
        var server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("localhost", 25565)));
        return server;
    }
}
