package com.devgbx9.mineflayer.remote;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import com.devgbx9.mineflayer.NmsReflect;

/**
 * The numeric packet ids the remote server expects, read out of this server's
 * own protocol tables.
 *
 * <p>Packet ids are positional: a protocol phase is built by registering codecs
 * in order, and a packet's id is its index in that list. Minecraft adds and
 * removes packets between drops, so every insertion shifts every id after it.
 * The ids for 26.1.x and 26.2.x are therefore not the same table, and the
 * protocol version differs too (775 against 776).
 *
 * <p>Rather than hard-code either table, the ids are derived at runtime from
 * {@code ProtocolInfo.Details.listPackets}, which hands out exactly the
 * {@code (PacketType, index)} pairs the server itself uses. That keeps one jar
 * correct on both series, and on any fork that has not renamed these classes.
 *
 * <p>There is deliberately no hard-coded fallback. A stale table would not fail
 * cleanly: the bot would send well-formed packets carrying the wrong ids, and
 * the remote server would reject them with an error naming the wrong cause.
 * Failing here, with the missing member named, is the more useful outcome.
 */
final class PacketIds {

    /**
     * The four protocol phases, with the class that declares each one's tables.
     *
     * <p>Handshake is serverbound only - the client opens with it and the server
     * never replies in that phase - so its clientbound table is absent rather
     * than empty.
     */
    enum Phase {
        HANDSHAKE("handshake.HandshakeProtocols", false),
        LOGIN("login.LoginProtocols", true),
        CONFIGURATION("configuration.ConfigurationProtocols", true),
        PLAY("game.GameProtocols", true);

        private final String protocolsClass;
        private final boolean hasClientbound;

        Phase(String protocolsClass, boolean hasClientbound) {
            this.protocolsClass = protocolsClass;
            this.hasClientbound = hasClientbound;
        }
    }

    /** Packet name ("minecraft:hello") to id, for packets this bot sends. */
    private final Map<Phase, Map<String, Integer>> serverbound = new HashMap<>();
    /** Id to packet name, for packets this bot receives. */
    private final Map<Phase, Map<Integer, String>> clientbound = new HashMap<>();

    private PacketIds() {
    }

    /**
     * Reads every phase's id table.
     *
     * @throws ReflectiveOperationException if the tables cannot be read, naming
     *         the member that was missing
     */
    static PacketIds load() throws ReflectiveOperationException {
        PacketIds ids = new PacketIds();
        for (Phase phase : Phase.values()) {
            Class<?> protocols = NmsReflect.clazz(
                    "net.minecraft.network.protocol." + phase.protocolsClass);

            Map<String, Integer> out = new HashMap<>();
            visit(protocols, "SERVERBOUND_TEMPLATE", (name, id) -> out.put(name, id));
            ids.serverbound.put(phase, out);

            if (phase.hasClientbound) {
                Map<Integer, String> in = new HashMap<>();
                visit(protocols, "CLIENTBOUND_TEMPLATE", (name, id) -> in.put(id, name));
                ids.clientbound.put(phase, in);
            }
        }
        return ids;
    }

    /**
     * Walks one table, handing every {@code (name, id)} pair to {@code sink}.
     *
     * <p>The template is read rather than the bound {@code ProtocolInfo} beside
     * it: binding the play phase needs a {@code GameProtocols.Context} built from
     * live registries, while the template implements {@code DetailsProvider}
     * directly and lists its packets without any of that. The two agree on ids -
     * binding attaches codecs, it does not renumber.
     */
    private static void visit(Class<?> protocols, String field, PacketSink sink)
            throws ReflectiveOperationException {
        Object template = NmsReflect.staticField(protocols, field);
        Object details = NmsReflect.method(template.getClass(), "details").invoke(template);

        Class<?> visitorClass = NmsReflect.clazz(
                "net.minecraft.network.ProtocolInfo$Details$PacketVisitor");

        // PacketVisitor is a single-method interface, so a proxy stands in for it
        // without needing a compile-time dependency on the server.
        Object visitor = Proxy.newProxyInstance(
                PacketIds.class.getClassLoader(),
                new Class<?>[] {visitorClass},
                new VisitorHandler(sink));

        NmsReflect.method(details.getClass(), "listPackets", visitor)
                .invoke(details, visitor);
    }

    /** Receives one {@code (packet name, id)} pair. */
    private interface PacketSink {
        void accept(String name, int id);
    }

    /** Turns {@code PacketVisitor.accept(PacketType, int)} calls into sink calls. */
    private record VisitorHandler(PacketSink sink) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Object's own methods reach the proxy too; only accept carries a packet.
            if (!method.getName().equals("accept") || args == null || args.length != 2) {
                return fallback(proxy, method, args);
            }
            Object packetType = args[0];
            int id = (Integer) args[1];

            // PacketType.id() returns an Identifier, whose toString is the
            // namespaced name ("minecraft:keep_alive").
            Object identifier = NmsReflect.method(packetType.getClass(), "id").invoke(packetType);
            sink.accept(identifier.toString(), id);
            return null;
        }

        /** Keeps the proxy usable as an ordinary object. */
        private Object fallback(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "PacketVisitor";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }
    }

    /**
     * The id to send {@code name} under.
     *
     * @throws java.io.IOException if this build has no such packet, which means
     *         the bot is speaking a protocol the server does not have
     */
    int serverbound(Phase phase, String name) throws java.io.IOException {
        Integer id = serverbound.getOrDefault(phase, Map.of()).get(name);
        if (id == null) {
            throw new java.io.IOException(
                    "this server build has no serverbound packet '" + name + "' in phase " + phase);
        }
        return id;
    }

    /**
     * The name of a received packet, or {@code null} if the phase has no packet
     * with that id.
     *
     * <p>Null is a normal answer, not a failure: it is how an unrecognised packet
     * is told apart from a known one, and unrecognised packets are skipped.
     */
    String clientbound(Phase phase, int id) {
        return clientbound.getOrDefault(phase, Map.of()).get(id);
    }
}
