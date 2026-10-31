package io.papermc.paper.inventory;

import io.papermc.paper.event.inventory.PlayerBundleItemSelectEvent.Direction;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the client selection at ordered snapshot acknowledgements, never at send time. Main thread
 * only.
 */
public final class BundleSelectionTracker {
    private static final int UNSYNCHRONIZED = Integer.MIN_VALUE;
    private static final int MAX_PENDING = 1024;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0x584D0000);
    private final Map<Integer, Integer> indices = new HashMap<>();
    private final Map<Integer, Snapshot> pending = new LinkedHashMap<>();
    private int container = -1;

    public record Selection(OptionalInt previous, Direction direction) {
        private static final Selection UNKNOWN =
                new Selection(OptionalInt.empty(), Direction.UNKNOWN);
    }

    private static final class Snapshot {
        private boolean full;
        private final Map<Integer, Boolean> slots = new HashMap<>();
    }

    public Packet<?> synchronize(Packet<?> packet, int activeContainer) {
        if (this.container != activeContainer) {
            this.container = activeContainer;
            this.indices.clear();
            this.pending.clear();
        }
        if (activeContainer == 0) {
            return packet;
        }
        Snapshot snapshot = new Snapshot();
        this.collect(packet, snapshot);
        if (snapshot.slots.isEmpty() && (!snapshot.full || this.indices.isEmpty())) {
            return packet;
        }
        if (this.pending.size() >= MAX_PENDING) {
            this.pending.clear();
            this.indices.replaceAll((slot, index) -> UNSYNCHRONIZED);
        }
        snapshot.slots.forEach(
                (slot, bundle) -> {
                    if (bundle) {
                        this.indices.putIfAbsent(slot, UNSYNCHRONIZED);
                    }
                });
        int marker = NEXT_ID.getAndUpdate(value -> 0x58000000 | ((value + 1) & 0x00FFFFFF));
        this.pending.put(marker, snapshot);
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        if (packet instanceof ClientboundBundlePacket bundle) {
            bundle.subPackets().forEach(packets::add);
        } else {
            // collect() only accepts game inventory packets here.
            @SuppressWarnings("unchecked")
            Packet<? super ClientGamePacketListener> inventoryPacket =
                    (Packet<? super ClientGamePacketListener>) packet;
            packets.add(inventoryPacket);
        }
        // Vanilla handles this entire bundle on its main thread and sends pong inline.
        // Therefore no wheel input can be sent between replacing the item and this pong.
        packets.add(new ClientboundPingPacket(marker));
        return new ClientboundBundlePacket(packets);
    }

    private void collect(Packet<?> packet, Snapshot snapshot) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            bundle.subPackets().forEach(child -> this.collect(child, snapshot));
        } else if (packet instanceof ClientboundContainerSetContentPacket content
                && content.containerId() == this.container) {
            snapshot.full = true;
            snapshot.slots.clear();
            for (int slot = 0; slot < content.items().size(); slot++) {
                if (isBundle(content.items().get(slot))) {
                    snapshot.slots.put(slot, true);
                }
            }
        } else if (packet instanceof ClientboundContainerSetSlotPacket update
                && update.getContainerId() == this.container) {
            int slot = update.getSlot();
            boolean bundle = isBundle(update.getItem());
            if (bundle || this.indices.containsKey(slot) || snapshot.slots.containsKey(slot)) {
                snapshot.slots.put(slot, bundle);
            }
        }
    }

    private static boolean isBundle(ItemStack item) {
        return item.has(DataComponents.BUNDLE_CONTENTS);
    }

    public void acknowledge(int marker) {
        Snapshot snapshot = this.pending.remove(marker);
        if (snapshot == null) {
            return;
        }
        if (snapshot.full) {
            this.indices.clear();
        }
        snapshot.slots.forEach(
                (slot, bundle) -> {
                    if (bundle) {
                        this.indices.put(slot, -1);
                    } else {
                        this.indices.remove(slot);
                    }
                });
    }

    public Selection select(int activeContainer, int slot, int selected, int count) {
        Integer previous = this.indices.get(slot);
        if (activeContainer != this.container
                || previous == null
                || previous == UNSYNCHRONIZED
                || selected < -1
                || selected >= count) {
            return Selection.UNKNOWN;
        }
        this.indices.put(slot, selected);
        Direction direction = Direction.UNKNOWN;
        if (count >= 3 && selected >= 0 && selected != previous) {
            if (previous == -1) {
                if (selected == 0) {
                    direction = Direction.FORWARD;
                } else if (selected == count - 1) {
                    direction = Direction.BACKWARD;
                }
            } else if (selected == (previous + 1) % count) {
                direction = Direction.FORWARD;
            } else if (selected == Math.floorMod(previous - 1, count)) {
                direction = Direction.BACKWARD;
            }
        }
        return new Selection(OptionalInt.of(previous), direction);
    }
}
