package io.bitsquare.p2p.peers;

import io.bitsquare.app.Log;
import io.bitsquare.common.UserThread;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.NodeAddress;
import io.bitsquare.p2p.network.*;
import io.bitsquare.p2p.peers.messages.peers.GetPeersRequest;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class PeerExchangeManager implements MessageListener, ConnectionListener {
    private static final Logger log = LoggerFactory.getLogger(PeerExchangeManager.class);

    private final NetworkNode networkNode;
    private final PeerManager peerManager;
    private final Set<NodeAddress> seedNodeAddresses;
    private final Map<NodeAddress, PeerExchangeHandshake> peerExchangeHandshakeMap = new HashMap<>();
    private Timer connectToMorePeersTimer;
    private boolean shutDownInProgress;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PeerExchangeManager(NetworkNode networkNode, PeerManager peerManager, Set<NodeAddress> seedNodeAddresses) {
        this.networkNode = networkNode;
        this.peerManager = peerManager;
        checkArgument(!seedNodeAddresses.isEmpty(), "seedNodeAddresses must not be empty");
        this.seedNodeAddresses = new HashSet<>(seedNodeAddresses);

        networkNode.addMessageListener(this);
    }

    public void shutDown() {
        Log.traceCall();
        shutDownInProgress = true;

        networkNode.removeMessageListener(this);
        stopConnectToMorePeersTimer();
        peerExchangeHandshakeMap.values().stream().forEach(PeerExchangeHandshake::closeHandshake);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void requestReportedPeersFromSeedNodes(NodeAddress nodeAddress) {
        checkNotNull(networkNode.getNodeAddress(), "My node address must not be null at requestReportedPeers");
        ArrayList<NodeAddress> remainingNodeAddresses = new ArrayList<>(seedNodeAddresses);
        remainingNodeAddresses.remove(nodeAddress);
        Collections.shuffle(remainingNodeAddresses);
        requestReportedPeers(nodeAddress, remainingNodeAddresses);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ConnectionListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onConnection(Connection connection) {
    }

    @Override
    public void onDisconnect(CloseConnectionReason closeConnectionReason, Connection connection) {
    }

    @Override
    public void onError(Throwable throwable) {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(Message message, Connection connection) {
        if (message instanceof GetPeersRequest) {
            Log.traceCall(message.toString() + "\n\tconnection=" + connection);
            PeerExchangeHandshake peerExchangeHandshake = new PeerExchangeHandshake(networkNode,
                    peerManager,
                    new PeerExchangeHandshake.Listener() {
                        @Override
                        public void onComplete() {
                            log.trace("PeerExchangeHandshake of inbound connection complete.\n\tConnection={}", connection);
                        }

                        @Override
                        public void onFault(String errorMessage, @Nullable Connection connection) {
                            log.trace("PeerExchangeHandshake of outbound connection failed.\n\terrorMessage={}\n\t" +
                                    "connection={}", errorMessage, connection);
                            peerManager.handleConnectionFault(connection);
                        }
                    });
            peerExchangeHandshake.onGetPeersRequest((GetPeersRequest) message, connection);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void requestReportedPeers(NodeAddress nodeAddress, List<NodeAddress> remainingNodeAddresses) {
        Log.traceCall("nodeAddress=" + nodeAddress);
        if (!peerExchangeHandshakeMap.containsKey(nodeAddress)) {
            PeerExchangeHandshake peerExchangeHandshake = new PeerExchangeHandshake(networkNode,
                    peerManager,
                    new PeerExchangeHandshake.Listener() {
                        @Override
                        public void onComplete() {
                            log.trace("PeerExchangeHandshake of outbound connection complete. nodeAddress={}", nodeAddress);
                            peerExchangeHandshakeMap.remove(nodeAddress);
                            connectToMorePeers();
                        }

                        @Override
                        public void onFault(String errorMessage, @Nullable Connection connection) {
                            log.trace("PeerExchangeHandshake of outbound connection failed.\n\terrorMessage={}\n\t" +
                                    "nodeAddress={}", errorMessage, nodeAddress);

                            peerExchangeHandshakeMap.remove(nodeAddress);
                            peerManager.handleConnectionFault(nodeAddress, connection);
                            if (!shutDownInProgress) {
                                if (!remainingNodeAddresses.isEmpty()) {
                                    log.info("There are remaining nodes available for requesting peers. " +
                                            "We will try getReportedPeers again.");
                                    requestReportedPeersFromRandomPeer(remainingNodeAddresses);
                                } else {
                                    log.info("There is no remaining node available for requesting peers. " +
                                            "That is expected if no other node is online.\n\t" +
                                            "We will try again after a random pause.");
                                    if (connectToMorePeersTimer == null)
                                        connectToMorePeersTimer = UserThread.runAfterRandomDelay(
                                                PeerExchangeManager.this::connectToMorePeers, 20, 30);
                                }
                            }
                        }
                    });
            peerExchangeHandshakeMap.put(nodeAddress, peerExchangeHandshake);
            peerExchangeHandshake.requestConnectedPeers(nodeAddress);
        } else {
            //TODO check when that happens
            log.warn("We have started already a peerExchangeHandshake. " +
                    "We ignore that call. " +
                    "nodeAddress=" + nodeAddress);
        }
    }


    private void connectToMorePeers() {
        Log.traceCall();

        stopConnectToMorePeersTimer();

        if (!peerManager.hasSufficientConnections()) {
            // We create a new list of not connected candidates
            // 1. reported sorted by most recent lastActivityDate
            // 2. persisted sorted by most recent lastActivityDate
            // 3. seenNodes
            List<NodeAddress> list = new ArrayList<>(getFilteredAndSortedList(peerManager.getReportedPeers(), new ArrayList<>()));
            list.addAll(getFilteredAndSortedList(peerManager.getPersistedPeers(), list));
            ArrayList<NodeAddress> seedNodeAddresses = new ArrayList<>(this.seedNodeAddresses);
            Collections.shuffle(seedNodeAddresses);
            list.addAll(seedNodeAddresses.stream()
                    .filter(e -> !list.contains(e) &&
                            !peerManager.isSelf(e) &&
                            !peerManager.isConfirmed(e))
                    .collect(Collectors.toSet()));
            log.info("Sorted and filtered list: list.size()=" + list.size());
            log.trace("Sorted and filtered list: list=" + list);
            if (!list.isEmpty()) {
                NodeAddress nextCandidate = list.get(0);
                list.remove(nextCandidate);
                requestReportedPeers(nextCandidate, list);
            } else {
                log.info("No more peers are available for requestReportedPeers.");
            }
        } else {
            log.info("We have already sufficient connections.");
        }
    }

    // sorted by most recent lastActivityDate
    private List<NodeAddress> getFilteredAndSortedList(Set<ReportedPeer> set, List<NodeAddress> list) {
        return set.stream()
                .filter(e -> !list.contains(e.nodeAddress) &&
                        !peerManager.isSeedNode(e) &&
                        !peerManager.isSelf(e) &&
                        !peerManager.isConfirmed(e))
                .collect(Collectors.toList())
                .stream()
                .filter(e -> e.lastActivityDate != null)
                .sorted((o1, o2) -> o2.lastActivityDate.compareTo(o1.lastActivityDate))
                .map(e -> e.nodeAddress)
                .collect(Collectors.toList());
    }

    private void requestReportedPeersFromRandomPeer(List<NodeAddress> remainingNodeAddresses) {
        NodeAddress nextCandidate = remainingNodeAddresses.get(new Random().nextInt(remainingNodeAddresses.size()));
        remainingNodeAddresses.remove(nextCandidate);
        requestReportedPeers(nextCandidate, remainingNodeAddresses);
    }

    private void stopConnectToMorePeersTimer() {
        if (connectToMorePeersTimer != null) {
            connectToMorePeersTimer.cancel();
            connectToMorePeersTimer = null;
        }
    }
}
