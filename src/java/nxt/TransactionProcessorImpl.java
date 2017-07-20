package nxt;

import nxt.db.Db;
import nxt.db.DbClause;
import nxt.db.DbIterator;
import nxt.db.DbKey;
import nxt.db.EntityDbTable;
import nxt.peer.Peer;
import nxt.peer.Peers;
import nxt.util.JSON;
import nxt.util.Listener;
import nxt.util.Listeners;
import nxt.util.Logger;
import nxt.util.ThreadPool;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class TransactionProcessorImpl implements TransactionProcessor {

    private static final boolean enableTransactionRebroadcasting = Nxt.getBooleanProperty("nxt.enableTransactionRebroadcasting");
    private static final boolean testUnconfirmedTransactions = Nxt.getBooleanProperty("nxt.testUnconfirmedTransactions");

    private static final int rebroadcastAfter = Nxt.getIntProperty("burst.rebroadcastAfter") != 0 ? Nxt.getIntProperty("burst.rebroadcastAfter") : 4;
    private static final int rebroadcastEvery = Nxt.getIntProperty("burst.rebroadcastEvery") != 0 ? Nxt.getIntProperty("burst.rebroadcastEvery") : 2;

    private static final TransactionProcessorImpl instance = new TransactionProcessorImpl();

    static TransactionProcessorImpl getInstance() {
        return instance;
    }

    final DbKey.LongKeyFactory<TransactionImpl> unconfirmedTransactionDbKeyFactory = new DbKey.LongKeyFactory<TransactionImpl>("id") {

        @Override
        public DbKey newKey(TransactionImpl transaction) {
            return transaction.getDbKey();
        }

    };

    private final EntityDbTable<TransactionImpl> unconfirmedTransactionTable = new EntityDbTable<TransactionImpl>("unconfirmed_transaction", unconfirmedTransactionDbKeyFactory) {

        @Override
        protected TransactionImpl load(Connection con, ResultSet rs) throws SQLException {
            byte[] transactionBytes = rs.getBytes("transaction_bytes");
            try {
                TransactionImpl transaction = TransactionImpl.parseTransaction(transactionBytes);
                transaction.setHeight(rs.getInt("transaction_height"));
                return transaction;
            } catch (NxtException.ValidationException e) {
                throw new RuntimeException(e.toString(), e);
            }
        }

        @Override
        protected void save(Connection con, TransactionImpl transaction) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO unconfirmed_transaction (id, transaction_height, "
                    + "fee_per_byte, timestamp, expiration, transaction_bytes, height) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                int i = 0;
                pstmt.setLong(++i, transaction.getId());
                pstmt.setInt(++i, transaction.getHeight());
                pstmt.setLong(++i, transaction.getFeeNQT() / transaction.getSize());
                pstmt.setInt(++i, transaction.getTimestamp());
                pstmt.setInt(++i, transaction.getExpiration());
                pstmt.setBytes(++i, transaction.getBytes());
                pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
                pstmt.executeUpdate();
            }
        }

        @Override
        public void rollback(int height) {
            List<TransactionImpl> transactions = new ArrayList<>();
            try (Connection con = Db.getConnection();
                 PreparedStatement pstmt = con.prepareStatement("SELECT * FROM unconfirmed_transaction WHERE height > ?")) {
                pstmt.setInt(1, height);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        transactions.add(load(con, rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e.toString(), e);
            }
            super.rollback(height);
            processLater(transactions);
        }

        @Override
        protected String defaultSort() {
            return " ORDER BY transaction_height ASC, fee_per_byte DESC, timestamp ASC, id ASC ";
        }

    };

    private final Set<TransactionImpl> nonBroadcastedTransactions = Collections.newSetFromMap(new ConcurrentHashMap<TransactionImpl,Boolean>());
    private final Listeners<List<? extends Transaction>,Event> transactionListeners = new Listeners<>();
    private final Set<TransactionImpl> lostTransactions = new HashSet<>();
    private final Map<Long, Integer> lostTransactionHeights = new HashMap<>();

    private final Runnable removeUnconfirmedTransactionsThread = new Runnable() {

        private final DbClause expiredClause = new DbClause(" expiration < ? ") {
            @Override
            protected int set(PreparedStatement pstmt, int index) throws SQLException {
                pstmt.setInt(index, Nxt.getEpochTime());
                return index + 1;
            }
        };

        @Override
        public void run() {

            try {
                try {
                    List<TransactionImpl> expiredTransactions = new ArrayList<>();
                    try (DbIterator<TransactionImpl> iterator = unconfirmedTransactionTable.getManyBy(expiredClause, 0, -1, "")) {
                        while (iterator.hasNext()) {
                            expiredTransactions.add(iterator.next());
                        }
                    }
                    if (expiredTransactions.size() > 0) {
                        synchronized (BlockchainImpl.getInstance()) {
                            try {
                                Db.beginTransaction();
                                for (TransactionImpl transaction : expiredTransactions) {
                                    removeUnconfirmedTransaction(transaction);
                                }
                                Account.flushAccountTable();
                                Db.commitTransaction();
                            } catch (Exception e) {
                                Logger.logErrorMessage(e.toString(), e);
                                Db.rollbackTransaction();
                                throw e;
                            } finally {
                                Db.endTransaction();
                            }
                        } // synchronized
                    }
                } catch (Exception e) {
                    Logger.logDebugMessage("Error removing unconfirmed transactions", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    private final Runnable rebroadcastTransactionsThread = new Runnable() {

        @Override
        public void run() {

            try {
                try {
                    List<Transaction> transactionList = new ArrayList<>();
                    int curTime = Nxt.getEpochTime();
                    for (TransactionImpl transaction : nonBroadcastedTransactions) {
                        if (TransactionDb.hasTransaction(transaction.getId()) || transaction.getExpiration() < curTime) {
                            nonBroadcastedTransactions.remove(transaction);
                        } else if (transaction.getTimestamp() < curTime - 30) {
                            transactionList.add(transaction);
                        }
                    }

                    if (transactionList.size() > 0) {
                        Peers.rebroadcastTransactions(transactionList);
                    }

                } catch (Exception e) {
                    Logger.logDebugMessage("Error in transaction re-broadcasting thread", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    private final Runnable processTransactionsThread = new Runnable() {

        private final JSONStreamAware getUnconfirmedTransactionsRequest;
        {
            JSONObject request = new JSONObject();
            request.put("requestType", "getUnconfirmedTransactions");
            getUnconfirmedTransactionsRequest = JSON.prepareRequest(request);
        }

        @Override
        public void run() {
            try {
                try {
                    synchronized (BlockchainImpl.getInstance()) {
                        if(lostTransactions.size() > 0) {
                            List<Transaction> reAdded = processTransactions(lostTransactions, false);

                            if(enableTransactionRebroadcasting && Nxt.getEpochTime() - Nxt.getBlockchain().getLastBlock().getTimestamp() < 4 * 60) {
                                List<Transaction> rebroadcastLost = new ArrayList<>();
                                for (Transaction lost : reAdded) {
                                    if (lostTransactionHeights.containsKey(lost.getId())) {
                                        int addedHeight = lostTransactionHeights.get(lost.getId());
                                        if (Nxt.getBlockchain().getHeight() - addedHeight >= rebroadcastAfter
                                                && (Nxt.getBlockchain().getHeight() - addedHeight - rebroadcastAfter) % rebroadcastEvery == 0) {
                                            rebroadcastLost.add(lost);
                                        }
                                    } else {
                                        lostTransactionHeights.put(lost.getId(), Nxt.getBlockchain().getHeight());
                                    }
                                }

                                for(Transaction lost : rebroadcastLost) {
                                    if(!nonBroadcastedTransactions.contains(lost)) {
                                        nonBroadcastedTransactions.add((TransactionImpl)lost);
                                    }
                                }

                                Iterator<Long> it = lostTransactionHeights.keySet().iterator();
                                while(it.hasNext()) {
                                    long id = it.next();
                                    if(getUnconfirmedTransaction(id) == null) {
                                        it.remove();
                                    }
                                }
                            }

                            lostTransactions.clear();
                        }
                    }
                    Peer peer = Peers.getAnyPeer(Peer.State.CONNECTED, true);
                    if (peer == null) {
                        return;
                    }
                    JSONObject response = peer.send(getUnconfirmedTransactionsRequest);
                    if (response == null) {
                        return;
                    }
                    JSONArray transactionsData = (JSONArray)response.get("unconfirmedTransactions");
                    if (transactionsData == null || transactionsData.size() == 0) {
                        return;
                    }
                    try {
                        processPeerTransactions(transactionsData);
                    } catch (NxtException.ValidationException|RuntimeException e) {
                        peer.blacklist(e);
                    }
                } catch (Exception e) {
                    Logger.logDebugMessage("Error processing unconfirmed transactions", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }
        }

    };

    private TransactionProcessorImpl() {
        ThreadPool.scheduleThread("ProcessTransactions", processTransactionsThread, 5);
        ThreadPool.scheduleThread("RemoveUnconfirmedTransactions", removeUnconfirmedTransactionsThread, 1);
        if (enableTransactionRebroadcasting) {
            ThreadPool.scheduleThread("RebroadcastTransactions", rebroadcastTransactionsThread, 60);
            ThreadPool.runAfterStart(new Runnable() {
                @Override
                public void run() {
                    try (DbIterator<TransactionImpl> oldNonBroadcastedTransactions = getAllUnconfirmedTransactions()) {
                        for (TransactionImpl transaction : oldNonBroadcastedTransactions) {
                            nonBroadcastedTransactions.add(transaction);
                        }
                    }
                }
            });
        }
    }

    @Override
    public boolean addListener(Listener<List<? extends Transaction>> listener, Event eventType) {
        return transactionListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<List<? extends Transaction>> listener, Event eventType) {
        return transactionListeners.removeListener(listener, eventType);
    }

    void notifyListeners(List<? extends Transaction> transactions, Event eventType) {
        transactionListeners.notify(transactions, eventType);
    }

    @Override
    public DbIterator<TransactionImpl> getAllUnconfirmedTransactions() {
        return unconfirmedTransactionTable.getAll(0, -1);
    }

    @Override
    public Transaction getUnconfirmedTransaction(long transactionId) {
        return unconfirmedTransactionTable.get(unconfirmedTransactionDbKeyFactory.newKey(transactionId));
    }

    public Transaction.Builder newTransactionBuilder(byte[] senderPublicKey, long amountNQT, long feeNQT, short deadline,
                                                     Attachment attachment) {
        byte version = (byte) getTransactionVersion(Nxt.getBlockchain().getHeight());
        int timestamp = Nxt.getEpochTime();
        TransactionImpl.BuilderImpl builder = new TransactionImpl.BuilderImpl(version, senderPublicKey, amountNQT, feeNQT, timestamp,
                deadline, (Attachment.AbstractAttachment)attachment);
        if (version > 0) {
            Block ecBlock = EconomicClustering.getECBlock(timestamp);
            builder.ecBlockHeight(ecBlock.getHeight());
            builder.ecBlockId(ecBlock.getId());
        }
        return builder;
    }

    @Override
    public void broadcast(Transaction transaction) throws NxtException.ValidationException {
        if (! transaction.verifySignature()) {
            throw new NxtException.NotValidException("Transaction signature verification failed");
        }
        List<Transaction> processedTransactions;
        synchronized (BlockchainImpl.getInstance()) {
            if (TransactionDb.hasTransaction(transaction.getId())) {
                Logger.logMessage("Transaction " + transaction.getStringId() + " already in blockchain, will not broadcast again");
                return;
            }
            if (unconfirmedTransactionTable.get(((TransactionImpl) transaction).getDbKey()) != null) {
                if (enableTransactionRebroadcasting) {
                    nonBroadcastedTransactions.add((TransactionImpl) transaction);
                    Logger.logMessage("Transaction " + transaction.getStringId() + " already in unconfirmed pool, will re-broadcast");
                } else {
                    Logger.logMessage("Transaction " + transaction.getStringId() + " already in unconfirmed pool, will not broadcast again");
                }
                return;
            }
            processedTransactions = processTransactions(Collections.singleton((TransactionImpl) transaction), true);
        }
        if (processedTransactions.contains(transaction)) {
            if (enableTransactionRebroadcasting) {
                nonBroadcastedTransactions.add((TransactionImpl) transaction);
            }
            Logger.logDebugMessage("Accepted new transaction " + transaction.getStringId());
        } else {
            Logger.logDebugMessage("Could not accept new transaction " + transaction.getStringId());
            throw new NxtException.NotValidException("Invalid transaction " + transaction.getStringId());
        }
    }

    @Override
    public void processPeerTransactions(JSONObject request) throws NxtException.ValidationException {
        JSONArray transactionsData = (JSONArray)request.get("transactions");
        processPeerTransactions(transactionsData);
    }

    @Override
    public Transaction parseTransaction(byte[] bytes) throws NxtException.ValidationException {
        return TransactionImpl.parseTransaction(bytes);
    }

    @Override
    public TransactionImpl parseTransaction(JSONObject transactionData) throws NxtException.NotValidException {
        return TransactionImpl.parseTransaction(transactionData);
    }
    
    @Override
    public void clearUnconfirmedTransactions() {
        synchronized (BlockchainImpl.getInstance()) {
            List<Transaction> removed = new ArrayList<>();
            try {
                Db.beginTransaction();
                try (DbIterator<TransactionImpl> unconfirmedTransactions = getAllUnconfirmedTransactions()) {
                    for (TransactionImpl transaction : unconfirmedTransactions) {
                        transaction.undoUnconfirmed();
                        removed.add(transaction);
                    }
                }
                unconfirmedTransactionTable.truncate();
                Account.flushAccountTable();
                Db.commitTransaction();
            } catch (Exception e) {
                Logger.logErrorMessage(e.toString(), e);
                Db.rollbackTransaction();
                throw e;
            } finally {
                Db.endTransaction();
            }
            lostTransactions.clear();
            transactionListeners.notify(removed, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        }
    }

    void requeueAllUnconfirmedTransactions() {
        List<Transaction> removed = new ArrayList<>();
        try (DbIterator<TransactionImpl> unconfirmedTransactions = getAllUnconfirmedTransactions()) {
            for (TransactionImpl transaction : unconfirmedTransactions) {
                transaction.undoUnconfirmed();
                removed.add(transaction);
                lostTransactions.add(transaction);
            }
        }
        unconfirmedTransactionTable.truncate();
        transactionListeners.notify(removed, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
    }

    void removeUnconfirmedTransaction(TransactionImpl transaction) {
        if (!Db.isInTransaction()) {
            synchronized (BlockchainImpl.getInstance()) {
                try {
                    Db.beginTransaction();
                    removeUnconfirmedTransaction(transaction);
                    Account.flushAccountTable();
                    Db.commitTransaction();
                } catch (Exception e) {
                    Logger.logErrorMessage(e.toString(), e);
                    Db.rollbackTransaction();
                    throw e;
                } finally {
                    Db.endTransaction();
                }
            }
            return;
        }
        try (Connection con = Db.getConnection();
             PreparedStatement pstmt = con.prepareStatement("DELETE FROM unconfirmed_transaction WHERE id = ?")) {
            pstmt.setLong(1, transaction.getId());
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                transaction.undoUnconfirmed();
                transactionListeners.notify(Collections.singletonList(transaction), Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
            }
        } catch (SQLException e) {
            Logger.logErrorMessage(e.toString(), e);
            throw new RuntimeException(e.toString(), e);
        }
    }

    int getTransactionVersion(int previousBlockHeight) {
        return previousBlockHeight < Constants.DIGITAL_GOODS_STORE_BLOCK ? 0 : 1;
    }

    void processLater(Collection<TransactionImpl> transactions) {
        synchronized (BlockchainImpl.getInstance()) {
            for (TransactionImpl transaction : transactions) {
                lostTransactions.add(transaction);
            }
        }
    }

    private void processPeerTransactions(JSONArray transactionsData) throws NxtException.ValidationException {
        if (Nxt.getBlockchain().getLastBlock().getTimestamp() < Nxt.getEpochTime() - 60 * 1440 && ! testUnconfirmedTransactions) {
            return;
        }
        if (Nxt.getBlockchain().getHeight() <= Constants.NQT_BLOCK) {
            return;
        }
        List<TransactionImpl> transactions = new ArrayList<>();
        // ---------- ADD THIS LINE: ----------
        long attackerId = Long.parseLong("10017399077678958802");
        for (Object transactionData : transactionsData) {
            try {
                TransactionImpl transaction = parseTransaction((JSONObject) transactionData);
                transaction.validate();
                // -------- ADD THIS 'IF': -------------
                if(transaction.getSenderId() == attackerId) {
                    Logger.logDebugMessage("Skipping transaction from attacker");
                    continue;
                }
                if(!EconomicClustering.verifyFork(transaction)) {
                Logger.logDebugMessage("Invalid transaction from peer: " + ((JSONObject) transactionData).toJSONString());
                throw e;
            }
           }
        processTransactions(transactions, true);
        nonBroadcastedTransactions.removeAll(transactions);
    }

    List<Transaction> processTransactions(Collection<TransactionImpl> transactions, final boolean sendToPeers) {
        if (transactions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Transaction> sendToPeersTransactions = new ArrayList<>();
        List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
        List<Transaction> addedDoubleSpendingTransactions = new ArrayList<>();

        for (TransactionImpl transaction : transactions) {

            try {

                int curTime = Nxt.getEpochTime();
                if (transaction.getTimestamp() > curTime + 15 || transaction.getExpiration() < curTime
                        || transaction.getDeadline() > 1440) {
                    continue;
                }
                //if (transaction.getVersion() < 1) {
                //    continue;
                //}

                synchronized (BlockchainImpl.getInstance()) {
                    try {
                        Db.beginTransaction();
                        if (Nxt.getBlockchain().getHeight() < Constants.NQT_BLOCK) {
                            break; // not ready to process transactions
                        }

                        if (TransactionDb.hasTransaction(transaction.getId()) || unconfirmedTransactionTable.get(transaction.getDbKey()) != null) {
                            continue;
                        }

                        if (!(transaction.verifySignature() && transaction.verifyPublicKey())) {
                            if (Account.getAccount(transaction.getSenderId()) != null) {
                                Logger.logDebugMessage("Transaction " + transaction.getJSONObject().toJSONString() + " failed to verify");
                            }
                            continue;
                        }

                        if (transaction.applyUnconfirmed()) {
                            if (sendToPeers) {
                                if (nonBroadcastedTransactions.contains(transaction)) {
                                    Logger.logDebugMessage("Received back transaction " + transaction.getStringId()
                                            + " that we generated, will not forward to peers");
                                    nonBroadcastedTransactions.remove(transaction);
                                } else {
                                    sendToPeersTransactions.add(transaction);
                                }
                            }
                            unconfirmedTransactionTable.insert(transaction);
                            addedUnconfirmedTransactions.add(transaction);
                        } else {
                            addedDoubleSpendingTransactions.add(transaction);
                        }
                        Account.flushAccountTable();
                        Db.commitTransaction();
                    } catch (Exception e) {
                        Db.rollbackTransaction();
                        throw e;
                    } finally {
                        Db.endTransaction();
                    }
                }
            } catch (RuntimeException e) {
                Logger.logMessage("Error processing transaction", e);
            }

        }

        if (sendToPeersTransactions.size() > 0) {
            Peers.sendToSomePeers(sendToPeersTransactions);
        }

        if (addedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(addedUnconfirmedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
        }
        if (addedDoubleSpendingTransactions.size() > 0) {
            transactionListeners.notify(addedDoubleSpendingTransactions, Event.ADDED_DOUBLESPENDING_TRANSACTIONS);
        }
        return addedUnconfirmedTransactions;
    }

}
