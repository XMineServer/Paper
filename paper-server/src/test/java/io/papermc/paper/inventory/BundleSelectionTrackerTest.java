package io.papermc.paper.inventory;

import static org.junit.jupiter.api.Assertions.*;

import io.papermc.paper.event.inventory.PlayerBundleItemSelectEvent.Direction;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;

import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

@AllFeatures
class BundleSelectionTrackerTest {

    private static final int MENU = 7;

    private static final int SLOT = 12;

    private ItemStack bundle() {
        ItemStack item = new ItemStack(Items.BUNDLE);
        item.set(
                DataComponents.BUNDLE_CONTENTS,
                new BundleContents(
                        List.of(
                                ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.PAPER)),
                                ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.STONE)),
                                ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.DIRT)))));
        return item;
    }

    private int marker(Packet<?> wrapped, Packet<?> expected) {
        assertInstanceOf(ClientboundBundlePacket.class, wrapped);
        List<Packet<?>> children = new ArrayList<>();
        ((ClientboundBundlePacket) wrapped).subPackets().forEach(children::add);
        assertSame(expected, children.getFirst());
        return ((ClientboundPingPacket) children.getLast()).getId();
    }

    private int update(BundleSelectionTracker tracker, int slot) {
        var packet = new ClientboundContainerSetSlotPacket(MENU, 1, slot, bundle());
        return marker(tracker.synchronize(packet, MENU), packet);
    }

    private BundleSelectionTracker initialized() {
        BundleSelectionTracker tracker = new BundleSelectionTracker();
        tracker.acknowledge(update(tracker, SLOT));
        return tracker;
    }

    @Test
    void snapshotAndMarkerAreAtomicAndInputWaitsForInitialAcknowledgement() {
        BundleSelectionTracker tracker = new BundleSelectionTracker();
        int marker = update(tracker, SLOT);
        assertEquals(Direction.UNKNOWN, tracker.select(MENU, SLOT, 0, 3).direction());
        tracker.acknowledge(marker);
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 0, 3).direction());
    }

    @Test
    void sameIndexAfterAcknowledgedTextUpdateIsAnotherForwardStep() {
        BundleSelectionTracker tracker = initialized();
        for (int step = 0; step < 100; step++) {
            assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 0, 3).direction());
            tracker.acknowledge(update(tracker, SLOT));
        }
    }

    @Test
    void queuedWheelPacketsBeforePongKeepOldClientIndexThenResetAtPong() {
        BundleSelectionTracker tracker = initialized();
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 0, 3).direction());
        int update = update(tracker, SLOT);
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 1, 3).direction());
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 2, 3).direction());
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 0, 3).direction());
        tracker.acknowledge(update);
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 0, 3).direction());
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 1, 3).direction());
    }

    @Test
    void repeatedBackwardScrollSurvivesUpdatesAndDirectionReversal() {
        BundleSelectionTracker tracker = initialized();
        for (int step = 0; step < 100; step++) {
            assertEquals(Direction.BACKWARD, tracker.select(MENU, SLOT, 2, 3).direction());
            tracker.acknowledge(update(tracker, SLOT));
        }
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 0, 3).direction());
        assertEquals(Direction.BACKWARD, tracker.select(MENU, SLOT, 2, 3).direction());
    }

    @Test
    void hoverResetIsObservedEvenThoughItDoesNotProduceASelectionEvent() {
        BundleSelectionTracker tracker = initialized();
        tracker.select(MENU, SLOT, 0, 3);
        assertEquals(Direction.UNKNOWN, tracker.select(MENU, SLOT, -1, 3).direction());
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 0, 3).direction());
        tracker.select(MENU, SLOT, -1, 3);
        assertEquals(Direction.BACKWARD, tracker.select(MENU, SLOT, 2, 3).direction());
    }

    @Test
    void slotsAreIndependentAndFullSnapshotResetsBothOnlyAfterPong() {
        BundleSelectionTracker tracker = initialized();
        tracker.acknowledge(update(tracker, SLOT + 1));
        tracker.select(MENU, SLOT, 0, 3);
        tracker.select(MENU, SLOT + 1, 2, 3);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i <= SLOT + 1; i++) {
            items.add(ItemStack.EMPTY);
        }
        items.set(SLOT, bundle());
        items.set(SLOT + 1, bundle());
        var full = new ClientboundContainerSetContentPacket(MENU, 2, items, ItemStack.EMPTY);
        int marker = marker(tracker.synchronize(full, MENU), full);
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 1, 3).direction());
        assertEquals(Direction.BACKWARD, tracker.select(MENU, SLOT + 1, 1, 3).direction());
        tracker.acknowledge(marker);
        assertEquals(Direction.FORWARD, tracker.select(MENU, SLOT, 0, 3).direction());
        assertEquals(Direction.BACKWARD, tracker.select(MENU, SLOT + 1, 2, 3).direction());
    }

    @Test
    void oldMenuAcknowledgementCannotResetNewMenuAndUnknownMarkersAreIgnored() {
        BundleSelectionTracker tracker = initialized();
        int old = update(tracker, SLOT);
        var packet = new ClientboundContainerSetSlotPacket(MENU + 1, 1, SLOT, bundle());
        int current = marker(tracker.synchronize(packet, MENU + 1), packet);
        tracker.acknowledge(old);
        assertTrue(tracker.select(MENU + 1, SLOT, 0, 3).previous().isEmpty());
        tracker.acknowledge(current);
        tracker.select(MENU + 1, SLOT, 0, 3);
        tracker.acknowledge(old);
        assertEquals(Direction.FORWARD, tracker.select(MENU + 1, SLOT, 1, 3).direction());
    }

    @Test
    void playerInventoryIsNotWrappedAndInvalidJumpsAreNeverGuessed() {
        BundleSelectionTracker tracker = new BundleSelectionTracker();
        var packet = new ClientboundContainerSetSlotPacket(0, 1, SLOT, bundle());
        assertSame(packet, tracker.synchronize(packet, 0));
        tracker = initialized();
        assertEquals(Direction.UNKNOWN, tracker.select(MENU, SLOT, 1, 3).direction());
        assertEquals(Direction.UNKNOWN, tracker.select(MENU, SLOT, 9, 3).direction());
        assertEquals(Direction.UNKNOWN, tracker.select(MENU, SLOT, 0, 2).direction());
    }
}
