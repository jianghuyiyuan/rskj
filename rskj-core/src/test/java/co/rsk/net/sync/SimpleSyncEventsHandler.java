package co.rsk.net.sync;


import co.rsk.net.MessageChannel;
import org.ethereum.core.BlockHeader;
import org.ethereum.core.BlockIdentifier;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Queue;

public class SimpleSyncEventsHandler implements SyncEventsHandler {
    private boolean startSyncingWasCalled_;
    private boolean stopSyncingWasCalled_;

    @Override
    public void sendBlockHashRequest(long height) { }

    @Override
    public void sendBlockHeadersRequest(ChunkDescriptor chunk) { }

    @Override
    public void onErrorSyncing(String message, Object... arguments) {
        stopSyncing();
    }

    @Override
    public void onCompletedSyncing() {
        stopSyncing();
    }

    @Override
    public long sendBodyRequest(@Nonnull BlockHeader header) { return 0; }

    @Override
    public void sendSkeletonRequest(long height) { }

    @Override
    public void startDownloadingHeaders(List<BlockIdentifier> skeleton, long connectionPoint) { }

    @Override
    public void startSyncing(MessageChannel peer) {
        this.startSyncingWasCalled_ = true;
    }

    @Override
    public void startDownloadingBodies(Queue<BlockHeader> pendingHeaders) { }

    @Override
    public void startDownloadingSkeleton(long connectionPoint) { }

    @Override
    public void stopSyncing() { this.stopSyncingWasCalled_ = true; }

    public boolean startSyncingWasCalled() {
        return startSyncingWasCalled_;
    }

    public boolean stopSyncingWasCalled() {
        return stopSyncingWasCalled_;
    }
}