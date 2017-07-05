package nxt;

import nxt.crypto.Crypto;
import nxt.util.Convert;
import nxt.util.Listener;
import nxt.util.Listeners;
import nxt.util.Logger;
import nxt.util.ThreadPool;
import nxt.util.MiningPlot;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import fr.cryptohash.Shabal256;

public final class GeneratorImpl implements Generator {

    private static final Listeners<GeneratorState,Event> listeners = new Listeners<>();

    private static final ConcurrentMap<Long, GeneratorStateImpl> generators = new ConcurrentHashMap<>();
    private static final Collection<? extends GeneratorState> allGenerators = Collections.unmodifiableCollection(generators.values());

    private static final Runnable generateBlockThread = new Runnable() {

        @Override
        public void run() {

            try {
                if (Nxt.getBlockchainProcessor().isScanning()) {
                    return;
                }
                try {
                    long currentBlock = Nxt.getBlockchain().getLastBlock().getHeight();
                    Iterator<Entry<Long, GeneratorStateImpl>> it = generators.entrySet().iterator();
                    while(it.hasNext()) {
                    	Entry<Long, GeneratorStateImpl> generator = it.next();
                    	if(currentBlock < generator.getValue().getBlock()) {
                    		generator.getValue().forge();
                    	}
                    	else {
                    		it.remove();
                    	}
                    }
                } catch (Exception e) {
                    Logger.logDebugMessage("Error in block generation thread", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    static {
        ThreadPool.scheduleThread("GenerateBlocks", generateBlockThread, 500, TimeUnit.MILLISECONDS);
    }

    @Override
    public void init() {}

    void clear() {
    }

    @Override
    public boolean addListener(Listener<GeneratorState> listener, Event eventType) {
        return listeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<GeneratorState> listener, Event eventType) {
        return listeners.removeListener(listener, eventType);
    }

    /*public static GeneratorImpl startForging(String secretPhrase) {
        return null;
    }*/

    @Override
    public GeneratorState addNonce(String secretPhrase, Long nonce) {
    	byte[] publicKey = Crypto.getPublicKey(secretPhrase);
    	return addNonce(secretPhrase, nonce, publicKey);
    }

    /*public static GeneratorImpl startForging(String secretPhrase, byte[] publicKey) {
        return null;
    }*/

    @Override
    public GeneratorState addNonce(String secretPhrase, Long nonce, byte[] publicKey) {
		byte[] publicKeyHash = Crypto.sha256().digest(publicKey);
		Long id = Convert.fullHashToId(publicKeyHash);
		
		GeneratorStateImpl generator = new GeneratorStateImpl(secretPhrase, nonce, publicKey, id);
		GeneratorStateImpl curGen = generators.get(id);
		if(curGen == null || generator.getBlock() > curGen.getBlock() || generator.getDeadline().compareTo(curGen.getDeadline()) < 0) {
			generators.put(id, generator);
			listeners.notify(generator, Event.START_FORGING);
			Logger.logDebugMessage("Account " + Convert.toUnsignedLong(id) + " started mining, deadline "
			        + generator.getDeadline() + " seconds");
		}
		else {
			Logger.logDebugMessage("Account " + Convert.toUnsignedLong(id) + " already has better nonce");
		}
		
		return generator;
    }

    /*public static GeneratorImpl stopForging(String secretPhrase) {
        return null;
    }

    public static GeneratorImpl getGenerator(String secretPhrase) {
    	return null;
    }*/

    @Override
    public Collection<? extends GeneratorState> getAllGenerators() {
        return allGenerators;
    }

    @Override
    public byte[] calculateGenerationSignature(byte[] lastGenSig, long lastGenId, byte[] transactionHash, long blockHeight) {
		ByteBuffer gensigbuf;
		if (blockHeight >= Constants.TRANSACTION_HASH_IN_GENERATION_SIG_BLOCK) {
			gensigbuf = ByteBuffer.allocate(32 + 8 + 32);
			gensigbuf.put(lastGenSig);
			gensigbuf.putLong(lastGenId);
			gensigbuf.put(transactionHash);
		} else {
			gensigbuf = ByteBuffer.allocate(32 + 8);
			gensigbuf.put(lastGenSig);
			gensigbuf.putLong(lastGenId);
		}
        
        Shabal256 md = new Shabal256();
        md.update(gensigbuf.array());
        return md.digest();
    }

    @Override
    public int calculateScoop(byte[] genSig, long height) {
        ByteBuffer posbuf = ByteBuffer.allocate(32 + 8);
        posbuf.put(genSig);
        posbuf.putLong(height);

        Shabal256 md = new Shabal256();
        md.update(posbuf.array());
        BigInteger hashnum = new BigInteger(1, md.digest());
        return hashnum.mod(BigInteger.valueOf(MiningPlot.SCOOPS_PER_PLOT)).intValue();
    }

    @Override
    public BigInteger calculateHit(long accountId, long nonce, byte[] genSig, int scoop) {
        MiningPlot plot = new MiningPlot(accountId, nonce);

        Shabal256 md = new Shabal256();
        md.update(genSig);
        plot.hashScoop(md, scoop);
        byte[] hash = md.digest();
        return new BigInteger(1, new byte[] {hash[7], hash[6], hash[5], hash[4], hash[3], hash[2], hash[1], hash[0]});
    }

    @Override
    public BigInteger calculateHit(long accountId, long nonce, byte[] genSig, byte[] scoopData) {
        Shabal256 md = new Shabal256();
        md.update(genSig);
        md.update(scoopData);
        byte[] hash = md.digest();
        return new BigInteger(1, new byte[] {hash[7], hash[6], hash[5], hash[4], hash[3], hash[2], hash[1], hash[0]});
    }

    @Override
    public BigInteger calculateDeadline(long accountId, long nonce, byte[] genSig, int scoop, long baseTarget) {
        BigInteger hit = calculateHit(accountId, nonce, genSig, scoop);

        return hit.divide(BigInteger.valueOf(baseTarget));
    }

    public class GeneratorStateImpl implements GeneratorState {
        private final Long accountId;
        private final String secretPhrase;
        private final byte[] publicKey;
        private volatile BigInteger deadline;
        private final long nonce;
        private final long block;

        private GeneratorStateImpl(String secretPhrase, Long nonce, byte[] publicKey, Long account) {
            this.secretPhrase = secretPhrase;
            this.publicKey = publicKey;
            // need to store publicKey in addition to accountId, because the account may not have had its publicKey set yet
            this.accountId = account;
            this.nonce = nonce;
            this.block = Nxt.getBlockchain().getLastBlock().getHeight() + 1;

            Block lastBlock = Nxt.getBlockchain().getLastBlock();
            byte[] lastGenSig = lastBlock.getGenerationSignature();
            Long lastGenerator = lastBlock.getGeneratorId();
            byte[] transactionHash = Nxt.getBlockchainProcessor().getTransactionHash(lastBlock);

            byte[] newGenSig = calculateGenerationSignature(lastGenSig, lastGenerator, transactionHash, block);

            int scoopNum = calculateScoop(newGenSig, lastBlock.getHeight() + 1);

            deadline = calculateDeadline(accountId, nonce, newGenSig, scoopNum, lastBlock.getBaseTarget());
        }

        @Override
        public byte[] getPublicKey() {
            return publicKey;
        }

        @Override
        public Long getAccountId() {
            return accountId;
        }

        @Override
        public BigInteger getDeadline() {
            return deadline;
        }

        @Override
        public long getBlock() {
            return block;
        }

        private void forge() throws BlockchainProcessor.BlockNotAcceptedException {
            Block lastBlock = Nxt.getBlockchain().getLastBlock();

            int elapsedTime = Nxt.getEpochTime() - lastBlock.getTimestamp();
            if (BigInteger.valueOf(elapsedTime).compareTo(deadline) > 0) {
                BlockchainProcessorImpl.getInstance().generateBlock(secretPhrase, publicKey, nonce);
            }
        }
    }

    public static class MockGeneratorImpl implements Generator {

        private static final Listeners<GeneratorState,Event> listeners = new Listeners<>();

        @Override
        public boolean addListener(Listener<GeneratorState> listener, Event eventType) {
            return listeners.addListener(listener, eventType);
        }

        @Override
        public boolean removeListener(Listener<GeneratorState> listener, Event eventType) {
            return listeners.removeListener(listener, eventType);
        }

        @Override
        public void init() {

        }

        @Override
        public GeneratorState addNonce(String secretPhrase, Long nonce) {
            byte[] publicKey = Crypto.getPublicKey(secretPhrase);
            return addNonce(secretPhrase, nonce, publicKey);
        }

        @Override
        public GeneratorState addNonce(String secretPhrase, Long nonce, byte[] publicKey) {
            try {
                BlockchainProcessorImpl.getInstance().generateBlock(secretPhrase, publicKey, nonce);
            }
            catch(BlockchainProcessor.BlockNotAcceptedException e) {
                e.printStackTrace();
                Logger.logDebugMessage(e.getMessage(), e);
            }
            return new MockGeneratorStateImpl();
        }

        @Override
        public Collection<? extends GeneratorState> getAllGenerators() {
            return Collections.EMPTY_LIST;
        }

        @Override
        public byte[] calculateGenerationSignature(byte[] lastGenSig, long lastGenId, byte[] transactionHash, long blockHeight) {
            return new byte[32];
        }

        @Override
        public int calculateScoop(byte[] genSig, long height) {
            return 0;
        }

        @Override
        public BigInteger calculateHit(long accountId, long nonce, byte[] genSig, int scoop) {
            return BigInteger.valueOf(0);
        }

        @Override
        public BigInteger calculateHit(long accountId, long nonce, byte[] genSig, byte[] scoopData) {
            return BigInteger.ZERO;
        }

        @Override
        public BigInteger calculateDeadline(long accountId, long nonce, byte[] genSig, int scoop, long baseTarget) {
            return BigInteger.valueOf(0);
        }

        public class MockGeneratorStateImpl implements GeneratorState {

            @Override
            public byte[] getPublicKey() {
                return new byte[32];
            }

            @Override
            public Long getAccountId() {
                return 0L;
            }

            @Override
            public BigInteger getDeadline() {
                return new BigInteger("0");
            }

            @Override
            public long getBlock() {
                return 0;
            }
        }
    }
}
